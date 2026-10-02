package com.example.javaaiagent;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent")
public record AgentSettings(
        String environment, String modelMode, String llmModel, String openaiApiKey,
        URI url, String authMode, String demoToken, String jwtPublicKeyFile,
        String jwtIssuer, String jwtAudience, URI statusUrl, URI runbookUrl,
        String statusToken, String runbookToken) {

    public AgentSettings {
        if (!List.of("local", "production").contains(environment)
                || !List.of("demo", "openai").contains(modelMode)
                || !List.of("demo", "jwt").contains(authMode)) {
            throw new IllegalArgumentException("Invalid environment, model mode or auth mode");
        }
        for (URI address : List.of(url, statusUrl, runbookUrl)) {
            if (!List.of("http", "https").contains(address.getScheme())
                    || address.getHost() == null || address.getUserInfo() != null
                    || address.getQuery() != null || address.getFragment() != null) {
                throw new IllegalArgumentException("Service URLs must be HTTP(S), without credentials or queries");
            }
            if (environment.equals("production") && !address.getScheme().equals("https")) {
                throw new IllegalArgumentException("Production service URLs require HTTPS");
            }
        }
        if (authMode.equals("jwt") && jwtPublicKeyFile.isBlank()) {
            throw new IllegalArgumentException("JWT_PUBLIC_KEY_FILE is required");
        }
        if (modelMode.equals("openai") && openaiApiKey.isBlank()) {
            throw new IllegalArgumentException("OPENAI_API_KEY is required");
        }
        if (environment.equals("production")) {
            if (!authMode.equals("jwt") || !modelMode.equals("openai")) {
                throw new IllegalArgumentException("Production requires JWT authentication and a real LLM");
            }
            for (String token : List.of(statusToken, runbookToken)) {
                if (token.length() < 32 || token.startsWith("local-demo-")) {
                    throw new IllegalArgumentException("Production requires strong downstream credentials");
                }
            }
            if (statusToken.equals(runbookToken)) {
                throw new IllegalArgumentException("Tool credentials must be distinct");
            }
        }
    }

    @Override public String toString() { return "AgentSettings[redacted]"; }
}
