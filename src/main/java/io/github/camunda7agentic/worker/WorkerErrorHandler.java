/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.worker;

import io.github.camunda7agentic.config.AgenticProperties;
import io.github.camunda7agentic.config.BusinessErrorMode;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.springframework.stereotype.Component;

/**
 * Surfaces <em>business-level</em> worker failures according to the configured
 * {@link BusinessErrorMode}.
 *
 * <ul>
 *   <li>{@link BusinessErrorMode#INCIDENT} (default) &ndash; report via
 *       {@link ExternalTaskService#handleFailure} with {@code retries = 0}, so Camunda creates an
 *       incident immediately. The error code is prefixed onto the incident message for traceability.
 *       No BPMN modelling is required.</li>
 *   <li>{@link BusinessErrorMode#BPMN_ERROR} &ndash; raise a BPMN error via
 *       {@link AgenticBpmnErrors} so the process can catch it on a boundary/event sub-process error
 *       event.</li>
 * </ul>
 *
 * <p>Technical failures (LLM/engine/blob-store exceptions) are handled directly by the workers via
 * {@code handleFailure} and never go through this class.
 */
@Component
public class WorkerErrorHandler {

    private final BusinessErrorMode mode;

    public WorkerErrorHandler(AgenticProperties props) {
        this.mode = props.businessErrorMode();
    }

    /**
     * Reports a business-level failure using the configured mode. After this call the external task
     * is either failed (incident) or completed with a BPMN error; the caller must stop processing.
     *
     * @param errorCode    stable error code (e.g. {@code AGENTIC_NO_TOOL}).
     * @param errorMessage human-readable reason.
     */
    public void handleBusinessError(ExternalTaskService service,
                                    ExternalTask task,
                                    String errorCode,
                                    String errorMessage) {
        if (mode == BusinessErrorMode.BPMN_ERROR) {
            AgenticBpmnErrors.handle(service, task, errorCode, errorMessage);
        } else {
            service.handleFailure(task, "[" + errorCode + "] " + errorMessage, errorMessage, 0, 0L);
        }
    }

    BusinessErrorMode mode() {
        return mode;
    }
}
