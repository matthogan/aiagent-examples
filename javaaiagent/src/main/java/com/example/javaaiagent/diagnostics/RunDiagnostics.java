package com.example.javaaiagent.diagnostics;

import com.example.javaaiagent.config.DiagnosticsSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Request-owned, explicitly passed across workers. No prompts, arguments or bodies are accepted.
 */
public final class RunDiagnostics {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String requestId;
    private final DiagnosticsSettings prices;
    private final Consumer<Map<String, Object>> sink;
    private final long started = System.nanoTime();
    private boolean finished;
    private int modelCalls, toolCalls, missingUsage, completedModels;
    private long inputTokens, outputTokens;

    public RunDiagnostics(String requestId, DiagnosticsSettings prices, Consumer<Map<String, Object>> sink) {
        // Only server-created UUIDs enter logs; other callers receive a fresh correlation ID.
        this.requestId = requestId != null && requestId.matches("[a-fA-F0-9-]{36}") ? requestId : UUID.randomUUID().toString();
        this.prices = prices;
        this.sink = sink;
        emit("run_started", Map.of());
    }

    public static RunDiagnostics logging(String requestId, DiagnosticsSettings prices) {
        return new RunDiagnostics(requestId,
                prices,
                fields -> {
                    try {
                        LoggerFactory.getLogger("agent.diagnostics")
                                .info("{}", JSON.writeValueAsString(fields));
                    } catch (java.io.IOException ignored) {
                        /* Only primitive diagnostic fields are serialized. */
                    }
                });
    }

    public synchronized void configuration(String model, String mode, String templatesHash) {
        if (!finished) {
            emit("run_configuration", Map.of(
                    "model", model == null ? "unspecified" : model,
                    "model_mode", mode,
                    "templates_sha256", templatesHash));
        }
    }

    public static RunDiagnostics quiet() {
        return new RunDiagnostics(null, new DiagnosticsSettings(null, null), fields -> {
        });
    }

    public synchronized int modelStarted() {
        if (finished) {
            return 0;
        }
        int round = ++modelCalls;
        emit("model_started", Map.of("round", round));
        return round;
    }

    public synchronized void modelFinished(int round, long start, TokenUsage usage, String outcome) {
        if (finished) {
            return;
        }
        completedModels++;
        if (usage.input() == null || usage.output() == null) {
            missingUsage++;
        }
        if (usage.input() != null) {
            inputTokens += usage.input();
        }
        if (usage.output() != null) {
            outputTokens += usage.output();
        }
        var fields = new LinkedHashMap<String, Object>();
        fields.put("round", round);
        fields.put("duration_ms", elapsed(start));
        fields.put("outcome", outcome);
        fields.put("input_tokens", usage.input());
        fields.put("output_tokens", usage.output());
        emit("model_finished", fields);
    }

    public synchronized void toolStarted(String tool) {
        if (finished) {
            return;
        }
        toolCalls++;
        emit("tool_started", Map.of("call", toolCalls, "tool", safeTool(tool)));
    }

    private static String safeTool(String tool) {
        return "get_service_status".equals(tool) || "get_runbook".equals(tool) ? tool : "unknown";
    }

    public synchronized void toolFinished(String tool, long start, boolean evidence) {
        if (finished) {
            return;
        }
        // Unknown model-selected tool names must not become arbitrary log content.
        String safeTool = safeTool(tool);
        emit("tool_finished", Map.of(
                "call", toolCalls,
                "tool", safeTool,
                "duration_ms", elapsed(start),
                "evidence_available", evidence));
    }

    public synchronized void finish(Termination reason) {
        if (finished) {
            return;
        }
        finished = true;
        var fields = new LinkedHashMap<String, Object>();
        fields.put("reason", reason.name());
        fields.put("successful", reason.successful());
        fields.put("duration_ms", elapsed(started));
        fields.put("model_calls", modelCalls);
        fields.put("tool_calls", toolCalls);
        // An unfinished provider call means aggregate usage is incomplete too.
        boolean complete = modelCalls == completedModels && missingUsage == 0;
        fields.put("usage_complete", complete);
        fields.put("input_tokens", complete ? inputTokens : null);
        fields.put("output_tokens", complete ? outputTokens : null);
        fields.put("estimated_cost_usd", complete && modelCalls > 0 && prices.inputUsdPerMillion() != null
                ? prices.inputUsdPerMillion()
                .multiply(BigDecimal.valueOf(inputTokens))
                .add(prices.outputUsdPerMillion().multiply(BigDecimal.valueOf(outputTokens)))
                .movePointLeft(6)
                : null);
        emit("run_finished", fields);
    }

    private void emit(String event, Map<String, Object> values) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("event", event);
        fields.put("request_id", requestId);
        fields.putAll(values);
        sink.accept(java.util.Collections.unmodifiableMap(fields));
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
