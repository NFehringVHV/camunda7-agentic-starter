/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.history;

import org.camunda.bpm.client.task.ExternalTask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects the active {@link AgenticHistoryStore} per task. The store defaults to the globally
 * configured one ({@code agentic.c7.history.store}) but can be overridden per process instance via
 * the process variable {@value #VAR_HISTORY_STORE}.
 *
 * <p>Fails fast at startup if the configured default store is not available (e.g. {@code external}
 * without an {@link AgenticBlobStore} bean).
 */
public class HistoryStoreSelector {

    /** Optional per-process-instance override for the history store id. */
    public static final String VAR_HISTORY_STORE = "agenticHistoryStore";

    private final Map<String, AgenticHistoryStore> byName = new LinkedHashMap<>();
    private final String defaultStore;

    public HistoryStoreSelector(List<AgenticHistoryStore> stores, String defaultStore) {
        for (AgenticHistoryStore store : stores) {
            byName.put(store.name(), store);
        }
        this.defaultStore = defaultStore;
        if (!byName.containsKey(defaultStore)) {
            throw new IllegalStateException("Configured history store '" + defaultStore
                    + "' is not available. Available: " + byName.keySet()
                    + " (the 'external' store requires an AgenticBlobStore bean).");
        }
    }

    public AgenticHistoryStore select(ExternalTask task) {
        String requested = task.getVariable(VAR_HISTORY_STORE);
        String name = requested == null || requested.isBlank() ? defaultStore : requested;
        AgenticHistoryStore store = byName.get(name);
        if (store == null) {
            throw new IllegalStateException("Unknown/unavailable history store '" + name
                    + "'. Available: " + byName.keySet()
                    + " (the 'external' store requires an AgenticBlobStore bean).");
        }
        return store;
    }
}
