package com.example.javaaiagent;

import com.example.javaaiagent.config.TimeoutSettings;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

public final class TestTimeouts {
    private TestTimeouts() {}

    public static TimeoutSettings defaults() {
        return new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("agent.timeouts", TimeoutSettings.class);
    }
}
