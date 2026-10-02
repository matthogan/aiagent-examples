package com.example.javaaiagent;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

@FunctionalInterface
public interface AgentModel {
    Turn complete(List<Turn> history, List<ToolCallback> tools);
}
