package com.example.javaaiagent.config;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AgentSettingsTest {
    private AgentSettings settings(Map<String, String> overrides) {
        var values =
                new HashMap<>(
                        Map.of(
                                "environment",
                                "local",
                                "model",
                                "demo",
                                "auth",
                                "demo",
                                "token",
                                "local-demo-status-token",
                                "key",
                                "",
                                "issuer",
                                "issuer",
                                "audience",
                                "audience",
                                "modelName",
                                "test",
                                "publicKey",
                                "public.pem"));
        values.putAll(overrides);
        URI url = URI.create("https://example.com");
        return new AgentSettings(
                values.get("environment"),
                values.get("model"),
                values.get("modelName"),
                values.get("key"),
                url,
                values.get("auth"),
                "demo-token",
                values.get("publicKey"),
                values.get("issuer"),
                values.get("audience"),
                url,
                url,
                values.get("token"),
                "runbook-token");
    }

    @Test
    void rejectsMissingModeSpecificConfigurationWithoutLeakingSecrets() {
        for (String field : new String[] {"issuer", "audience", "publicKey"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> settings(Map.of("auth", "jwt", field, " ")));
        }
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of("model", "openai")));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(Map.of("model", "openai", "key", "secret", "modelName", " ")));
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> settings(Map.of("token", "secret\r\nheader")));
        assertFalse(error.getMessage().contains("secret"));
        assertEquals("AgentSettings[redacted]", settings(Map.of()).toString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "abc\nxyz", "abc xyz", "abc\u007f"})
    void rejectsInvalidCredentials(String token) {
        assertThrows(IllegalArgumentException.class, () -> InputValidation.token("TOKEN", token));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/relative",
                "ftp://example.com",
                "https://user:pass@example.com",
                "http://example.com:0",
                "http://example.com:65536",
                "https://example.com?q=1",
                "https://example.com/#fragment"
            })
    void rejectsUnsafeAddresses(String value) {
        assertThrows(
                IllegalArgumentException.class,
                () -> InputValidation.serviceUrl("URL", URI.create(value)));
    }

    @Test
    void validatesNullsAndQuestionLength() {
        assertThrows(IllegalArgumentException.class, () -> InputValidation.serviceUrl("URL", null));
        assertThrows(IllegalArgumentException.class, () -> InputValidation.question(null));
        assertThrows(
                IllegalArgumentException.class, () -> InputValidation.question("x".repeat(4001)));
        assertEquals("x".repeat(4000), InputValidation.question("x".repeat(4000)));
        assertEquals("payments", InputValidation.question(" payments "));
    }
}
