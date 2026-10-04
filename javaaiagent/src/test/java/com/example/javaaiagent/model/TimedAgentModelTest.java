package com.example.javaaiagent.model;

import static org.junit.jupiter.api.Assertions.*;

import com.example.javaaiagent.application.Turn;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TimedAgentModelTest {
    @Test
    void deadlineInterruptsTheProviderCall() throws Exception {
        var interrupted = new CountDownLatch(1);
        try (var model =
                new TimedAgentModel(
                        (history, tools) -> {
                            try {
                                new CountDownLatch(1).await();
                                throw new AssertionError("Provider should have been interrupted");
                            } catch (InterruptedException ex) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                                return Turn.text("assistant", "cancelled");
                            }
                        },
                        Duration.ofMillis(300))) {
            var error =
                    assertTimeoutPreemptively(
                            Duration.ofSeconds(3),
                            () ->
                                    assertThrows(
                                            IllegalStateException.class,
                                            () -> model.complete(List.of(), List.of())));
            assertEquals(
                    com.example.javaaiagent.diagnostics.Termination.MODEL_TIMEOUT,
                    assertInstanceOf(com.example.javaaiagent.diagnostics.AgentFailure.class, error)
                            .reason());
            assertNull(error.getCause());
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void successfulResponsesAreReturnedUnchanged() {
        Turn reply = Turn.text("assistant", "answer");
        try (var model = new TimedAgentModel((history, tools) -> reply, Duration.ofSeconds(1))) {
            assertSame(reply, model.complete(List.of(), List.of()));
        }
    }
}
