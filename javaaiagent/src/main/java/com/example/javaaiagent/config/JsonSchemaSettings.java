package com.example.javaaiagent.config;

import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Configuration properties for JSON schema settings.
 * The 'observationResponse' property specifies the path to the
 * JSON schema for observation responses.
 */
@ConfigurationProperties("agent.json-schemas")
public record JsonSchemaSettings(
        @DefaultValue("classpath:schemas/observation-response.json") Resource observationResponse) {

    public JsonSchemaSettings {
        if (observationResponse == null) {
            throw new IllegalArgumentException("agent.json-schemas.observationResponse must not be null");
        }

        try {
            var contentAsString = observationResponse.getContentAsString(Charset.defaultCharset());
            LoggerFactory.getLogger("json-schemas").info("Loaded {}", observationResponse.getFilename());
            LoggerFactory.getLogger("json-schemas").debug("{}", contentAsString);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read observation response schema content. Check agent.json-schemas.", e);
        }
    }
}
