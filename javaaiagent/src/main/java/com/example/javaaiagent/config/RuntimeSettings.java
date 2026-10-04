package com.example.javaaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Deployment-tunable budgets, bounded to keep accidental configuration finite.
 */
@ConfigurationProperties("agent.runtime")
public record RuntimeSettings(
        @DefaultValue("4") int maxModelRounds,
        @DefaultValue("2") int maxToolCallsPerRound,
        @DefaultValue("1000") int maxOutputTokens,
        @DefaultValue("8") int workers) {

    public RuntimeSettings {
        range("max-model-rounds", maxModelRounds, 2, 16);
        range("max-tool-calls-per-round", maxToolCallsPerRound, 1, 8);
        range("max-output-tokens", maxOutputTokens, 1, 16000);
        range("workers", workers, 1, 64);
    }

    public static RuntimeSettings defaults() {
        return new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("agent.runtime", RuntimeSettings.class);
    }

    private static void range(String key, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("agent.runtime." + key + " must be between " + min + " and " + max);
        }
    }
}
