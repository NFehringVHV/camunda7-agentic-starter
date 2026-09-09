/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
@Component
public class AgenticHistoryCodec {

    private static final Logger log = LoggerFactory.getLogger(AgenticHistoryCodec.class);

    private final ObjectMapper objectMapper;

    public AgenticHistoryCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Parses the history JSON; returns a new mutable list on {@code null}/blank/unparseable input. */
    public List<AgenticHistoryEntry> read(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AgenticHistoryEntry>>() {
            });
        } catch (JacksonException e) {
            log.warn("agenticHistory not parseable, starting fresh", e);
            return new ArrayList<>();
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
