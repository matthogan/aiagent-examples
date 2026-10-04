package com.example.javaaiagent.application;

import com.example.javaaiagent.concurrent.BoundedExecutor;
import com.example.javaaiagent.config.AgentSettings;
import com.example.javaaiagent.config.DiagnosticsSettings;
import com.example.javaaiagent.config.TimeoutSettings;
import com.example.javaaiagent.diagnostics.AgentFailure;
import com.example.javaaiagent.diagnostics.AgentResult;
import com.example.javaaiagent.diagnostics.RunDiagnostics;
import com.example.javaaiagent.security.Caller;
import com.example.javaaiagent.templates.MessageTemplates;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Owns request execution capacity; caller-bound tools are supplied through a port.
 */
@Service
public final class AgentExecutionService {

    private final DiagnosticsSettings diagnosticsSettings;
    private final MessageTemplates templates;
    private final AgentSettings settings;
    private final ToolProvider toolProvider;
    private final OperationsGraph graph;
    private final TimeoutSettings timeouts;
    // No waiting queue: when all workers are busy, reject promptly instead of accumulating
    // requests.
    private final BoundedExecutor workers;

    public AgentExecutionService(
            AgentSettings settings,
            ToolProvider toolProvider,
            OperationsGraph graph,
            TimeoutSettings timeouts,
            MessageTemplates templates,
            DiagnosticsSettings diagnosticsSettings,
            com.example.javaaiagent.config.RuntimeSettings runtime) {
        this.diagnosticsSettings = diagnosticsSettings;
        this.templates = templates;
        this.settings = settings;
        this.timeouts = timeouts;
        this.toolProvider = toolProvider;
        this.graph = graph;
        this.workers = new BoundedExecutor("agent-", runtime.workers());
    }

    public String answer(String question, Caller caller, String requestId)
            throws InterruptedException, ExecutionException, TimeoutException {
        return execute(question, caller, requestId).text();
    }

    public AgentResult execute(String question, Caller caller, String requestId)
            throws InterruptedException, ExecutionException, TimeoutException {
        var diagnostics = RunDiagnostics.logging(requestId, diagnosticsSettings);
        try {
            diagnostics.configuration(settings.llmModel(), settings.modelMode(), templates.fingerprint());
            var tools = toolProvider.forCaller(caller, requestId);
            var result = workers.call(() -> graph.execute(question, tools, diagnostics), timeouts.execution());
            diagnostics.finish(result.reason());
            return result;
        } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException ex) {
            diagnostics.finish(AgentFailure.classify(ex));
            throw ex;
        }
    }

    @PreDestroy
    void close() {
        workers.close();
    }
}
