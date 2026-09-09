/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolArgumentResolverTest {

    private final ToolArgumentResolver resolver = new ToolArgumentResolver();

    @Test
    void resolvesArgumentsFromCamundaProperties() {
        BpmnModelInstance model = Bpmn.readModelFromStream(
                getClass().getResourceAsStream("/bpmn/agentic-demo.bpmn"));

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("location", "Hanover");

        Map<String, Object> args = resolver.resolveArguments(model, "getWeather", "toolCall", toolCall);

        assertThat(args).containsEntry("location", "Hanover");
    }

    @Test
    void resolvesNestedPath() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("city", "Berlin");
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("address", inner);

        Object value = resolver.resolvePath("toolCall.address.city", "toolCall", toolCall);
        assertThat(value).isEqualTo("Berlin");
    }

    @Test
    void unknownMessageThrows() {
        BpmnModelInstance model = Bpmn.readModelFromStream(
                getClass().getResourceAsStream("/bpmn/agentic-demo.bpmn"));

        assertThatThrownBy(() -> resolver.resolveArguments(model, "doesNotExist", "toolCall", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("doesNotExist");
    }

    @Test
    void declaredArgumentWithoutValueIsCorrelatedAsNull() {
        BpmnModelInstance model = Bpmn.readModelFromStream(
                getClass().getResourceAsStream("/bpmn/agentic-demo.bpmn"));

        // toolCall without 'location' -> resolvePath returns null. The argument must still land in
        // the map (value null) so that values from an earlier loop iteration are overwritten in
        // agentic mode (stale-data protection; see ToolArgumentResolver javadoc).
        Map<String, Object> args = resolver.resolveArguments(model, "getWeather", "toolCall",
                new LinkedHashMap<>());

        assertThat(args).containsEntry("location", null);
    }
}
