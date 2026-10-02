package com.example.javaaiagent;

import java.io.Serializable;
import java.util.List;

/** Serializable graph state; only locally constructed objects enter the state serializer. */
public record Turn(String role, String text, List<Call> calls, List<Result> results) implements Serializable {
    public record Call(String id, String name, String arguments) implements Serializable { }
    public record Result(String id, String name, String data) implements Serializable { }
    public static Turn text(String role, String text) { return new Turn(role, text, List.of(), List.of()); }
}
