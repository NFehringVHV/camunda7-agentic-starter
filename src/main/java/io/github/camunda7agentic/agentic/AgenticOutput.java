/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Structured output schema the LLM must return per turn. Enforced in the system prompt and parsed
 * directly from the JSON string by the worker.
 *
 * <p><b>Open point (D5):</b> {@link JsonProperty#required()} is only used for schema generation and
 * is <em>not</em> enforced by Jackson on deserialization. If the model omits {@code agenticDone} it
 * silently defaults to {@code false}; the loop then continues and surfaces a later
 * {@code LLM_TOOLCALL_MISSING} business error rather than a "required field missing" one. Left as-is
 * for now (the system prompt already mandates the field).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgenticOutput(
        @JsonProperty(required = true) boolean agenticDone,
        @JsonProperty(required = true) String reasoning,
        ToolCall toolCall,
        String finalAnswer,
        String abortReason,
        String nextStepPlan) {

    /** A single tool invocation requested by the LLM. */
    public record ToolCall(String name, Map<String, Object> arguments) {
    }
}
