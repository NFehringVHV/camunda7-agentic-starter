/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.worker;

import io.github.camunda7agentic.camunda.CamundaBpmnLoader;
import io.github.camunda7agentic.camunda.CamundaMessageCorrelator;
import io.github.camunda7agentic.agentic.ToolArgumentResolver;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Worker (topic {@code agentic-tool-correlation}): reads {@code agenticNextMessage} and
 * {@code toolCall} (or the configured variable name) from the external task, loads the BPMN model of
 * the running process, finds the message start event of the target tool, resolves its
 * {@code camunda:property} markers {@code fromAi(...)} against the tool-call map, and correlates the
 * message via REST ({@code POST /message}) with the resolved arguments as process variables.
 *
 * <p>Completes the external task afterwards. The downstream catch in the main flow keeps waiting for
 * {@code LLM-Result}.
 */
@Component
public class AgenticToolCorrelationWorker implements ExternalTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(AgenticToolCorrelationWorker.class);
    private static final String DEFAULT_TOOL_CALL_VAR = "toolCall";

    private final CamundaBpmnLoader bpmnLoader;
    private final ToolArgumentResolver argumentResolver;
    private final CamundaMessageCorrelator messageCorrelator;
    private final WorkerErrorHandler errorHandler;

    public AgenticToolCorrelationWorker(CamundaBpmnLoader bpmnLoader,
                                        ToolArgumentResolver argumentResolver,
                                        CamundaMessageCorrelator messageCorrelator,
                                        WorkerErrorHandler errorHandler) {
        this.bpmnLoader = bpmnLoader;
        this.argumentResolver = argumentResolver;
        this.messageCorrelator = messageCorrelator;
        this.errorHandler = errorHandler;
    }

    @Override
    public void execute(ExternalTask task, ExternalTaskService service) {
        try {
            String messageName = task.getVariable("agenticNextMessage");
            if (messageName == null || messageName.isBlank()) {
                errorHandler.handleBusinessError(service, task, "AGENTIC_NO_TOOL",
                        "Variable 'agenticNextMessage' is missing - the LLM worker did not choose a tool.");
                return;
            }
            String toolCallVar = TaskVariables.nonBlank(task.getVariable("toolCallVariableName"), DEFAULT_TOOL_CALL_VAR);
            Object toolCall = task.getVariable(toolCallVar);

            String bpmnXml = bpmnLoader.loadBpmnXml(task);
            BpmnModelInstance model = Bpmn.readModelFromStream(
                    new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));

            Map<String, Object> arguments = argumentResolver.resolveArguments(
                    model, messageName, toolCallVar, toolCall);

            log.info("agentic-tool-correlation: task={} pi={} message={} args={}",
                    task.getId(), task.getProcessInstanceId(), messageName, arguments.keySet());

            messageCorrelator.correlate(messageName, task.getProcessInstanceId(), arguments);

            service.complete(task);

        } catch (RuntimeException ex) {
            log.error("agentic-tool-correlation error: task={}", task.getId(), ex);
            service.handleFailure(task, ex.getMessage(), TaskVariables.stackTrace(ex), 0, 0L);
        }
    }
}
