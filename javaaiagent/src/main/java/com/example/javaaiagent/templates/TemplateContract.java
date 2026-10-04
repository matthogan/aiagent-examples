package com.example.javaaiagent.templates;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One definition of each public catalog key, its value type and its required variables.
 */
enum TemplateContract {
    SYSTEM_PROMPT(Kind.TEXT),
    FINAL_ANSWER_CONTRACT(Kind.TEXT),
    FINAL_ROUND_INSTRUCTION(Kind.TEXT),
    EVIDENCE_REJECTED(Kind.TEXT),
    EVIDENCE_NONE(Kind.TEXT),
    EVIDENCE_DEMO(Kind.TEXT),
    EVIDENCE_MISSING(Kind.TEXT, "tool", "service"),
    EVIDENCE_STATUS(Kind.TEXT, "service", "status", "source", "summary"),
    EVIDENCE_RUNBOOK(Kind.TEXT, "service", "source", "steps"),
    EVIDENCE_DISCLAIMER(Kind.TEXT),
    EVIDENCE_ANSWER(Kind.TEXT, "results"),
    EVIDENCE_OVERFLOW(Kind.TEXT),
    USER_MESSAGE(Kind.TEXT, "text"),
    TOOL_RESULT(Kind.VALUE, "data"),
    SERVICE_SCHEMA(Kind.OBJECT),
    STATUS_DESCRIPTION(Kind.TEXT),
    RUNBOOK_DESCRIPTION(Kind.TEXT),
    ERROR_INVALID_TOOL(Kind.OBJECT),
    ERROR_DENIED_TOOL(Kind.OBJECT),
    ERROR_UNAVAILABLE_TOOL(Kind.OBJECT),
    ERROR_UNKNOWN_TOOL(Kind.OBJECT),
    GRAPH_LIMIT(Kind.TEXT),
    GRAPH_TOO_MANY(Kind.TEXT),
    GRAPH_INVALID(Kind.TEXT),
    EXECUTION_FAILED(Kind.TEXT),
    DEMO_UNKNOWN(Kind.TEXT),
    DEMO_ANSWER(Kind.TEXT, "results"),
    DEMO_CALL(Kind.OBJECT, "service"),
    CLIENT_QUESTION(Kind.TEXT),
    RPC_INVALID_JSON(Kind.TEXT),
    RPC_INVALID_ENVELOPE(Kind.TEXT),
    RPC_UNSUPPORTED_METHOD(Kind.TEXT),
    RPC_INVALID_PARAMS(Kind.TEXT),
    RPC_INTERRUPTED(Kind.TEXT),
    ERROR_BUSY(Kind.OBJECT),
    ERROR_OVERSIZED(Kind.OBJECT),
    DEMO_STATUS(Kind.OBJECT, "service", "status", "summary"),
    DEMO_RUNBOOK(Kind.OBJECT, "service"),
    DEMO_DEGRADED_SUMMARY(Kind.TEXT),
    DEMO_HEALTHY_SUMMARY(Kind.TEXT),
    DEMO_UNAUTHORIZED(Kind.OBJECT),
    DEMO_METHOD(Kind.OBJECT),
    DEMO_NOT_FOUND(Kind.OBJECT),
    ASSISTANT_MESSAGE(Kind.TEXT, "text"),
    AGENT_NAME(Kind.TEXT),
    AGENT_DESCRIPTION(Kind.TEXT),
    SECURITY_DESCRIPTION(Kind.TEXT),
    SKILL_NAME(Kind.TEXT),
    SKILL_DESCRIPTION(Kind.TEXT);

    enum Kind {
        TEXT,
        OBJECT,
        VALUE
    }

    private static final Map<String, TemplateContract> BY_KEY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(TemplateContract::key, Function.identity()));
    final Kind kind;
    final Set<String> parameters;

    TemplateContract(Kind kind, String... parameters) {
        this.kind = kind;
        this.parameters = Set.of(parameters);
    }

    String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static Set<String> keys() {
        return BY_KEY.keySet();
    }

    static TemplateContract named(String key) {
        var contract = BY_KEY.get(key);
        if (contract == null) {
            throw new IllegalArgumentException("Unknown template: " + key);
        }
        return contract;
    }
}
