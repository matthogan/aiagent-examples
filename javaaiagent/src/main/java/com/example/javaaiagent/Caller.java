package com.example.javaaiagent;

import java.util.Set;

/** Identity established at the HTTP boundary, never constructed from model output. */
public record Caller(String subject, Set<String> scopes, Set<String> services) {
    public Caller {
        scopes = Set.copyOf(scopes);
        services = Set.copyOf(services);
    }
}
