package com.example.javaaiagent.model;

import com.example.javaaiagent.application.AgentModel;
import com.example.javaaiagent.application.EvidenceAnswer;
import com.example.javaaiagent.application.Turn;
import com.example.javaaiagent.templates.MessageTemplates;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic local demonstration; no provider calls.
 */
public final class DemoAgentModel {

    private DemoAgentModel() {
    }

    public static AgentModel create(MessageTemplates templates) {
        return new AgentModel() {
            @Override
            public boolean scripted() {
                return true;
            }

            @Override
            public Turn complete(List<Turn> history, List<ToolCallback> tools) {
                var last = history.getLast();
                if (last.role().equals("tool")) {
                    return Turn.text("assistant", EvidenceAnswer.proposal(history));
                }
                String question = history.getFirst().text().toLowerCase(Locale.ROOT);
                String service = question.contains("payments") ? "payments" : question.contains("orders") ? "orders" : null;
                if (service == null) {
                    return Turn.text("assistant", EvidenceAnswer.proposal(history));
                }
                String args = templates.json("demo-call", Map.of("service", service));
                return new Turn("assistant", "",
                        List.of(new Turn.Call("demo-0", "get_service_status", args),
                                new Turn.Call("demo-1", "get_runbook", args)),
                        List.of());
            }
        };
    }
}
