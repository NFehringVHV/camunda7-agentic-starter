/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.history;

import io.github.camunda7agentic.agentic.AgenticHistoryEntry;
import org.camunda.bpm.client.task.ExternalTask;

import java.util.List;
import java.util.Map;

/**
 * Strategy for loading and persisting the agentic conversation history.
 *
 * <p>Three tiers are available:
 * <ol>
 *   <li>{@code inline} -- stores the history JSON in a String process variable
 *       ({@value #VAR_HISTORY}). Simplest and Cockpit-readable, but limited by the Camunda
 *       {@code varchar(4000)} String variable size.</li>
 *   <li>{@code camunda-bytearray} -- stores the history as a byte-array/JSON typed variable
 *       (persisted in {@code ACT_GE_BYTEARRAY}); no 4000-char limit and no external store.</li>
 *   <li>{@code external} -- offloads the history to a user-provided {@link AgenticBlobStore}
 *       and keeps only the blob id ({@value #VAR_HISTORY_BLOB_ID}) in the process.</li>
 * </ol>
 */
public interface AgenticHistoryStore {

    /** Process variable holding the inline history JSON / byte-array payload. */
    String VAR_HISTORY = "agenticHistory";

    /** Process variable holding the external blob id of the history. */
    String VAR_HISTORY_BLOB_ID = "agenticHistoryBlobId";

    /** The store id (e.g. {@code inline}, {@code camunda-bytearray}, {@code external}). */
    String name();

    /** Loads the history for the given task; returns an empty, mutable list if none exists yet. */
    List<AgenticHistoryEntry> load(ExternalTask task);

    /**
     * Persists the history and writes the appropriate completion variables into {@code vars}
     * (which is later passed to {@code service.complete}).
     */
    void persist(ExternalTask task, List<AgenticHistoryEntry> history, Map<String, Object> vars);
}
