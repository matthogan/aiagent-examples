package com.example.javaaiagent.application;

import static com.example.javaaiagent.TestTemplates.templates;
import static com.example.javaaiagent.TestTimeouts.defaults;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperationsGraphTest {
    @Test
    void rejectsInvalidModelOutputBeforeToolExecution() throws Exception {
        var invalid =
                List.of(
                        Turn.text("assistant", "x".repeat(16001)),
                        Turn.text("user", "unexpected role"),
                        new Turn(
                                "assistant",
                                "",
                                List.of(new Turn.Call("id", "tool", "x".repeat(4097))),
                                List.of()));
        for (Turn turn : invalid) {
            assertTrue(
                    new OperationsGraph((history, tools) -> turn, defaults(), templates())
                            .answer("payments", List.of())
                            .contains("invalid response"));
        }
        assertTrue(
                new OperationsGraph((history, tools) -> null, defaults(), templates())
                        .answer("payments", List.of())
                        .contains("invalid response"));
    }

    @Test
    void conversationStateOwnsItsCollections() {
        var calls = new ArrayList<Turn.Call>();
        var turn = new Turn("assistant", "", calls, List.of());
        calls.add(new Turn.Call("id", "tool", "{}"));
        assertTrue(turn.calls().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> turn.calls().clear());
    }
}
