/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.worker;

import io.github.camunda7agentic.camunda.CamundaBpmnLoader;
import io.github.camunda7agentic.camunda.CamundaMessageCorrelator;
import io.github.camunda7agentic.agentic.ToolArgumentResolver;
import io.github.camunda7agentic.config.AgenticProperties;
import io.github.camunda7agentic.config.BusinessErrorMode;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgenticToolCorrelationWorkerTest {

    private final CamundaBpmnLoader bpmnLoader = mock(CamundaBpmnLoader.class);
    private final ToolArgumentResolver argumentResolver = mock(ToolArgumentResolver.class);
    private final CamundaMessageCorrelator messageCorrelator = mock(CamundaMessageCorrelator.class);

    private final AgenticToolCorrelationWorker worker = workerWith(BusinessErrorMode.INCIDENT);

    private AgenticToolCorrelationWorker workerWith(BusinessErrorMode mode) {
        AgenticProperties props = new AgenticProperties("toolCall", 20, 8000, 10, 0L, 2, mode);
        return new AgenticToolCorrelationWorker(
                bpmnLoader, argumentResolver, messageCorrelator, new WorkerErrorHandler(props));
    }

    /** Minimal BPMN with a message start event in an event sub-process - enough for parsing. */
    private static final String DEMO_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://test">
              <bpmn:process id="p1" isExecutable="true">
                <bpmn:startEvent id="s1"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Test
    void happyPathCorrelatesMessage() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);
        when(task.getVariable("agenticNextMessage")).thenReturn("getWeather");
        when(task.getVariable("toolCallVariableName")).thenReturn(null);
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("location", "Hanover");
        when(task.getVariable("toolCall")).thenReturn(toolCall);

        Map<String, Object> resolved = Map.of("location", "Hanover");
        when(argumentResolver.resolveArguments(any(BpmnModelInstance.class), eq("getWeather"),
                eq("toolCall"), eq(toolCall))).thenReturn(resolved);

        worker.execute(task, service);

        verify(messageCorrelator).correlate(eq("getWeather"), eq("pi1"), eq(resolved));
        verify(service).complete(task);
    }

    @Test
    void customToolCallVariableNameIsUsed() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);
        when(task.getVariable("agenticNextMessage")).thenReturn("doFoo");
        when(task.getVariable("toolCallVariableName")).thenReturn("myToolCall");
        Object call = Map.of("a", 1);
        when(task.getVariable("myToolCall")).thenReturn(call);
        when(argumentResolver.resolveArguments(any(), eq("doFoo"), eq("myToolCall"), eq(call)))
                .thenReturn(Map.of("a", 1));

        worker.execute(task, service);

        verify(messageCorrelator).correlate(eq("doFoo"), eq("pi1"), any());
        verify(service).complete(task);
    }

    @Test
    void missingMessageCreatesIncidentByDefault() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);
        when(task.getVariable("agenticNextMessage")).thenReturn(null);

        worker.execute(task, service);

        verify(service).handleFailure(eq(task), contains("AGENTIC_NO_TOOL"), anyString(), eq(0), anyLong());
        verify(service, never()).handleBpmnError(any(ExternalTask.class), anyString(), anyString(), any());
        verify(messageCorrelator, never()).correlate(any(), any(), any());
        verify(service, never()).complete(any());
    }

    @Test
    void missingMessageRaisesBpmnErrorWhenConfigured() {
        AgenticToolCorrelationWorker bpmnErrorWorker = workerWith(BusinessErrorMode.BPMN_ERROR);
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);
        when(task.getVariable("agenticNextMessage")).thenReturn(null);

        bpmnErrorWorker.execute(task, service);

        verify(service).handleBpmnError(eq(task), eq("AGENTIC_NO_TOOL"), anyString(), any());
        verify(messageCorrelator, never()).correlate(any(), any(), any());
        verify(service, never()).complete(any());
    }

    @Test
    void blankMessageCreatesIncidentByDefault() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);
        when(task.getVariable("agenticNextMessage")).thenReturn("   ");

        worker.execute(task, service);

        verify(service).handleFailure(eq(task), contains("AGENTIC_NO_TOOL"), anyString(), eq(0), anyLong());
    }

    @Test
    void exceptionLeadsToHandleFailure() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);
        when(task.getVariable("agenticNextMessage")).thenReturn("getWeather");
        when(argumentResolver.resolveArguments(any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("boom"));

        worker.execute(task, service);

        verify(service).handleFailure(eq(task), eq("boom"), anyString(), eq(0), anyLong());
        verify(messageCorrelator, never()).correlate(any(), any(), any());
    }

    private ExternalTask baseTask() {
        ExternalTask task = mock(ExternalTask.class);
        lenient().when(task.getId()).thenReturn("t1");
        lenient().when(task.getProcessInstanceId()).thenReturn("pi1");
        lenient().when(bpmnLoader.loadBpmnXml(any())).thenReturn(DEMO_BPMN);
        return task;
    }
}
