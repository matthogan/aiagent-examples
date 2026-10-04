package com.example.javaaiagent.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedExecutorTest {
    @Test
    void saturationRejectsInsteadOfQueuing() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        try (var executor = new BoundedExecutor("test-", 1)) {
            Thread caller =
                    Thread.startVirtualThread(
                            () -> {
                                try {
                                    executor.call(
                                            () -> {
                                                started.countDown();
                                                release.await();
                                                return null;
                                            },
                                            Duration.ofSeconds(5));
                                } catch (Throwable error) {
                                    failure.set(error);
                                }
                            });
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertThrows(
                        RejectedExecutionException.class,
                        () -> executor.call(() -> "should not run", Duration.ofSeconds(1)));
            } finally {
                release.countDown();
                caller.join(3000);
            }
            assertFalse(caller.isAlive());
            assertNull(failure.get());
        }
    }

    @Test
    void invalidDeadlineDoesNotStartWorkAndClosePreventsNewCalls() throws Exception {
        var executor = new BoundedExecutor("test-", 1);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            executor.call(
                                    () -> {
                                        fail("Invalid deadline must not start work");
                                        return null;
                                    },
                                    Duration.ZERO));
            assertEquals("ready", executor.call(() -> "ready", Duration.ofSeconds(1)));
        } finally {
            executor.close();
        }
        assertThrows(
                RejectedExecutionException.class,
                () -> executor.call(() -> "closed", Duration.ofSeconds(1)));
    }
}
