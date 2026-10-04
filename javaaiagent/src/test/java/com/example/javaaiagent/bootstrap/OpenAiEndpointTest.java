package com.example.javaaiagent.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.example.javaaiagent.AgentApplication;
import com.example.javaaiagent.application.AgentModel;
import com.example.javaaiagent.application.Turn;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

class OpenAiEndpointTest {
    @Test
    void configuredBasePrefixAndCompletionPathReachTheProvider() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var authorization = new AtomicReference<String>();
        var requestBody = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        server.createContext(
                "/gateway/custom/chat",
                exchange -> {
                    try (exchange) {
                        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                        requestBody.set(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .readTree(exchange.getRequestBody()));
                        byte[] body =
                                """
                        {"id":"test","object":"chat.completion","created":1,"model":"test",
                         "choices":[{"index":0,"finish_reason":"stop",
                         "message":{"role":"assistant","content":"Configured endpoint reached"}}]}
                        """
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        server.start();
        try (var app =
                new SpringApplicationBuilder(AgentApplication.class)
                        .run(
                                "--server.port=0",
                                "--agent.environment=local",
                                "--agent.auth-mode=demo",
                                "--agent.model-mode=openai",
                                "--agent.openai-api-key=endpoint-test-key",
                                "--agent.llm-model=test",
                                "--agent.runtime.max-output-tokens=2048",
                                "--agent.runtime.workers=2",
                                "--agent.openai.completions-path=/custom/chat",
                                "--agent.openai.base-url=http://127.0.0.1:"
                                        + server.getAddress().getPort()
                                        + "/gateway/")) {
            Turn result =
                    app.getBean(AgentModel.class)
                            .complete(List.of(Turn.text("user", "Hello")), List.of());
            assertEquals("Configured endpoint reached", result.text());
            assertEquals("Bearer endpoint-test-key", authorization.get());
            assertEquals(2048, requestBody.get().path("max_tokens").asInt());
            assertEquals(
                    2, app.getBean(com.example.javaaiagent.config.RuntimeSettings.class).workers());
        } finally {
            server.stop(0);
        }
    }
}
