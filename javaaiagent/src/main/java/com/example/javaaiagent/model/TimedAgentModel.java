package com.example.javaaiagent.model;

import com.example.javaaiagent.application.AgentModel;
import com.example.javaaiagent.application.Turn;
import com.example.javaaiagent.concurrent.BoundedExecutor;
import com.example.javaaiagent.config.RuntimeSettings;
import com.example.javaaiagent.config.TimeoutSettings;
import com.example.javaaiagent.diagnostics.AgentFailure;
import com.example.javaaiagent.diagnostics.Termination;
import com.example.javaaiagent.diagnostics.TokenUsage;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Bounds the whole provider call, including body consumption and adapter conversion.
 */
public final class TimedAgentModel implements AgentModel, AutoCloseable {

    private final AgentModel delegate;
    private final Duration timeout;
    // A bounded pool also caps calls whose underlying provider ignores interruption.
    private final BoundedExecutor workers;

    public TimedAgentModel(AgentModel delegate, Duration timeout) {
        this(delegate, timeout, RuntimeSettings.defaults().workers());
    }

    public TimedAgentModel(AgentModel delegate, Duration timeout, int workers) {
        this.workers = new BoundedExecutor("llm-", workers);
        this.delegate = delegate;
        this.timeout = TimeoutSettings.validate("llm-call", timeout);
    }

    @Override
    public Turn complete(List<Turn> history, List<ToolCallback> tools) {
        return complete(history, tools, usage -> {
        });
    }

    @Override
    public Turn complete(List<Turn> history, List<ToolCallback> tools, Consumer<TokenUsage> usage) {
        try {
            return workers.call(() -> delegate.complete(history, tools, usage), timeout);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Model call interrupted");
        } catch (TimeoutException ex) {
            throw new AgentFailure(Termination.MODEL_TIMEOUT);
        } catch (ExecutionException ex) {
            throw new AgentFailure(Termination.PROVIDER_ERROR);
        }
    }

    @Override
    public boolean scripted() {
        return delegate.scripted();
    }

    @Override
    public void close() {
        workers.close();
    }
}
