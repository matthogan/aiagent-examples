package com.example.javaaiagent.config;

import java.net.URI;
import java.util.Set;

/**
 * Shared validation for environment configuration and standalone commands.
 */
public final class InputValidation {

    private InputValidation() {
    }

    public static void required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public static void token(String name, String value) {
        required(name, value);
        if (value.length() > 8192 || value.chars().anyMatch(c -> c <= 32 || c >= 127)) {
            throw new IllegalArgumentException(name + " must contain only visible ASCII characters (max 8192)");
        }
    }

    public static void serviceUrl(String name, URI address) {
        if (address == null
                || !Set.of("http", "https").contains(address.getScheme() == null ? "" : address.getScheme())
                || address.getHost() == null
                || address.getUserInfo() != null
                || address.getQuery() != null
                || address.getFragment() != null
                || address.getPort() == 0
                || address.getPort() > 65535) {
            throw new IllegalArgumentException(name + " must be an HTTP(S) URL without credentials, query or fragment and with a valid port");
        }
    }

    public static String question(String text) {
        required("Question", text);
        if (text.length() > 4000) {
            throw new IllegalArgumentException("Question must contain 1–4000 characters");
        }
        return text.strip();
    }
}
