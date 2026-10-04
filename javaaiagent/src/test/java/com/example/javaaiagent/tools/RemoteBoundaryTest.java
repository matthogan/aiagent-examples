package com.example.javaaiagent.tools;

import static com.example.javaaiagent.TestTemplates.templates;
import static com.example.javaaiagent.TestTimeouts.defaults;
import static org.junit.jupiter.api.Assertions.*;

import com.example.javaaiagent.config.AgentSettings;
import com.example.javaaiagent.http.BoundedHttp;
import com.example.javaaiagent.security.Caller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RemoteBoundaryTest {
    @Test
    void redirectsOversizedBodiesAndInvalidSchemasFailClosed() throws Exception {
        var requests = new AtomicInteger();
        var mode = new AtomicInteger(0);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/services/payments",
                exchange -> {
                    try (exchange) {
                        requests.incrementAndGet();
                        String data =
                                switch (mode.get()) {
                                    case 0 -> "redirect";
                                    case 1 -> "x".repeat(8193);
                                    case 2 ->
                                            "{\"service\":\"orders\",\"source\":\"test\",\"status\":\"healthy\",\"summary\":\"wrong service\"}";
                                    default -> "not-json";
                                };
                        if (mode.get() == 0)
                            exchange.getResponseHeaders().set("Location", "/forbidden");
                        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(mode.get() == 0 ? 302 : 200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        server.createContext(
                "/forbidden",
                exchange -> {
                    requests.addAndGet(100);
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                });
        server.start();
        try (var client = BoundedHttp.client(defaults().connect())) {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var settings =
                    new AgentSettings(
                            "local",
                            "demo",
                            "test",
                            "",
                            url,
                            "demo",
                            "demo",
                            "",
                            "issuer",
                            "aud",
                            url,
                            url,
                            "status-token",
                            "runbook-token");
            var tool =
                    new RemoteTools(
                                    settings,
                                    defaults(),
                                    new Caller("test", Set.of("status:read"), Set.of("payments")),
                                    "test",
                                    client,
                                    new ObjectMapper(),
                                    templates())
                            .callbacks()
                            .getFirst();
            for (int i = 0; i < 4; i++) {
                mode.set(i);
                assertTrue(
                        tool.call("{\"service\":\"payments\"}")
                                .contains("Remote data unavailable"));
                assertEquals(i + 1, requests.get());
            }
            for (String input :
                    new String[] {
                        null,
                        "",
                        "null",
                        "{} {}",
                        "{\"service\":\"payments\",\"service\":\"orders\"}",
                        "x".repeat(4097)
                    }) {
                assertTrue(tool.call(input).contains("Invalid tool arguments"));
                assertEquals(4, requests.get(), "Invalid input must not reach HTTP");
            }
            var denied =
                    new RemoteTools(
                                    settings,
                                    defaults(),
                                    new Caller("test", Set.of(), Set.of("payments")),
                                    "test",
                                    client,
                                    new ObjectMapper(),
                                    templates())
                            .callbacks()
                            .getFirst();
            assertTrue(denied.call("{\"service\":\"payments\"}").contains("Access denied"));
            assertEquals(4, requests.get(), "Authorization denial must occur before HTTP");
        } finally {
            server.stop(0);
        }
    }
}
