/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

import java.util.List;

/**
 * Description of a tool extracted from the BPMN model.
 *
 * @param name        tool name (= name of the message event that starts the event sub-process)
 * @param description tool description for the LLM (= bpmn:documentation of the tool sub-process)
 * @param arguments   tool arguments (in the order modelled in the BPMN)
 */
public record ToolDefinition(String name, String description, List<Argument> arguments) {

    /**
     * A tool argument.
     *
     * @param name        argument name (= name attribute of the camunda:property)
     * @param description description (= second parameter of the fromAi expression)
     * @param type        data type; currently always "string"
     */
    public record Argument(String name, String description, String type) {
        public Argument(String name, String description) {
            this(name, description, "string");
        }
    }
}
