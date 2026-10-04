package com.example.javaaiagent.application;

import com.example.javaaiagent.diagnostics.TokenUsage;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.function.Consumer;

@FunctionalInterface
public interface AgentModel {
    Turn complete(List<Turn> history, List<ToolCallback> tools);

    default Turn complete(List<Turn> history, List<ToolCallback> tools, Consumer<TokenUsage> usage) {
        return complete(history, tools);
    }

    default boolean scripted() {
        return false;
    }
}
