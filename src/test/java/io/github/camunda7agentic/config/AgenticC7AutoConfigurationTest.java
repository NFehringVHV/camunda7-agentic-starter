/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.config;

import io.github.camunda7agentic.agentic.AgenticChatService;
import io.github.camunda7agentic.camunda.CamundaRestClient;
import io.github.camunda7agentic.history.HistoryStoreSelector;
import io.github.camunda7agentic.worker.AgenticToolCorrelationWorker;
import io.github.camunda7agentic.worker.LlmAgenticWorker;
import io.github.camunda7agentic.worker.TechnicalFailureHandler;
import io.github.camunda7agentic.worker.WorkerErrorHandler;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.bedrock.converse.autoconfigure.BedrockConverseProxyChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Auto-configuration contract tests. Deliberately does <em>not</em> register a
 * {@code RestClient.Builder} bean: this starter's base is {@code spring-boot-starter} (not
 * {@code -web}), so it must not depend on one (see {@code camundaRestClient}). The external-task
 * client is disabled so no bean tries to open subscriptions against a real engine.
 */
class AgenticC7AutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgenticC7AutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("agentic.c7.client.enabled=false");

    @Test
    void startsWithoutChatModelAndBacksOffProviderBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // Provider-neutral beans are created even without a ChatModel (and without a
            // RestClient.Builder bean in the context).
            assertThat(context).hasSingleBean(CamundaRestClient.class);
            assertThat(context).hasSingleBean(HistoryStoreSelector.class);
            assertThat(context).hasSingleBean(TechnicalFailureHandler.class);
            assertThat(context).hasSingleBean(WorkerErrorHandler.class);
            // Provider-dependent beans back off gracefully instead of failing startup.
            assertThat(context).doesNotHaveBean(AgenticChatService.class);
            assertThat(context).doesNotHaveBean(LlmAgenticWorker.class);
            assertThat(context).doesNotHaveBean(AgenticToolCorrelationWorker.class);
        });
    }

    @Test
    void wiresProviderBeansWhenChatModelPresent() {
        runner.withBean(ChatModel.class, () -> mock(ChatModel.class)).run(context -> {
            assertThat(context).hasNotFailed();
            // CamundaRestClient wires up without any RestClient.Builder bean present.
            assertThat(context).hasSingleBean(CamundaRestClient.class);
            assertThat(context).hasSingleBean(AgenticChatService.class);
            assertThat(context).hasSingleBean(LlmAgenticWorker.class);
            assertThat(context).hasSingleBean(AgenticToolCorrelationWorker.class);
        });
    }

    /**
     * Regression test for the auto-configuration ordering bug: when the {@link ChatModel} is
     * contributed by a <em>provider auto-configuration</em> (here the real Spring AI Bedrock
     * Converse auto-config) rather than a user-registered bean, our provider-dependent beans must
     * still be wired. This only holds because {@link AgenticC7AutoConfiguration} declares
     * {@code @AutoConfigureAfter} the provider auto-configs; without that ordering our
     * {@code @ConditionalOnBean(ChatModel.class)} would be evaluated before the ChatModel bean
     * exists and the whole provider section (workers + external task client) would silently back
     * off (context starts but exits immediately with no subscriptions).
     *
     * <p>Dummy static AWS credentials + region let the Bedrock ChatModel bean be constructed fully
     * offline (no AWS call is made at wiring time).
     */
    @Test
    void wiresProviderBeansWhenChatModelComesFromProviderAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        SpringAiRetryAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class,
                        BedrockConverseProxyChatAutoConfiguration.class,
                        AgenticC7AutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "agentic.c7.client.enabled=false",
                        "spring.ai.bedrock.aws.region=eu-central-1",
                        "spring.ai.bedrock.aws.access-key=test",
                        "spring.ai.bedrock.aws.secret-key=test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // Sanity: the provider auto-config actually contributed a ChatModel.
                    assertThat(context).hasSingleBean(ChatModel.class);
                    // The point of the test: our provider-dependent beans are wired despite the
                    // ChatModel coming from another auto-configuration.
                    assertThat(context).hasSingleBean(AgenticChatService.class);
                    assertThat(context).hasSingleBean(LlmAgenticWorker.class);
                    assertThat(context).hasSingleBean(AgenticToolCorrelationWorker.class);
                });
    }
}
