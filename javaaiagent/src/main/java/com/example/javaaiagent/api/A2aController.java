package com.example.javaaiagent.api;

import com.example.javaaiagent.application.AgentExecutionService;
import com.example.javaaiagent.config.AgentSettings;
import com.example.javaaiagent.diagnostics.AgentFailure;
import com.example.javaaiagent.diagnostics.AgentResult;
import com.example.javaaiagent.diagnostics.Termination;
import com.example.javaaiagent.security.Caller;
import com.example.javaaiagent.templates.MessageTemplates;
import com.example.javaaiagent.templates.TemplateRenderException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import io.a2a.spec.HTTPAuthSecurityScheme;
import io.a2a.spec.Message;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.TextPart;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Minimal Spring MVC A2A 0.3 transport using the official SDK's wire types.
 */
@RestController
public class A2aController {

    private static final int MAX_REQUEST_BYTES = 16_384;
    private final MessageTemplates templates;
    private final AgentSettings settings;
    private final ObjectMapper json;
    private final AgentExecutionService execution;

    public A2aController(AgentSettings settings, ObjectMapper json, AgentExecutionService execution, MessageTemplates templates) {
        this.templates = templates;
        this.settings = settings;
        this.json = json;
        this.execution = execution;
    }

    /**
     * Liveness checks the process only; downstream outages must not trigger restart loops.
     */
    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /**
     * Advertise only implemented capabilities; authentication is enforced by the filter chain.
     */
    @GetMapping("/.well-known/agent-card.json")
    public AgentCard card() {
        return new AgentCard.Builder()
                .name(templates.text("agent-name"))
                .description(templates.text("agent-description"))
                .url(settings.url().toString())
                .version("0.1.0")
                .protocolVersion("0.3.0")
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .securitySchemes(Map.of("bearer", bearerScheme()))
                .security(List.of(Map.of("bearer", List.of())))
                .skills(List.of(operationsSkill()))
                .build();
    }

    private HTTPAuthSecurityScheme bearerScheme() {
        return new HTTPAuthSecurityScheme(
                settings.authMode().equals("jwt") ? "JWT" : "opaque",
                "bearer",
                templates.text("security-description"));
    }

    private AgentSkill operationsSkill() {
        return new AgentSkill.Builder()
                .id("operations")
                .name(templates.text("skill-name"))
                .description(templates.text("skill-description"))
                .tags(List.of("operations", "read-only"))
                .examples(List.of(templates.text("client-question")))
                .build();
    }

    @PostMapping(value = "/", produces = "application/json")
    public ResponseEntity<?> send(HttpServletRequest request, Authentication authentication) throws Exception {
        // Read one extra byte to detect an oversized body without buffering an arbitrary upload.
        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            return ResponseEntity.status(413).body(templates.render("error-oversized", Map.of()));
        }
        try {
            var parsed = JsonRpcRequestParser.parse(body, json, templates);
            // Spring Security supplied this identity; request JSON cannot supply caller grants.
            var caller = (Caller) authentication.getPrincipal();
            return execute(parsed, caller, (String) request.getAttribute("requestId"));
        } catch (JsonRpcRequestParser.InvalidRequest ex) {
            return rpcError(ex.id, ex.code, ex.getMessage());
        }
    }

    /**
     * Translate execution outcomes here so the application layer does not need HTTP concepts.
     */
    private ResponseEntity<?> execute(JsonRpcRequestParser.Request request, Caller caller, String requestId) {
        AgentResult answer;
        try {
            answer = execution.execute(request.question(), caller, requestId);
        } catch (RejectedExecutionException ex) {
            return ResponseEntity.status(429).body(templates.render("error-busy", Map.of()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return executionError(request.id(),
                    templates.text("rpc-interrupted"),
                    Termination.CANCELLED,
                    requestId);
        } catch (ExecutionException | TimeoutException | RuntimeException ex) {
            return executionError(request.id(),
                    templates.text("execution-failed"),
                    AgentFailure.classify(ex),
                    requestId);
        }
        if (!answer.reason().successful()) {
            return executionError(request.id(), answer.text(), answer.reason(), requestId);
        }
        return answerResponse(request.id(), answer.text(), requestId);
    }

    private ResponseEntity<?> answerResponse(Object id, String answer, String requestId) {
        try {
            String text = templates.text("assistant-message", Map.of("text", answer));
            return ResponseEntity.ok(new SendMessageResponse(id,
                    new Message.Builder()
                            .role(Message.Role.AGENT)
                            .parts(new TextPart(text))
                            .build()));
        } catch (TemplateRenderException ex) {
            // A wrapper can exceed the limit even when the model's answer fits. Keep the RPC
            // contract.
            return executionError(id, templates.text("execution-failed"), Termination.OUTPUT_LIMIT, requestId);
        }
    }

    private ResponseEntity<?> executionError(Object id, String message, Termination reason, String requestId) {
        LoggerFactory.getLogger("agent.audit")
                .info("event=response_failed request_id={} reason={}", requestId, reason);
        var body = json.createObjectNode().put("jsonrpc", "2.0");
        body.set("id", json.valueToTree(id));
        body.putObject("error")
                .put("code", -32603)
                .put("message", message)
                .putObject("data")
                .put("reason", reason.name())
                .put("requestId", requestId);
        return ResponseEntity.ok(body);
    }

    // JSON-RPC errors use an HTTP 200 envelope; transport limits above use HTTP 413/429.
    private ResponseEntity<?> rpcError(Object id, int code, String message) {
        var error = json.createObjectNode().put("jsonrpc", "2.0");
        error.set("id", json.valueToTree(id));
        error.putObject("error").put("code", code).put("message", message);
        return ResponseEntity.ok(error);
    }
}
