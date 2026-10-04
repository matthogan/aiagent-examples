package com.example.javaaiagent.http;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedHttpTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deadlineIncludesWaitingForHeadersAndSlowBodies(boolean sendHeaders) throws Exception {
        var release = new CountDownLatch(1);
        var received = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        if (sendHeaders) {
                            exchange.sendResponseHeaders(200, 100);
                            exchange.getResponseBody().write(1);
                            exchange.getResponseBody().flush();
                        }
                        received.countDown();
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
        server.start();
        try (var http = BoundedHttp.client(Duration.ofSeconds(1))) {
            assertEquals(Duration.ofSeconds(1), http.connectTimeout().orElseThrow());
            var request =
                    HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                            .GET()
                            .build();
            assertTimeoutPreemptively(
                    Duration.ofSeconds(3),
                    () ->
                            assertThrows(
                                    TimeoutException.class,
                                    () ->
                                            BoundedHttp.send(
                                                    http, request, 1024, Duration.ofMillis(500))));
            assertTrue(received.await(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
