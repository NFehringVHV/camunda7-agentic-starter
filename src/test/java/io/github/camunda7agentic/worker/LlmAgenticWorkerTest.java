/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.worker;

import io.github.camunda7agentic.agentic.AgenticCallResult;
import io.github.camunda7agentic.agentic.AgenticChatService;
import io.github.camunda7agentic.agentic.AgenticHistoryEntry;
import io.github.camunda7agentic.agentic.AgenticOutput;
import io.github.camunda7agentic.agentic.BpmnToolExtractor;
import io.github.camunda7agentic.agentic.SystemPromptBuilder;
import io.github.camunda7agentic.camunda.CamundaBpmnLoader;
import io.github.camunda7agentic.config.AgenticProperties;
import io.github.camunda7agentic.config.BusinessErrorMode;
import io.github.camunda7agentic.history.AgenticHistoryStore;
import io.github.camunda7agentic.history.HistoryStoreSelector;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmAgenticWorkerTest {

    private final CamundaBpmnLoader bpmnLoader = mock(CamundaBpmnLoader.class);
    private final BpmnToolExtractor toolExtractor = mock(BpmnToolExtractor.class);
    private final SystemPromptBuilder systemPromptBuilder = mock(SystemPromptBuilder.class);
    private final AgenticChatService chatService = mock(AgenticChatService.class);
    private final BlobResolver blobResolver = mock(BlobResolver.class);
    private final HistoryStoreSelector historyStoreSelector = mock(HistoryStoreSelector.class);
    private final AgenticHistoryStore historyStore = mock(AgenticHistoryStore.class);

    // toolCall="toolCall", historyMaxTurns=20, toolCallResultMaxChars=8000,
    // defaultMaxIterations=5, defaultMaxTokens=1000, promptFullToolResultTurns=2, businessErrorMode=INCIDENT
    private final AgenticProperties props = new AgenticProperties(
            "toolCall", 20, 8000, 5, 1000L, 2, BusinessErrorMode.INCIDENT);

    private final LlmAgenticWorker worker = new LlmAgenticWorker(
            bpmnLoader, toolExtractor, systemPromptBuilder, chatService, blobResolver,
            historyStoreSelector, new WorkerErrorHandler(props),
            mock(TechnicalFailureHandler.class), props);

    @Test
    void countsIterationAndTokens() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);
        when(task.getVariable("agenticIteration")).thenReturn(2);
        when(task.getVariable("agenticTokensUsed")).thenReturn(123L);

        AgenticOutput out = new AgenticOutput(false, "continue",
                new AgenticOutput.ToolCall("getWeather", Map.of("location", "Hanover")),
                null, null, null);
        when(chatService.call(any(), any(), any(), any(), any()))
                .thenReturn(new AgenticCallResult(out, 50L, 40L, 10L));

        worker.execute(task, service);

        Map<String, Object> vars = captureComplete(service, task);
        assertThat(vars).containsEntry("agenticIteration", 3);
        assertThat(vars).containsEntry("agenticTokensUsed", 173L);
        assertThat(vars).containsEntry("agenticTokensLastTurn", 50L);
        assertThat(vars).containsEntry("agenticInputTokensUsed", 40L);
        assertThat(vars).containsEntry("agenticOutputTokensUsed", 10L);
        assertThat(vars).containsEntry("agenticInputTokensLastTurn", 40L);
        assertThat(vars).containsEntry("agenticOutputTokensLastTurn", 10L);
        assertThat(vars).containsEntry("agenticDone", false);
        assertThat(vars).containsEntry("agenticNextMessage", "getWeather");
    }

    @Test
    void abortOnPreCheckMaxIterations() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);
        // last allowed turn was max=5; incoming iteration 5 -> +1=6 > 5 -> abort
        when(task.getVariable("agenticIteration")).thenReturn(5);

        worker.execute(task, service);

        verify(chatService, never()).call(any(), any(), any(), any(), any());
        Map<String, Object> vars = captureComplete(service, task);
        assertThat(vars).containsEntry("agenticDone", true);
        assertThat(vars).containsEntry("agenticAbortReason", "max-iterations-exceeded");
        assertThat(vars).containsEntry("agenticIteration", 5);
    }

    @Test
    void abortOnPostCheckMaxTokens() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);
        when(task.getVariable("agenticIteration")).thenReturn(0);
        when(task.getVariable("agenticTokensUsed")).thenReturn(900L);

        AgenticOutput out = new AgenticOutput(false, "continue",
                new AgenticOutput.ToolCall("getWeather", Map.of("location", "Hanover")),
                null, null, null);
        // +200 -> 1100, max=1000 -> abort
        when(chatService.call(any(), any(), any(), any(), any()))
                .thenReturn(new AgenticCallResult(out, 200L, 160L, 40L));

        worker.execute(task, service);

        Map<String, Object> vars = captureComplete(service, task);
        assertThat(vars).containsEntry("agenticDone", true);
        assertThat(vars).containsEntry("agenticAbortReason", "max-tokens-exceeded");
        assertThat(vars).containsEntry("agenticTokensUsed", 1100L);
        assertThat(vars).containsEntry("agenticNextMessage", null);
    }

    @Test
    void processVariablesOverrideDefaults() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);
        when(task.getVariable("maxIterations")).thenReturn(2);
        when(task.getVariable("agenticIteration")).thenReturn(2);

        worker.execute(task, service);

        verify(chatService, never()).call(any(), any(), any(), any(), any());
        Map<String, Object> vars = captureComplete(service, task);
        assertThat(vars).containsEntry("agenticAbortReason", "max-iterations-exceeded");
    }

    @Test
    void abortOnPostCheckReachedIterationWithoutDone() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);
        when(task.getVariable("maxIterations")).thenReturn(3);
        when(task.getVariable("agenticIteration")).thenReturn(2);

        AgenticOutput out = new AgenticOutput(false, "continue",
                new AgenticOutput.ToolCall("getWeather", Map.of()), null, null, null);
        when(chatService.call(any(), any(), any(), any(), any()))
                .thenReturn(new AgenticCallResult(out, 10L, 8L, 2L));

        worker.execute(task, service);

        Map<String, Object> vars = captureComplete(service, task);
        assertThat(vars).containsEntry("agenticDone", true);
        assertThat(vars).containsEntry("agenticAbortReason", "max-iterations-exceeded");
        assertThat(vars).containsEntry("agenticIteration", 3);
    }

    @Test
    void doneWithFinalAnswerClearsNextMessage() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);

        AgenticOutput out = new AgenticOutput(true, "done", null, "The answer.", null, null);
        when(chatService.call(any(), any(), any(), any(), any()))
                .thenReturn(new AgenticCallResult(out, 10L, 8L, 2L));

        worker.execute(task, service);

        Map<String, Object> vars = captureComplete(service, task);
        assertThat(vars).containsEntry("agenticDone", true);
        assertThat(vars).containsEntry("agenticFinalAnswer", "The answer.");
        assertThat(vars).containsEntry("agenticNextMessage", null);
    }

    @Test
    void missingInputSkipsLlmCall() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);
        // BlobResolver already raised a BPMN error and returned null.
        when(blobResolver.resolveUserPromptOrHandleError(eq(task), eq(service), any(), anyString()))
                .thenReturn(null);

        worker.execute(task, service);

        verify(chatService, never()).call(any(), any(), any(), any(), any());
    }

    @Test
    void persistsHistoryImmediatelyBeforeCompleteOnSuccess() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);

        AgenticOutput out = new AgenticOutput(false, "continue",
                new AgenticOutput.ToolCall("getWeather", Map.of("location", "Hanover")),
                null, null, null);
        when(chatService.call(any(), any(), any(), any(), any()))
                .thenReturn(new AgenticCallResult(out, 10L, 8L, 2L));

        worker.execute(task, service);

        InOrder inOrder = inOrder(historyStore, service);
        inOrder.verify(historyStore).persist(eq(task), any(), any());
        inOrder.verify(service).complete(eq(task), any());
    }

    @Test
    void doesNotPersistHistoryWhenBusinessErrorAborts() {
        ExternalTask task = baseTask();
        ExternalTaskService service = mock(ExternalTaskService.class);

        // agenticDone=false but no toolCall -> LLM_TOOLCALL_MISSING business error, task not completed.
        AgenticOutput out = new AgenticOutput(false, "continue", null, null, null, null);
        when(chatService.call(any(), any(), any(), any(), any()))
                .thenReturn(new AgenticCallResult(out, 10L, 8L, 2L));

        worker.execute(task, service);

        verify(historyStore, never()).persist(any(), any(), any());
        verify(service, never()).complete(any(), any());
    }

    private ExternalTask baseTask() {
        ExternalTask task = mock(ExternalTask.class);
        lenient().when(task.getId()).thenReturn("t1");
        lenient().when(task.getProcessInstanceId()).thenReturn("pi1");
        lenient().when(bpmnLoader.loadBpmnXml(any())).thenReturn("<xml/>");
        lenient().when(toolExtractor.extract(anyString())).thenReturn(List.of());
        lenient().when(systemPromptBuilder.build(any(), any())).thenReturn("sys");
        lenient().when(blobResolver.resolveUserPromptOrHandleError(any(), any(), any(), anyString()))
                .thenReturn("do it");
        lenient().when(blobResolver.resolveOptionalText(any(), anyString(), anyString(), any()))
                .thenReturn(null);
        lenient().when(blobResolver.resolveToolCallResult(any(), any())).thenReturn(null);
        lenient().when(historyStoreSelector.select(any())).thenReturn(historyStore);
        lenient().when(historyStore.name()).thenReturn("inline");
        lenient().when(historyStore.load(any())).thenReturn(new ArrayList<AgenticHistoryEntry>());
        return task;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureComplete(ExternalTaskService service, ExternalTask task) {
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(service, atLeastOnce()).complete(eq(task), cap.capture());
        return cap.getValue();
    }
}
