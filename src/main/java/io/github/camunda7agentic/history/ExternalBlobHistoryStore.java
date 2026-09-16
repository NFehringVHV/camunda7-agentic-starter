/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.history;

import io.github.camunda7agentic.agentic.AgenticHistoryCodec;
import io.github.camunda7agentic.agentic.AgenticHistoryEntry;
import io.github.camunda7agentic.config.HistoryReadErrorMode;
import org.camunda.bpm.client.task.ExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tier 3: offloads the history to a user-provided {@link AgenticBlobStore}. Only the deterministic
 * blob id ({@value #VAR_HISTORY_BLOB_ID}), stable per process instance, is kept as a process
 * variable. Activated automatically when an {@link AgenticBlobStore} bean is present.
 */
public class ExternalBlobHistoryStore implements AgenticHistoryStore {

    private static final Logger log = LoggerFactory.getLogger(ExternalBlobHistoryStore.class);
    private static final String HISTORY_ID_PREFIX = "agenticHistory-";

    private final AgenticBlobStore blobStore;
    private final AgenticHistoryCodec codec;
    private final HistoryReadErrorMode onReadError;

    public ExternalBlobHistoryStore(AgenticBlobStore blobStore, AgenticHistoryCodec codec,
                                    HistoryReadErrorMode onReadError) {
        this.blobStore = blobStore;
        this.codec = codec;
        this.onReadError = onReadError;
    }

    @Override
    public String name() {
        return "external";
    }

    @Override
    public List<AgenticHistoryEntry> load(ExternalTask task) {
        String blobId = task.getVariable(VAR_HISTORY_BLOB_ID);
        if (blobId == null || blobId.isBlank()) {
            return new ArrayList<>();
        }
        byte[] bytes;
        try {
            bytes = blobStore.read(blobId);
        } catch (HistoryUnreadableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // A present blob id whose content cannot be fetched is a real read failure, never
            // "no history yet". Do NOT return empty here: the next persist would overwrite the
            // (recoverable) blob and destroy the conversation.
            if (onReadError == HistoryReadErrorMode.RESET) {
                log.warn("history blob not readable (id={}), starting with an empty history "
                        + "(on-read-error=reset).", blobId, ex);
                return new ArrayList<>();
            }
            throw new HistoryUnreadableException("history blob not readable (id=" + blobId + ")", ex);
        }
        String json = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        // Parse errors are surfaced by the codec according to the same policy.
        return codec.read(json);
    }

    @Override
    public void persist(ExternalTask task, List<AgenticHistoryEntry> history, Map<String, Object> vars) {
        String pi = task.getProcessInstanceId();
        String blobId = stableHistoryId(pi);
        String json = codec.write(history);
        blobStore.writeOrUpdate(blobId, pi, json.getBytes(StandardCharsets.UTF_8), VAR_HISTORY);
        vars.put(VAR_HISTORY_BLOB_ID, blobId);
        vars.put(VAR_HISTORY, null);
    }

    /** Deterministic, per-process-instance stable blob id for the history. */
    public static String stableHistoryId(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            throw new IllegalArgumentException("processInstanceId must not be blank.");
        }
        return HISTORY_ID_PREFIX + processInstanceId;
    }
}
