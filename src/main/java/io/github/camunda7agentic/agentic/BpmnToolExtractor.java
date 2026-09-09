/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Documentation;
import org.camunda.bpm.model.bpmn.instance.FlowElement;
import org.camunda.bpm.model.bpmn.instance.Message;
import org.camunda.bpm.model.bpmn.instance.MessageEventDefinition;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.SubProcess;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts tool definitions from a BPMN model using the following convention:
 *
 * <ul>
 *   <li>Tool = event sub-process ({@code triggeredByEvent=true}) with a message start event.</li>
 *   <li>Tool name = name of the message referenced by the start event.</li>
 *   <li>Tool description = {@code bpmn:documentation} of the event sub-process
 *       (fallback: on the message start event).</li>
 *   <li>Tool arguments = all {@code camunda:property} entries on the message start event whose
 *       value matches {@code fromAi("<toolCallVar>.<arg>", "<description>")}.</li>
 * </ul>
 */
@Component
public class BpmnToolExtractor {

    /** Captures the argument path (group 1) and the description (group 2). */
    private static final Pattern FROM_AI = Pattern.compile(
            "\\s*fromAi\\(\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]*)\"\\s*\\)\\s*");

    public List<ToolDefinition> extract(String bpmnXml) {
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
        return extract(model);
    }

    public List<ToolDefinition> extract(BpmnModelInstance model) {
        List<ToolDefinition> result = new ArrayList<>();
        for (Process process : model.getModelElementsByType(Process.class)) {
            for (SubProcess sub : process.getChildElementsByType(SubProcess.class)) {
                if (!sub.triggeredByEvent()) {
                    continue;
                }
                extractTool(sub).ifPresent(result::add);
            }
        }
        return result;
    }

    private Optional<ToolDefinition> extractTool(SubProcess sub) {
        StartEvent messageStart = findMessageStartEvent(sub).orElse(null);
        if (messageStart == null) {
            return Optional.empty();
        }
        String toolName = messageName(messageStart);
        if (toolName == null) {
            return Optional.empty();
        }
        String description = readDocumentation(sub);
        if (description == null || description.isBlank()) {
            description = readDocumentation(messageStart);
        }
        List<ToolDefinition.Argument> args = readArguments(messageStart);
        return Optional.of(new ToolDefinition(toolName, description, args));
    }

    private Optional<StartEvent> findMessageStartEvent(SubProcess sub) {
        return sub.getChildElementsByType(StartEvent.class).stream()
                .filter(start -> start.getEventDefinitions().stream()
                        .anyMatch(MessageEventDefinition.class::isInstance))
                .findFirst();
    }

    private String messageName(StartEvent start) {
        return start.getEventDefinitions().stream()
                .filter(MessageEventDefinition.class::isInstance)
                .map(MessageEventDefinition.class::cast)
                .map(MessageEventDefinition::getMessage)
                .filter(Objects::nonNull)
                .map(Message::getName)
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String readDocumentation(FlowElement element) {
        Collection<Documentation> docs = element.getDocumentations();
        if (docs == null || docs.isEmpty()) {
            return null;
        }
        String text = docs.iterator().next().getTextContent();
        return text == null ? null : text.trim();
    }

    private List<ToolDefinition.Argument> readArguments(StartEvent start) {
        if (start.getExtensionElements() == null) {
            return List.of();
        }
        List<ToolDefinition.Argument> args = new ArrayList<>();
        for (CamundaProperties block : start.getExtensionElements()
                .getChildElementsByType(CamundaProperties.class)) {
            for (CamundaProperty prop : block.getCamundaProperties()) {
                String name = prop.getCamundaName();
                String value = prop.getCamundaValue();
                if (name == null || value == null) {
                    continue;
                }
                Matcher m = FROM_AI.matcher(value);
                if (m.matches()) {
                    args.add(new ToolDefinition.Argument(name, m.group(2)));
                }
            }
        }
        return args;
    }
}
