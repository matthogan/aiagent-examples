package com.example.javaaiagent.application;

import com.example.javaaiagent.evidence.ToolEvidence;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Serializable graph state; only locally constructed objects enter the state serializer.
 */
public record Turn(String role, String text, List<Call> calls, List<Result> results)
        implements Serializable {

    public Turn {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
        calls = List.copyOf(calls);
        results = List.copyOf(results);
    }

    public record Call(String id, String name, String arguments) implements Serializable {
    }

    public record Result(String id, String name, String data, String service,
                         ToolEvidence evidence) implements Serializable {
        public Result(String id, String name, String data) {
            this(id, name, data, null, null);
        }
    }

    public static Turn text(String role, String text) {
        return new Turn(role, text, List.of(), List.of());
    }
}
