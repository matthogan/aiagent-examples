package com.example.javaaiagent.templates;

import static com.example.javaaiagent.TestTemplates.templates;
import static org.junit.jupiter.api.Assertions.*;

import com.example.javaaiagent.config.TemplateSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

class MessageTemplatesTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    private MessageTemplates load(String content) throws Exception {
        Path file = directory.resolve("messages.json");
        Files.writeString(file, content);
        return new MessageTemplates(
                new TemplateSettings(file.toUri().toString()), json, new DefaultResourceLoader());
    }

    @Test
    void partialOverridesPreserveDefaultsAndJsonTypes() throws Exception {
        var templates =
                load(
                        """
                {"system-prompt":"Custom operator instructions",
                 "tool-result":{"evidence":"{{data}}"},
                 "user-message":"Investigate: {{text}}"}
                """);
        assertEquals("Custom operator instructions", templates.text("system-prompt"));
        assertEquals(64, templates.fingerprint().length());
        assertNotEquals(templates().fingerprint(), templates.fingerprint());
        assertEquals(templates().fingerprint(), templates().fingerprint());
        assertEquals("DEMO: Ask about payments or orders.", templates.text("demo-unknown"));
        String input = "quotes \" and slash \\ and newline\n{{data}}";
        assertEquals(
                "Investigate: " + input, templates.text("user-message", Map.of("text", input)));
        var data = json.readTree("{\"count\":2,\"healthy\":false,\"steps\":[\"check\"]}");
        var rendered = json.readTree(templates.json("tool-result", Map.of("data", data)));
        assertEquals(data, rendered.get("evidence"));
        String call = templates.json("demo-call", Map.of("service", input));
        assertEquals(input, json.readTree(call).get("service").asText());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{",
                "{} {}",
                "{\"system-prompt\":\"one\",\"system-prompt\":\"two\"}",
                "{\"unknown\":\"value\"}",
                "{\"system-prompt\":null}",
                "{\"system-prompt\":\" \"}",
                "{\"user-message\":\"missing text placeholder\"}",
                "{\"user-message\":\"{{secret}}\"}",
                "{\"user-message\":\"{{text\"}",
                "{\"service-schema\":[]}",
                "{\"service-schema\":{\"type\":\"string\"}}",
                "{\"error-busy\":{}}"
            })
    void rejectsInvalidOverridesDuringLoading(String content) {
        assertThrows(IllegalArgumentException.class, () -> load(content));
    }

    @Test
    void rejectsMissingFilesAndUnboundedResources() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MessageTemplates(
                                new TemplateSettings(
                                        directory.resolve("absent.json").toUri().toString()),
                                json,
                                new DefaultResourceLoader()));
        assertThrows(IllegalArgumentException.class, () -> load(" ".repeat(65_537)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TemplateSettings("https://example.com/templates.json"));
        assertThrows(
                IllegalArgumentException.class,
                () -> load(json.writeValueAsString(Map.of("system-prompt", "x".repeat(16_385)))));
    }

    @Test
    void rejectsStaticJsonThatCannotFitTheOutputBudget() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        load(
                                json.writeValueAsString(
                                        Map.of("error-busy", Map.of("error", "x".repeat(8193))))));
    }

    @Test
    void renderedDataCannotMutateCatalogOrExpandWithoutBounds() throws Exception {
        var templates = templates();
        var result =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        templates.render("error-busy", Map.of());
        result.put("error", "changed");
        assertTrue(templates.json("error-busy").contains("Agent is busy"));
        assertThrows(
                TemplateRenderException.class,
                () -> templates.render("user-message", Map.of("text", "x".repeat(16_001))));
        assertThrows(
                TemplateRenderException.class,
                () ->
                        templates.render(
                                "tool-result", Map.of("data", Map.of("large", "x".repeat(8193)))));
        assertThrows(
                IllegalArgumentException.class, () -> templates.text("user-message", Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> templates.text("user-message", Map.of("text", "x".repeat(16_001))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        templates.json(
                                "tool-result", Map.of("data", Map.of("large", "x".repeat(8193)))));
    }
}
