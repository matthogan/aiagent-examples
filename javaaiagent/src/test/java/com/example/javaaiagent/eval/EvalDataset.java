package com.example.javaaiagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.JsonFactory;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

record EvalDataset(String version, Map<String, Response> responses, List<Scenario> cases) {
    static final Path PATH = Path.of("evals/operations-v1.json");
    static final Set<String> KEYS = Set.of("get_service_status/payments", "get_service_status/orders",
            "get_runbook/payments", "get_runbook/orders");

    record Response(int status, Boolean usable, JsonNode body) {}
    record Scenario(String id, String question, Set<String> scopes, Set<String> services,
                    Set<String> requiredCalls, Set<String> allowedCalls, int maxToolCalls,
                    Map<String, Response> responses) {
        boolean authorized(String key) {
            String[] pieces = key.split("/");
            return KEYS.contains(key) && services.contains(pieces[1])
                    && scopes.contains(pieces[0].equals("get_service_status") ? "status:read" : "runbooks:read");
        }
    }

    static EvalDataset load() throws Exception {
        var json = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        EvalDataset data = json.readValue(PATH.toFile(), EvalDataset.class);
        data.validate();
        return data;
    }

    void validate() {
        if (version == null || version.isBlank() || cases == null || cases.isEmpty()
                || responses == null || !responses.keySet().equals(KEYS)) {
            throw new IllegalArgumentException("Invalid evaluation dataset");
        }
        var ids = new HashSet<String>();
        for (var scenario : cases) {
            if (scenario.id() == null || !scenario.id().matches("[a-z0-9-]+") || !ids.add(scenario.id())
                    || scenario.question() == null || scenario.question().isBlank() || scenario.question().length() > 4000
                    || scenario.allowedCalls() == null || !KEYS.containsAll(scenario.allowedCalls())
                    || scenario.requiredCalls() == null || !scenario.allowedCalls().containsAll(scenario.requiredCalls())
                    || scenario.maxToolCalls() < scenario.requiredCalls().size() || scenario.maxToolCalls() > 8
                    || scenario.scopes() == null || !Set.of("status:read", "runbooks:read").containsAll(scenario.scopes())
                    || scenario.services() == null || !Set.of("payments", "orders").containsAll(scenario.services())
                    || scenario.responses() == null || !KEYS.containsAll(scenario.responses().keySet())) {
                throw new IllegalArgumentException("Invalid evaluation scenario");
            }
            scenario.responses().values().forEach(EvalDataset::validateResponse);
        }
        responses.values().forEach(EvalDataset::validateResponse);
    }

    private static void validateResponse(Response response) {
        if (response == null || response.status() < 100 || response.status() > 599 || response.body() == null
                || response.usable() == null || (response.usable() && response.status() != 200)) {
            throw new IllegalArgumentException("Invalid evaluation response");
        }
    }
}
