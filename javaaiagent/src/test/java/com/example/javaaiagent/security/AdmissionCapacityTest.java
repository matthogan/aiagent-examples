package com.example.javaaiagent.security;

import static com.example.javaaiagent.TestTemplates.templates;
import static org.junit.jupiter.api.Assertions.*;

import com.example.javaaiagent.config.RuntimeSettings;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdmissionCapacityTest {
    @Test
    void configuredCapacityRejectsExcessAndReleasesAfterCompletion() throws Exception {
        var filter = new AdmissionFilter(templates(), new RuntimeSettings(4, 2, 1000, 1));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var active =
                    executor.submit(
                            () -> {
                                filter.doFilter(
                                        new MockHttpServletRequest(),
                                        new MockHttpServletResponse(),
                                        (request, response) -> {
                                            entered.countDown();
                                            try {
                                                assertTrue(release.await(3, TimeUnit.SECONDS));
                                            } catch (InterruptedException ex) {
                                                Thread.currentThread().interrupt();
                                                throw new AssertionError(ex);
                                            }
                                        });
                                return null;
                            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var excess = new MockHttpServletResponse();
                filter.doFilter(
                        new MockHttpServletRequest(), excess, (a, b) -> fail("Over capacity"));
                assertEquals(429, excess.getStatus());
            } finally {
                release.countDown();
            }
            active.get(3, TimeUnit.SECONDS);
            var next = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest(), next, (a, b) -> {});
            assertEquals(200, next.getStatus());
        }
    }
}
