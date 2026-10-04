package com.example.javaaiagent.application;

import com.example.javaaiagent.diagnostics.AgentResult;
import com.example.javaaiagent.diagnostics.Termination;
import com.example.javaaiagent.evidence.ToolEvidence;
import com.example.javaaiagent.http.StrictJson;
import com.example.javaaiagent.templates.MessageTemplates;
import com.example.javaaiagent.templates.TemplateRenderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks model proposals against this run's evidence, then renders only source-owned facts.
 */
public final class EvidenceAnswer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MessageTemplates templates;

    public EvidenceAnswer(MessageTemplates templates) {
        this.templates = templates;
    }

    public String render(String proposal, List<Turn> history, boolean scripted) {
        return evaluate(proposal, history, scripted).text();
    }

    public AgentResult evaluate(String proposal, List<Turn> history, boolean scripted) {
        var latest = latestResults(history);
        try {
            JsonNode proposed = StrictJson.reader(JSON).readValue(proposal);
            JsonNode expected = proposalNode(history);
            if (proposed == null
                    || !proposed.isObject()
                    || proposed.size() != 2
                    || !sameEntries(proposed.path("observations"), expected.path("observations"))
                    || !sameEntries(proposed.path("runbooks"), expected.path("runbooks"))) {
                return new AgentResult(templates.text("evidence-rejected"), Termination.EVIDENCE_REJECTED);
            }
        } catch (IOException ex) {
            return new AgentResult(templates.text("evidence-rejected"), Termination.EVIDENCE_REJECTED);
        }

        try {
            var lines = new ArrayList<String>();
            if (scripted) {
                lines.add(templates.text("evidence-demo"));
            }
            if (latest.values().stream().noneMatch(r -> r.evidence() != null)) {
                lines.add(templates.text("evidence-none"));
            }
            for (Turn.Result result : latest.values()) {
                ToolEvidence evidence = result.evidence();
                if (evidence == null) {
                    // Never turn an upstream error body into a fact or a source citation.
                    lines.add(templates.text("evidence-missing", Map.of(
                            "tool", quote(result.name()),
                            "service", quote(result.service()))));
                } else if (evidence.status() != null) {
                    lines.add(templates.text("evidence-status", Map.of(
                            "service", evidence.service(),
                            "status", evidence.status(),
                            "source", quote(evidence.source()),
                            "summary", quote(evidence.summary()))));
                } else {
                    lines.add(templates.text("evidence-runbook", Map.of(
                            "service", evidence.service(),
                            "source", quote(evidence.source()),
                            "steps", JSON.valueToTree(evidence.steps()).toString())));
                }
            }
            lines.add(templates.text("evidence-disclaimer"));
            Termination reason = latest.values().stream().noneMatch(r -> r.evidence() != null)
                    ? Termination.NO_EVIDENCE : latest.values().stream().anyMatch(r -> r.evidence() == null)
                                                ? Termination.PARTIAL_EVIDENCE : Termination.SUCCESS;
            return new AgentResult(templates.text("evidence-answer",
                    Map.of("results", String.join("\n", lines))), reason);
        } catch (TemplateRenderException ex) {
            return new AgentResult(templates.text("evidence-overflow"), Termination.OUTPUT_LIMIT);
        }
    }

    /**
     * Scripted models use the same proposal contract and validation path as real models.
     */
    public static String proposal(List<Turn> history) {
        return proposalNode(history).toString();
    }

    private static ObjectNode proposalNode(List<Turn> history) {
        var root = JSON.createObjectNode();
        ArrayNode observations = root.putArray("observations");
        ArrayNode runbooks = root.putArray("runbooks");
        for (Turn.Result result : latestResults(history).values()) {
            var evidence = result.evidence();
            if (evidence == null) {
                continue;
            }
            var entry = (evidence.status() == null ? runbooks : observations).addObject();
            entry.put("service", evidence.service()).put("source", evidence.source());
            if (evidence.status() != null) {
                entry.put("status", evidence.status());
            }
        }
        return root;
    }

    private static boolean sameEntries(JsonNode proposed, JsonNode expected) {
        if (!proposed.isArray() || proposed.size() != expected.size()) {
            return false;
        }
        var actual = new HashSet<JsonNode>();
        proposed.forEach(actual::add);
        var required = new HashSet<JsonNode>();
        expected.forEach(required::add);
        // Exact object equality rejects invented fields, health claims, citations and duplicate
        // entries.
        return actual.size() == proposed.size() && actual.equals(required);
    }

    private static Map<String, Turn.Result> latestResults(List<Turn> history) {
        var latest = new LinkedHashMap<String, Turn.Result>();
        for (Turn turn : history) {
            if (!turn.role().equals("tool")) {
                continue;
            }
            for (Turn.Result result : turn.results()) {
                // A subsequent failure supersedes an earlier success for the same tool/resource.
                latest.put(result.name() + ":" + result.service(), result);
            }
        }
        return latest;
    }

    private static String quote(String value) {
        return JSON.valueToTree(value).toString();
    }
}
