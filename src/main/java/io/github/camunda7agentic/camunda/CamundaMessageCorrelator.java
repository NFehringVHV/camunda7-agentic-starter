/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.camunda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends a message correlation to the Camunda 7 REST API ({@code POST /message}). Values are wrapped
 * into the Camunda variable format {@code { "value": <v>, "type": "<t>" }}.
 */
@Component
public class CamundaMessageCorrelator {

    private static final Logger log = LoggerFactory.getLogger(CamundaMessageCorrelator.class);

    private final CamundaRestClient client;
    private final ObjectMapper objectMapper;

    public CamundaMessageCorrelator(CamundaRestClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public void correlate(String messageName, String processInstanceId, Map<String, Object> variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageName", messageName);
        body.put("processInstanceId", processInstanceId);
        body.put("processVariables", toVariableMap(variables));

        log.info("Camunda message correlation: message={} pi={} vars={}",
                messageName, processInstanceId, variables == null ? 0 : variables.size());

        client.rest().post()
                .uri("/message")
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, Object> toVariableMap(Map<String, Object> args) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (args == null) {
            return result;
        }
        for (Map.Entry<String, Object> e : args.entrySet()) {
            result.put(e.getKey(), toTypedValue(e.getValue()));
        }
        return result;
    }

    private Map<String, Object> toTypedValue(Object value) {
        Map<String, Object> wrapped = new LinkedHashMap<>();
        if (value == null) {
            wrapped.put("value", null);
            wrapped.put("type", "Null");
        } else if (value instanceof String s) {
            wrapped.put("value", s);
            wrapped.put("type", "String");
        } else if (value instanceof Boolean b) {
            wrapped.put("value", b);
            wrapped.put("type", "Boolean");
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            wrapped.put("value", ((Number) value).longValue());
            wrapped.put("type", "Long");
        } else if (value instanceof Float || value instanceof Double) {
            wrapped.put("value", ((Number) value).doubleValue());
            wrapped.put("type", "Double");
        } else {
            try {
                wrapped.put("value", objectMapper.writeValueAsString(value));
                wrapped.put("type", "Json");
            } catch (JacksonException ex) {
                log.warn("Could not serialize value as JSON, falling back to String. type={}",
                        value.getClass().getName(), ex);
                wrapped.put("value", value.toString());
                wrapped.put("type", "String");
            }
        }
        return wrapped;
    }
}
