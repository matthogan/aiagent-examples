package agent

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/a2aproject/a2a-go/a2a"
	"github.com/google/uuid"
)

// Ask authenticates discovery and sends a single-turn request. Credentials only go to the configured URL.
func Ask(ctx context.Context, address, token, question string) ([]byte, error) {
	if err := validateURL(address, false); err != nil {
		return nil, err
	}
	if token == "" {
		return nil, errors.New("set A2A_TOKEN to an issuer-provided access token")
	}
	client := NewHTTPClient(60 * time.Second)
	defer client.CloseIdleConnections()
	discovery, err := http.NewRequestWithContext(ctx, "GET", strings.TrimRight(address, "/")+"/.well-known/agent-card.json", nil)
	if err != nil {
		return nil, err
	}
	discovery.Header.Set("Authorization", "Bearer "+token)
	data, err := clientExchange(client, discovery)
	if err != nil {
		return nil, err
	}
	var card a2a.AgentCard
	if json.Unmarshal(data, &card) != nil || card.ProtocolVersion != "0.3.0" {
		return nil, errors.New("expected an A2A 0.3 Agent Card")
	}
	id := uuid.NewString()
	payload, err := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": id, "method": "message/send",
		"params": a2a.MessageSendParams{Message: a2a.NewMessage(a2a.MessageRoleUser, a2a.TextPart{Text: question})}})
	if err != nil {
		return nil, err
	}
	request, err := http.NewRequestWithContext(ctx, "POST", address, bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Content-Type", "application/json")
	data, err = clientExchange(client, request)
	if err != nil {
		return nil, err
	}
	var response struct {
		Version string          `json:"jsonrpc"`
		ID      string          `json:"id"`
		Result  *a2a.Message    `json:"result"`
		Error   json.RawMessage `json:"error"`
	}
	if json.Unmarshal(data, &response) != nil || response.Version != "2.0" || response.ID != id ||
		(len(response.Error) > 0 && string(response.Error) != "null") || response.Result == nil || response.Result.Role != a2a.MessageRoleAgent {
		return nil, errors.New("invalid or failed A2A response")
	}
	var pretty bytes.Buffer
	if err := json.Indent(&pretty, data, "", "  "); err != nil {
		return nil, err
	}
	return pretty.Bytes(), nil
}

func clientExchange(client *http.Client, request *http.Request) ([]byte, error) {
	response, err := client.Do(request)
	if err != nil {
		return nil, errors.New("agent connection failed")
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return nil, fmt.Errorf("agent returned HTTP %d", response.StatusCode)
	}
	return readLimited(response.Body, 65_536)
}
