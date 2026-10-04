package com.example.javaaiagent.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class RuntimeSettingsTest {
    @Test
    void yamlDefaultsAndEnvironmentOverridesBindConsistently() {
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "test",
                                Map.of("spring.config.location", "classpath:application.yaml")));
        ClientConfiguration.load(environment);
        assertEquals(
                RuntimeSettings.defaults(),
                Binder.get(environment).bindOrCreate("agent.runtime", RuntimeSettings.class));
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "overrides",
                                Map.of(
                                        "AGENT_MAX_MODEL_ROUNDS",
                                        "6",
                                        "AGENT_MAX_TOOL_CALLS_PER_ROUND",
                                        "3",
                                        "LLM_MAX_OUTPUT_TOKENS",
                                        "2048",
                                        "AGENT_WORKERS",
                                        "2")));
        assertEquals(
                new RuntimeSettings(6, 3, 2048, 2),
                Binder.get(environment).bindOrCreate("agent.runtime", RuntimeSettings.class));
    }

    @ParameterizedTest
    @CsvSource({
        "max-model-rounds,1",
        "max-model-rounds,17",
        "max-tool-calls-per-round,0",
        "max-tool-calls-per-round,9",
        "max-output-tokens,0",
        "max-output-tokens,16001",
        "workers,0",
        "workers,65"
    })
    void rejectsInvalidDeploymentBudgetsDuringBinding(String property, int value) {
        var binder =
                new Binder(
                        new MapConfigurationPropertySource(
                                Map.of("agent.runtime." + property, value)));
        assertThrows(
                org.springframework.boot.context.properties.bind.BindException.class,
                () -> binder.bindOrCreate("agent.runtime", RuntimeSettings.class));
    }
}
