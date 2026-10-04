package com.example.javaaiagent.bootstrap;

import com.example.javaaiagent.application.ToolProvider;
import com.example.javaaiagent.config.AgentSettings;
import com.example.javaaiagent.config.TimeoutSettings;
import com.example.javaaiagent.http.BoundedHttp;
import com.example.javaaiagent.templates.MessageTemplates;
import com.example.javaaiagent.tools.RemoteTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/**
 * Composition and lifecycle for the HTTP implementation of the tool boundary.
 */
@Configuration
public class ToolConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    HttpClient toolHttpClient(TimeoutSettings timeouts) {
        return BoundedHttp.client(timeouts.connect());
    }

    @Bean
    ToolProvider toolProvider(AgentSettings settings, TimeoutSettings timeouts, MessageTemplates templates,
                              ObjectMapper json, @Qualifier("toolHttpClient") HttpClient http) {
        return (caller, requestId) ->
                new RemoteTools(settings, timeouts, caller, requestId, http, json, templates)
                        .callbacks();
    }
}
