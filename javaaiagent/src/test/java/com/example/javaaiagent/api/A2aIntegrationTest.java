package com.example.javaaiagent.api;

import static com.example.javaaiagent.TestTemplates.templates;
import static com.example.javaaiagent.TestTimeouts.defaults;
import static org.junit.jupiter.api.Assertions.*;

import com.example.javaaiagent.AgentApplication;
import com.example.javaaiagent.application.OperationsGraph;
import com.example.javaaiagent.application.Turn;
import com.example.javaaiagent.config.AgentSettings;
import com.example.javaaiagent.config.JsonSchemaSettings;
import com.example.javaaiagent.demo.MockServices;
import com.example.javaaiagent.http.BoundedHttp;
import com.example.javaaiagent.model.SpringAiAgentModel;
import com.example.javaaiagent.security.Caller;
import com.example.javaaiagent.tools.RemoteTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.retry.support.RetryTemplate;

class A2aIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static ServletWebServerApplicationContext app;
    static HttpServer status;
    static HttpServer runbook;
    static HttpClient client;
    static URI base;
    static AgentSettings settings;

    @BeforeAll
    static void start() throws Exception {
        status = MockServices.create("status", 0, "local-demo-status-token", templates());
        runbook = MockServices.create("runbook", 0, "local-demo-runbook-token", templates());
        status.start();
        runbook.start();
        client = BoundedHttp.client(defaults().connect());
        var messageFile = java.nio.file.Files.createTempFile("a2a-messages", ".json");
        java.nio.file.Files.writeString(
                messageFile, "{\"assistant-message\":\"Configured reply: {{text}}\"}");
        app =
                (ServletWebServerApplicationContext)
                        new SpringApplicationBuilder(AgentApplication.class)
                                .run(
                                        "--server.port=0",
                                        "--agent.templates.location=" + messageFile.toUri(),
                                        "--agent.environment=local",
                                        "--agent.auth-mode=demo",
                                        "--agent.model-mode=demo",
                                        "--agent.demo-token=local-demo-client-token",
                                        "--agent.status-token=local-demo-status-token",
                                        "--agent.runbook-token=local-demo-runbook-token",
                                        "--agent.status-url=http://127.0.0.1:"
                                                + status.getAddress().getPort(),
                                        "--agent.runbook-url=http://127.0.0.1:"
                                                + runbook.getAddress().getPort());
        java.nio.file.Files.delete(messageFile);
        base = URI.create("http://127.0.0.1:" + app.getWebServer().getPort());
        settings = app.getBean(AgentSettings.class);
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
        if (status != null) status.stop(0);
        if (runbook != null) runbook.stop(0);
        if (client != null) client.close();
    }

    static String payload(String method, String text) throws Exception {
        return JSON.writeValueAsString(
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        "test-1",
                        "method",
                        method,
                        "params",
                        Map.of(
                                "message",
                                Map.of(
                                        "messageId",
                                        "msg-1",
                                        "role",
                                        "user",
                                        "parts",
                                        List.of(Map.of("kind", "text", "text", text))))));
    }

    static HttpResponse<String> post(String data) throws Exception {
        return client.send(
                HttpRequest.newBuilder(base.resolve("/"))
                        .header("Authorization", "Bearer local-demo-client-token")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(data))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void ambiguousJsonAndNullTextAreRejected() throws Exception {
        String valid = payload("message/send", "payments");
        for (String input :
                List.of(
                        valid + " {}",
                        valid.replace(
                                "\"jsonrpc\":\"2.0\"",
                                "\"jsonrpc\":\"2.0\",\"jsonrpc\":\"2.0\""))) {
            assertEquals(
                    -32700, JSON.readTree(post(input).body()).path("error").path("code").asInt());
        }
        assertEquals(
                -32602,
                JSON.readTree(post(valid.replace("\"text\":\"payments\"", "\"text\":null")).body())
                        .path("error")
                        .path("code")
                        .asInt());
    }

    @Test
    void fullA2aGraphAndTwoRemoteServices() throws Exception {
        var response = post(payload("message/send", "Investigate payments"));
        assertEquals(200, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertFalse(body.has("error"), response.body());
        assertEquals("message", body.path("result").path("kind").asText());
        String text = body.path("result").path("parts").get(0).path("text").asText();
        assertTrue(text.startsWith("Configured reply: "), text);
        assertTrue(text.contains("demo-status:payments"), text);
        assertTrue(text.contains("demo-runbook:payments"), text);
        assertTrue(text.contains("degraded"));
        assertTrue(response.headers().firstValue("x-request-id").isPresent());
    }

    @Test
    void protectedDiscoveryAdvertisesSecurityAndNoStreaming() throws Exception {
        var response =
                client.send(
                        HttpRequest.newBuilder(base.resolve("/.well-known/agent-card.json"))
                                .header("Authorization", "Bearer local-demo-client-token")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        JsonNode card = JSON.readTree(response.body());
        assertEquals("0.3.0", card.path("protocolVersion").asText());
        assertEquals("bearer", card.path("securitySchemes").path("bearer").path("scheme").asText());
        assertFalse(card.path("capabilities").path("streaming").asBoolean());
    }

    @Test
    void missingOrInvalidAuthenticationIsRejected() throws Exception {
        for (String token : List.of("", "Bearer invalid")) {
            var response =
                    client.send(
                            HttpRequest.newBuilder(base.resolve("/.well-known/agent-card.json"))
                                    .header("Authorization", token)
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
            assertTrue(response.headers().firstValue("www-authenticate").isPresent());
        }
    }

    @Test
    void livenessIsPublic() throws Exception {
        assertEquals(
                200,
                client.send(
                                HttpRequest.newBuilder(base.resolve("/healthz")).build(),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode());
    }

    @Test
    void unsupportedMethodsCannotExecute() throws Exception {
        for (String method :
                List.of("message/stream", "tasks/get", "tasks/pushNotificationConfig/set")) {
            assertEquals(
                    -32601,
                    JSON.readTree(post(payload(method, "payments")).body())
                            .path("error")
                            .path("code")
                            .asInt());
        }
    }

    @Test
    void oversizedMalformedAndEmptyRequestsAreRejected() throws Exception {
        assertEquals(413, post("x".repeat(16_385)).statusCode());
        assertEquals(-32700, JSON.readTree(post("{").body()).path("error").path("code").asInt());
        assertEquals(
                -32602,
                JSON.readTree(post(payload("message/send", "")).body())
                        .path("error")
                        .path("code")
                        .asInt());
        assertEquals(-32600, JSON.readTree(post("[]").body()).path("error").path("code").asInt());
    }

    @Test
    void conversationReuseAndDataPartsAreRejected() throws Exception {
        var payload =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        JSON.readTree(payload("message/send", "payments"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("params").path("message"))
                .put("contextId", "other-caller");
        assertEquals(
                -32602,
                JSON.readTree(post(payload.toString()).body()).path("error").path("code").asInt());
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("params").path("message"))
                .remove("contextId");
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        payload.path("params").path("message").path("parts").get(0))
                .put("kind", "data");
        assertEquals(
                -32602,
                JSON.readTree(post(payload.toString()).body()).path("error").path("code").asInt());
    }

    @Test
    void scopesResourcesAndArgumentsAreEnforcedAtToolBoundary() {
        var tools =
                new RemoteTools(
                                settings,
                                defaults(),
                                new Caller("restricted", Set.of("status:read"), Set.of("payments")),
                                "test",
                                client,
                                JSON,
                                templates())
                        .callbacks();
        assertTrue(tools.get(0).call("{\"service\":\"payments\"}").contains("degraded"));
        assertTrue(tools.get(1).call("{\"service\":\"payments\"}").contains("Access denied"));
        assertTrue(tools.get(0).call("{\"service\":\"orders\"}").contains("Access denied"));
        assertTrue(
                tools.get(0)
                        .call("{\"service\":\"https://evil.example\"}")
                        .contains("Invalid tool arguments"));
        assertTrue(
                tools.get(0)
                        .call("{\"service\":\"payments\",\"url\":\"https://evil.example\"}")
                        .contains("Invalid tool arguments"));
    }

    @Test
    void repeatingModelIsBounded() throws Exception {
        var count = new AtomicInteger();
        var graph =
                new OperationsGraph(
                        (history, tools) -> {
                            count.incrementAndGet();
                            return new Turn(
                                    "assistant",
                                    "",
                                    List.of(new Turn.Call("x", "unknown", "{}")),
                                    List.of());
                        },
                        defaults(),
                        templates());
        assertTrue(graph.answer("loop", List.of()).contains("limit reached"));
        assertEquals(4, count.get());
    }

    @Test
    void excessiveToolFanOutIsBounded() throws Exception {
        var call = new Turn.Call("x", "unknown", "{}");
        var graph =
                new OperationsGraph(
                        (history, tools) ->
                                new Turn("assistant", "", List.of(call, call, call), List.of()),
                        defaults(),
                        templates());
        assertTrue(graph.answer("loop", List.of()).contains("Too many tool calls"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,true", "true,true", "false,false"})
    void springAiAdapterReturnsToolCallsToGraph(boolean fabricateHealth, boolean includeUsage)
            throws Exception {
        var overrideFile = java.nio.file.Files.createTempFile("agent-message-templates", ".json");
        java.nio.file.Files.writeString(
                overrideFile,
                """
                {"system-prompt":"Custom test system prompt",
                 "user-message":"Custom question: {{text}}",
                 "status-description":"Custom status tool description",
                 "tool-result":{"evidence":"{{data}}"}}
                """);
        var configuredTemplates =
                new com.example.javaaiagent.templates.MessageTemplates(
                        new com.example.javaaiagent.config.TemplateSettings(
                                overrideFile.toUri().toString()),
                        JSON,
                        new org.springframework.core.io.DefaultResourceLoader());
        java.nio.file.Files.delete(overrideFile); // Loading is eager; requests do not reread files.
        var provider = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
        provider.createContext(
                "/v1/chat/completions",
                exchange -> {
                    try (exchange) {
                        JsonNode body = JSON.readTree(exchange.getRequestBody());
                        requests.add(body);
                        String message =
                                requests.size() == 1
                                        ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"get_service_status\",\"arguments\":\"{\\\"service\\\":\\\"payments\\\"}\"}}]}"
                                        : JSON.writeValueAsString(
                                                Map.of(
                                                        "role",
                                                        "assistant",
                                                        "content",
                                                        "{\"observations\":[{\"service\":\"payments\",\"source\":\"demo-status:payments\",\"status\":\""
                                                                + (fabricateHealth
                                                                        ? "healthy"
                                                                        : "degraded")
                                                                + "\"}],\"runbooks\":[]}"));
                        byte[] bytes =
                                ("{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"finish_reason\":\""
                                                + (requests.size() == 1 ? "tool_calls" : "stop")
                                                + "\",\"message\":"
                                                + message
                                                + "}]"
                                                + (includeUsage
                                                        ? ",\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":10,\"total_tokens\":40}"
                                                        : "")
                                                + "}")
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        provider.start();
        try {
            var api =
                    OpenAiApi.builder()
                            .baseUrl("http://127.0.0.1:" + provider.getAddress().getPort())
                            .apiKey("fake-test-key")
                            .build();
            var model =
                    OpenAiChatModel.builder()
                            .openAiApi(api)
                            .defaultOptions(
                                    OpenAiChatOptions.builder()
                                            .model("test")
                                            .internalToolExecutionEnabled(false)
                                            .build())
                            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                            .build();
            var tools =
                    new RemoteTools(
                                    settings,
                                    defaults(),
                                    new Caller("test", Set.of("status:read"), Set.of("payments")),
                                    "test",
                                    client,
                                    JSON,
                                    configuredTemplates)
                            .callbacks();
            var observedUsage =
                    new java.util.concurrent.CopyOnWriteArrayList<
                            org.springframework.ai.chat.metadata.Usage>();
            var diagnosticEvents = new java.util.ArrayList<java.util.Map<String, Object>>();
            var diagnostics =
                    new com.example.javaaiagent.diagnostics.RunDiagnostics(
                            null,
                            new com.example.javaaiagent.config.DiagnosticsSettings(null, null),
                            diagnosticEvents::add);
            var jsonSchemaSettings =
                    new JsonSchemaSettings(
                            new ClassPathResource("schemas/observation-response.json"));
            var result =
                    new OperationsGraph(
                                    SpringAiAgentModel.create(
                                            model,
                                            configuredTemplates,
                                            jsonSchemaSettings,
                                            response ->
                                                    observedUsage.add(
                                                            response.getMetadata().getUsage())),
                                    defaults(),
                                    configuredTemplates)
                            .execute("payments", tools, diagnostics);
            diagnostics.finish(result.reason());
            String answer = result.text();
            assertEquals(includeUsage ? 60L : null, diagnosticEvents.getLast().get("input_tokens"));
            assertEquals(
                    includeUsage ? 20L : null, diagnosticEvents.getLast().get("output_tokens"));
            assertEquals(1, diagnosticEvents.getLast().get("tool_calls"));
            if (fabricateHealth) {
                assertEquals(configuredTemplates.text("evidence-rejected"), answer);
            } else {
                assertTrue(answer.contains("demo-status:payments"));
                assertTrue(answer.contains("payments: degraded"));
            }
            assertEquals(2, requests.size());
            assertEquals(2, observedUsage.size());
            assertEquals(
                    includeUsage ? 60 : 0,
                    observedUsage.stream()
                            .mapToInt(org.springframework.ai.chat.metadata.Usage::getPromptTokens)
                            .sum());
            assertEquals(
                    includeUsage ? 20 : 0,
                    observedUsage.stream()
                            .mapToInt(
                                    org.springframework.ai.chat.metadata.Usage::getCompletionTokens)
                            .sum());
            assertEquals(
                    "Custom test system prompt\n\n"
                            + configuredTemplates.text("final-answer-contract"),
                    requests.getFirst().path("messages").get(0).path("content").asText());
            assertEquals(
                    "Custom question: payments",
                    requests.getFirst().path("messages").get(1).path("content").asText());
            assertEquals(2, requests.getFirst().path("messages").size());
            for (var request : requests) {
                assertEquals(
                        1,
                        java.util.stream.StreamSupport.stream(
                                        request.path("messages").spliterator(), false)
                                .filter(message -> message.path("role").asText().equals("system"))
                                .count());
            }
            assertTrue(
                    requests.getFirst()
                            .path("tools")
                            .toString()
                            .contains("Custom status tool description"));
            assertTrue(requests.getLast().path("messages").toString().contains("evidence"));
            assertEquals(2, requests.getFirst().path("tools").size());
            assertTrue(
                    requests.getLast().path("messages").toString().contains("\"role\":\"tool\""));
            assertTrue(requests.getLast().path("messages").toString().contains("degraded"));
        } finally {
            provider.stop(0);
        }
    }
}
