package com.example.javaaiagent.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * Bounded response accumulation with a total timeout, including slow bodies.
 */
public final class BoundedHttp {

    private BoundedHttp() {
    }

    public static HttpClient client(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(
                            URI uri, SocketAddress address, IOException e) {
                    }
                })
                .build();
    }

    public static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, int limit, Duration timeout) throws Exception {
        if (limit <= 0 || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Response size and timeout must be positive");
        }
        // Body completion is part of this future, so a server cannot evade the deadline by
        // sending headers quickly and then trickling response bytes indefinitely.
        var future = client.sendAsync(request, info -> new LimitedBody(limit));
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            // Request one batch at a time; the subscriber controls how much data it accepts.
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                // Check before allocating/copying the chunk, not after the body is in memory.
                if (chunk.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Response exceeds limit"));
                    return;
                }
                byte[] part = new byte[chunk.remaining()];
                chunk.get(part);
                bytes.writeBytes(part);
            }
            // Request one batch at a time; the subscriber controls how much data it accepts.
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
