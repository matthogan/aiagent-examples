package com.example.javaaiagent.concurrent;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared worker mechanics; callers retain their own pools, deadlines and error policy.
 */
public final class BoundedExecutor implements AutoCloseable {

    private final ExecutorService workers;

    public BoundedExecutor(String threadPrefix, int capacity) {
        // A zero-length queue rejects excess work immediately instead of hiding a growing backlog.
        workers = new ThreadPoolExecutor(
                capacity,
                capacity,
                0,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().name(threadPrefix, 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> T call(Callable<T> operation, Duration timeout) throws InterruptedException, ExecutionException, TimeoutException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Execution timeout must be positive");
        }
        long nanos = timeout.toNanos();
        var task = workers.submit(operation);
        try {
            return task.get(nanos, TimeUnit.NANOSECONDS);
        } finally {
            // Cancel even when the waiting thread is interrupted. The operation must cooperate.
            if (!task.isDone()) {
                task.cancel(true);
            }
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
