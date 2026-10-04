package com.example.javaaiagent.application;

import static com.example.javaaiagent.TestTemplates.templates;
import static com.example.javaaiagent.TestTimeouts.defaults;
import static org.junit.jupiter.api.Assertions.*;

import com.example.javaaiagent.config.AgentSettings;
import com.example.javaaiagent.config.TimeoutSettings;
import com.example.javaaiagent.security.Caller;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class ExecutionTimeoutTest {
    @Test
    void configuredExecutionDeadlineCancelsTheWorker() throws Exception {
        var defaultTimeouts = defaults();
        var timeouts =
                new TimeoutSettings(
                        defaultTimeouts.connect(),
                        defaultTimeouts.toolCall(),
                        defaultTimeouts.llmCall(),
                        Duration.ofMillis(300),
                        defaultTimeouts.clientDiscovery(),
                        defaultTimeouts.clientRequest());
        var interrupted = new CountDownLatch(1);
        var graph =
                new OperationsGraph(
                        (history, tools) -> {
                            try {
                                new CountDownLatch(1).await();
                                throw new AssertionError("Model should have been interrupted");
                            } catch (InterruptedException ex) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                                return Turn.text("assistant", "cancelled");
                            }
                        },
                        timeouts,
                        templates());
        var url = URI.create("http://127.0.0.1");
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
        var execution =
                new AgentExecutionService(
                        settings,
                        (caller, id) -> {
                            assertEquals("test", caller.subject());
                            assertEquals("test", id);
                            return java.util.List.of();
                        },
                        graph,
                        timeouts,
                        templates(),
                        new com.example.javaaiagent.config.DiagnosticsSettings(null, null),
                        com.example.javaaiagent.config.RuntimeSettings.defaults());
        try {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(3),
                    () ->
                            assertThrows(
                                    TimeoutException.class,
                                    () ->
                                            execution.answer(
                                                    "payments",
                                                    new Caller("test", Set.of(), Set.of()),
                                                    "test")));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        } finally {
            execution.close();
        }
    }
}
