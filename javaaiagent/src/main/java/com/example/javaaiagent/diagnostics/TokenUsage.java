package com.example.javaaiagent.diagnostics;

/** Null represents unavailable usage, never a fabricated zero. */
public record TokenUsage(Long input, Long output) {
    public TokenUsage {
        if (input != null && input < 0) input = null;
        if (output != null && output < 0) output = null;
    }

    public static TokenUsage unknown() {
        return new TokenUsage(null, null);
    }
}
