/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.config;

/**
 * How the history stores react when an <em>existing</em> history cannot be read or parsed (a
 * transient blob-store outage, a malformed byte, an incompatible wire format).
 *
 * <p>A missing history ({@code null}/blank variable or blob id) is never an error -- it simply
 * means "no history yet" and always yields an empty history regardless of this setting.
 */
public enum HistoryReadErrorMode {

    /**
     * Fail fast: propagate the read/parse failure so it becomes a technical failure (retry, then
     * incident). This is the default because silently resetting a durable history to empty and then
     * overwriting it on the next {@code persist} destroys the conversation permanently and is very
     * hard to diagnose from a single WARN line.
     */
    FAIL,

    /**
     * Recover silently: log a warning and continue with an empty history. Only choose this if losing
     * the prior conversation on an unreadable history is acceptable for your use case. Note that for
     * the {@code external} store this will overwrite the previously unreadable blob on the next
     * {@code persist}.
     */
    RESET
}
