package com.example.javaaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Synthetic APIs in separate processes; not part of the agent's in-process tool implementation. */
public final class MockServices {
    public static void main(String[] args) throws Exception {
        if (!System.getenv().getOrDefault("ENVIRONMENT", "local").equals("local")) {
            throw new IllegalArgumentException("Synthetic services are for local development only");
        }
        if (args.length < 1 || !List.of("status", "runbook").contains(args[0])) {
            throw new IllegalArgumentException("Usage: mock status|runbook [port]");
        }
        String kind = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : kind.equals("status") ? 8081 : 8082;
        String token = System.getenv().getOrDefault(kind.equals("status") ? "STATUS_TOKEN" : "RUNBOOK_TOKEN",
                "local-demo-" + kind + "-token");
        var executor = Executors.newFixedThreadPool(4);
        var server = create(kind, port, token);
        server.setExecutor(executor);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); executor.shutdownNow(); }));
        server.start();
        System.out.println("Synthetic " + kind + " API listening on 127.0.0.1:" + server.getAddress().getPort());
    }

    static HttpServer create(String kind, int port, String token) throws Exception {
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/services/", exchange -> {
            try (exchange) {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                int status;
                Object result;
                String service = exchange.getRequestURI().getPath().substring("/services/".length());
                if (!SecurityConfiguration.constantEquals(authorization == null ? "" : authorization, "Bearer " + token)) {
                    status = 401;
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    result = Map.of("error", "Unauthorized");
                } else if (!exchange.getRequestMethod().equals("GET")) {
                    status = 405;
                    result = Map.of("error", "GET required");
                } else if (!List.of("payments", "orders").contains(service)) {
                    status = 404;
                    result = Map.of("error", "Unknown service");
                } else {
                    status = 200;
                    result = kind.equals("status") ? Map.of(
                            "service", service, "source", "demo-status:" + service,
                            "status", service.equals("payments") ? "degraded" : "healthy",
                            "summary", service.equals("payments") ? "Synthetic fixture: elevated gateway latency"
                                    : "Synthetic fixture: all checks passing") : Map.of(
                            "service", service, "source", "demo-runbook:" + service,
                            "steps", List.of("Review latency and error dashboards.",
                                    "Check recent deployments and upstream provider status.",
                                    "Escalate to the on-call engineer before making changes."));
                }
                byte[] bytes = json.writeValueAsBytes(result);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        return server;
    }
}
