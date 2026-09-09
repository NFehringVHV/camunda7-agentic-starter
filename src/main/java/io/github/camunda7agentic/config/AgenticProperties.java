/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Core agentic behaviour settings (prefix {@code agentic.c7}).
 *
 * @param defaultToolCallVariableName default process variable name holding the tool-call arguments.
 * @param historyMaxTurns             max number of history turns sent to the LLM (0 = unlimited).
 * @param toolCallResultMaxChars      max characters of a tool result embedded into the prompt
 *                                    (0 = unlimited).
 * @param defaultMaxIterations        default loop iteration cap (0 = unlimited).
 * @param defaultMaxTokens            default cumulative token budget (0 = unlimited).
 * @param promptFullToolResultTurns   number of most-recent turns whose full tool result is embedded.
 * @param businessErrorMode           how business-level failures are surfaced: {@code incident}
 *                                    (default) or {@code bpmn-error}. Technical failures always
 *                                    become incidents.
 */
@ConfigurationProperties("agentic.c7")
public record AgenticProperties(
        @DefaultValue("toolCall") String defaultToolCallVariableName,
        @DefaultValue("20") int historyMaxTurns,
        @DefaultValue("8000") int toolCallResultMaxChars,
        @DefaultValue("10") int defaultMaxIterations,
        @DefaultValue("0") long defaultMaxTokens,
        @DefaultValue("2") int promptFullToolResultTurns,
        @DefaultValue("incident") BusinessErrorMode businessErrorMode) {
}
