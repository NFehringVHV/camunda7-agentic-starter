/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

/**
 * Result of a single LLM call in the agentic worker.
 *
 * @param output       parsed structured output JSON of the LLM.
 * @param totalTokens  total number of tokens (prompt + completion) per the usage metadata; 0 if the
 *                     model returned no usage data.
 * @param inputTokens  input/prompt tokens per the usage metadata; 0 if none.
 * @param outputTokens output/completion tokens per the usage metadata; 0 if none.
 */
public record AgenticCallResult(AgenticOutput output, long totalTokens, long inputTokens, long outputTokens) {
}
