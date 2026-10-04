package agent

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/a2aproject/a2a-go/a2a"
	"github.com/google/uuid"
)

type Server struct {
	config Config
	auth   *Authenticator
	model  Model
	http   *http.Client
	log    *slog.Logger
	slots  chan struct{}
}

func NewServer(c Config, model Model, log *slog.Logger) (*Server, error) {
	if err := c.Validate(); err != nil {
		return nil, err
	}
	auth, err := NewAuthenticator(c)
	if err != nil {
		return nil, err
	}
	if model == nil {
		return nil, errors.New("model is required")
	}
	return &Server{c, auth, model, NewHTTPClient(5 * time.Second), log, make(chan struct{}, 8)}, nil
}

func (s *Server) Close() { s.http.CloseIdleConnections() }

func (s *Server) Card() *a2a.AgentCard {
	format := "opaque"
	if s.config.AuthMode == "jwt" {
		format = "JWT"
	}
	return &a2a.AgentCard{
		Name: "Operations assistant (Go)", Description: "Read-only service health and runbook assistant",
		URL: s.config.AgentURL, Version: "0.1.0", ProtocolVersion: "0.3.0", PreferredTransport: "JSONRPC",
		Capabilities: a2a.AgentCapabilities{}, DefaultInputModes: []string{"text/plain"}, DefaultOutputModes: []string{"text/plain"},
		SecuritySchemes: a2a.NamedSecuritySchemes{"bearer": a2a.HTTPAuthSecurityScheme{Scheme: "bearer", BearerFormat: format,
			Description: "Requires agent:invoke; tools also require scopes and service grants"}},
		Security: []a2a.SecurityRequirements{{"bearer": a2a.SecuritySchemeScopes{}}},
		Skills: []a2a.AgentSkill{{ID: "operations", Name: "Investigate service health",
			Description: "Read health and suggest runbook steps for payments or orders", Tags: []string{"operations", "read-only"},
			Examples: []string{"Why is payments degraded, and what should I check?"}}},
	}
}

// ServeHTTP is a deliberately small A2A 0.3 transport using official SDK wire types.
// Only single-turn message/send is supported; no durable A2A tasks are created.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	id := uuid.NewString()
	w.Header().Set("X-Request-ID", id)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	tracked := &statusWriter{ResponseWriter: w, status: 200}
	w = tracked
	started := time.Now()
	defer func() {
		s.log.Info("http_request", "request_id", id, "status", tracked.status, "duration_ms", time.Since(started).Milliseconds())
	}()
	if r.Method == "GET" && r.URL.Path == "/healthz" {
		writeJSON(w, 200, map[string]string{"status": "ok"})
		return
	}
	caller, err := s.auth.Authenticate(r.Header.Get("Authorization"))
	if err != nil {
		w.Header().Set("WWW-Authenticate", "Bearer")
		writeJSON(w, 401, map[string]string{"error": "Invalid or missing bearer token"})
		return
	}
	if !caller.Scopes["agent:invoke"] {
		writeJSON(w, 403, map[string]string{"error": "agent:invoke scope required"})
		return
	}
	select {
	case s.slots <- struct{}{}:
		defer func() { <-s.slots }()
	default:
		writeJSON(w, 429, map[string]string{"error": "Agent is busy; retry later"})
		return
	}
	if r.Method == "GET" && r.URL.Path == "/.well-known/agent-card.json" {
		writeJSON(w, 200, s.Card())
		return
	}
	if r.URL.Path != "/" {
		writeJSON(w, 404, map[string]string{"error": "Not found"})
		return
	}
	if r.Method != "POST" {
		w.Header().Set("Allow", "POST")
		writeJSON(w, 405, map[string]string{"error": "POST required"})
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 16_384)
	data, err := io.ReadAll(r.Body)
	if err != nil {
		var maxError *http.MaxBytesError
		if errors.As(err, &maxError) {
			writeJSON(w, 413, map[string]string{"error": "Request exceeds 16 KiB"})
		} else {
			writeJSON(w, 400, map[string]string{"error": "Request body could not be read"})
		}
		return
	}
	if !utf8.Valid(data) || !json.Valid(data) {
		rpcError(w, nil, -32700, "Invalid JSON")
		return
	}
	var envelope struct {
		Version string          `json:"jsonrpc"`
		ID      json.RawMessage `json:"id"`
		Method  string          `json:"method"`
		Params  json.RawMessage `json:"params"`
	}
	if json.Unmarshal(data, &envelope) != nil || envelope.Version != "2.0" || envelope.Method == "" || !validID(envelope.ID) {
		rpcError(w, nil, -32600, "A JSON-RPC request with an id is required")
		return
	}
	if envelope.Method != "message/send" {
		rpcError(w, envelope.ID, -32601, "Only message/send is supported")
		return
	}
	var params a2a.MessageSendParams
	var shape struct {
		Message struct {
			Kind *string `json:"kind"`
		} `json:"message"`
	}
	if json.Unmarshal(envelope.Params, &shape) != nil || (shape.Message.Kind != nil && *shape.Message.Kind != "message") || json.Unmarshal(envelope.Params, &params) != nil {
		rpcError(w, envelope.ID, -32602, "Invalid message parameters")
		return
	}
	question, err := userQuestion(params.Message)
	if err != nil {
		rpcError(w, envelope.ID, -32602, "Use a new user text message containing 1–4000 characters")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 45*time.Second)
	defer cancel()
	answer, err := RunGraph(ctx, question, s.model, RemoteTools{s.config, caller, id, s.http, s.log})
	if err != nil {
		// Exception text can contain provider credentials or prompt content: never emit it.
		s.log.Warn("execution_failed", "request_id", id)
		answer = "The request could not be completed. Retry or contact the service operator."
	}
	writeJSON(w, 200, map[string]any{"jsonrpc": "2.0", "id": envelope.ID,
		"result": a2a.NewMessage(a2a.MessageRoleAgent, a2a.TextPart{Text: answer})})
}

func userQuestion(message *a2a.Message) (string, error) {
	if message == nil || message.ID == "" || message.Role != a2a.MessageRoleUser || len(message.Parts) == 0 ||
		message.TaskID != "" || message.ContextID != "" || len(message.ReferenceTasks) != 0 {
		return "", errors.New("invalid user message")
	}
	var pieces []string
	for _, part := range message.Parts {
		text, ok := part.(a2a.TextPart)
		if !ok {
			return "", errors.New("only text is supported")
		}
		pieces = append(pieces, text.Text)
	}
	question := strings.TrimSpace(strings.Join(pieces, "\n"))
	if question == "" || utf8.RuneCountInString(question) > 4000 {
		return "", errors.New("invalid question length")
	}
	return question, nil
}

func validID(raw json.RawMessage) bool {
	if len(raw) == 0 || string(raw) == "null" {
		return false
	}
	var value any
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	decoder.UseNumber()
	if decoder.Decode(&value) != nil {
		return false
	}
	switch value.(type) {
	case string, json.Number:
		return true
	default:
		return false
	}
}

func rpcError(w http.ResponseWriter, id json.RawMessage, code int, message string) {
	writeJSON(w, 200, map[string]any{"jsonrpc": "2.0", "id": id, "error": map[string]any{"code": code, "message": message}})
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) { w.status = code; w.ResponseWriter.WriteHeader(code) }

// HTTPServer supplies whole-body read deadlines, not just header/inactivity timeouts.
func HTTPServer(address string, handler http.Handler) *http.Server {
	return &http.Server{Addr: address, Handler: handler, ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout: 10 * time.Second, WriteTimeout: 60 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 16_384}
}
