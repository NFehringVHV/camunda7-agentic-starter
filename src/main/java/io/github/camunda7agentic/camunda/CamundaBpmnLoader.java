/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.camunda;

import org.camunda.bpm.client.task.ExternalTask;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the BPMN XML for the process definition of an external task from the Camunda 7 REST API.
 * Endpoint: {@code GET {baseUrl}/process-definition/{id}/xml}.
 *
 * <p>The process definition id in Camunda 7 is immutable ({@code key:version:deploymentSuffix}) - a
 * new deployment yields a new id. Therefore the loaded XML is cached per process definition id.
 */
@Component
public class CamundaBpmnLoader {

    private final CamundaRestClient client;
    private final Map<String, String> xmlCache = new ConcurrentHashMap<>();

    public CamundaBpmnLoader(CamundaRestClient client) {
        this.client = client;
    }

    public String loadBpmnXml(ExternalTask task) {
        String pdId = task.getProcessDefinitionId();
        if (pdId == null) {
            throw new IllegalStateException("ExternalTask has no process definition id.");
        }
        return xmlCache.computeIfAbsent(pdId, this::fetchBpmnXml);
    }

    private String fetchBpmnXml(String pdId) {
        XmlResponse resp = client.rest().get()
                .uri("/process-definition/{id}/xml", pdId)
                .retrieve()
                .body(XmlResponse.class);
        if (resp == null || resp.bpmn20Xml() == null) {
            throw new IllegalStateException("Empty BPMN XML response for " + pdId);
        }
        return resp.bpmn20Xml();
    }

    public record XmlResponse(String id, String bpmn20Xml) {
    }
}
