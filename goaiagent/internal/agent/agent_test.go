package agent

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/cloudwego/eino/components/model"
	"github.com/cloudwego/eino/schema"
	"github.com/golang-jwt/jwt/v5"
)

func testConfig() Config {
	return Config{Environment: "local", ModelMode: "demo", Model: "gpt-4.1-mini", Address: "127.0.0.1", Port: "8090",
		AgentURL: "http://127.0.0.1:8090/", AuthMode: "demo", DemoToken: "local-demo-client-token",
		Issuer: "https://identity.example.com/", Audience: "jagent", StatusURL: "http://127.0.0.1:8091", RunbookURL: "http://127.0.0.1:8092",
		StatusToken: "local-demo-status-token", RunbookToken: "local-demo-runbook-token"}
}

func quietLog() *slog.Logger { return slog.New(slog.NewJSONHandler(io.Discard, nil)) }
func fullCaller() Caller {
	return Caller{"test", grants([]string{"agent:invoke", "status:read", "runbooks:read"}), grants([]string{"payments", "orders"})}
}

func fixtureTools(t *testing.T) Config {
	t.Helper()
	c := testConfig()
	for _, kind := range []string{"status", "runbook"} {
		handler, err := MockHandler(c, kind)
		if err != nil {
			t.Fatal(err)
		}
		server := httptest.NewServer(handler)
		t.Cleanup(server.Close)
		if kind == "status" {
			c.StatusURL = server.URL
		} else {
			c.RunbookURL = server.URL
		}
	}
	return c
}

func startAgent(t *testing.T, c Config, m Model) *httptest.Server {
	t.Helper()
	s, err := NewServer(c, m, quietLog())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Close)
	server := httptest.NewServer(s)
	t.Cleanup(server.Close)
	return server
}

func payload(text string) string {
	data, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": "test", "method": "message/send", "params": map[string]any{
		"message": map[string]any{"messageId": "test-message", "role": "user", "parts": []any{map[string]string{"kind": "text", "text": text}}}}})
	return string(data)
}

func request(t *testing.T, handler http.Handler, method, path, body, token string) *httptest.ResponseRecorder {
	t.Helper()
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Header.Set("Authorization", token)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	return w
}

func TestFullA2AClientGraphAndRemoteServices(t *testing.T) {
	c := fixtureTools(t)
	server := startAgent(t, c, DemoModel{})
	answer, err := Ask(t.Context(), server.URL+"/", c.DemoToken, "Investigate payments")
	if err != nil {
		t.Fatal(err)
	}
	for _, text := range []string{"DEMO (scripted, no LLM)", "demo-status:payments", "demo-runbook:payments", "degraded"} {
		if !strings.Contains(string(answer), text) {
			t.Fatalf("missing %q: %s", text, answer)
		}
	}
}

