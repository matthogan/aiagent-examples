package com.example.javaaiagent.application;

import static com.example.javaaiagent.TestTemplates.templates;
import static com.example.javaaiagent.TestTimeouts.defaults;
import static org.junit.jupiter.api.Assertions.*;

import com.example.javaaiagent.diagnostics.*;
import com.example.javaaiagent.evidence.*;
import com.example.javaaiagent.config.JsonSchemaSettings;
import com.example.javaaiagent.model.SpringAiAgentModel;
import com.example.javaaiagent.model.TimedAgentModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.retry.support.RetryTemplate;

class FinalRoundTest {
    private static EvidenceTool tool(AtomicInteger calls) {
        return new EvidenceTool() {
            public ToolDefinition getToolDefinition() {
                return new DefaultToolDefinition(
                        "get_service_status", "Read status", templates().json("service-schema"));
            }

            public Outcome execute(String arguments) {
                int count = calls.incrementAndGet();
                return new Outcome(
                        "verified result",
                        "payments",
                        new ToolEvidence(
                                "payments", "source-" + count, "degraded", "observed", List.of()));
            }
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "invented", "tool-call"})
    void fourthRoundAnswersFromExistingEvidenceOrStopsWithoutMoreTools(String finalReply)
            throws Exception {
        var modelCalls = new AtomicInteger();
        var toolCalls = new AtomicInteger();
        AgentModel model =
                (history, available) -> {
                    int round = modelCalls.incrementAndGet();
                    if (round < 4) assertEquals(1, available.size());
                    else {
                        assertTrue(available.isEmpty());
                        assertEquals(3, toolCalls.get());
                        assertEquals(
                                "source-3",
                                history.getLast().results().getFirst().evidence().source());
                        if (finalReply.equals("valid"))
                            return Turn.text("assistant", EvidenceAnswer.proposal(history));
                        if (finalReply.equals("invented"))
                            return Turn.text("assistant", "Everything is healthy");
                    }
                    return new Turn(
                            "assistant",
                            "",
                            List.of(new Turn.Call("call-" + round, "get_service_status", "{}")),
                            List.of());
                };
        var events = new java.util.ArrayList<Map<String, Object>>();
        var diagnostics =
                new RunDiagnostics(
                        null,
                        new com.example.javaaiagent.config.DiagnosticsSettings(null, null),
                        events::add);
        try (var timed = new TimedAgentModel(model, Duration.ofSeconds(2))) {
            var result =
                    new OperationsGraph(timed, defaults(), templates())
                            .execute("payments", List.of(tool(toolCalls)), diagnostics);
            assertEquals(
                    switch (finalReply) {
                        case "valid" -> Termination.SUCCESS;
                        case "invented" -> Termination.EVIDENCE_REJECTED;
                        default -> Termination.ROUND_LIMIT;
                    },
                    result.reason());
            if (finalReply.equals("valid")) assertTrue(result.text().contains("source-3"));
            diagnostics.finish(result.reason());
        }
        assertEquals(4, modelCalls.get());
        assertEquals(3, toolCalls.get());
        assertEquals(4, events.getLast().get("model_calls"));
        assertEquals(3, events.getLast().get("tool_calls"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {2, 6, 16})
    void configuredBudgetReservesItsLastRound(int budget) throws Exception {
        var modelCalls = new AtomicInteger();
        var toolCalls = new AtomicInteger();
        AgentModel model =
                (history, tools) -> {
                    modelCalls.incrementAndGet();
                    return tools.isEmpty()
                            ? Turn.text("assistant", EvidenceAnswer.proposal(history))
                            : new Turn(
                                    "assistant",
                                    "",
                                    List.of(new Turn.Call("call", "get_service_status", "{}")),
                                    List.of());
                };
        var limits = new com.example.javaaiagent.config.RuntimeSettings(budget, 1, 1000, 1);
        var result =
                new OperationsGraph(model, defaults(), templates(), limits)
                        .execute("payments", List.of(tool(toolCalls)), RunDiagnostics.quiet());
        assertEquals(Termination.SUCCESS, result.reason());
        assertEquals(budget, modelCalls.get());
        assertEquals(budget - 1, toolCalls.get());
    }

    @Test
    void configuredFanoutIsEnforcedBeforeToolExecution() throws Exception {
        var calls = new AtomicInteger();
        AgentModel model =
                (h, t) ->
                        new Turn(
                                "assistant",
                                "",
                                List.of(
                                        new Turn.Call("a", "get_service_status", "{}"),
                                        new Turn.Call("b", "get_service_status", "{}")),
                                List.of());
        var result =
                new OperationsGraph(
                                model,
                                defaults(),
                                templates(),
                                new com.example.javaaiagent.config.RuntimeSettings(4, 1, 1000, 1))
                        .execute("payments", List.of(tool(calls)), RunDiagnostics.quiet());
        assertEquals(Termination.TOOL_LIMIT, result.reason());
        assertEquals(0, calls.get());
    }

    @Test
    void realAdapterSendsAnswerOnlyFourthRequestWithHistoryAndOneSystemMessage() throws Exception {
        var json = new ObjectMapper();
        var requests =
                new java.util.concurrent.CopyOnWriteArrayList<
                        com.fasterxml.jackson.databind.JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    try (exchange) {
                        requests.add(json.readTree(exchange.getRequestBody()));
                        int round = requests.size();
                        Object message =
                                round < 4
                                        ? Map.of(
                                                "role",
                                                "assistant",
                                                "tool_calls",
                                                List.of(
                                                        Map.of(
                                                                "id",
                                                                "c" + round,
                                                                "type",
                                                                "function",
                                                                "function",
                                                                Map.of(
                                                                        "name",
                                                                        "get_service_status",
                                                                        "arguments",
                                                                        "{}"))))
                                        : Map.of(
                                                "role",
                                                "assistant",
                                                "content",
                                                "{\"observations\":[{\"service\":\"payments\",\"source\":\"source-3\",\"status\":\"degraded\"}],\"runbooks\":[]}");
                        byte[] body =
                                json.writeValueAsBytes(
                                        Map.of(
                                                "id",
                                                "test",
                                                "object",
                                                "chat.completion",
                                                "created",
                                                1,
                                                "model",
                                                "test",
                                                "choices",
                                                List.of(
                                                        Map.of(
                                                                "index",
                                                                0,
                                                                "finish_reason",
                                                                round < 4 ? "tool_calls" : "stop",
                                                                "message",
                                                                message))));
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        server.start();
        try {
            var provider =
                    OpenAiChatModel.builder()
                            .openAiApi(
                                    OpenAiApi.builder()
                                            .baseUrl(
                                                    "http://127.0.0.1:"
                                                            + server.getAddress().getPort())
                                            .apiKey("test")
                                            .build())
                            .defaultOptions(OpenAiChatOptions.builder().model("test").build())
                            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                            .build();
            var jsonSchemaSettings =
                    new JsonSchemaSettings(
                            new ClassPathResource("schemas/observation-response.json"));
            var calls = new AtomicInteger();
            var result =
                    new OperationsGraph(
                                    SpringAiAgentModel.create(
                                            provider, templates(), jsonSchemaSettings),
                                    defaults(),
                                    templates())
                            .execute("payments", List.of(tool(calls)), RunDiagnostics.quiet());
            assertEquals(Termination.SUCCESS, result.reason());
            assertEquals(4, requests.size());
            assertEquals(3, calls.get());
            assertEquals(1, requests.getFirst().path("tools").size());
            assertEquals("auto", requests.getFirst().path("tool_choice").asText());
            var last = requests.getLast();
            assertEquals("none", last.path("tool_choice").asText());
            assertEquals(0, last.path("tools").size());
            var messages = last.path("messages");
            assertEquals(8, messages.size()); // system, user, three assistant/tool pairs
            assertTrue(
                    messages.get(0)
                            .path("content")
                            .asText()
                            .contains(templates().text("final-round-instruction")));
            assertEquals("tool", messages.get(7).path("role").asText());
            assertEquals(
                    1,
                    java.util.stream.StreamSupport.stream(messages.spliterator(), false)
                            .filter(m -> m.path("role").asText().equals("system"))
                            .count());
        } finally {
            server.stop(0);
        }
    }
}
