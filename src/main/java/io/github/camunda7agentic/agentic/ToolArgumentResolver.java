/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Message;
import org.camunda.bpm.model.bpmn.instance.MessageEventDefinition;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.SubProcess;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the message start event of a tool event sub-process by message name and resolves its
 * {@code camunda:property} markers {@code fromAi("<toolCallVar>.<arg>", "<desc>")} against the
 * {@code toolCall} map.
 *
 * <h2>Stale-data protection in the agentic loop</h2>
 *
 * <p>Within the agentic loop (LLM task &rarr; gateway &rarr; tool correlation &rarr; catch
 * {@code LLM-Result} &rarr; back) the same tool sub-process may be invoked across multiple
 * iterations. Because {@link CamundaMessageCorrelator} sends the resolved arguments via
 * {@code processVariables} (= process-instance scope), values from earlier iterations would linger
 * if the LLM omits an argument in a later iteration.</p>
 *
 * <p>Therefore <strong>every {@code fromAi} argument declared on the start event is written to the
 * result map deterministically</strong> -- either with the value provided by the LLM or as
 * {@code null} (which {@link CamundaMessageCorrelator} correlates as the typed value
 * {@code "Null"}). This way each tool iteration fully overwrites the variable state of the previous
 * iterations.</p>
 */
public class ToolArgumentResolver {

    private static final Logger log = LoggerFactory.getLogger(ToolArgumentResolver.class);

    private static final Pattern FROM_AI = Pattern.compile(
            "\\s*fromAi\\(\\s*\"([^\"]+)\"\\s*(?:,\\s*\"([^\"]*)\"\\s*)?\\)\\s*");

    public Map<String, Object> resolveArguments(BpmnModelInstance model,
                                                String messageName,
                                                String toolCallVariableName,
                                                Object toolCall) {
        StartEvent start = findMessageStartEvent(model, messageName)
                .orElseThrow(() -> new IllegalStateException(
                        "No message start event found in a triggeredByEvent sub-process for message '"
                                + messageName + "'."));

        Map<String, Object> resolved = new LinkedHashMap<>();
        if (start.getExtensionElements() == null) {
            return resolved;
        }
        for (CamundaProperties block : start.getExtensionElements()
                .getChildElementsByType(CamundaProperties.class)) {
            for (CamundaProperty prop : block.getCamundaProperties()) {
                String name = prop.getCamundaName();
                String value = prop.getCamundaValue();
                if (name == null || value == null) {
                    continue;
                }
                Matcher m = FROM_AI.matcher(value);
                if (!m.matches()) {
                    continue;
                }
                Object argValue = resolvePath(m.group(1), toolCallVariableName, toolCall);
                if (argValue == null && log.isDebugEnabled()) {
                    log.debug("Tool argument '{}' (path '{}') not in toolCall - correlated explicitly as Null (stale-data protection).",
                            name, m.group(1));
                }
                resolved.put(name, argValue);
            }
        }
        return resolved;
    }

    private Optional<StartEvent> findMessageStartEvent(BpmnModelInstance model, String messageName) {
        for (Process process : model.getModelElementsByType(Process.class)) {
            for (SubProcess sub : process.getChildElementsByType(SubProcess.class)) {
                if (!sub.triggeredByEvent()) {
                    continue;
                }
                for (StartEvent start : sub.getChildElementsByType(StartEvent.class)) {
                    for (var def : start.getEventDefinitions()) {
                        if (def instanceof MessageEventDefinition med) {
                            Message m = med.getMessage();
                            if (m != null && messageName.equals(m.getName())) {
                                return Optional.of(start);
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Navigates a path such as {@code toolCall.address.city} against the {@code toolCall} map. The
     * first path segment equals the configured variable name and is skipped.
     */
    Object resolvePath(String path, String toolCallVar, Object toolCall) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("\\.");
        if (parts.length == 0) {
            return null;
        }
        int startIdx = parts[0].equals(toolCallVar) ? 1 : 0;
        Object current = toolCall;
        for (int i = startIdx; i < parts.length; i++) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[i]);
            } else if (current == null) {
                return null;
            } else {
                log.warn("Cannot navigate path '{}' further - value at position {} is not a Map (type {})",
                        path, i, current.getClass().getName());
                return null;
            }
        }
        return current;
    }
}
