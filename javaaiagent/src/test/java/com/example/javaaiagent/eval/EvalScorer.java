package com.example.javaaiagent.eval;

import com.example.javaaiagent.evidence.ToolEvidence;
import com.example.javaaiagent.http.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic task and trajectory scoring; a blocked hallucination is not a quality pass. */
final class EvalScorer {
    static final ObjectMapper JSON = new ObjectMapper();
    record Attempt(String key, ToolEvidence evidence) {}

    static Map<String, Boolean> score(EvalDataset dataset, EvalDataset.Scenario scenario, List<String> requested,
            List<Attempt> attempts, List<String> remoteRequests, String finalProposal,
            boolean completed, long elapsedMillis) {
        var scores = new LinkedHashMap<String, Boolean>();
        JsonNode proposal = null;
        try { proposal = StrictJson.reader(JSON).readValue(finalProposal == null ? "null" : finalProposal); }
        catch (Exception ignored) { /* Invalid model output is a scored failure, never an exception log. */ }
        boolean structured = proposal != null && proposal.isObject() && proposal.size() == 2
                && proposal.path("observations").isArray() && proposal.path("runbooks").isArray();
        scores.put("structuredAnswer", structured);
        scores.put("evidenceAccuracy", structured && matchesEvidence(proposal, attempts));
        scores.put("requiredTools", attempts.stream().map(Attempt::key).toList().containsAll(scenario.requiredCalls()));
        scores.put("toolOutcomes", attempts.stream().allMatch(a -> expectedOutcome(dataset, scenario, a, remoteRequests)));
        scores.put("toolEfficiency", requested.size() <= scenario.maxToolCalls()
                && scenario.allowedCalls().containsAll(requested));
        scores.put("authorization", remoteRequests.stream().allMatch(scenario::authorized));
        scores.put("executionSucceeded", completed);
        scores.put("withinDeadline", completed && elapsedMillis <= 45_000);
        return scores;
    }

    private static boolean expectedOutcome(EvalDataset dataset, EvalDataset.Scenario scenario,
            Attempt attempt, List<String> remoteRequests) {
        if (!EvalDataset.KEYS.contains(attempt.key())) return false;
        if (!scenario.authorized(attempt.key())) return attempt.evidence() == null;
        if (!remoteRequests.contains(attempt.key())) return false;
        var fixture = scenario.responses().getOrDefault(attempt.key(), dataset.responses().get(attempt.key()));
        if (!fixture.usable()) return attempt.evidence() == null;
        var evidence = attempt.evidence();
        if (evidence == null || !evidence.service().equals(fixture.body().path("service").asText())
                || !evidence.source().equals(fixture.body().path("source").asText())) return false;
        if (attempt.key().startsWith("get_service_status/")) {
            return java.util.Objects.equals(evidence.status(), fixture.body().path("status").asText())
                    && java.util.Objects.equals(evidence.summary(), fixture.body().path("summary").asText());
        }
        return evidence.status() == null && JSON.valueToTree(evidence.steps()).equals(fixture.body().path("steps"));
    }

    private static boolean matchesEvidence(JsonNode proposal, List<Attempt> attempts) {
        var latest = new LinkedHashMap<String, ToolEvidence>();
        attempts.forEach(a -> latest.put(a.key(), a.evidence()));
        var expectedStatus = new HashSet<JsonNode>();
        var expectedRunbooks = new HashSet<JsonNode>();
        latest.values().stream().filter(java.util.Objects::nonNull).forEach(e -> {
            var entry = JSON.createObjectNode().put("service", e.service()).put("source", e.source());
            if (e.status() == null) expectedRunbooks.add(entry);
            else expectedStatus.add(entry.put("status", e.status()));
        });
        return matches(proposal.get("observations"), expectedStatus)
                && matches(proposal.get("runbooks"), expectedRunbooks);
    }

    private static boolean matches(JsonNode entries, java.util.Set<JsonNode> expected) {
        var actual = new HashSet<JsonNode>();
        entries.forEach(actual::add);
        return actual.size() == entries.size() && actual.equals(expected);
    }

    static String key(String name, String arguments) {
        try {
            JsonNode args = StrictJson.reader(JSON).readValue(arguments);
            String key = name + "/" + args.path("service").asText();
            return args.isObject() && args.size() == 1 && EvalDataset.KEYS.contains(key) ? key : "invalid";
        } catch (Exception ignored) { return "invalid"; }
    }
}
