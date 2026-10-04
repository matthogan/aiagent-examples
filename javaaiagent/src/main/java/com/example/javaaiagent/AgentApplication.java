package com.example.javaaiagent;

import com.example.javaaiagent.cli.AgentClient;
import com.example.javaaiagent.config.AgentSettings;
import com.example.javaaiagent.config.OpenAiSettings;
import com.example.javaaiagent.config.TemplateSettings;
import com.example.javaaiagent.config.TimeoutSettings;
import com.example.javaaiagent.demo.MockServices;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Arrays;

@SpringBootApplication
@EnableConfigurationProperties({
    AgentSettings.class,
    TimeoutSettings.class,
    TemplateSettings.class,
    com.example.javaaiagent.config.DiagnosticsSettings.class,
    com.example.javaaiagent.config.RuntimeSettings.class,
    OpenAiSettings.class
})
public class AgentApplication {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("mock")) {
            MockServices.main(Arrays.copyOfRange(args, 1, args.length));
        } else if (args.length > 0 && args[0].equals("client")) {
            AgentClient.main(Arrays.copyOfRange(args, 1, args.length));
        } else {
            SpringApplication.run(AgentApplication.class, args);
        }
    }
}
