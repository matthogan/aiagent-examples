package com.example.javaaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

@ConfigurationProperties("agent")
public record AgentSettings(
        String environment,
        String modelMode,
        String llmModel,
        String openaiApiKey,
        URI url,
        String authMode,
        String demoToken,
        String jwtPublicKeyFile,
        String jwtIssuer,
        String jwtAudience,
        URI statusUrl,
        URI runbookUrl,
        String statusToken,
        String runbookToken) {

    public AgentSettings {
        validateModes(environment, modelMode, authMode);
        validateUrls(url, statusUrl, runbookUrl);
        InputValidation.token("STATUS_TOKEN", statusToken);
        InputValidation.token("RUNBOOK_TOKEN", runbookToken);
        if (authMode.equals("demo")) {
            InputValidation.token("DEMO_TOKEN", demoToken);
        }
        for (URI address : List.of(url, statusUrl, runbookUrl)) {
            if (environment.equals("production") && !address.getScheme().equals("https")) {
                throw new IllegalArgumentException("Production service URLs require HTTPS");
            }
        }
        if (authMode.equals("jwt")) {
            InputValidation.required("JWT_PUBLIC_KEY_FILE", jwtPublicKeyFile);
            InputValidation.required("JWT_ISSUER", jwtIssuer);
            InputValidation.required("JWT_AUDIENCE", jwtAudience);
        }
        if (modelMode.equals("openai")) {
            InputValidation.token("OPENAI_API_KEY", openaiApiKey);
            InputValidation.required("LLM_MODEL", llmModel);
        }
        validateProduction(environment, authMode, modelMode, statusToken, runbookToken);
    }

    private static void validateModes(String environment, String modelMode, String authMode) {
        InputValidation.required("ENVIRONMENT", environment);
        InputValidation.required("MODEL_MODE", modelMode);
        InputValidation.required("AUTH_MODE", authMode);
        if (!List.of("local", "production").contains(environment)
                || !List.of("demo", "openai").contains(modelMode)
                || !List.of("demo", "jwt").contains(authMode)) {
            throw new IllegalArgumentException("Invalid environment, model mode or auth mode");
        }
    }

    private static void validateUrls(URI url, URI statusUrl, URI runbookUrl) {
        InputValidation.serviceUrl("AGENT_URL", url);
        InputValidation.serviceUrl("STATUS_URL", statusUrl);
        InputValidation.serviceUrl("RUNBOOK_URL", runbookUrl);
    }

    /**
     * Validate deployment policy before any network resources or model beans are created.
     */
    private static void validateProduction(String environment, String authMode, String modelMode,
                                           String statusToken, String runbookToken) {
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

    @Override
    public String toString() {
        return "AgentSettings[redacted]";
    }
}
