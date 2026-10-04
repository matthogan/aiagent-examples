package com.example.javaaiagent.tools;

import static com.example.javaaiagent.TestTemplates.templates;
import static com.example.javaaiagent.TestTimeouts.defaults;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.javaaiagent.config.AgentSettings;
import com.example.javaaiagent.http.BoundedHttp;
import com.example.javaaiagent.security.Caller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RunbookBoundaryTest {
    static Stream<String> invalidResponses() {
        String valid = "{\"service\":\"payments\",\"source\":\"test\",\"steps\":[\"Investigate\"]}";
        return Stream.of(
                valid + " {}",
                valid.replace("\"source\":\"test\"", "\"source\":\"test\",\"source\":\"other\""),
                valid.replace("\"test\"", "\" \""),
                valid.replace("[\"Investigate\"]", "[]"),
                valid.replace("Investigate", " "),
                valid.replace("Investigate", "x".repeat(1501)));
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsAmbiguousOrInvalidRunbookResponses(String body) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/services/payments",
                exchange -> {
                    try (exchange) {
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        server.start();
        try (var http = BoundedHttp.client(defaults().connect())) {
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
                            "audience",
                            url,
                            url,
                            "status",
                            "runbook");
            var tool =
                    new RemoteTools(
                                    settings,
                                    defaults(),
                                    new Caller("test", Set.of("runbooks:read"), Set.of("payments")),
                                    "test",
                                    http,
                                    new ObjectMapper(),
                                    templates())
                            .callbacks()
                            .get(1);
            assertTrue(tool.call("{\"service\":\"payments\"}").contains("Remote data unavailable"));
        } finally {
            server.stop(0);
        }
    }
}
