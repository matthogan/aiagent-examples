package com.example.javaaiagent.eval;

import com.example.javaaiagent.templates.MessageTemplates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EvalReport {
    static Map<String, Object> create(EvalDataset dataset, EvalConfig config, MessageTemplates templates,
            List<EvalRunner.Result> results, Instant started) throws Exception {
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", 1);
        report.put("mode", "live-provider-with-synthetic-tools");
        report.put("startedAt", started.toString());
        report.put("updatedAt", Instant.now().toString());
        report.put("datasetVersion", dataset.version());
        report.put("datasetSha256", hash(Files.readAllBytes(EvalDataset.PATH)));
        report.put("templatesSha256", templates.fingerprint());
        report.put("sourceSha256", sourceFingerprint());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("model", config.model());
        report.put("providerBaseUrl", config.endpoint().baseUrl().toString());
        report.put("completionsPath", config.endpoint().completionsPath());
        report.put("repetitions", config.repetitions());
        report.put("executionSettings", Map.of("maxModelRounds", 4, "maxOutputTokensPerCall", 1000,
                "modelTimeoutMs", 20000, "executionTimeoutMs", 45000, "providerRetries", 0,
                "concurrentScenarios", 1, "temperature", "provider default"));
        int planned = dataset.cases().size() * config.repetitions();
        report.put("plannedRuns", planned);
        report.put("completedRuns", results.size());
        boolean complete = results.size() == planned;
        report.put("status", complete ? "complete" : "incomplete");
        report.put("allPassed", complete && results.stream().allMatch(EvalRunner.Result::passed));
        report.put("passRate", results.isEmpty() ? null : results.stream().filter(EvalRunner.Result::passed).count() / (double) results.size());
        var rates = new LinkedHashMap<String, Double>();
        if (!results.isEmpty()) results.getFirst().checks().keySet().forEach(check -> rates.put(check,
                results.stream().filter(r -> Boolean.TRUE.equals(r.checks().get(check))).count() / (double) results.size()));
        report.put("checkPassRates", rates);
        var latencies = results.stream().map(EvalRunner.Result::latencyMs).sorted().toList();
        report.put("latencyP50Ms", percentile(latencies, 0.5));
        report.put("latencyP95Ms", percentile(latencies, 0.95));
        report.put("modelCalls", results.stream().mapToInt(EvalRunner.Result::modelCalls).sum());
        report.put("inputTokens", results.isEmpty() || results.stream().anyMatch(r -> r.inputTokens() == null)
                ? null : results.stream().mapToLong(EvalRunner.Result::inputTokens).sum());
        report.put("outputTokens", results.isEmpty() || results.stream().anyMatch(r -> r.outputTokens() == null)
                ? null : results.stream().mapToLong(EvalRunner.Result::outputTokens).sum());
        report.put("inputUsdPerMillion", config.inputUsdPerMillion());
        report.put("outputUsdPerMillion", config.outputUsdPerMillion());
        report.put("tokenCostEstimateUsd", results.isEmpty() || results.stream().anyMatch(r -> r.tokenCostEstimateUsd() == null)
                ? null : results.stream().map(EvalRunner.Result::tokenCostEstimateUsd).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        report.put("results", List.copyOf(results));
        return report;
    }

    static void write(Path path, Map<String, Object> report) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        EvalScorer.JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
        Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static Long percentile(List<Long> values, double quantile) {
        return values.isEmpty() ? null : values.get((int) Math.ceil(values.size() * quantile) - 1);
    }

    private static String hash(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String sourceFingerprint() throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        // Includes uncommitted source and the evaluator itself, not only the last Git commit.
        try (var paths = Files.walk(Path.of("src"))) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                digest.update(path.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(path));
                digest.update((byte) 0);
            }
        }
        digest.update(Files.readAllBytes(Path.of("pom.xml")));
        return HexFormat.of().formatHex(digest.digest());
    }
}
