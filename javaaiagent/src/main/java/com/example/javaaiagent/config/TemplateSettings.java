package com.example.javaaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Templates are trusted deployment files, never locations supplied in a user message.
 */
@ConfigurationProperties("agent.templates")
public record TemplateSettings(@DefaultValue("classpath:templates/messages.json") String location) {
    public TemplateSettings {
        if (location == null || !(location.startsWith("classpath:") || location.startsWith("file:"))) {
            throw new IllegalArgumentException("agent.templates.location requires a classpath: or file: resource");
        }
    }
}
