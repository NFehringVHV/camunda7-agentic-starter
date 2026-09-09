/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.util;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Broken-down token usage of an LLM call: total, input (prompt) and output (completion).
 * Null-safe &ndash; missing values are treated as {@code 0} (e.g. when the model returns no usage
 * metadata).
 *
 * @param total  total number of tokens (prompt + completion).
 * @param input  input/prompt tokens.
 * @param output output/completion tokens.
 */
public record TokenUsage(long total, long input, long output) {

    /** Empty usage (all values 0), e.g. when no usage metadata is available. */
    public static final TokenUsage EMPTY = new TokenUsage(0L, 0L, 0L);

    /**
     * Reads the token usage null-safely from the chat response metadata.
     *
     * @param metadata metadata of the LLM call (may be {@code null}).
     * @return broken-down usage; {@link #EMPTY} if no usage data is available.
     */
    public static TokenUsage from(ChatResponseMetadata metadata) {
        if (metadata == null) {
            return EMPTY;
        }
        Usage usage = metadata.getUsage();
        if (usage == null) {
            return EMPTY;
        }
        return new TokenUsage(
                toLong(usage.getTotalTokens()),
                toLong(usage.getPromptTokens()),
                toLong(usage.getCompletionTokens()));
    }

    private static long toLong(Number value) {
        return value == null ? 0L : value.longValue();
    }
}
