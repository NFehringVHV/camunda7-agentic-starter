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
