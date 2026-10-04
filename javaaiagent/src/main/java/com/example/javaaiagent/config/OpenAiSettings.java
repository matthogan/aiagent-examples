package com.example.javaaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

/**
 * The provider URL is deployment configuration, never input supplied by a model or caller.
 */
@ConfigurationProperties("agent.openai")
public record OpenAiSettings(
        @DefaultValue("http://localhost:6666") URI baseUrl,
        @DefaultValue("/v1/chat/completions") String completionsPath) {

    public OpenAiSettings {
        InputValidation.serviceUrl("OPENAI_BASE_URL", baseUrl);
        InputValidation.required("OPENAI_COMPLETIONS_PATH", completionsPath);
        URI path;
        try {
            path = URI.create(completionsPath);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("OPENAI_COMPLETIONS_PATH must be an absolute URL path");
        }
        if (!completionsPath.startsWith("/")
                || completionsPath.startsWith("//")
                || path.isAbsolute()
                || path.getRawAuthority() != null
                || path.getQuery() != null
                || path.getFragment() != null) {
            throw new IllegalArgumentException("OPENAI_COMPLETIONS_PATH must be a path without host, query or fragment");
        }
        // Avoid a doubled slash when the configured base URL ends with '/'.
        baseUrl = URI.create(baseUrl.toString().replaceAll("/+$", ""));
    }
}
