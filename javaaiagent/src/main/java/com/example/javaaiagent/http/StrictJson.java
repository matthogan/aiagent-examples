package com.example.javaaiagent.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

/** A single, unambiguous JSON document at each untrusted input boundary. */
public final class StrictJson {
    private StrictJson() {}

    public static ObjectReader reader(ObjectMapper mapper) {
        return mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
}
