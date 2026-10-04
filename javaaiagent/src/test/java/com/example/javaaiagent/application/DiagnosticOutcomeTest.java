package com.example.javaaiagent.application;

import static com.example.javaaiagent.TestTemplates.templates;
import static com.example.javaaiagent.TestTimeouts.defaults;
import static org.junit.jupiter.api.Assertions.*;

import com.example.javaaiagent.diagnostics.*;
import com.example.javaaiagent.model.TimedAgentModel;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticOutcomeTest {
    @Test
    void outcomesDoNotDependOnRenderedText() throws Exception {
        assertEquals(
                Termination.NO_EVIDENCE,
                run((h, t) -> Turn.text("assistant", EvidenceAnswer.proposal(h))).reason());
        assertEquals(
                Termination.EVIDENCE_REJECTED,
                run((h, t) -> Turn.text("assistant", "invented answer")).reason());
        assertEquals(Termination.INVALID_MODEL_OUTPUT, run((h, t) -> null).reason());
        assertEquals(
                Termination.ROUND_LIMIT,
                run((h, t) ->
                                new Turn(
                                        "assistant",
                                        "",
                                        List.of(new Turn.Call("id", "unknown", "{}")),
                                        List.of()))
                        .reason());
        assertEquals(
                Termination.TOOL_LIMIT,
                run((h, t) ->
                                new Turn(
                                        "assistant",
                                        "",
                                        List.of(
                                                new Turn.Call("1", "unknown", "{}"),
                                                new Turn.Call("2", "unknown", "{}"),
                                                new Turn.Call("3", "unknown", "{}")),
                                        List.of()))
                        .reason());
    }

    @Test
    void usageCallbackCrossesTheModelWorkerBoundary() throws Exception {
        AgentModel provider =
                new AgentModel() {
                    public Turn complete(
                            List<Turn> h, List<org.springframework.ai.tool.ToolCallback> t) {
                        throw new AssertionError("Observed overload must be used");
                    }

                    public Turn complete(
                            List<Turn> h,
                            List<org.springframework.ai.tool.ToolCallback> t,
                            java.util.function.Consumer<TokenUsage> usage) {
                        usage.accept(new TokenUsage(123L, 45L));
                        return Turn.text("assistant", EvidenceAnswer.proposal(h));
                    }
                };
        var events = new java.util.ArrayList<java.util.Map<String, Object>>();
        var diagnostics =
                new RunDiagnostics(
                        null,
                        new com.example.javaaiagent.config.DiagnosticsSettings(null, null),
                        events::add);
        try (var timed = new TimedAgentModel(provider, Duration.ofSeconds(1))) {
            var result =
                    new OperationsGraph(timed, defaults(), templates())
                            .execute("payments", List.of(), diagnostics);
            diagnostics.finish(result.reason());
        }
        assertEquals(123L, events.getLast().get("input_tokens"));
        assertEquals(45L, events.getLast().get("output_tokens"));
    }

    @Test
    void providerSecretsNeverReachGraphExceptionLogging() {
        var logger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var error =
                    assertThrows(
                            Exception.class,
                            () ->
                                    run(
                                            (h, t) -> {
                                                throw new IllegalStateException(
                                                        "SECRET-PROVIDER-BODY");
                                            }));
            assertEquals(Termination.PROVIDER_ERROR, AgentFailure.classify(error));
            for (var event : appender.list) {
                assertFalse(event.getFormattedMessage().contains("SECRET-PROVIDER-BODY"));
                if (event.getThrowableProxy() != null)
                    assertFalse(
                            ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
                                            event.getThrowableProxy())
                                    .contains("SECRET-PROVIDER-BODY"));
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private AgentResult run(AgentModel model) throws Exception {
        return new OperationsGraph(model, defaults(), templates())
                .execute("private question", List.of(), RunDiagnostics.quiet());
    }
}
