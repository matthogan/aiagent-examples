package com.example.javaaiagent.config;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class OpenAiSettingsTest {
    @Test
    void defaultsMatchTheLocalProvider() {
        var settings =
                new Binder(new MapConfigurationPropertySource())
                        .bindOrCreate("agent.openai", OpenAiSettings.class);
        assertEquals(URI.create("http://localhost:6666"), settings.baseUrl());
        assertEquals("/v1/chat/completions", settings.completionsPath());
    }

    @Test
    void shippedYamlUsesLocalLlmAndAllowsExplicitDemoOverride() {
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().remove(org.springframework.core.env.StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "testConfig", java.util.Map.of("spring.config.location", "classpath:/application.yaml")));
        ClientConfiguration.load(environment);
        var binder = Binder.get(environment);
        var agent = binder.bind("agent", AgentSettings.class).get();
        var endpoint = binder.bind("agent.openai", OpenAiSettings.class).get();
        assertEquals("local", agent.environment());
        assertEquals("openai", agent.modelMode());
        assertEquals("qwen3.5-9b", agent.llmModel());
        assertEquals("demo", agent.authMode());
        assertEquals(URI.create("http://localhost:6666"), endpoint.baseUrl());
        assertEquals("/v1/chat/completions", endpoint.completionsPath());
        assertEquals("INFO", environment.getProperty("logging.level.org.springframework.ai"));
        assertEquals("INFO", environment.getProperty("logging.level.org.bsc.langgraph4j"));

        environment.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                "testOverrides", java.util.Map.of("MODEL_MODE", "demo", "LLM_MODEL", "another-local-alias",
                        "OPENAI_BASE_URL", "http://127.0.0.1:1234", "OPENAI_API_KEY", "local-test-key")));
        agent = Binder.get(environment).bind("agent", AgentSettings.class).get();
        endpoint = Binder.get(environment).bind("agent.openai", OpenAiSettings.class).get();
        assertEquals("demo", agent.modelMode());
        assertEquals("another-local-alias", agent.llmModel());
        assertEquals(URI.create("http://127.0.0.1:1234"), endpoint.baseUrl());
        assertEquals("local-test-key", agent.openaiApiKey());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "relative",
                "ftp://example.com",
                "https://user:secret@example.com",
                "https://example.com?q=x",
                "https://example.com/#x"
            })
    void rejectsInvalidBaseUrls(String url) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OpenAiSettings(URI.create(url), "/v1/chat/completions"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "chat/completions",
                "//other.example/chat",
                "https://other.example/chat",
                "/chat?token=x",
                "/chat#x"
            })
    void rejectsPathsThatCouldOverrideTheHostOrAddQueries(String path) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OpenAiSettings(URI.create("https://example.com"), path));
    }
}
