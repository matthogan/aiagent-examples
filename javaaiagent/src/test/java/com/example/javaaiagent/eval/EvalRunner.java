package com.example.javaaiagent.eval;

import com.example.javaaiagent.application.AgentModel;
import com.example.javaaiagent.application.OperationsGraph;
import com.example.javaaiagent.concurrent.BoundedExecutor;
import com.example.javaaiagent.config.AgentSettings;
import com.example.javaaiagent.evidence.EvidenceTool;
import com.example.javaaiagent.http.BoundedHttp;
import com.example.javaaiagent.model.TimedAgentModel;
import com.example.javaaiagent.security.Caller;
import com.example.javaaiagent.templates.MessageTemplates;
import com.example.javaaiagent.tools.RemoteTools;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

final class EvalRunner {
    interface ModelFactory { AgentModel create(Consumer<ChatResponse> observer); }
    record Result(String caseId, int repetition, Map<String, Boolean> checks, boolean passed,
                  long latencyMs, int modelCalls, List<String> requestedTools, List<String> remoteRequests,
                  Long inputTokens, Long outputTokens, java.math.BigDecimal tokenCostEstimateUsd,
                  List<String> reportedModels, String errorType) {}

    private final EvalDataset dataset;
    private final MessageTemplates templates;
    private final ModelFactory models;
    private final EvalConfig config;

    EvalRunner(EvalDataset dataset, MessageTemplates templates, ModelFactory models, EvalConfig config) {
        this.dataset = dataset;
        this.templates = templates;
        this.models = models;
        this.config = config;
    }

    Result run(EvalDataset.Scenario scenario, int repetition) throws Exception {
        var requested = new CopyOnWriteArrayList<String>();
        var attempts = new CopyOnWriteArrayList<EvalScorer.Attempt>();
        var finalProposal = new AtomicReference<String>();
        var modelCalls = new AtomicInteger();
        var usage = new UsageMeter();
        AgentModel raw = models.create(usage::observe);
        AgentModel observed = (history, tools) -> {
            modelCalls.incrementAndGet();
            com.example.javaaiagent.application.Turn turn;
            try {
                turn = raw.complete(history, tools);
            } catch (RuntimeException ex) {
                // Graph libraries may log their exception chain; remove provider details before it enters the graph.
                throw new IllegalStateException("Evaluation provider call failed");
            }
            if (turn != null) {
                turn.calls().forEach(c -> requested.add(EvalScorer.key(c.name(), c.arguments())));
                if (turn.calls().isEmpty() && turn.role().equals("assistant") && turn.results().isEmpty()) {
                    finalProposal.set(turn.text());
                }
            }
            return turn;
        };
        var timeouts = com.example.javaaiagent.TestTimeouts.defaults();
        try (var fixture = new Fixture(dataset, scenario);
             var http = BoundedHttp.client(timeouts.connect());
             var model = new TimedAgentModel(observed, timeouts.llmCall());
             var workers = new BoundedExecutor("eval-", 1)) {
            var settings = new AgentSettings("local", "openai", config.model(), config.key(),
                    fixture.base(), "demo", "evaluation-client-token", null, "https://identity.example.com/", "jagent",
                    fixture.base().resolve("/status"), fixture.base().resolve("/runbook"),
                    "evaluation-status-token", "evaluation-runbook-token");
            List<ToolCallback> tools = new RemoteTools(settings, timeouts,
                    new Caller("evaluation", scenario.scopes(), scenario.services()), "eval-" + scenario.id(),
                    http, EvalScorer.JSON, templates).callbacks().stream().map(tool -> (ToolCallback) new EvidenceTool() {
                        public ToolDefinition getToolDefinition() { return tool.getToolDefinition(); }
                        public Outcome execute(String arguments) {
                            var outcome = ((EvidenceTool) tool).execute(arguments);
                            attempts.add(new EvalScorer.Attempt(EvalScorer.key(tool.getToolDefinition().name(), arguments), outcome.evidence()));
                            return outcome;
                        }
                    }).toList();
            long start = System.nanoTime();
            boolean completed = false;
            String errorType = null;
            try {
                String answer = workers.call(() -> new OperationsGraph(model, timeouts, templates)
                        .answer(scenario.question(), tools), timeouts.execution());
                completed = finalProposal.get() != null
                        && !List.of("graph-limit", "graph-too-many", "graph-invalid", "evidence-rejected", "evidence-overflow")
                                .stream().map(templates::text).toList().contains(answer);
            } catch (Exception ex) {
                // No provider exception text, request bodies, keys, or raw model output in reports.
                errorType = ex.getClass().getSimpleName();
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            var checks = EvalScorer.score(dataset, scenario, List.copyOf(requested), List.copyOf(attempts),
                    List.copyOf(fixture.requests), finalProposal.get(), completed, elapsed);
            Long input = usage.input(modelCalls.get());
            Long output = usage.output(modelCalls.get());
            var cost = input == null || config.inputUsdPerMillion() == null ? null
                    : config.inputUsdPerMillion().multiply(java.math.BigDecimal.valueOf(input))
                        .add(config.outputUsdPerMillion().multiply(java.math.BigDecimal.valueOf(output)))
                        .movePointLeft(6);
            return new Result(scenario.id(), repetition, checks, checks.values().stream().allMatch(Boolean::booleanValue),
                    elapsed, modelCalls.get(), List.copyOf(requested), List.copyOf(fixture.requests), input, output,
                    cost, usage.models(), errorType);
        }
    }

    static final class UsageMeter {
        private long input;
        private long output;
        private int validResponses;
        private final java.util.Set<String> models = new java.util.TreeSet<>();

        synchronized void observe(ChatResponse response) {
            var metadata = response.getMetadata();
            if (metadata.getModel() != null && !metadata.getModel().isBlank()) models.add(metadata.getModel());
            var usage = metadata.getUsage();
            // Some adapters represent missing usage as zero; do not report that as a free request.
            if (usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0
                    && usage.getCompletionTokens() != null && usage.getCompletionTokens() >= 0) {
                input += usage.getPromptTokens();
                output += usage.getCompletionTokens();
                validResponses++;
            }
        }
        synchronized Long input(int calls) { return validResponses == calls && calls > 0 ? input : null; }
        synchronized Long output(int calls) { return validResponses == calls && calls > 0 ? output : null; }
        synchronized List<String> models() { return List.copyOf(models); }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();

        Fixture(EvalDataset dataset, EvalDataset.Scenario scenario) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            for (String kind : List.of("status", "runbook")) {
                server.createContext("/" + kind + "/services/", exchange -> {
                    try (exchange) {
                        String service = exchange.getRequestURI().getPath().substring(("/" + kind + "/services/").length());
                        String key = (kind.equals("status") ? "get_service_status/" : "get_runbook/") + service;
                        requests.add(EvalDataset.KEYS.contains(key) ? key : "invalid");
                        var response = scenario.responses().getOrDefault(key, dataset.responses().get(key));
                        int status = response == null ? 404 : response.status();
                        String body = response == null ? "{}" : response.body().toString();
                        if (!exchange.getRequestMethod().equals("GET") || !java.util.Objects.equals(
                                exchange.getRequestHeaders().getFirst("Authorization"), "Bearer evaluation-" + kind + "-token")) {
                            status = 401;
                            body = "{}";
                        }
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
            }
            server.start();
        }
        URI base() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        public void close() { server.stop(0); }
    }
}
