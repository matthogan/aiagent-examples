package com.example.javaaiagent.demo;

import com.example.javaaiagent.config.ClientConfiguration;
import com.example.javaaiagent.config.InputValidation;
import com.example.javaaiagent.templates.MessageTemplates;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Synthetic APIs in separate processes; not part of the agent's in-process tool implementation.
 */
public final class MockServices {

    public static void main(String[] args) throws Exception {

        if (!System.getenv().getOrDefault("ENVIRONMENT", "local").equals("local")) {
            throw new IllegalArgumentException("Synthetic services are for local development only");
        }
        if (args.length < 1 || args.length > 2 || !List.of("status", "runbook").contains(args[0])) {
            throw new IllegalArgumentException("Usage: mock status|runbook [port]");
        }
        String kind = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : kind.equals("status") ? 8081 : 8082;
        String token = System.getenv().getOrDefault(kind.equals("status") ? "STATUS_TOKEN" : "RUNBOOK_TOKEN",
                "local-demo-" + kind + "-token");
        var executor = Executors.newFixedThreadPool(4);
        var configuration = ClientConfiguration.load();
        var templates = new MessageTemplates(configuration.templates(), new ObjectMapper(), new DefaultResourceLoader());
        var server = create(kind, port, token, templates);
        server.setExecutor(executor);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            executor.shutdownNow();
        }));
        server.start();
        System.out.println("Synthetic " + kind + " API listening on 127.0.0.1:" + server.getAddress().getPort());
    }

    public static HttpServer create(String kind, int port, String token, MessageTemplates templates)
            throws Exception {
        if (!List.of("status", "runbook").contains(kind) || port < 0 || port > 65535) {
            throw new IllegalArgumentException("Use status or runbook and a port from 0 to 65535");
        }
        InputValidation.token("Mock service token", token);
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/services/", exchange -> handle(exchange, kind, token, json, templates));
        return server;
    }

    /**
     * Keep HTTP handling separate from fixtures so learners can replace either independently.
     */
    private static void handle(HttpExchange exchange, String kind, String token, ObjectMapper json, MessageTemplates templates)
            throws IOException {
        try (exchange) {
            String service = exchange.getRequestURI().getPath().substring("/services/".length());
            int status = requestStatus(exchange, token, service);
            Object result = status == 200 ? fixture(kind, service, templates) : error(status, templates);
            byte[] bytes = json.writeValueAsBytes(result);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static int requestStatus(HttpExchange exchange, String token, String service) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (!MessageDigest.isEqual((authorization == null ? "" : authorization).getBytes(StandardCharsets.UTF_8),
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            return 401;
        }
        if (!exchange.getRequestMethod().equals("GET")) return 405;
        return List.of("payments", "orders").contains(service) ? 200 : 404;
    }

    private static Object error(int status, MessageTemplates templates) {
        String name = switch (status) {
            case 401 -> "demo-unauthorized";
            case 405 -> "demo-method";
            default -> "demo-not-found";
        };
        return templates.render(name, Map.of());
    }

    private static Object fixture(String kind, String service, MessageTemplates templates) {
        if (kind.equals("runbook")) {
            return templates.render("demo-runbook", Map.of("service", service));
        }
        boolean degraded = service.equals("payments");
        return templates.render("demo-status", Map.of("service", service,
                "status", degraded ? "degraded" : "healthy",
                "summary", templates.text(degraded ? "demo-degraded-summary" : "demo-healthy-summary")));
    }
}
