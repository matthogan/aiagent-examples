package com.example.javaaiagent.cli;

import com.example.javaaiagent.config.ClientConfiguration;
import com.example.javaaiagent.config.InputValidation;
import com.example.javaaiagent.http.BoundedHttp;
import com.example.javaaiagent.http.StrictJson;
import com.example.javaaiagent.templates.MessageTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.TextPart;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.UUID;

public final class AgentClient {

    /**
     * The client follows the same discovery/send flow a separate A2A consumer would use.
     */
    public static void main(String[] args) throws Exception {
        var configuration = ClientConfiguration.load();
        validate(configuration);
        var templates = new MessageTemplates(configuration.templates(), new ObjectMapper(), new DefaultResourceLoader());
        String question = InputValidation.question(args.length == 0
                ? templates.text("client-question") : String.join(" ", args));
        var json = new ObjectMapper();
        try (var http = BoundedHttp.client(configuration.timeouts().connect())) {
            discover(configuration, http, json);
            sendQuestion(configuration, question, http, json);
        }
    }

    private static void validate(ClientConfiguration configuration) {
        if (configuration.token() == null) {
            throw new IllegalArgumentException("Set A2A_TOKEN to an issuer-provided access token");
        }
        InputValidation.serviceUrl("AGENT_URL", URI.create(configuration.url()));
        InputValidation.token("A2A_TOKEN", configuration.token());
    }

    private static void discover(ClientConfiguration configuration, HttpClient http, ObjectMapper json) throws Exception {
        URI uri = URI.create(configuration.url()
                .replaceAll("/+$", "") + "/.well-known/agent-card.json");
        var request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + configuration.token())
                .GET()
                .build();
        var discovery = BoundedHttp.send(http, request, 65_536, configuration.timeouts().clientDiscovery());
        if (discovery.statusCode() != 200) {
            throw new IllegalStateException("Discovery HTTP " + discovery.statusCode());
        }
        AgentCard card = json.readValue(discovery.body(), AgentCard.class);
        System.out.println("Discovered: " + card.name() + " (A2A " + card.protocolVersion() + ")");
    }

    private static void sendQuestion(ClientConfiguration configuration, String question, HttpClient http, ObjectMapper json)
            throws Exception {
        // Discovery describes capabilities; it must not redirect the client's credentials to a new URL.
        var result = BoundedHttp.send(http, messageRequest(configuration, question, json), 65_536,
                configuration.timeouts().clientRequest());
        if (result.statusCode() != 200) {
            throw new IllegalStateException("Agent HTTP " + result.statusCode());
        }
        JsonNode response = StrictJson.reader(json).readValue(result.body());
        System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(response));
        if (response.has("error")) {
            throw new IllegalStateException("A2A request failed");
        }
    }

    private static HttpRequest messageRequest(ClientConfiguration configuration, String question, ObjectMapper json) throws IOException {
        var message = new Message.Builder().role(Message.Role.USER).parts(new TextPart(question)).build();
        var envelope = new SendMessageRequest(
                UUID.randomUUID().toString(), new MessageSendParams(message, null, null));
        return HttpRequest.newBuilder(URI.create(configuration.url()))
                .timeout(configuration.timeouts().clientRequest())
                .header("Authorization", "Bearer " + configuration.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(envelope)))
                .build();
    }
}
