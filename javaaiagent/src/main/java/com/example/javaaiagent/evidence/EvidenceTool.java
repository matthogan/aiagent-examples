package com.example.javaaiagent.evidence;

import org.springframework.ai.tool.ToolCallback;

/** Only application-executed tools can supply evidence; model messages cannot. */
public interface EvidenceTool extends ToolCallback {
    record Outcome(String content, String service, ToolEvidence evidence) {}

    Outcome execute(String arguments);

    @Override
    default String call(String arguments) {
        return execute(arguments).content();
    }
}
