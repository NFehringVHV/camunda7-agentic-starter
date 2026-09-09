/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.history;

import io.github.camunda7agentic.agentic.AgenticHistoryCodec;
import io.github.camunda7agentic.agentic.AgenticHistoryEntry;
import org.camunda.bpm.client.task.ExternalTask;

import java.util.List;
import java.util.Map;

/**
 * Tier 1: stores the history JSON in a plain String process variable ({@value #VAR_HISTORY}).
 * Cockpit-readable and dependency-free, but bounded by the Camunda {@code varchar(4000)} String
 * variable size. Suitable for short loops / small tool results only.
 */
public class InlineStringHistoryStore implements AgenticHistoryStore {

    private final AgenticHistoryCodec codec;

    public InlineStringHistoryStore(AgenticHistoryCodec codec) {
        this.codec = codec;
    }

    @Override
    public String name() {
        return "inline";
    }

    @Override
    public List<AgenticHistoryEntry> load(ExternalTask task) {
        String json = task.getVariable(VAR_HISTORY);
        return codec.read(json);
    }

    @Override
    public void persist(ExternalTask task, List<AgenticHistoryEntry> history, Map<String, Object> vars) {
        vars.put(VAR_HISTORY, codec.write(history));
        vars.put(VAR_HISTORY_BLOB_ID, null);
    }
}
