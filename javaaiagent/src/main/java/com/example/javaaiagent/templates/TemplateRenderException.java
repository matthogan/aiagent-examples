package com.example.javaaiagent.templates;

/**
 * A valid configured template could not represent the runtime data within its contract.
 */
public final class TemplateRenderException extends IllegalArgumentException {
    TemplateRenderException(String message) {
        super(message);
    }
}
