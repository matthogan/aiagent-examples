package com.example.javaaiagent.application;

import com.example.javaaiagent.security.Caller;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/** Creates fresh tools bound to the authenticated caller, never to model-supplied grants. */
@FunctionalInterface
public interface ToolProvider {
    List<ToolCallback> forCaller(Caller caller, String requestId);
}
