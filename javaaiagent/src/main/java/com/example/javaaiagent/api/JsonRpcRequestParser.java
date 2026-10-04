package com.example.javaaiagent.api;

import com.example.javaaiagent.http.StrictJson;
import com.example.javaaiagent.templates.MessageTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Separates JSON syntax, JSON-RPC envelope validation, and A2A message validation.
 */
final class JsonRpcRequestParser {

    private JsonRpcRequestParser() {
    }

    record Request(Object id, String question) {
    }

    static Request parse(byte[] body, ObjectMapper json, MessageTemplates templates) throws InvalidRequest {
        JsonNode payload = readDocument(body, json, templates);
        validateEnvelope(payload, templates);
        Object id = json.convertValue(payload.get("id"), Object.class);
        if (!payload.get("method").asText().equals("message/send")) {
            throw new InvalidRequest(id, -32601, templates.text("rpc-unsupported-method"));
        }
        try {
            return new Request(id, A2aMessageParser.question(payload, json));
        } catch (Exception ex) {
            // A valid envelope lets us echo the caller's id even when its parameters are invalid.
            throw new InvalidRequest(id, -32602, templates.text("rpc-invalid-params"));
        }
    }

    private static JsonNode readDocument(byte[] body, ObjectMapper json, MessageTemplates templates) throws InvalidRequest {
        try {
            return StrictJson.reader(json).readValue(body);
        } catch (Exception ex) {
            throw new InvalidRequest(null, -32700, templates.text("rpc-invalid-json"));
        }
    }

    private static void validateEnvelope(JsonNode payload, MessageTemplates templates) throws InvalidRequest {
        // This synchronous example requires an id; notifications and batch requests are
        // unsupported.
        if (payload == null
                || !payload.isObject()
                || !payload.path("jsonrpc").isTextual()
                || !payload.path("jsonrpc").textValue().equals("2.0")
                || !payload.path("method").isTextual()
                || !payload.hasNonNull("id")
                || !(payload.get("id").isTextual() || payload.get("id").isIntegralNumber())) {
            throw new InvalidRequest(null, -32600, templates.text("rpc-invalid-envelope"));
        }
    }

    /**
     * Carries a safe protocol error, never the underlying parser exception or submitted text.
     */
    static final class InvalidRequest extends Exception {
        final Object id;
        final int code;

        InvalidRequest(Object id, int code, String message) {
            super(message);
            this.id = id;
            this.code = code;
        }
    }
}
