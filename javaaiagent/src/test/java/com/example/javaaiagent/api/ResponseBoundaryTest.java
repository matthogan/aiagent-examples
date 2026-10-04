package com.example.javaaiagent.api;

import static com.example.javaaiagent.TestTemplates.templates;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.javaaiagent.application.AgentExecutionService;
import com.example.javaaiagent.config.TemplateSettings;
import com.example.javaaiagent.security.Caller;
import com.example.javaaiagent.templates.MessageTemplates;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class ResponseBoundaryTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void replyWrapperOverflowReturnsACorrelatedRpcError() throws Exception {
        Path file = directory.resolve("messages.json");
        Files.writeString(file, "{\"assistant-message\":\"Reply: {{text}}\"}");
        var templates =
                new MessageTemplates(
                        new TemplateSettings(file.toUri().toString()),
                        json,
                        new DefaultResourceLoader());
        var execution = mock(AgentExecutionService.class);
        when(execution.execute(anyString(), any(), anyString()))
                .thenReturn(
                        new com.example.javaaiagent.diagnostics.AgentResult(
                                "x".repeat(16_000),
                                com.example.javaaiagent.diagnostics.Termination.SUCCESS));
        var controller = new A2aController(null, json, execution, templates);
        var request = new MockHttpServletRequest();
        request.setAttribute("requestId", "test");
        request.setContent(
                """
                {"jsonrpc":"2.0","id":"request-1","method":"message/send",
                 "params":{"message":{"messageId":"message-1","role":"user",
                 "parts":[{"kind":"text","text":"payments"}]}}}
                """
                        .getBytes(StandardCharsets.UTF_8));
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        new Caller("test", Set.of(), Set.of()), null, List.of());
        var response = controller.send(request, authentication);
        var body = json.valueToTree(response.getBody());
        assertEquals(200, response.getStatusCode().value());
        assertEquals("request-1", body.path("id").asText());
        assertEquals(-32603, body.path("error").path("code").asInt());
        assertFalse(body.toString().contains("x".repeat(100)));
    }

    @Test
    void executionFailuresAreRpcErrorsWithStableReasons() throws Exception {
        for (var reason :
                List.of(
                        com.example.javaaiagent.diagnostics.Termination.EVIDENCE_REJECTED,
                        com.example.javaaiagent.diagnostics.Termination.ROUND_LIMIT,
                        com.example.javaaiagent.diagnostics.Termination.MODEL_TIMEOUT,
                        com.example.javaaiagent.diagnostics.Termination.EXECUTION_TIMEOUT)) {
            var execution = mock(AgentExecutionService.class);
            if (reason == com.example.javaaiagent.diagnostics.Termination.EXECUTION_TIMEOUT) {
                when(execution.execute(anyString(), any(), anyString()))
                        .thenThrow(new java.util.concurrent.TimeoutException("PRIVATE-ERROR"));
            } else if (reason == com.example.javaaiagent.diagnostics.Termination.MODEL_TIMEOUT) {
                when(execution.execute(anyString(), any(), anyString()))
                        .thenThrow(
                                new java.util.concurrent.ExecutionException(
                                        new com.example.javaaiagent.diagnostics.AgentFailure(
                                                reason)));
            } else {
                when(execution.execute(anyString(), any(), anyString()))
                        .thenReturn(
                                new com.example.javaaiagent.diagnostics.AgentResult(
                                        "safe configured message", reason));
            }
            var request = new MockHttpServletRequest();
            request.setAttribute("requestId", "correlation-id");
            request.setContent(
                    """
                    {"jsonrpc":"2.0","id":"rpc-id","method":"message/send",
                     "params":{"message":{"messageId":"m","role":"user",
                     "parts":[{"kind":"text","text":"payments"}]}}}
                    """
                            .getBytes(StandardCharsets.UTF_8));
            var auth =
                    UsernamePasswordAuthenticationToken.authenticated(
                            new Caller("test", Set.of(), Set.of()), null, List.of());
            var response =
                    new A2aController(null, json, execution, templates()).send(request, auth);
            var body = json.valueToTree(response.getBody());
            assertEquals("rpc-id", body.path("id").asText());
            assertFalse(body.has("result"));
            assertEquals(reason.name(), body.at("/error/data/reason").asText());
            assertEquals("correlation-id", body.at("/error/data/requestId").asText());
            assertFalse(body.toString().contains("PRIVATE-ERROR"));
        }
    }

    @Test
    void jsonRpcVersionMustBeAString() {
        byte[] body =
                "{\"jsonrpc\":2.0,\"id\":1,\"method\":\"message/send\"}"
                        .getBytes(StandardCharsets.UTF_8);
        var error =
                assertThrows(
                        JsonRpcRequestParser.InvalidRequest.class,
                        () -> JsonRpcRequestParser.parse(body, json, templates()));
        assertEquals(-32600, error.code);
        assertNull(error.id);
    }
}
