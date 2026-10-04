package com.example.javaaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Independent call limits; the execution deadline may expire before any individual call limit.
 */
@ConfigurationProperties("agent.timeouts")
public record TimeoutSettings(
        @DefaultValue("5s") Duration connect,
        @DefaultValue("5s") Duration toolCall,
        @DefaultValue("20s") Duration llmCall,
        @DefaultValue("45s") Duration execution,
        @DefaultValue("10s") Duration clientDiscovery,
        @DefaultValue("60s") Duration clientRequest) {

    public TimeoutSettings {
        validate("connect", connect);
        validate("tool-call", toolCall);
        validate("llm-call", llmCall);
        validate("execution", execution);
        validate("client-discovery", clientDiscovery);
        validate("client-request", clientRequest);
    }

    public static Duration validate(String name, Duration value) {
        if (value == null || value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("agent.timeouts." + name + " must be between 1ms and 1h");
        }
        return value;
    }
}
