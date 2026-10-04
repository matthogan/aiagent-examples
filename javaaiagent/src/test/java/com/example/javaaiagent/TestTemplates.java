package com.example.javaaiagent;

import com.example.javaaiagent.config.TemplateSettings;
import com.example.javaaiagent.templates.MessageTemplates;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.DefaultResourceLoader;

public final class TestTemplates {
    private TestTemplates() {}

    public static MessageTemplates templates() {
        return new MessageTemplates(
                new TemplateSettings(MessageTemplates.DEFAULT_LOCATION),
                new ObjectMapper(),
                new DefaultResourceLoader());
    }
}
