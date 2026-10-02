package com.example.javaaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.spec.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.UUID;

public final class AgentClient {
    public static void main(String[] args) throws Exception {
        String url = System.getenv().getOrDefault("AGENT_URL", "http://127.0.0.1:8080/");
        String token = System.getenv("A2A_TOKEN");
        if (token == null && System.getenv().getOrDefault("AUTH_MODE", "demo").equals("demo")) {
            token = System.getenv().getOrDefault("DEMO_TOKEN", "local-demo-client-token");
        }
        if (token == null) throw new IllegalArgumentException("Set A2A_TOKEN to an issuer-provided access token");
        String question = args.length == 0 ? "Why is payments degraded, and what should I check?" : String.join(" ", args);
        var json = new ObjectMapper();
        try (var http = BoundedHttp.client()) {
            var discovery = BoundedHttp.send(http, HttpRequest.newBuilder(
                    URI.create(url.replaceAll("/+$", "") + "/.well-known/agent-card.json"))
                    .header("Authorization", "Bearer " + token).GET().build(), 65_536, 10);
            if (discovery.statusCode() != 200) throw new IllegalStateException("Discovery HTTP " + discovery.statusCode());
            AgentCard card = json.readValue(discovery.body(), AgentCard.class);
            System.out.println("Discovered: " + card.name() + " (A2A " + card.protocolVersion() + ")");
            var message = new Message.Builder().role(Message.Role.USER).parts(new TextPart(question)).build();
            var request = new SendMessageRequest(UUID.randomUUID().toString(), new MessageSendParams(message, null, null));
            // The trusted configured URL controls where credentials go, not the discovery document.
            var result = BoundedHttp.send(http, HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(request))).build(), 65_536, 60);
            if (result.statusCode() != 200) throw new IllegalStateException("Agent HTTP " + result.statusCode());
            var response = json.readTree(result.body());
            System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(response));
            if (response.has("error")) throw new IllegalStateException("A2A request failed");
        }
    }
}
