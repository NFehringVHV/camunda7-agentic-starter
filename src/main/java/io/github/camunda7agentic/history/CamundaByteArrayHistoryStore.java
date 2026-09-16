/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.history;

import io.github.camunda7agentic.agentic.AgenticHistoryCodec;
import io.github.camunda7agentic.agentic.AgenticHistoryEntry;
import org.camunda.bpm.client.task.ExternalTask;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Tier 2: stores the history as a byte-array / JSON typed process variable. Such variables are
 * persisted by Camunda 7 in {@code ACT_GE_BYTEARRAY} (not the {@code varchar(4000)} column), so the
 * history can grow well beyond the String-variable limit without an external store.
 *
 * <p>The variable type is switchable via {@code agentic.c7.history.byte-array-type}:
 * <ul>
 *   <li>{@link ByteArrayType#BYTES} -- a raw {@code byte[]} value. No extra dependency; in
 *       Cockpit it appears as a downloadable binary.</li>
 *   <li>{@link ByteArrayType#JSON} -- a SPIN {@code Json} value, human-readable in Cockpit.
 *       Requires camunda-spin-dataformat-json(-jackson) on the worker classpath (resolved
 *       reflectively; fail-fast if missing).</li>
 * </ul>
 */
public class CamundaByteArrayHistoryStore implements AgenticHistoryStore {

    /** Wire type of the history byte-array variable. */
    public enum ByteArrayType {
        /** Raw {@code byte[]} value; dependency-free, Cockpit shows a download. */
        BYTES,
        /** SPIN {@code Json} value; Cockpit-readable, requires camunda-spin on the classpath. */
        JSON
    }

    private final AgenticHistoryCodec codec;
    private final ByteArrayType byteArrayType;

    public CamundaByteArrayHistoryStore(AgenticHistoryCodec codec, ByteArrayType byteArrayType) {
        this.codec = codec;
        this.byteArrayType = byteArrayType;
    }

    @Override
    public String name() {
        return "camunda-bytearray";
    }

    @Override
    public List<AgenticHistoryEntry> load(ExternalTask task) {
        Object value = task.getVariable(VAR_HISTORY);
        return codec.read(toJsonString(value));
    }

    @Override
    public void persist(ExternalTask task, List<AgenticHistoryEntry> history, Map<String, Object> vars) {
        String json = codec.write(history);
        if (byteArrayType == ByteArrayType.JSON) {
            vars.put(VAR_HISTORY, SpinJsonValues.jsonValue(json));
        } else {
            vars.put(VAR_HISTORY, json.getBytes(StandardCharsets.UTF_8));
        }
        vars.put(VAR_HISTORY_BLOB_ID, null);
    }

    private static String toJsonString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        // String, or a SPIN json node whose toString() yields the JSON text.
        return value.toString();
    }
}
