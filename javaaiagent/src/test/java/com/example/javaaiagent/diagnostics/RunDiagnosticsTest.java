package com.example.javaaiagent.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import com.example.javaaiagent.config.DiagnosticsSettings;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunDiagnosticsTest {
    @Test
    void aggregatesUsageAndCostAndIgnoresLateCompletions() {
        var events = new ArrayList<Map<String, Object>>();
        var run =
                new RunDiagnostics(
                        "00000000-0000-0000-0000-000000000001",
                        new DiagnosticsSettings(new BigDecimal("2"), new BigDecimal("4")),
                        events::add);
        int round = run.modelStarted();
        run.modelFinished(round, System.nanoTime(), new TokenUsage(100L, 25L), "completed");
        run.finish(Termination.SUCCESS);
        var end = events.getLast();
        assertEquals(100L, end.get("input_tokens"));
        assertEquals(25L, end.get("output_tokens"));
        assertEquals(new BigDecimal("0.000300"), end.get("estimated_cost_usd"));
        assertEquals(true, end.get("usage_complete"));
        int size = events.size();
        run.modelFinished(round, System.nanoTime(), new TokenUsage(999L, 999L), "completed");
        run.modelStarted();
        run.toolStarted("get_runbook");
        run.finish(Termination.CANCELLED);
        assertEquals(size, events.size());
    }

    @Test
    void missingAndUnfinishedUsageAreUnknownNotFree() {
        for (boolean completed : List.of(true, false)) {
            var events = new ArrayList<Map<String, Object>>();
            var run =
                    new RunDiagnostics(
                            null,
                            new DiagnosticsSettings(BigDecimal.ZERO, BigDecimal.ZERO),
                            events::add);
            int round = run.modelStarted();
            if (completed)
                run.modelFinished(round, System.nanoTime(), TokenUsage.unknown(), "completed");
            run.finish(Termination.EXECUTION_TIMEOUT);
            assertEquals(false, events.getLast().get("usage_complete"));
            assertNull(events.getLast().get("input_tokens"));
            assertNull(events.getLast().get("estimated_cost_usd"));
        }
    }

    @Test
    void concurrentRunsRemainIsolatedAndUntrustedIdentifiersAreNotLogged() throws Exception {
        var first = new ArrayList<Map<String, Object>>();
        var second = new ArrayList<Map<String, Object>>();
        var prices = new DiagnosticsSettings(null, null);
        var a = new RunDiagnostics("secret\ninjected", prices, first::add);
        var b = new RunDiagnostics(null, prices, second::add);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var fa =
                    executor.submit(
                            () -> {
                                a.modelFinished(
                                        a.modelStarted(),
                                        System.nanoTime(),
                                        new TokenUsage(10L, 2L),
                                        "completed");
                                a.toolStarted("secret\ninjected");
                                a.toolFinished("secret\ninjected", System.nanoTime(), false);
                                a.finish(Termination.NO_EVIDENCE);
                            });
            var fb =
                    executor.submit(
                            () -> {
                                b.modelFinished(
                                        b.modelStarted(),
                                        System.nanoTime(),
                                        new TokenUsage(20L, 4L),
                                        "completed");
                                b.finish(Termination.SUCCESS);
                            });
            fa.get();
            fb.get();
        }
        assertEquals(10L, first.getLast().get("input_tokens"));
        assertEquals(20L, second.getLast().get("input_tokens"));
        assertNotEquals(first.getFirst().get("request_id"), second.getFirst().get("request_id"));
        assertFalse(first.toString().contains("secret"));
    }

    @Test
    void pricesMustBeAnExplicitNonnegativePair() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsSettings(BigDecimal.ONE, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsSettings(BigDecimal.ONE.negate(), BigDecimal.ONE));
    }
}
