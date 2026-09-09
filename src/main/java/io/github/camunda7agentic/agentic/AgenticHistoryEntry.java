/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single entry in the conversation history, one per loop turn.
 *
 * @param assistant      the full output JSON the LLM produced in this turn.
 * @param toolCallResult the tool result (observation) after correlating the tool message. Only set
 *                       when this turn was a tool call.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgenticHistoryEntry(AgenticOutput assistant, Object toolCallResult) {
}
