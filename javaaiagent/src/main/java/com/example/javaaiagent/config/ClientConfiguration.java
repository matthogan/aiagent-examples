package com.example.javaaiagent.config;

import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

/**
 * Loads Boot YAML and environment overrides without starting a server or creating model beans.
 */
public record ClientConfiguration(String url, String token, TimeoutSettings timeouts, TemplateSettings templates) {

    @Override
    public String toString() {
        return "ClientConfiguration[redacted]";
    }

    public static ClientConfiguration load() {
        return load(new StandardEnvironment());
    }

    static ClientConfiguration load(ConfigurableEnvironment environment) {
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        var timeouts = Binder.get(environment).bindOrCreate("agent.timeouts", TimeoutSettings.class);
        String token = environment.getProperty("A2A_TOKEN");
        if (token == null && "demo".equals(environment.getProperty("agent.auth-mode"))) {
            token = environment.getProperty("agent.demo-token");
        }
        return new ClientConfiguration(environment.getRequiredProperty("agent.url"), token, timeouts,
                Binder.get(environment).bindOrCreate("agent.templates", TemplateSettings.class));
    }
}
