/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.history;

/**
 * Thrown when an existing agentic history cannot be read or parsed and the configured
 * {@code agentic.c7.history.on-read-error} policy is {@code FAIL}. Propagating this out of a worker
 * turns it into a technical failure (retry, then incident) instead of silently discarding --
 * and overwriting -- the conversation.
 */
public class HistoryUnreadableException extends RuntimeException {

    public HistoryUnreadableException(String message, Throwable cause) {
        super(message, cause);
    }
}
