/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

import io.github.camunda7agentic.config.HistoryReadErrorMode;
import io.github.camunda7agentic.history.HistoryUnreadableException;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgenticHistoryCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void nullOrBlankIsTreatedAsNoHistory() {
        AgenticHistoryCodec codec = new AgenticHistoryCodec(objectMapper, HistoryReadErrorMode.FAIL);

        assertThat(codec.read(null)).isEmpty();
        assertThat(codec.read("   ")).isEmpty();
    }

    @Test
    void validJsonRoundTrips() {
        AgenticHistoryCodec codec = new AgenticHistoryCodec(objectMapper, HistoryReadErrorMode.FAIL);
        List<AgenticHistoryEntry> history = List.of(
                new AgenticHistoryEntry(new AgenticOutput(true, "done", null, "answer", null, null), null));

        String json = codec.write(history);
        List<AgenticHistoryEntry> parsed = codec.read(json);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).assistant().finalAnswer()).isEqualTo("answer");
    }

    @Test
    void failModeThrowsOnUnparseableInput() {
        AgenticHistoryCodec codec = new AgenticHistoryCodec(objectMapper, HistoryReadErrorMode.FAIL);

        assertThatThrownBy(() -> codec.read("{ this is not valid json"))
                .isInstanceOf(HistoryUnreadableException.class);
    }

    @Test
    void resetModeReturnsEmptyOnUnparseableInput() {
        AgenticHistoryCodec codec = new AgenticHistoryCodec(objectMapper, HistoryReadErrorMode.RESET);

        assertThat(codec.read("{ this is not valid json")).isEmpty();
    }
}
