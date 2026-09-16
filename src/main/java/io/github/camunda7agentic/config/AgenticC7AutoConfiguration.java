/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.config;

import io.github.camunda7agentic.agentic.AgenticChatService;
import io.github.camunda7agentic.agentic.AgenticHistoryCodec;
import io.github.camunda7agentic.agentic.BpmnToolExtractor;
import io.github.camunda7agentic.agentic.SystemPromptBuilder;
import io.github.camunda7agentic.agentic.ToolArgumentResolver;
import io.github.camunda7agentic.camunda.CamundaBpmnLoader;
import io.github.camunda7agentic.camunda.CamundaMessageCorrelator;
import io.github.camunda7agentic.camunda.CamundaRestClient;
import io.github.camunda7agentic.history.AgenticBlobStore;
import io.github.camunda7agentic.history.AgenticHistoryStore;
import io.github.camunda7agentic.history.CamundaByteArrayHistoryStore;
import io.github.camunda7agentic.history.ExternalBlobHistoryStore;
import io.github.camunda7agentic.history.HistoryStoreSelector;
import io.github.camunda7agentic.history.InlineStringHistoryStore;
import io.github.camunda7agentic.worker.AgenticToolCorrelationWorker;
import io.github.camunda7agentic.worker.BlobResolver;
import io.github.camunda7agentic.worker.LlmAgenticWorker;
import io.github.camunda7agentic.worker.TechnicalFailureHandler;
import io.github.camunda7agentic.worker.WorkerErrorHandler;
import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.interceptor.auth.BasicAuthProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Auto-configuration for the Camunda 7 agentic worker. Wires the agentic core beans, the Camunda
 * REST client, the history stores and (optionally) the external-task-client subscriptions for the
 * {@code llm-agentic} and {@code agentic-tool-correlation} topics.
 *
 * <p>The whole configuration backs off unless Spring AI's {@link ChatModel} type is on the classpath
 * ({@link ConditionalOnClass}). The provider-specific beans (the chat service and the workers/task
 * client that depend on it) are additionally guarded by {@link ConditionalOnBean}, so a user who
 * adds the starter but has <em>not yet</em> wired a {@code ChatModel} bean gets a graceful back-off
 * instead of a {@code NoSuchBeanDefinitionException} at startup.
 *
 * <p>Every bean is declared with {@link ConditionalOnMissingBean}, so any of them
 * -- e.g. {@link SystemPromptBuilder} or {@link CamundaRestClient} -- can be overridden by
 * the user application simply by defining a bean of the same type. For the {@code external} history
 * mode or blob-backed inputs, an {@link AgenticBlobStore} bean must be supplied by the application.
 */
