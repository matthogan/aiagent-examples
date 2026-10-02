package com.example.javaaiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.a2a.spec.*;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Minimal Spring MVC A2A 0.3 transport using the official SDK's wire types. */
@RestController
public class A2aController {
    private final AgentSettings settings;
    private final ObjectMapper json;
    private final OperationsGraph graph;
    private final HttpClient http = BoundedHttp.client();
    private final ExecutorService work = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("agent-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public A2aController(AgentSettings settings, ObjectMapper json, OperationsGraph graph) {
        this.settings = settings;
        this.json = json;
        this.graph = graph;
    }

    @GetMapping("/healthz")
    public Map<String, String> health() { return Map.of("status", "ok"); }

    @GetMapping("/.well-known/agent-card.json")
    public AgentCard card() {
        return new AgentCard.Builder().name("Operations assistant (Java)")
                .description("Read-only service health and runbook assistant")
                .url(settings.url().toString()).version("0.1.0").protocolVersion("0.3.0")
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text/plain")).defaultOutputModes(List.of("text/plain"))
                .securitySchemes(Map.of("bearer", new HTTPAuthSecurityScheme(
                        settings.authMode().equals("jwt") ? "JWT" : "opaque", "bearer",
                        "Requires agent:invoke; tools also require scopes and service grants")))
                .security(List.of(Map.of("bearer", List.of())))
                .skills(List.of(new AgentSkill.Builder().id("operations").name("Investigate service health")
                        .description("Read health and suggest runbook steps for payments or orders")
                        .tags(List.of("operations", "read-only"))
                        .examples(List.of("Why is payments degraded, and what should I check?")).build()))
                .build();
    }

    @PostMapping(value = "/", produces = "application/json")
    public ResponseEntity<?> send(HttpServletRequest request, Authentication authentication) throws Exception {
        byte[] body = request.getInputStream().readNBytes(16_385);
        if (body.length > 16_384) return ResponseEntity.status(413).body(Map.of("error", "Request exceeds 16 KiB"));
        JsonNode payload;
        try {
            payload = json.readTree(body);
        } catch (Exception ex) {
            return rpcError(null, -32700, "Invalid JSON");
        }
        if (payload == null || !payload.isObject() || !payload.path("jsonrpc").asText().equals("2.0")
                || !payload.path("method").isTextual() || !payload.hasNonNull("id")
                || !(payload.get("id").isTextual() || payload.get("id").isIntegralNumber())) {
            return rpcError(null, -32600, "A JSON-RPC request with an id is required");
        }
        Object id = json.convertValue(payload.get("id"), Object.class);
        if (!payload.get("method").asText().equals("message/send")) {
            return rpcError(id, -32601, "Only message/send is supported");
        }
        String question;
        try {
            JsonNode wireMessage = payload.path("params").path("message");
            if (!wireMessage.isObject()) throw new IllegalArgumentException();
            // kind is optional in some 0.3 clients, including the Python SDK request builder.
            if (!wireMessage.has("kind")) ((ObjectNode) wireMessage).put("kind", "message");
            var parsed = json.treeToValue(payload, SendMessageRequest.class);
            Message message = parsed.getParams().message();
            if (message.getRole() != Message.Role.USER || message.getParts().isEmpty()
                    || message.getTaskId() != null || message.getContextId() != null
                    || message.getReferenceTaskIds() != null
                    || message.getParts().stream().anyMatch(p -> !(p instanceof TextPart))) {
                throw new IllegalArgumentException();
            }
            question = message.getParts().stream().map(p -> ((TextPart) p).getText())
                    .collect(java.util.stream.Collectors.joining("\n")).strip();
            if (question.isBlank() || question.length() > 4000) throw new IllegalArgumentException();
        } catch (Exception ex) {
            return rpcError(id, -32602, "Use a new user text message containing 1–4000 characters");
        }
        var caller = (Caller) authentication.getPrincipal();
        String requestId = (String) request.getAttribute("requestId");
        var tools = new RemoteTools(settings, caller, requestId, http, json).callbacks();
        Future<String> task;
        try {
            task = work.submit(() -> graph.answer(question, tools));
        } catch (RejectedExecutionException ex) {
            return ResponseEntity.status(429).body(Map.of("error", "Agent is busy; retry later"));
        }
        String answer;
        try {
            answer = task.get(45, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            return rpcError(id, -32603, "Request interrupted");
        } catch (ExecutionException | TimeoutException ex) {
            task.cancel(true);
            LoggerFactory.getLogger("agent.audit").warn("event=execution_failed request_id={} error_type={}",
                    requestId, ex.getClass().getSimpleName());
            answer = "The request could not be completed. Retry or contact the service operator.";
        }
        return ResponseEntity.ok(new SendMessageResponse(id,
                new Message.Builder().role(Message.Role.AGENT).parts(new TextPart(answer)).build()));
    }

    private ResponseEntity<?> rpcError(Object id, int code, String message) {
        var error = json.createObjectNode().put("jsonrpc", "2.0");
        error.set("id", json.valueToTree(id));
        error.putObject("error").put("code", code).put("message", message);
        return ResponseEntity.ok(error);
    }

    @PreDestroy void close() {
        work.shutdownNow();
        http.shutdownNow();
    }
}
