package com.example.javaaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties("agent.diagnostics")
public record DiagnosticsSettings(BigDecimal inputUsdPerMillion, BigDecimal outputUsdPerMillion) {
    public DiagnosticsSettings {
        if ((inputUsdPerMillion == null) != (outputUsdPerMillion == null) || inputUsdPerMillion != null
                && (inputUsdPerMillion.signum() < 0 || outputUsdPerMillion.signum() < 0)) {
            throw new IllegalArgumentException("Configure both nonnegative agent.diagnostics token prices, or neither");
        }
    }
}
