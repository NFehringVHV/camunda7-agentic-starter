/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.camunda;

import org.springframework.web.client.RestClient;

/**
 * Thin wrapper around a preconfigured {@link RestClient} pointing at the Camunda 7 REST API
 * (base URL such as {@code http://localhost:8080/engine-rest}, optionally with basic-auth or a
 * bearer token). Replaces the multi-engine client registry of the original VHV worker with a
 * single, generically configurable client.
 */
public class CamundaRestClient {

    private final RestClient restClient;

    public CamundaRestClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** The underlying REST client, already bound to the Camunda REST base URL and auth. */
    public RestClient rest() {
        return restClient;
    }
}
