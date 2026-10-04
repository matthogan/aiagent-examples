package com.example.javaaiagent.api;

import com.example.javaaiagent.config.InputValidation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.a2a.spec.Message;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.TextPart;

import java.io.IOException;

/**
 * Validates the supported single-turn subset of A2A before execution.
 */
final class A2aMessageParser {

    private A2aMessageParser() {
    }

    static String question(JsonNode payload, ObjectMapper json) throws IOException {
        prepareWireMessage(payload);
        var parsed = json.treeToValue(payload, SendMessageRequest.class);
        Message message = parsed.getParams().message();
        validateSingleTurn(message);
        String question = message.getParts().stream()
                .map(p -> ((TextPart) p).getText())
                .collect(java.util.stream.Collectors.joining("\n"))
                .strip();
        return InputValidation.question(question);
    }

    private static void prepareWireMessage(JsonNode payload) {
        // Validate raw JSON first: SDK conversion must not turn a null text value into user input.
        JsonNode wireMessage = payload.path("params").path("message");
        if (!wireMessage.isObject()) {
            throw new IllegalArgumentException();
        }
        for (JsonNode part : wireMessage.path("parts")) {
            if (!part.path("text").isTextual()) {
                throw new IllegalArgumentException();
            }
        }
        // kind is optional in some 0.3 clients, including the Python SDK request builder.
        if (!wireMessage.has("kind")) {
            ((ObjectNode) wireMessage).put("kind", "message");
        }
    }

    private static void validateSingleTurn(Message message) {
        // Resuming a task requires persistent ownership checks, which this single-turn example
        // lacks.
        if (message.getRole() != Message.Role.USER
                || message.getParts().isEmpty()
                || message.getTaskId() != null
                || message.getContextId() != null
                || message.getReferenceTaskIds() != null
                || message.getParts().stream().anyMatch(p -> !(p instanceof TextPart))) {
            throw new IllegalArgumentException();
        }
    }
}