func TestDiscoveryAndAuthentication(t *testing.T) {
	c := testConfig()
	s, err := NewServer(c, DemoModel{}, quietLog())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	for _, path := range []string{"/", "/.well-known/agent-card.json"} {
		for _, token := range []string{"", "Bearer wrong", "Basic local-demo-client-token"} {
			w := request(t, s, "GET", path, "", token)
			if w.Code != 401 || w.Header().Get("WWW-Authenticate") != "Bearer" {
				t.Fatalf("expected 401: %d", w.Code)
			}
		}
	}
	if w := request(t, s, "GET", "/healthz", "", ""); w.Code != 200 {
		t.Fatal(w.Code)
	}
	w := request(t, s, "GET", "/.well-known/agent-card.json", "", "Bearer "+c.DemoToken)
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"protocolVersion":"0.3.0"`) || !strings.Contains(w.Body.String(), `"scheme":"bearer"`) {
		t.Fatal(w.Body.String())
	}
	if w.Header().Get("X-Request-ID") == "" || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("missing security headers")
	}
}

func TestProtocolInputBoundaries(t *testing.T) {
	c := testConfig()
	s, err := NewServer(c, DemoModel{}, quietLog())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	tests := []struct {
		name, body string
		code       int
	}{
		{"invalid-json", "{", -32700}, {"batch", "[]", -32600},
		{"empty", payload(""), -32602}, {"too-long", payload(strings.Repeat("x", 4001)), -32602},
		{"stream", strings.Replace(payload("payments"), "message/send", "message/stream", 1), -32601},
		{"task", strings.Replace(payload("payments"), "message/send", "tasks/get", 1), -32601},
		{"context", strings.Replace(payload("payments"), `"role":"user"`, `"role":"user","contextId":"other-caller"`, 1), -32602},
		{"data-part", strings.Replace(payload("payments"), `"kind":"text"`, `"kind":"data"`, 1), -32602},
		{"wrong-role", strings.Replace(payload("payments"), `"role":"user"`, `"role":"agent"`, 1), -32602},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			w := request(t, s, "POST", "/", test.body, "Bearer "+c.DemoToken)
			var response struct {
				Error struct {
					Code int `json:"code"`
				} `json:"error"`
			}
			if json.Unmarshal(w.Body.Bytes(), &response) != nil || response.Error.Code != test.code {
				t.Fatalf("got %s", w.Body.String())
			}
		})
	}
	if w := request(t, s, "POST", "/", strings.Repeat("x", 16_385), "Bearer "+c.DemoToken); w.Code != 413 {
		t.Fatal(w.Code)
	}
}

func TestNullOptionalFieldsAreAccepted(t *testing.T) {
	c := fixtureTools(t)
	s, err := NewServer(c, DemoModel{}, quietLog())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	body := strings.Replace(payload("payments"), `"role":"user"`, `"role":"user","contextId":null,"taskId":null`, 1)
	w := request(t, s, "POST", "/", body, "Bearer "+c.DemoToken)
	if !strings.Contains(w.Body.String(), "demo-status:payments") {
		t.Fatal(w.Body.String())
	}
}

func TestToolPermissionsAndArguments(t *testing.T) {
	c := fixtureTools(t)
	client := NewHTTPClient(time.Second)
	defer client.CloseIdleConnections()
	tools := RemoteTools{c, Caller{"test", grants([]string{"status:read"}), grants([]string{"payments"})}, "test", client, quietLog()}
	if result := tools.Call(t.Context(), "get_service_status", `{"service":"payments"}`); !strings.Contains(result, "degraded") {
		t.Fatal(result)
	}
	for _, test := range []struct{ name, args, want string }{
		{"get_runbook", `{"service":"payments"}`, "Access denied"},
		{"get_service_status", `{"service":"orders"}`, "Access denied"},
		{"get_service_status", `{"service":"https://evil.example"}`, "Invalid tool arguments"},
		{"get_service_status", `{"service":"payments","url":"https://evil.example"}`, "Invalid tool arguments"},
		{"unknown", `{}`, "Unknown tool"},
	} {
		if result := tools.Call(t.Context(), test.name, test.args); !strings.Contains(result, test.want) {
			t.Fatal(result)
		}
	}
}

func TestRemoteBoundariesAndCredentials(t *testing.T) {
	var calls atomic.Int32
	var leaked atomic.Bool
	var redirected atomic.Int32
	destination := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { redirected.Add(1) }))
	defer destination.Close()
	for _, test := range []struct {
		name, body string
		code       int
	}{
		{"redirect", "", 302}, {"oversized", strings.Repeat("x", 8193), 200}, {"upstream-error", "secret upstream detail", 500},
		{"wrong-service", `{"service":"orders","status":"healthy","summary":"","source":"test"}`, 200},
		{"missing-field", `{"service":"payments","status":"healthy","source":"test"}`, 200},
		{"unknown-field", `{"service":"payments","status":"healthy","summary":"","source":"test","extra":1}`, 200},
	} {
		t.Run(test.name, func(t *testing.T) {
			remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				if r.Header.Get("Authorization") != "Bearer local-demo-status-token" {
					leaked.Store(true)
				}
				w.Header().Set("Location", destination.URL)
				w.WriteHeader(test.code)
				_, _ = io.WriteString(w, test.body)
			}))
			defer remote.Close()
			c := testConfig()
			c.StatusURL = remote.URL
			client := NewHTTPClient(time.Second)
			defer client.CloseIdleConnections()
			tools := RemoteTools{c, fullCaller(), "test", client, quietLog()}
			if result := tools.Call(t.Context(), "get_service_status", `{"service":"payments"}`); !strings.Contains(result, "Remote data unavailable") {
				t.Fatal(result)
			}
			before := calls.Load()
			tools.Caller.Scopes = map[string]bool{}
			tools.Call(t.Context(), "get_service_status", `{"service":"payments"}`)
			if calls.Load() != before {
				t.Fatal("denial contacted remote service")
			}
		})
	}
	if leaked.Load() || redirected.Load() != 0 {
		t.Fatal("credential or redirect boundary failed")
	}
}

type modelFunc func(context.Context, []*schema.Message) (*schema.Message, error)

func (f modelFunc) Generate(ctx context.Context, m []*schema.Message, _ ...model.Option) (*schema.Message, error) {
	return f(ctx, m)
}

func TestGraphBounds(t *testing.T) {
	for _, count := range []int{1, 3} {
		t.Run(string(rune('0'+count)), func(t *testing.T) {
			calls := 0
			model := modelFunc(func(ctx context.Context, m []*schema.Message) (*schema.Message, error) {
				calls++
				var toolCalls []schema.ToolCall
				for i := 0; i < count; i++ {
					toolCalls = append(toolCalls, schema.ToolCall{ID: "test", Type: "function", Function: schema.FunctionCall{Name: "unknown", Arguments: "{}"}})
				}
				return schema.AssistantMessage("", toolCalls), nil
			})
			answer, err := RunGraph(t.Context(), "loop", model, RemoteTools{})
			if err != nil {
				t.Fatal(err)
			}
			if count == 1 && (calls != 4 || !strings.Contains(answer, "limit reached")) {
				t.Fatalf("%d %s", calls, answer)
			}
			if count == 3 && (calls != 1 || !strings.Contains(answer, "Too many")) {
				t.Fatalf("%d %s", calls, answer)
			}
		})
	}
}

func TestCancellationReachesSlowRemote(t *testing.T) {
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	defer remote.Close()
	c := testConfig()
	c.StatusURL = remote.URL
	client := NewHTTPClient(time.Second)
	defer client.CloseIdleConnections()
	tools := RemoteTools{c, fullCaller(), "test", client, quietLog()}
	ctx, cancel := context.WithTimeout(t.Context(), 50*time.Millisecond)
	defer cancel()
	start := time.Now()
	result := tools.Call(ctx, "get_service_status", `{"service":"payments"}`)
	if !strings.Contains(result, "unavailable") || time.Since(start) > time.Second {
		t.Fatal("cancellation did not bound slow response")
	}
}

func TestExecutionFailureDoesNotLeakDetails(t *testing.T) {
	var logs bytes.Buffer
	model := modelFunc(func(context.Context, []*schema.Message) (*schema.Message, error) {
		return nil, errors.New("secret-provider-token")
	})
	c := testConfig()
	s, err := NewServer(c, model, slog.New(slog.NewJSONHandler(&logs, nil)))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	w := request(t, s, "POST", "/", payload("payments"), "Bearer "+c.DemoToken)
	if !strings.Contains(w.Body.String(), "could not be completed") || strings.Contains(w.Body.String()+logs.String(), "secret-provider-token") {
		t.Fatal("unsafe error handling")
	}
}

func TestConcurrentRequestLimit(t *testing.T) {
	entered := make(chan struct{}, 8)
	release := make(chan struct{})
	model := modelFunc(func(ctx context.Context, _ []*schema.Message) (*schema.Message, error) {
		entered <- struct{}{}
		select {
		case <-release:
			return schema.AssistantMessage("done", nil), nil
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	})
	c := testConfig()
	server := startAgent(t, c, model)
	var wg sync.WaitGroup
	defer func() { close(release); wg.Wait() }()
	for i := 0; i < 8; i++ {
		wg.Go(func() {
			r, _ := http.NewRequest("POST", server.URL, strings.NewReader(payload("payments")))
			r.Header.Set("Authorization", "Bearer "+c.DemoToken)
			response, err := server.Client().Do(r)
			if err == nil {
				_, _ = io.Copy(io.Discard, response.Body)
				_ = response.Body.Close()
			}
		})
	}
	for i := 0; i < 8; i++ {
		select {
		case <-entered:
		case <-time.After(5 * time.Second):
			t.Fatal("concurrent work did not start")
		}
	}
	r, _ := http.NewRequest("POST", server.URL, strings.NewReader(payload("payments")))
	r.Header.Set("Authorization", "Bearer "+c.DemoToken)
	response, err := server.Client().Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != 429 {
		t.Fatal(response.StatusCode)
	}
}

func TestJWTValidationAndScopeEnforcement(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "public.pem")
	if err := os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: encoded}), 0600); err != nil {
		t.Fatal(err)
	}
	c := testConfig()
	c.AuthMode = "jwt"
	c.PublicKeyFile = path
	s, err := NewServer(c, DemoModel{}, quietLog())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	base := jwt.MapClaims{"sub": "operator-1", "iss": c.Issuer, "aud": c.Audience, "iat": time.Now().Unix(), "exp": time.Now().Add(time.Hour).Unix(), "scope": "agent:invoke status:read", "services": []string{"payments"}}
	for _, test := range []struct {
		name, claim string
		value       any
		want        int
	}{
		{"valid", "scope", "agent:invoke", 200}, {"no-invoke", "scope", "status:read", 403},
		{"audience", "aud", "other", 401}, {"issuer", "iss", "https://evil.example", 401}, {"expired", "exp", 1, 401},
		{"missing-exp", "exp", nil, 401}, {"missing-iat", "iat", nil, 401}, {"missing-sub", "sub", nil, 401},
		{"future-iat", "iat", time.Now().Add(time.Hour).Unix(), 401}, {"wrong-services-type", "services", "payments", 401},
	} {
		t.Run(test.name, func(t *testing.T) {
			claims := jwt.MapClaims{}
			for k, v := range base {
				claims[k] = v
			}
			claims[test.claim] = test.value
			token, err := jwt.NewWithClaims(jwt.SigningMethodRS256, claims).SignedString(key)
			if err != nil {
				t.Fatal(err)
			}
			w := request(t, s, "GET", "/.well-known/agent-card.json", "", "Bearer "+token)
			if w.Code != test.want {
				t.Fatalf("got %d want %d", w.Code, test.want)
			}
		})
	}
	wrongKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	wrongToken, _ := jwt.NewWithClaims(jwt.SigningMethodRS256, base).SignedString(wrongKey)
	hmacToken, _ := jwt.NewWithClaims(jwt.SigningMethodHS256, base).SignedString(encoded)
	for _, token := range []string{wrongToken, hmacToken} {
		if w := request(t, s, "GET", "/.well-known/agent-card.json", "", "Bearer "+token); w.Code != 401 {
			t.Fatal(w.Code)
		}
	}
}

func TestProductionConfiguration(t *testing.T) {
	c := testConfig()
	c.Environment = "production"
	if c.Validate() == nil {
		t.Fatal("production accepted demo configuration")
	}
	c.AgentURL = "https://agent.example.com/"
	c.StatusURL = "https://status.example.com"
	c.RunbookURL = "https://runbooks.example.com"
	c.AuthMode = "jwt"
	c.PublicKeyFile = "trusted.pem"
	c.ModelMode = "openai"
	c.APIKey = "test-provider-key"
	if c.Validate() == nil {
		t.Fatal("production accepted public demo tool credentials")
	}
	c.StatusToken = strings.Repeat("a", 32)
	c.RunbookToken = strings.Repeat("b", 32)
	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	c.RunbookToken = c.StatusToken
	if c.Validate() == nil {
		t.Fatal("production accepted shared credentials")
	}
	if _, err := MockHandler(c, "status"); err == nil {
		t.Fatal("production accepted synthetic APIs")
	}
}

func TestRealModelAdapterAgainstSimulatedProvider(t *testing.T) {
	c := fixtureTools(t)
	c.APIKey = "fake-provider-key"
	var requestMu sync.Mutex
	var requests []json.RawMessage
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		requestMu.Lock()
		defer requestMu.Unlock()
		requests = append(requests, body)
		message := map[string]any{"role": "assistant", "content": "Payments degraded; source demo-status:payments"}
		finish := "stop"
		if len(requests) == 1 {
			finish = "tool_calls"
			message = map[string]any{"role": "assistant", "content": nil, "tool_calls": []any{
				map[string]any{"id": "call-1", "type": "function", "function": map[string]string{"name": "get_service_status", "arguments": `{"service":"payments"}`}}}}
		}
		writeJSON(w, 200, map[string]any{"id": "test", "object": "chat.completion", "created": 1, "model": "test",
			"choices": []any{map[string]any{"index": 0, "finish_reason": finish, "message": message}}})
	}))
	defer provider.Close()
	model, err := newOpenAIModel(t.Context(), c, provider.URL+"/v1")
	if err != nil {
		t.Fatal(err)
	}
	client := NewHTTPClient(time.Second)
	defer client.CloseIdleConnections()
	answer, err := RunGraph(t.Context(), "payments", model, RemoteTools{c, fullCaller(), "test", client, quietLog()})
	if err != nil {
		t.Fatal(err)
	}
	requestMu.Lock()
	defer requestMu.Unlock()
	if !strings.Contains(answer, "demo-status:payments") || len(requests) != 2 {
		t.Fatalf("%s, requests=%d", answer, len(requests))
	}
	if !strings.Contains(string(requests[0]), `"tools"`) || !strings.Contains(string(requests[1]), `"role":"tool"`) || !strings.Contains(string(requests[1]), "degraded") {
		t.Fatal("model/tool handshake incomplete")
	}
}
