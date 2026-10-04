package com.example.javaaiagent.eval;

import static com.example.javaaiagent.TestTemplates.templates;
import static org.junit.jupiter.api.Assertions.*;

import com.example.javaaiagent.application.EvidenceAnswer;
import com.example.javaaiagent.application.Turn;
import com.example.javaaiagent.evidence.ToolEvidence;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;

/** Offline tests of the evaluator, explicitly not evidence of real-model quality. */
class EvaluationInfrastructureTest {
    @TempDir Path directory;

    static EvalConfig config() {
        return EvalConfig.from(Map.of("OPENAI_API_KEY", "synthetic-test-key", "EVAL_MODEL", "test-double",
                "EVAL_BASE_URL", "http://127.0.0.1:1", "EVAL_INPUT_USD_PER_MILLION", "2", "EVAL_OUTPUT_USD_PER_MILLION", "8"));
    }

    static ChatResponse usage(int input, int output) {
        return new ChatResponse(List.of(), ChatResponseMetadata.builder().model("test-double")
                .usage(new DefaultUsage(input, output)).build());
    }

    @Test
    void datasetHasStableUniqueCasesAndRejectsInvalidContracts() throws Exception {
        var dataset = EvalDataset.load();
        assertEquals("operations-v1", dataset.version());
        assertEquals(11, dataset.cases().size());
        assertThrows(IllegalArgumentException.class, () -> new EvalDataset(dataset.version(), dataset.responses(),
                List.of(dataset.cases().getFirst(), dataset.cases().getFirst())).validate());
    }

    @Test
    void configurationRequiresExplicitProviderAndNeverPrintsSecrets() {
        assertThrows(IllegalArgumentException.class, () -> EvalConfig.from(Map.of()));
        assertFalse(config().toString().contains("synthetic-test-key"));
        var env = new java.util.HashMap<>(Map.of("OPENAI_API_KEY", "synthetic-test-key", "EVAL_MODEL", "model",
                "EVAL_BASE_URL", "http://remote.example.com"));
        assertThrows(IllegalArgumentException.class, () -> EvalConfig.from(env));
        env.put("EVAL_BASE_URL", "https://provider.example.com");
        env.put("EVAL_REPETITIONS", "6");
        assertThrows(IllegalArgumentException.class, () -> EvalConfig.from(env));
        env.put("EVAL_REPETITIONS", "1");
        env.put("EVAL_INPUT_USD_PER_MILLION", "2");
        assertThrows(IllegalArgumentException.class, () -> EvalConfig.from(env));
        env.put("EVAL_OUTPUT_USD_PER_MILLION", "-1");
        assertThrows(IllegalArgumentException.class, () -> EvalConfig.from(env));
    }

    @Test
    void blockedHallucinationAndEmptyEvasionFailQualityScoring() throws Exception {
        var dataset = EvalDataset.load();
        var scenario = dataset.cases().getFirst();
        var evidence = new ToolEvidence("payments", "eval-status:payments:v1", "degraded", "latency", List.of());
        var attempts = List.of(new EvalScorer.Attempt("get_service_status/payments", evidence));
        var metrics = EvalScorer.score(dataset, scenario, List.of("get_service_status/payments"), attempts,
                List.of("get_service_status/payments"),
                "{\"observations\":[{\"service\":\"payments\",\"source\":\"eval-status:payments:v1\",\"status\":\"healthy\"}],\"runbooks\":[]}", false, 10);
        assertTrue(metrics.get("structuredAnswer"));
        assertFalse(metrics.get("evidenceAccuracy"));
        assertFalse(metrics.get("executionSucceeded"));
        metrics = EvalScorer.score(dataset, scenario, List.of(), List.of(), List.of(),
                "{\"observations\":[],\"runbooks\":[]}", true, 10);
        assertTrue(metrics.get("evidenceAccuracy"));
        assertFalse(metrics.get("requiredTools"));
    }

    @Test
    void scoresUnnecessaryCallsUnauthorizedHttpAndStaleCitationsIndependently() throws Exception {
        var dataset = EvalDataset.load();
        var denied = dataset.cases().stream().filter(s -> s.id().equals("denied-service")).findFirst().orElseThrow();
        var scores = EvalScorer.score(dataset, denied, List.of("get_service_status/orders", "get_runbook/payments"),
                List.of(new EvalScorer.Attempt("get_service_status/orders", null)), List.of("get_service_status/orders"),
                "{\"observations\":[],\"runbooks\":[]}", true, 10);
        assertFalse(scores.get("toolEfficiency"));
        assertFalse(scores.get("authorization"));
        var evidence = new ToolEvidence("payments", "old", "degraded", "latency", List.of());
        scores = EvalScorer.score(dataset, dataset.cases().getFirst(), List.of("get_service_status/payments"),
                List.of(new EvalScorer.Attempt("get_service_status/payments", evidence),
                        new EvalScorer.Attempt("get_service_status/payments", null)), List.of(),
                "{\"observations\":[{\"service\":\"payments\",\"source\":\"old\",\"status\":\"degraded\"}],\"runbooks\":[]}", true, 10);
        assertFalse(scores.get("evidenceAccuracy"));
        assertEquals("invalid", EvalScorer.key("get_service_status", "{\"service\":\"payments\",\"extra\":true}"));
    }

