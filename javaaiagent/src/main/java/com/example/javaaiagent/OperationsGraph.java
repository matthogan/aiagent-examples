package com.example.javaaiagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

/** Explicit model -> tools -> model graph with fresh state for every request. */
@Service
public class OperationsGraph {
    private final AgentModel model;
    public OperationsGraph(AgentModel model) { this.model = model; }

    @SuppressWarnings("unchecked")
    private static List<Turn> history(AgentState state) {
        return (List<Turn>) state.data().get("history");
    }

    public String answer(String question, List<ToolCallback> tools) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(45).toNanos();
        var graph = new StateGraph<>(AgentState::new)
                .addNode("model", (AgentState state) -> {
                    checkDeadline(deadline);
                    int rounds = (int) state.data().getOrDefault("rounds", 0);
                    Turn next = rounds >= 4 ? Turn.text("assistant", "Execution limit reached; narrow the question.")
                            : model.complete(history(state), tools);
                    if (next.calls().size() > 2) {
                        next = Turn.text("assistant", "Too many tool calls requested; narrow the question.");
                    }
                    List<Turn> turns = new ArrayList<>(history(state));
                    turns.add(next);
                    return CompletableFuture.completedFuture(Map.of("history", turns, "rounds", rounds + 1));
                })
                .addNode("tools", (AgentState state) -> {
                    List<Turn.Result> results = new ArrayList<>();
                    for (Turn.Call call : history(state).getLast().calls()) {
                        checkDeadline(deadline);
                        var selected = tools.stream().filter(t -> t.getToolDefinition().name().equals(call.name())).findFirst();
                        String data = selected.isPresent() ? selected.get().call(call.arguments()) : "{\"error\":\"Unknown tool\"}";
                        results.add(new Turn.Result(call.id(), call.name(), data));
                    }
                    List<Turn> turns = new ArrayList<>(history(state));
                    turns.add(new Turn("tool", "", List.of(), results));
                    return CompletableFuture.completedFuture(Map.of("history", turns));
                })
                .addEdge(StateGraph.START, "model")
                .addConditionalEdges("model", state -> CompletableFuture.completedFuture(
                        history(state).getLast().calls().isEmpty() ? "done" : "tools"),
                        Map.of("done", StateGraph.END, "tools", "tools"))
                .addEdge("tools", "model").compile();
        graph.setMaxIterations(12);
        var result = graph.invoke(Map.of("history", List.of(Turn.text("user", question)), "rounds", 0)).orElseThrow();
        return history(result).getLast().text();
    }

    private static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new CancellationException("Execution deadline reached");
        }
    }
}
