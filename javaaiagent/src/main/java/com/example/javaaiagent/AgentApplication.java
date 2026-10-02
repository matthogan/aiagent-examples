package com.example.javaaiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentSettings.class)
public class AgentApplication {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("mock")) {
            MockServices.main(java.util.Arrays.copyOfRange(args, 1, args.length));
        } else if (args.length > 0 && args[0].equals("client")) {
            AgentClient.main(java.util.Arrays.copyOfRange(args, 1, args.length));
        } else {
            SpringApplication.run(AgentApplication.class, args);
        }
    }
}
