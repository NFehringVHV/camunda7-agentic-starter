/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

import io.github.camunda7agentic.config.HistoryReadErrorMode;
import io.github.camunda7agentic.history.HistoryUnreadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializes/deserializes the agentic conversation history to and from its JSON representation.
 * Kept separate from persistence so that all history stores share the exact same wire format,
 * regardless of where the JSON is stored (process variable, byte array, external blob).
 */
public class AgenticHistoryCodec {

    private static final Logger log = LoggerFactory.getLogger(AgenticHistoryCodec.class);

    private final ObjectMapper objectMapper;
    private final HistoryReadErrorMode onReadError;

    public AgenticHistoryCodec(ObjectMapper objectMapper, HistoryReadErrorMode onReadError) {
        this.objectMapper = objectMapper;
        this.onReadError = onReadError;
    }

    /**
     * Parses the history JSON. Returns a new mutable list for {@code null}/blank input ("no history
     * yet"). If the input is present but unparseable, the behaviour depends on the configured
     * {@link HistoryReadErrorMode}: {@code FAIL} (default) throws {@link HistoryUnreadableException},
     * {@code RESET} logs a warning and returns an empty list.
     */
    public List<AgenticHistoryEntry> read(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AgenticHistoryEntry>>() {
            });
        } catch (JacksonException e) {
            if (onReadError == HistoryReadErrorMode.RESET) {
                log.warn("agenticHistory not parseable, starting fresh (on-read-error=reset)", e);
                return new ArrayList<>();
            }
            throw new HistoryUnreadableException("agenticHistory not parseable", e);
        }
    }

    /** Serializes the history to JSON. */
    public String write(List<AgenticHistoryEntry> history) {
        try {
            return objectMapper.writeValueAsString(history);
        } catch (JacksonException e) {
            throw new IllegalStateException("agenticHistory not serializable", e);
        }
    }
}
