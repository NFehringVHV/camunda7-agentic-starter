/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.worker;

import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskService;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper for BPMN errors raised by the agentic workers.
 *
 * <p>Camunda 7 propagates only the error code into the process on
 * {@link ExternalTaskService#handleBpmnError(ExternalTask, String, String)}; the error message is
 * not readily visible in Cockpit/history (BPMN errors do not create incidents). So that the concrete
 * reason is visible in Cockpit, this helper additionally writes a few
 * {@code agenticWorkerError*} process variables that survive independently of the catch:
 * <ul>
 *   <li>{@code agenticWorkerErrorCode}</li>
 *   <li>{@code agenticWorkerErrorMessage}</li>
 *   <li>{@code agenticWorkerErrorTopic}</li>
 *   <li>{@code agenticWorkerErrorWorkerId}</li>
 *   <li>{@code agenticWorkerErrorTime}</li>
 * </ul>
 *
 * <p>Additionally it is recommended to set {@code camunda:errorCodeVariable}/
 * {@code camunda:errorMessageVariable} on the boundary event so the catch path also receives
 * code/message cleanly.
 */
public final class AgenticBpmnErrors {

    public static final String VAR_ERROR_CODE = "agenticWorkerErrorCode";
    public static final String VAR_ERROR_MESSAGE = "agenticWorkerErrorMessage";
    public static final String VAR_ERROR_TOPIC = "agenticWorkerErrorTopic";
    public static final String VAR_ERROR_WORKER_ID = "agenticWorkerErrorWorkerId";
    public static final String VAR_ERROR_TIME = "agenticWorkerErrorTime";

    private AgenticBpmnErrors() {
    }

    /**
     * Raises a BPMN error and additionally writes the {@code agenticWorkerError*} process variables
     * so the concrete reason is visible in Cockpit.
     */
    public static void handle(ExternalTaskService service,
                              ExternalTask task,
                              String errorCode,
                              String errorMessage) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(VAR_ERROR_CODE, errorCode);
        vars.put(VAR_ERROR_MESSAGE, errorMessage);
        vars.put(VAR_ERROR_TOPIC, task.getTopicName());
        vars.put(VAR_ERROR_WORKER_ID, task.getWorkerId());
        vars.put(VAR_ERROR_TIME, OffsetDateTime.now().toString());
        service.handleBpmnError(task, errorCode, errorMessage, vars);
    }
}
