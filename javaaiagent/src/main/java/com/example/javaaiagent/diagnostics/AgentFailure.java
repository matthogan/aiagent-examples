package com.example.javaaiagent.diagnostics;

import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Never retain provider exception messages or causes: graph libraries may log them.
 */
public final class AgentFailure extends IllegalStateException {
    private final Termination reason;

    public AgentFailure(Termination reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Termination reason() {
        return reason;
    }

    public static Termination classify(Throwable error) {
        for (int i = 0; error != null && i < 10; i++, error = error.getCause()) {
            if (error instanceof AgentFailure failure) {
                return failure.reason();
            }
            if (error instanceof TimeoutException) {
                return Termination.EXECUTION_TIMEOUT;
            }
            if (error instanceof InterruptedException || error instanceof CancellationException) {
                return Termination.CANCELLED;
            }
            if (error instanceof RejectedExecutionException) {
                return Termination.CAPACITY_REJECTED;
            }
        }
        return Termination.INTERNAL_ERROR;
    }
}
