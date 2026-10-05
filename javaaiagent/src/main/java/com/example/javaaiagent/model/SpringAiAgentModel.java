package com.example.javaaiagent.model;

import com.example.javaaiagent.application.AgentModel;
import com.example.javaaiagent.application.Turn;
import com.example.javaaiagent.config.JsonSchemaSettings;
import com.example.javaaiagent.diagnostics.TokenUsage;
import com.example.javaaiagent.templates.MessageTemplates;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Translates graph history into Spring AI messages without handing over tool execution.
 */
public final class SpringAiAgentModel {

    private SpringAiAgentModel() {
    }

    public static AgentModel create(OpenAiChatModel model, MessageTemplates templates, JsonSchemaSettings jsonSchemaSettings) {
        return create(model, templates, jsonSchemaSettings, response -> {
        });
    }

    /**
     * Optional response observation for usage accounting; does not change the agent loop.
     */
    public static AgentModel create(OpenAiChatModel model, MessageTemplates templates, JsonSchemaSettings jsonSchemaSettings, Consumer<ChatResponse> observer) {
        return new AgentModel() {
            @Override
            public Turn complete(List<Turn> history, List<ToolCallback> tools) {
                return complete(history, tools, usage -> {
                });
            }

            @Override
            public Turn complete(List<Turn> history, List<ToolCallback> tools, Consumer<TokenUsage> usageObserver) {
                List<Message> messages = messages(history, templates, tools.isEmpty());
                String observationResponseSchemaContent;
                try {
                    var observationResponseSchema = jsonSchemaSettings.observationResponse();
                    observationResponseSchemaContent = observationResponseSchema.getContentAsString(Charset.defaultCharset());
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to read observation response schema content. Check agent.json-schemas.", e);
                }
                var responseFormat = tools.isEmpty() ? ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build()
                        : ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA).jsonSchema(observationResponseSchemaContent).build();
                // LangGraph4j owns the tool loop; Spring AI must not execute tools internally.
                var options = OpenAiChatOptions.builder()
                        .toolCallbacks(tools)
                        .internalToolExecutionEnabled(false)
                        .toolChoice(tools.isEmpty() ? "none" : "auto")
                        .responseFormat(responseFormat)
                        .build();
                var response = model.call(new Prompt(messages, options));
                observer.accept(response);
                var usage = response.getMetadata().getUsage();
                // Spring AI's EmptyUsage represents missing provider accounting.
                if (usage != null && !(usage instanceof EmptyUsage)) {
                    usageObserver.accept(new TokenUsage(
                            usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue(),
                            usage.getCompletionTokens() == null ? null : usage.getCompletionTokens().longValue()));
                }
                var output = response.getResult().getOutput();
                return assistantTurn(output);
            }
        };
    }

    /**
     * Keep provider message types at this adapter boundary; graph state stays provider-neutral.
     */
    private static List<Message> messages(List<Turn> history, MessageTemplates templates, boolean answerOnly) {
        List<Message> messages = new ArrayList<>();
        // Some provider chat templates accept only one leading system message.
        messages.add(new SystemMessage(templates.text("system-prompt")
                + "\n\n"
                + templates.text("final-answer-contract")
                + (answerOnly
                ? "\n\n" + templates.text("final-round-instruction")
                : "")));
        history.stream().map(turn -> message(turn, templates)).forEach(messages::add);
        return messages;
    }

    private static Message message(Turn turn, MessageTemplates templates) {
        return switch (turn.role()) {
            case "user" -> new UserMessage(templates.text("user-message", java.util.Map.of("text", turn.text())));
            case "assistant" -> AssistantMessage.builder()
                    .content(turn.text())
                    .toolCalls(turn.calls().stream()
                            .map(SpringAiAgentModel::toolCall)
                            .toList())
                    .build();
            case "tool" -> ToolResponseMessage.builder()
                    .responses(turn.results().stream()
                            .map(SpringAiAgentModel::toolResult)
                            .toList())
                    .build();
            default -> throw new IllegalArgumentException("Unknown history role");
        };
    }

    private static AssistantMessage.ToolCall toolCall(Turn.Call call) {
        return new AssistantMessage.ToolCall(call.id(), "function", call.name(), call.arguments());
    }

    private static ToolResponseMessage.ToolResponse toolResult(Turn.Result result) {
        // The id links a result to the model's original call, including rounds with multiple tools.
        return new ToolResponseMessage.ToolResponse(result.id(), result.name(), result.data());
    }

    private static Turn assistantTurn(AssistantMessage output) {
        // Tool-only replies can have null text; calls still need to reach the graph for
        // authorization.
        return new Turn("assistant",
                output.getText() == null ? "" : output.getText(),
                output.getToolCalls().stream()
                        .map(call -> new Turn.Call(call.id(), call.name(), call.arguments()))
                        .toList(),
                List.of());
    }
}