@AutoConfiguration(afterName = {
        // The provider-dependent beans below are guarded by @ConditionalOnBean(ChatModel.class).
        // For that condition to see a ChatModel contributed by a Spring AI provider auto-config,
        // this auto-config MUST run after them: without explicit ordering Spring Boot falls back to
        // alphabetical FQN ordering, and "io.github.camunda7agentic..." sorts before
        // "org.springframework.ai...", so our config would otherwise be evaluated first (before the
        // ChatModel bean exists) and the whole provider section (workers + external task client)
        // would silently back off. afterName tolerates classes that are absent from the classpath,
        // so listing all common providers is safe regardless of which starter the user picked.
        "org.springframework.ai.model.bedrock.converse.autoconfigure.BedrockConverseProxyChatAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
        "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration",
        "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration",
        "org.springframework.ai.model.mistralai.autoconfigure.MistralAiChatAutoConfiguration",
        "org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration",
        "org.springframework.ai.model.google.genai.autoconfigure.GoogleGenAiChatAutoConfiguration",
        "org.springframework.ai.model.vertexai.gemini.autoconfigure.VertexAiGeminiChatAutoConfiguration"
})
@ConditionalOnClass(ChatModel.class)
@ConditionalOnProperty(prefix = "agentic.c7", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({
        AgenticProperties.class,
        AgenticHistoryProperties.class,
        CamundaRestClientProperties.class,
        ExternalTaskClientProperties.class
})
public class AgenticC7AutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgenticC7AutoConfiguration.class);

    // --- Camunda REST client -------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public CamundaRestClient camundaRestClient(CamundaRestClientProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(props.readTimeoutMs()));
        // Build the RestClient from scratch instead of injecting an autoconfigured
        // RestClient.Builder: this starter's base is spring-boot-starter (not -web), so a
        // RestClient.Builder bean is not guaranteed to exist (e.g. a non-web worker application),
        // which would fail context startup. Constructing it here keeps the starter self-contained.
        var builder = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(requestFactory);
        if (props.bearerToken() != null && !props.bearerToken().isBlank()) {
            builder.defaultHeaders(h -> h.setBearerAuth(props.bearerToken()));
        } else if (props.username() != null && !props.username().isBlank()) {
            builder.defaultHeaders(h -> h.setBasicAuth(props.username(), props.password() == null ? "" : props.password()));
        }
        return new CamundaRestClient(builder.build());
    }

    // --- Provider-neutral core beans -----------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public AgenticHistoryCodec agenticHistoryCodec(ObjectMapper objectMapper,
                                                   AgenticHistoryProperties historyProps) {
        return new AgenticHistoryCodec(objectMapper, historyProps.onReadError());
    }

    @Bean
    @ConditionalOnMissingBean
    public BpmnToolExtractor bpmnToolExtractor() {
        return new BpmnToolExtractor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolArgumentResolver toolArgumentResolver() {
        return new ToolArgumentResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public SystemPromptBuilder systemPromptBuilder(
            ObjectMapper objectMapper,
            @Value("classpath:prompts/default-system-prompt.txt") Resource defaultPromptResource) {
        return new SystemPromptBuilder(objectMapper, defaultPromptResource);
    }

    @Bean
    @ConditionalOnMissingBean
    public CamundaBpmnLoader camundaBpmnLoader(CamundaRestClient client) {
        return new CamundaBpmnLoader(client);
    }

    @Bean
    @ConditionalOnMissingBean
    public CamundaMessageCorrelator camundaMessageCorrelator(CamundaRestClient client, ObjectMapper objectMapper) {
        return new CamundaMessageCorrelator(client, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerErrorHandler workerErrorHandler(AgenticProperties props) {
        return new WorkerErrorHandler(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public TechnicalFailureHandler technicalFailureHandler(ExternalTaskClientProperties props) {
        return new TechnicalFailureHandler(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public BlobResolver blobResolver(ObjectProvider<AgenticBlobStore> blobStoreProvider,
                                     WorkerErrorHandler errorHandler) {
        return new BlobResolver(blobStoreProvider, errorHandler);
    }

    // --- History stores ------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean(name = "inlineStringHistoryStore")
    public InlineStringHistoryStore inlineStringHistoryStore(AgenticHistoryCodec codec) {
        return new InlineStringHistoryStore(codec);
    }

    @Bean
    @ConditionalOnMissingBean(name = "camundaByteArrayHistoryStore")
    public CamundaByteArrayHistoryStore camundaByteArrayHistoryStore(AgenticHistoryCodec codec,
                                                                     AgenticHistoryProperties props) {
        return new CamundaByteArrayHistoryStore(codec, props.byteArrayType());
    }

    @Bean
    @ConditionalOnBean(AgenticBlobStore.class)
    @ConditionalOnMissingBean(name = "externalBlobHistoryStore")
    public ExternalBlobHistoryStore externalBlobHistoryStore(AgenticBlobStore blobStore,
                                                             AgenticHistoryCodec codec,
                                                             AgenticHistoryProperties historyProps) {
        return new ExternalBlobHistoryStore(blobStore, codec, historyProps.onReadError());
    }

    @Bean
    @ConditionalOnMissingBean
    public HistoryStoreSelector historyStoreSelector(List<AgenticHistoryStore> stores,
                                                     AgenticHistoryProperties props) {
        return new HistoryStoreSelector(stores, props.store());
    }

    // --- Provider-dependent beans (require a ChatModel) -----------------------------------------

    /**
     * Beans that need a Spring AI {@link ChatModel}. Grouped into a nested configuration guarded by
     * {@link ConditionalOnBean} so the surrounding provider-neutral beans are still created (and the
     * context still starts) when no provider is wired yet.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(ChatModel.class)
    public static class AgenticChatModelConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public AgenticChatService agenticChatService(ChatModel chatModel, ObjectMapper objectMapper,
                                                     AgenticProperties props) {
            return new AgenticChatService(chatModel, objectMapper, props);
        }

        @Bean
        @ConditionalOnMissingBean
        public LlmAgenticWorker llmAgenticWorker(CamundaBpmnLoader bpmnLoader,
                                                 BpmnToolExtractor toolExtractor,
                                                 SystemPromptBuilder systemPromptBuilder,
                                                 AgenticChatService chatService,
                                                 BlobResolver blobResolver,
                                                 HistoryStoreSelector historyStoreSelector,
                                                 WorkerErrorHandler errorHandler,
                                                 TechnicalFailureHandler technicalFailureHandler,
                                                 AgenticProperties props) {
            return new LlmAgenticWorker(bpmnLoader, toolExtractor, systemPromptBuilder, chatService,
                    blobResolver, historyStoreSelector, errorHandler, technicalFailureHandler, props);
        }

        @Bean
        @ConditionalOnMissingBean
        public AgenticToolCorrelationWorker agenticToolCorrelationWorker(CamundaBpmnLoader bpmnLoader,
                                                                         ToolArgumentResolver argumentResolver,
                                                                         CamundaMessageCorrelator messageCorrelator,
                                                                         WorkerErrorHandler errorHandler,
                                                                         TechnicalFailureHandler technicalFailureHandler) {
            return new AgenticToolCorrelationWorker(bpmnLoader, argumentResolver, messageCorrelator,
                    errorHandler, technicalFailureHandler);
        }

        // --- External task client subscriptions ------------------------------------------------

        @Bean(destroyMethod = "stop")
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "agentic.c7.client", name = "enabled", havingValue = "true", matchIfMissing = true)
        public ExternalTaskClient agenticExternalTaskClient(ExternalTaskClientProperties props,
                                                            LlmAgenticWorker llmAgenticWorker,
                                                            AgenticToolCorrelationWorker toolCorrelationWorker) {
            var clientBuilder = ExternalTaskClient.create()
                    .baseUrl(props.baseUrl())
                    .maxTasks(props.maxTasks())
                    .asyncResponseTimeout(props.asyncResponseTimeoutMs());
            if (props.workerId() != null && !props.workerId().isBlank()) {
                clientBuilder.workerId(props.workerId());
            }
            if (props.username() != null && !props.username().isBlank()) {
                clientBuilder.addInterceptor(new BasicAuthProvider(props.username(),
                        props.password() == null ? "" : props.password()));
            }
            ExternalTaskClient client = clientBuilder.build();

            long agenticLock = Math.max(props.lockDurationMs(), props.agenticMinLockMs());
            client.subscribe(props.agenticTopic())
                    .lockDuration(agenticLock)
                    .handler(llmAgenticWorker)
                    .open();
            client.subscribe(props.toolCorrelationTopic())
                    .lockDuration(props.lockDurationMs())
                    .handler(toolCorrelationWorker)
                    .open();

            log.info("camunda7-agentic subscriptions started: baseUrl={} topics=[{} (lock={}ms), {} (lock={}ms)]",
                    props.baseUrl(), props.agenticTopic(), agenticLock,
                    props.toolCorrelationTopic(), props.lockDurationMs());
            return client;
        }
    }
}
