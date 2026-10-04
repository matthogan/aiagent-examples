package com.example.javaaiagent.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class TimeoutSettingsTest {
    @ParameterizedTest
    @ValueSource(strings = {"0ms", "-1s", "PT0S", "PT0.000000001S", "2h", "invalid"})
    void rejectsInvalidTimeoutForEveryCallBoundary(String value) {
        for (String field :
                new String[] {
                    "connect",
                    "tool-call",
                    "llm-call",
                    "execution",
                    "client-discovery",
                    "client-request"
                }) {
            var source =
                    new MapConfigurationPropertySource(Map.of("agent.timeouts." + field, value));
            assertThrows(
                    BindException.class,
                    () -> new Binder(source).bindOrCreate("agent.timeouts", TimeoutSettings.class),
                    field);
        }
    }

    @Test
    void yamlLoadsForTheClientWithEnvironmentAndPropertyOverrides() {
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .addFirst(
                        new SystemEnvironmentPropertySource(
                                "testEnvironment",
                                Map.of(
                                        "HTTP_CONNECT_TIMEOUT",
                                        "250ms",
                                        "LLM_CALL_TIMEOUT",
                                        "PT1.5S",
                                        "MESSAGE_TEMPLATES_LOCATION",
                                        "file:config/custom-messages.json",
                                        "A2A_TOKEN",
                                        "test-token")));
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "testProperties",
                                Map.of(
                                        "spring.config.location",
                                        "classpath:/application.yaml",
                                        "agent.timeouts.execution",
                                        "2m")));
        var configuration = ClientConfiguration.load(environment);
        assertEquals(Duration.ofMillis(250), configuration.timeouts().connect());
        assertEquals(Duration.ofMillis(1500), configuration.timeouts().llmCall());
        assertEquals(Duration.ofMinutes(2), configuration.timeouts().execution());
        assertEquals(Duration.ofSeconds(5), configuration.timeouts().toolCall());
        assertEquals(Duration.ofSeconds(10), configuration.timeouts().clientDiscovery());
        assertEquals(Duration.ofSeconds(60), configuration.timeouts().clientRequest());
        assertEquals("file:config/custom-messages.json", configuration.templates().location());
        assertEquals("test-token", configuration.token());
        assertEquals("ClientConfiguration[redacted]", configuration.toString());
        assertEquals("http://127.0.0.1:8080/", configuration.url());
    }
}
