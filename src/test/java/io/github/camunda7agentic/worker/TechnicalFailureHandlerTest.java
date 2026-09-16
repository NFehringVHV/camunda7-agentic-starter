/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.worker;

import io.github.camunda7agentic.config.ExternalTaskClientProperties;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TechnicalFailureHandlerTest {

    private final ExternalTaskClientProperties props = new ExternalTaskClientProperties(
            "http://localhost:8080/engine-rest", null, 30000L, 10, 20000L,
            "llm-agentic", "agentic-tool-correlation", 300000L, null, null, true, 3, 30000L);

    private final TechnicalFailureHandler handler = new TechnicalFailureHandler(props);

    @Test
    void firstTransientFailureSeedsConfiguredRetriesWithBackoff() {
        ExternalTask task = mock(ExternalTask.class);
        when(task.getId()).thenReturn("t1");
        when(task.getRetries()).thenReturn(null);
        ExternalTaskService service = mock(ExternalTaskService.class);

        handler.handleTechnicalFailure(service, task, "llm-agentic", new RuntimeException("429 throttled"));

        verify(service).handleFailure(eq(task), eq("429 throttled"), anyString(), eq(3), eq(30000L));
    }

    @Test
    void subsequentTransientFailureDecrementsRetries() {
        ExternalTask task = mock(ExternalTask.class);
        when(task.getId()).thenReturn("t1");
        when(task.getRetries()).thenReturn(2);
        ExternalTaskService service = mock(ExternalTaskService.class);

        handler.handleTechnicalFailure(service, task, "llm-agentic", new RuntimeException("boom"));

        verify(service).handleFailure(eq(task), eq("boom"), anyString(), eq(1), eq(30000L));
    }

    @Test
    void lastTransientFailureRaisesIncidentWithoutBackoff() {
        ExternalTask task = mock(ExternalTask.class);
        when(task.getId()).thenReturn("t1");
        when(task.getRetries()).thenReturn(1);
        ExternalTaskService service = mock(ExternalTaskService.class);

        handler.handleTechnicalFailure(service, task, "llm-agentic", new RuntimeException("boom"));

        verify(service).handleFailure(eq(task), eq("boom"), anyString(), eq(0), eq(0L));
    }

    @Test
    void parseFailureIsNonRetryableAndCreatesIncidentImmediately() {
        ExternalTask task = mock(ExternalTask.class);
        when(task.getId()).thenReturn("t1");
        ExternalTaskService service = mock(ExternalTaskService.class);

        handler.handleTechnicalFailure(service, task, "llm-agentic",
                new IllegalStateException("LLM returned invalid JSON"));

        verify(service).handleFailure(eq(task), eq("LLM returned invalid JSON"), anyString(), eq(0), eq(0L));
    }

    @Test
    void retryableClassificationIsCorrect() {
        assertThat(TechnicalFailureHandler.isRetryable(new IllegalStateException("x"))).isFalse();
        assertThat(TechnicalFailureHandler.isRetryable(new RuntimeException("x"))).isTrue();
        assertThat(TechnicalFailureHandler.isRetryable(new java.io.IOException("x"))).isTrue();
    }

    @Test
    void zeroConfiguredRetriesKeepsFailImmediately() {
        ExternalTaskClientProperties zero = new ExternalTaskClientProperties(
                "http://localhost:8080/engine-rest", null, 30000L, 10, 20000L,
                "llm-agentic", "agentic-tool-correlation", 300000L, null, null, true, 0, 30000L);
        TechnicalFailureHandler zeroHandler = new TechnicalFailureHandler(zero);
        ExternalTask task = mock(ExternalTask.class);
        when(task.getId()).thenReturn("t1");
        when(task.getRetries()).thenReturn(null);
        ExternalTaskService service = mock(ExternalTaskService.class);

        zeroHandler.handleTechnicalFailure(service, task, "llm-agentic", new RuntimeException("boom"));

        verify(service).handleFailure(eq(task), eq("boom"), anyString(), eq(0), eq(0L));
    }
}
