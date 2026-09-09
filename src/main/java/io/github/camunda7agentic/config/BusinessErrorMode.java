/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.config;

/**
 * How the workers surface <em>business-level</em> failures (missing input, no tool chosen, missing
 * tool call). Technical failures (LLM/engine/blob-store exceptions) always become incidents via
 * {@code handleFailure} regardless of this setting.
 */
public enum BusinessErrorMode {

    /**
     * Report as a Camunda <b>incident</b> via {@code handleFailure} (retries = 0). Visible in the
     * Cockpit incident view; no BPMN modelling required. This is the default because it needs no
     * boundary error events on the process.
     */
    INCIDENT,

    /**
     * Raise a <b>BPMN error</b> via {@code handleBpmnError}. Lets the process author route the
     * condition to a business fallback path with a boundary/event-subprocess error event. If no
     * matching error catch exists, Camunda turns the unhandled BPMN error into an incident anyway.
     */
    BPMN_ERROR
}
