/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Camunda 7 REST API connection used for loading BPMN XML and correlating messages
 * (prefix {@code agentic.c7.camunda}).
 *
 * @param baseUrl        Camunda REST base URL, e.g. {@code http://localhost:8080/engine-rest}.
 * @param username       optional basic-auth user.
 * @param password       optional basic-auth password.
 * @param bearerToken    optional bearer token (takes precedence over basic auth).
 * @param connectTimeoutMs connection timeout in milliseconds for the Camunda REST calls.
 * @param readTimeoutMs  read timeout in milliseconds. A finite read timeout is important: without it
 *                       a hung engine blocks the (single-threaded) external-task client indefinitely.
 */
@ConfigurationProperties("agentic.c7.camunda")
public record CamundaRestClientProperties(
        @DefaultValue("http://localhost:8080/engine-rest") String baseUrl,
        String username,
        String password,
        String bearerToken,
        @DefaultValue("5000") long connectTimeoutMs,
        @DefaultValue("10000") long readTimeoutMs) {
}
