package com.example.javaaiagent.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validated source data, kept outside the model's proposals and presentation templates. */
public record ToolEvidence(String service, String source, String status, String summary,
                           List<String> steps) implements Serializable {
    public ToolEvidence {
        steps = List.copyOf(steps);
        if (!Set.of("payments", "orders").contains(service)
                || source == null || source.isBlank() || source.length() > 200
                || (status != null && (!Set.of("healthy", "degraded", "unavailable").contains(status)
                    || summary == null || summary.length() > 1500 || !steps.isEmpty()))
                || (status == null && (summary != null || steps.isEmpty() || steps.size() > 8
                    || steps.stream().anyMatch(s -> s.isBlank() || s.length() > 1500)))) {
            throw new IllegalArgumentException("Invalid remote evidence");
        }
    }

    public static ToolEvidence from(JsonNode data, String service, boolean statusResult) {
        if (data == null || !data.isObject() || !data.path("service").isTextual()
                || !data.path("service").asText().equals(service) || !data.path("source").isTextual()) {
            throw new IllegalArgumentException("Invalid remote response");
        }
        if (statusResult) {
            if (data.size() != 4 || !data.path("status").isTextual() || !data.path("summary").isTextual()) {
                throw new IllegalArgumentException("Invalid status response");
            }
            return new ToolEvidence(service, data.get("source").asText(), data.get("status").asText(),
                    data.get("summary").asText(), List.of());
        }
        if (data.size() != 3 || !data.path("steps").isArray()) {
            throw new IllegalArgumentException("Invalid runbook response");
        }
        var steps = new ArrayList<String>();
        for (JsonNode step : data.get("steps")) {
            if (!step.isTextual()) throw new IllegalArgumentException("Invalid runbook step");
            steps.add(step.asText());
        }
        return new ToolEvidence(service, data.get("source").asText(), null, null, steps);
    }
}
