package agent

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/cloudwego/eino-ext/components/model/openai"
	"github.com/cloudwego/eino/components/model"
	"github.com/cloudwego/eino/schema"
)

// Model is intentionally small so tests and demo mode can substitute a deterministic model.
type Model interface {
	Generate(context.Context, []*schema.Message, ...model.Option) (*schema.Message, error)
}

func NewModel(ctx context.Context, c Config) (Model, error) {
	if c.ModelMode == "demo" {
		return DemoModel{}, nil
	}
	return newOpenAIModel(ctx, c, "")
}

// baseURL is injectable for protocol tests, not a model- or caller-controlled option.
func newOpenAIModel(ctx context.Context, c Config, baseURL string) (Model, error) {
	maxTokens := 1000
	chat, err := openai.NewChatModel(ctx, &openai.ChatModelConfig{
		APIKey: c.APIKey, Model: c.Model, BaseURL: baseURL, MaxCompletionTokens: &maxTokens,
		HTTPClient: NewHTTPClient(20 * time.Second),
	})
	if err != nil {
		return nil, err
	}
	return chat.WithTools(ToolDefinitions())
}

type DemoModel struct{}

func (DemoModel) Generate(ctx context.Context, messages []*schema.Message, _ ...model.Option) (*schema.Message, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	var results []string
	var question string
	for _, message := range messages {
		if message.Role == schema.Tool {
			results = append(results, message.Content)
		}
		if message.Role == schema.User {
			question = strings.ToLower(message.Content)
		}
	}
	if len(results) > 0 {
		return schema.AssistantMessage("DEMO (scripted, no LLM):\n"+strings.Join(results, "\n")+
			"\nReview runbook steps before taking action; no changes were made.", nil), nil
	}
	service := ""
	for _, candidate := range []string{"payments", "orders"} {
		if strings.Contains(question, candidate) {
			service = candidate
			break
		}
	}
	if service == "" {
		return schema.AssistantMessage("DEMO: Ask about payments or orders.", nil), nil
	}
	calls := make([]schema.ToolCall, 0, 2)
	for i, name := range []string{"get_service_status", "get_runbook"} {
		calls = append(calls, schema.ToolCall{ID: fmt.Sprintf("demo-%d", i), Type: "function",
			Function: schema.FunctionCall{Name: name, Arguments: `{"service":"` + service + `"}`}})
	}
	return schema.AssistantMessage("", calls), nil
}
