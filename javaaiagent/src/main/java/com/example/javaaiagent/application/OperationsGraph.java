package com.example.javaaiagent.application;

import com.example.javaaiagent.config.InputValidation;
import com.example.javaaiagent.config.RuntimeSettings;
import com.example.javaaiagent.config.TimeoutSettings;
import com.example.javaaiagent.diagnostics.AgentFailure;
import com.example.javaaiagent.diagnostics.AgentResult;
import com.example.javaaiagent.diagnostics.RunDiagnostics;
import com.example.javaaiagent.diagnostics.Termination;
import com.example.javaaiagent.diagnostics.TokenUsage;
import com.example.javaaiagent.evidence.EvidenceTool;
import com.example.javaaiagent.templates.MessageTemplates;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicit model -> tools -> model graph with fresh state for every request.
 */
@Service
public class OperationsGraph {

    private final RuntimeSettings runtime;
    private static final int MAX_OUTPUT_CHARACTERS = 16_000;
    private final Duration executionTimeout;
    private final AgentModel model;
    private final MessageTemplates templates;

    public OperationsGraph(AgentModel model, TimeoutSettings timeouts, MessageTemplates templates) {
        this(model, timeouts, templates, RuntimeSettings.defaults());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OperationsGraph(AgentModel model, TimeoutSettings timeouts, MessageTemplates templates, RuntimeSettings runtime) {
        this.runtime = runtime;
        this.templates = templates;
        this.model = model;
        this.executionTimeout = timeouts.execution();
    }

    public String answer(String question, List<ToolCallback> tools) throws Exception {
        return execute(question, tools, RunDiagnostics.quiet()).text();
    }

    public AgentResult execute(String question, List<ToolCallback> tools, RunDiagnostics diagnostics)
            throws Exception {
        String validatedQuestion = InputValidation.question(question);
        // A monotonic clock measures elapsed time even if the wall clock is adjusted.
        long deadline = System.nanoTime() + executionTimeout.toNanos();
        // Each invocation owns its history and deadline; no conversation is shared between callers.
        // completedFuture adapts a synchronous step to the graph API; it does not start a thread.
        var graph = new StateGraph<>(AgentState::new)
                .addNode("model", (AgentState state) ->
                        CompletableFuture.completedFuture(modelStep(state, tools, deadline, diagnostics)))
                .addNode("tools", (AgentState state) ->
                        CompletableFuture.completedFuture(toolStep(state, tools, deadline, diagnostics)))
                .addEdge(StateGraph.START, "model")
                .addConditionalEdges("model",
                        (AgentState state) -> CompletableFuture.completedFuture(nextStep(state)),
                        Map.of("done", StateGraph.END, "tools", "tools"))
                .addEdge("tools", "model")
                .compile();
        graph.setMaxIterations(2 * runtime.maxModelRounds() + 4);
        var initialState = Map.<String, Object>of("history", List.of(Turn.text("user", validatedQuestion)), "rounds", 0);
        var result = graph.invoke(initialState).orElseThrow();
        return new AgentResult(
                history(result).getLast().text(), (Termination) result.data().get("termination"));
    }

    private Map<String, Object> modelStep(AgentState state, List<ToolCallback> tools, long deadline, RunDiagnostics diagnostics) {
        checkDeadline(deadline);
        int rounds = (int) state.data().getOrDefault("rounds", 0);
        if (rounds >= runtime.maxModelRounds()) {
            return terminal(state, templates.text("graph-limit"), Termination.ROUND_LIMIT, rounds);
        }
        long start = System.nanoTime();
        int round = diagnostics.modelStarted();
        var usage = new AtomicReference<>(TokenUsage.unknown());
        Turn next;
        try {
            // Reserve the last call for an answer using already collected evidence.
            var availableTools = rounds == runtime.maxModelRounds() - 1 ? List.<ToolCallback>of() : tools;
            next = model.complete(history(state), availableTools, usage::set);
        } catch (RuntimeException ex) {
            Termination reason = ex instanceof AgentFailure failure ? failure.reason()
                    : ex instanceof CancellationException ? Termination.CANCELLED
                      : ex instanceof RejectedExecutionException ? Termination.CAPACITY_REJECTED : Termination.PROVIDER_ERROR;
            diagnostics.modelFinished(round, start, usage.get(), reason.name());
            // Do not pass raw provider exceptions to the graph library's logger.
            throw new AgentFailure(reason);
        }
        diagnostics.modelFinished(round, start, usage.get(), "completed");
        checkDeadline(deadline);
        return validatedOutput(next, state, rounds + 1);
    }

    private Map<String, Object> terminal(AgentState state, String text, Termination reason, int rounds) {
        var turns = new ArrayList<>(history(state));
        turns.add(Turn.text("assistant", text));
        return Map.of("history", turns, "rounds", rounds, "termination", reason);
    }

    private Map<String, Object> toolStep(AgentState state, List<ToolCallback> tools, long deadline, RunDiagnostics diagnostics) {
        List<Turn.Result> results = new ArrayList<>();
        for (Turn.Call call : history(state).getLast().calls()) {
            checkDeadline(deadline);
            long start = System.nanoTime();
            diagnostics.toolStarted(call.name());
            try {
                var selected = tools.stream()
                        .filter(tool -> tool.getToolDefinition().name().equals(call.name()))
                        .findFirst();
                if (selected.isPresent() && selected.get() instanceof EvidenceTool tool) {
                    var outcome = tool.execute(call.arguments());
                    results.add(new Turn.Result(
                            call.id(),
                            call.name(),
                            outcome.content(),
                            outcome.service(),
                            outcome.evidence()));
                    diagnostics.toolFinished(call.name(), start, outcome.evidence() != null);
                } else {
                    String data = selected.isPresent()
                            ? selected.get().call(call.arguments())
                            : templates.json("error-unknown-tool");
                    results.add(new Turn.Result(call.id(), call.name(), data));
                    diagnostics.toolFinished(call.name(), start, false);
                }
            } catch (RuntimeException ex) {
                diagnostics.toolFinished(call.name(), start, false);
                throw new AgentFailure(Termination.INTERNAL_ERROR);
            }
        }
        List<Turn> turns = new ArrayList<>(history(state));
        turns.add(new Turn("tool", "", List.of(), results));
        return Map.of("history", turns);
    }

    /**
     * Treat model output as untrusted proposals before selecting or invoking a tool.
     */
    private Map<String, Object> validatedOutput(Turn next, AgentState state, int rounds) {
        if (next != null && next.calls().size() > runtime.maxToolCallsPerRound()) {
            return terminal(state, templates.text("graph-too-many"), Termination.TOOL_LIMIT, rounds);
        }
        if (next == null
                || !"assistant".equals(next.role())
                || next.text().length() > MAX_OUTPUT_CHARACTERS
                || (next.calls().isEmpty() && next.text().isBlank())
                || !next.results().isEmpty()
                || next.calls().stream().anyMatch(OperationsGraph::invalidCall)
                || next.calls().stream().map(Turn.Call::id).distinct().count()
                != next.calls().size()) {
            return terminal(state, templates.text("graph-invalid"), Termination.INVALID_MODEL_OUTPUT, rounds);
        }
        if (next.calls().isEmpty()) {
            var answer = new EvidenceAnswer(templates).evaluate(next.text(), history(state), model.scripted());
            return terminal(state, answer.text(), answer.reason(), rounds);
        }
        // Enforce the budget even if a provider ignores tool_choice=none.
        if (rounds >= runtime.maxModelRounds()) {
            return terminal(state, templates.text("graph-limit"), Termination.ROUND_LIMIT, rounds);
        }
        var turns = new ArrayList<>(history(state));
        turns.add(next);
        return Map.of("history", turns, "rounds", rounds);
    }

    private static boolean invalidCall(Turn.Call call) {
        return call.id() == null
                || call.id().isBlank()
                || call.id().length() > 200
                || call.name() == null
                || call.name().isBlank()
                || call.name().length() > 100
                || call.arguments() == null
                || call.arguments().length() > 4096;
    }

    private static String nextStep(AgentState state) {
        return history(state).getLast().calls().isEmpty() ? "done" : "tools";
    }

    // Only this class writes history, so the cast is constrained to locally constructed Turn
    // values.
    @SuppressWarnings("unchecked")
    private static List<Turn> history(AgentState state) {
        return (List<Turn>) state.data().get("history");
    }

    private static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new AgentFailure(Thread.currentThread().isInterrupted() ? Termination.CANCELLED : Termination.EXECUTION_TIMEOUT);
        }
    }
}
