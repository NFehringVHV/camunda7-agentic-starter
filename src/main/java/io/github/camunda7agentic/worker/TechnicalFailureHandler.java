/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.worker;

import io.github.camunda7agentic.config.ExternalTaskClientProperties;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Surfaces <em>technical</em> worker failures (LLM/engine/blob-store exceptions) via
 * {@link ExternalTaskService#handleFailure}.
 *
 * <p>The dominant failure modes of an LLM workload are <em>transient</em>: provider throttling
 * ({@code 429}), provider/engine {@code 5xx}, connection resets, TLS hiccups, a rolling engine
 * restart, or a message-correlation race. Failing those immediately (with {@code retries = 0})
 * turns normal operation into manual-resolution incidents. This handler therefore retries transient
 * failures with a configurable count and backoff (see {@code agentic.c7.client.technical-retries}
 * and {@code agentic.c7.client.technical-retry-timeout-ms}) and only raises an incident once the
 * retries are exhausted.
 *
 * <p>Deterministic failures that would not succeed on retry (e.g. an {@link IllegalStateException}
 * from parsing an invalid LLM response) are reported immediately with {@code retries = 0}.
 */
public class TechnicalFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(TechnicalFailureHandler.class);

    private final int technicalRetries;
    private final long retryTimeoutMs;

    public TechnicalFailureHandler(ExternalTaskClientProperties props) {
        this.technicalRetries = Math.max(props.technicalRetries(), 0);
        this.retryTimeoutMs = Math.max(props.technicalRetryTimeoutMs(), 0L);
    }

    /**
     * Reports a technical failure, retrying transient errors before creating an incident.
     *
     * @param service the external-task service to report through.
     * @param task    the failed external task.
     * @param topic   the worker topic (for logging only).
     * @param ex      the exception that occurred.
     */
    public void handleTechnicalFailure(ExternalTaskService service, ExternalTask task, String topic, Throwable ex) {
        if (!isRetryable(ex)) {
            log.error("{} non-retryable error -> incident: task={}", topic, task.getId(), ex);
            service.handleFailure(task, ex.getMessage(), TaskVariables.stackTrace(ex), 0, 0L);
            return;
        }
        int remaining = remainingRetries(task);
        long timeout = remaining > 0 ? retryTimeoutMs : 0L;
        if (remaining > 0) {
            log.warn("{} transient error, will retry: task={} retriesLeft={} backoffMs={}",
                    topic, task.getId(), remaining, timeout, ex);
        } else {
            log.error("{} transient error, retries exhausted -> incident: task={}", topic, task.getId(), ex);
        }
        service.handleFailure(task, ex.getMessage(), TaskVariables.stackTrace(ex), remaining, timeout);
    }

    /**
     * Computes the remaining retries for a task: the first failure seeds the configured count, each
     * subsequent failure decrements it (Camunda creates an incident once it hits {@code 0}).
     */
    int remainingRetries(ExternalTask task) {
        Integer current = task.getRetries();
        int remaining = current == null ? technicalRetries : current - 1;
        return Math.max(remaining, 0);
    }

    /**
     * Whether the given exception is worth retrying. Deterministic parse failures
     * ({@link IllegalStateException}) are not; everything else (I/O, HTTP 429/5xx, correlation
     * races, …) is treated as transient.
     */
    static boolean isRetryable(Throwable ex) {
        return !(ex instanceof IllegalStateException);
    }
}
