/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Camunda external-task-client settings (prefix {@code agentic.c7.client}). These drive the
 * fetch-and-lock subscription for the two agentic topics.
 *
 * @param baseUrl                Camunda REST base URL for fetching external tasks.
 * @param workerId               optional worker id; auto-generated when blank.
 * @param lockDurationMs         default lock duration in milliseconds.
 * @param maxTasks               max tasks fetched per cycle.
 * @param asyncResponseTimeoutMs long-polling timeout in milliseconds.
 * @param agenticTopic           topic name for the agentic turn worker.
 * @param toolCorrelationTopic   topic name for the tool-correlation worker.
 * @param agenticMinLockMs       minimum lock duration for the agentic topic (LLM calls are slow).
 * @param username               optional basic-auth user.
 * @param password               optional basic-auth password.
 * @param enabled                whether to start the external-task-client subscriptions.
 */
@ConfigurationProperties("agentic.c7.client")
public record ExternalTaskClientProperties(
        @DefaultValue("http://localhost:8080/engine-rest") String baseUrl,
        String workerId,
        @DefaultValue("30000") long lockDurationMs,
        @DefaultValue("10") int maxTasks,
        @DefaultValue("20000") long asyncResponseTimeoutMs,
        @DefaultValue("llm-agentic") String agenticTopic,
        @DefaultValue("agentic-tool-correlation") String toolCorrelationTopic,
        @DefaultValue("300000") long agenticMinLockMs,
        String username,
        String password,
        @DefaultValue("true") boolean enabled) {
}
