package com.example.javaaiagent.diagnostics;

/** Stable machine-readable outcomes, independent of configurable messages. */
public enum Termination {
    SUCCESS(true),
    PARTIAL_EVIDENCE(true),
    NO_EVIDENCE(true),
    EVIDENCE_REJECTED(false),
    OUTPUT_LIMIT(false),
    ROUND_LIMIT(false),
    TOOL_LIMIT(false),
    INVALID_MODEL_OUTPUT(false),
    MODEL_TIMEOUT(false),
    EXECUTION_TIMEOUT(false),
    PROVIDER_ERROR(false),
    CANCELLED(false),
    CAPACITY_REJECTED(false),
    INTERNAL_ERROR(false);

    private final boolean successful;

    Termination(boolean successful) {
        this.successful = successful;
    }

    public boolean successful() {
        return successful;
    }
}