    @Test
    void missingUsageIsUnknownRatherThanZeroCost() {
        var meter = new EvalRunner.UsageMeter();
        meter.observe(usage(20, 5));
        assertEquals(20L, meter.input(1));
        assertEquals(5L, meter.output(1));
        assertNull(meter.input(2)); // Second provider call failed before returning usage.
        meter.observe(new ChatResponse(List.of()));
        assertNull(meter.input(2));
    }

    @Test
    void expectedHealthyFixtureCannotDisappearBehindAnEmptyAnswer() throws Exception {
        var dataset = EvalDataset.load();
        var scenario = dataset.cases().getFirst();
        var scores = EvalScorer.score(dataset, scenario, List.of("get_service_status/payments"),
                List.of(new EvalScorer.Attempt("get_service_status/payments", null)),
                List.of("get_service_status/payments"), "{\"observations\":[],\"runbooks\":[]}", true, 10);
        assertTrue(scores.get("requiredTools"));
        assertTrue(scores.get("evidenceAccuracy"));
        assertFalse(scores.get("toolOutcomes"));
    }

    @Test
    void offlineReferenceModelExercisesEveryFixtureAndReportAccounting() throws Exception {
        var dataset = EvalDataset.load();
        var results = new ArrayList<EvalRunner.Result>();
        for (var scenario : dataset.cases()) {
            var runner = new EvalRunner(dataset, templates(), observer -> (history, tools) -> {
                observer.accept(usage(100, 10));
                if (history.getLast().role().equals("tool") || scenario.requiredCalls().isEmpty()) {
                    return Turn.text("assistant", EvidenceAnswer.proposal(history));
                }
                var calls = scenario.requiredCalls().stream().sorted().map(key -> {
                    String[] pieces = key.split("/");
                    return new Turn.Call(key, pieces[0], "{\"service\":\"" + pieces[1] + "\"}");
                }).toList();
                return new Turn("assistant", "", calls, List.of());
            }, config());
            var result = runner.run(scenario, 1);
            assertTrue(result.passed(), scenario.id() + ": " + result.checks());
            assertNotNull(result.inputTokens());
            assertTrue(result.tokenCostEstimateUsd().compareTo(BigDecimal.ZERO) > 0);
            results.add(result);
        }
        var report = EvalReport.create(dataset, config(), templates(), results, Instant.now());
        // This file is a test artifact, not a live baseline; label it explicitly.
        report.put("mode", "offline-evaluator-test");
        assertEquals(true, report.get("allPassed"));
        assertEquals(1.0, report.get("passRate"));
        assertEquals(64, report.get("templatesSha256").toString().length());
        Path output = directory.resolve("report.json");
        EvalReport.write(output, report);
        var saved = EvalScorer.JSON.readTree(output.toFile());
        assertEquals(11, saved.get("completedRuns").asInt());
        assertFalse(saved.toString().contains("synthetic-test-key"));
        assertFalse(saved.toString().contains("SYSTEM OVERRIDE"));
        assertEquals("offline-evaluator-test", saved.get("mode").asText());
        var incomplete = EvalReport.create(dataset, config(), templates(), results.subList(0, 1), Instant.now());
        assertEquals(false, incomplete.get("allPassed"));
        assertEquals("incomplete", incomplete.get("status"));
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void providerFailureIsScoredAndSanitized(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var dataset = EvalDataset.load();
        var runner = new EvalRunner(dataset, templates(), observer -> (h, t) -> {
            throw new IllegalStateException("synthetic-test-key secret prompt");
        }, config());
        var result = runner.run(dataset.cases().getFirst(), 1);
        assertFalse(result.passed());
        assertFalse(result.checks().get("executionSucceeded"));
        assertNull(result.inputTokens());
        assertNull(result.tokenCostEstimateUsd());
        assertFalse(EvalScorer.JSON.writeValueAsString(result).contains("secret prompt"));
        assertFalse(output.getAll().contains("secret prompt"));
    }
}
