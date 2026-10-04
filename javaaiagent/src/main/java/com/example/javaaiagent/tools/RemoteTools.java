package com.example.javaaiagent.tools;

import com.example.javaaiagent.config.AgentSettings;
import com.example.javaaiagent.config.TimeoutSettings;
import com.example.javaaiagent.evidence.EvidenceTool;
import com.example.javaaiagent.evidence.ToolEvidence;
import com.example.javaaiagent.http.BoundedHttp;
import com.example.javaaiagent.http.StrictJson;
import com.example.javaaiagent.security.Caller;
import com.example.javaaiagent.templates.MessageTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Set;

/**
 * A pair of narrow read-only tools, bound to one verified caller.
 */
public final class RemoteTools {

    private final MessageTemplates templates;
    private final AgentSettings settings;
    private final TimeoutSettings timeouts;
    private final Caller caller;
    private final String requestId;
    private final HttpClient http;
    private final ObjectMapper json;

    public RemoteTools(AgentSettings settings, TimeoutSettings timeouts, Caller caller,
                       String requestId, HttpClient http, ObjectMapper json, MessageTemplates templates) {
        this.templates = templates;
        this.settings = settings;
        this.timeouts = timeouts;
        this.caller = caller;
        this.requestId = requestId;
        this.http = http;
        this.json = json;
    }

    public List<ToolCallback> callbacks() {
        return List.of(tool("get_service_status",
                        templates.text("status-description"),
                        "status:read",
                        settings.statusUrl(),
                        settings.statusToken()),
                tool("get_runbook",
                        templates.text("runbook-description"),
                        "runbooks:read",
                        settings.runbookUrl(),
                        settings.runbookToken()));
    }

    private ToolCallback tool(String name, String description, String scope, URI base, String token) {
        // Definitions are immutable for this request; do not re-render the schema during dispatch.
        var definition = new DefaultToolDefinition(name, description, templates.json("service-schema"));
        return new EvidenceTool() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public Outcome execute(String input) {
                return invoke(name, scope, base, token, input);
            }
        };
    }

    /**
     * The order matters: parse, authorize, fetch, validate, then expose data to the model.
     */
    private EvidenceTool.Outcome invoke(String name, String scope, URI base, String token, String input) {
        long started = System.nanoTime();
        String outcome = "invalid_arguments";
        String service = "invalid";
        try {
            String parsedService = requestedService(input);
            if (parsedService == null) {
                return new EvidenceTool.Outcome(templates.json("error-invalid-tool"), null, null);
            }
            service = parsedService;
            outcome = "denied";
            // Scope grants access to the operation; service grants access to the particular
            // resource.
            if (!caller.scopes().contains(scope) || !caller.services().contains(service)) {
                return new EvidenceTool.Outcome(templates.json("error-denied-tool"), service, null);
            }
            outcome = "unavailable";
            EvidenceTool.Outcome result = fetch(base, token, service, scope);
            outcome = "ok";
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new EvidenceTool.Outcome(unavailable(), service, null);
        } catch (Exception ex) {
            return new EvidenceTool.Outcome(unavailable(), service, null);
        } finally {
            audit(name, service, outcome, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private String requestedService(String input) {
        if (input == null || input.length() > 4096) return null;
        JsonNode args;
        try {
            args = StrictJson.reader(json).readValue(input);
        } catch (java.io.IOException ex) {
            return null;
        }
        if (args == null
                || !args.isObject()
                || args.size() != 1
                || !args.path("service").isTextual()
                || !Set.of("payments", "orders").contains(args.path("service").asText())) {
            return null;
        }
        return args.get("service").asText();
    }

    private EvidenceTool.Outcome fetch(URI base, String token, String service, String scope) throws Exception {
        // Only an allow-listed service name is appended; the model cannot choose a host or path.
        URI uri = URI.create(base.toString().replaceAll("/+$", "") + "/services/" + service);
        var request = HttpRequest.newBuilder(uri)
                .timeout(timeouts.toolCall())
                .header("Authorization", "Bearer " + token)
                .header("X-Request-ID", requestId)
                .GET()
                .build();
        var response = BoundedHttp.send(http, request, 8192, timeouts.toolCall());
        if (response.statusCode() != 200) {
            throw new IllegalArgumentException("Remote failure");
        }
        JsonNode data = StrictJson.reader(json).readValue(response.body());
        ToolEvidence evidence = ToolEvidence.from(data, service, scope.equals("status:read"));
        return new EvidenceTool.Outcome(templates.json("tool-result", java.util.Map.of("data", data)), service, evidence);
    }

    private String unavailable() {
        // Failure is data the model must acknowledge, never permission to invent a tool result.
        return templates.json("error-unavailable-tool");
    }

    private void audit(String name, String service, String outcome, long durationMs) {
        // Identifiers explain the decision without logging prompts, response bodies or credentials.
        LoggerFactory.getLogger("agent.audit").info("event=tool_call request_id={} tool={} service={} outcome={} duration_ms={}",
                requestId, name, service, outcome, durationMs);
    }
}
