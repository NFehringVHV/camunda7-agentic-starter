/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.config;

import io.github.camunda7agentic.agentic.AgenticHistoryCodec;
import io.github.camunda7agentic.camunda.CamundaRestClient;
import io.github.camunda7agentic.history.AgenticBlobStore;
import io.github.camunda7agentic.history.AgenticHistoryStore;
import io.github.camunda7agentic.history.CamundaByteArrayHistoryStore;
import io.github.camunda7agentic.history.ExternalBlobHistoryStore;
import io.github.camunda7agentic.history.HistoryStoreSelector;
import io.github.camunda7agentic.history.InlineStringHistoryStore;
import io.github.camunda7agentic.worker.AgenticToolCorrelationWorker;
import io.github.camunda7agentic.worker.LlmAgenticWorker;
import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.interceptor.auth.BasicAuthProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Auto-configuration for the Camunda 7 agentic worker. Wires the agentic core beans, the Camunda
 * REST client, the history stores and (optionally) the external-task-client subscriptions for the
 * {@code llm-agentic} and {@code agentic-tool-correlation} topics.
 *
 * <p>The user application must provide a Spring AI {@code ChatModel} bean (bring your own provider)
 * and, for the {@code external} history mode or blob-backed inputs, an {@link AgenticBlobStore} bean.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agentic.c7", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({
        AgenticProperties.class,
        AgenticHistoryProperties.class,
        CamundaRestClientProperties.class,
        ExternalTaskClientProperties.class
})
@ComponentScan(basePackages = {
        "io.github.camunda7agentic.agentic",
        "io.github.camunda7agentic.camunda",
        "io.github.camunda7agentic.worker"
})
public class AgenticC7AutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgenticC7AutoConfiguration.class);

    // --- Camunda REST client -------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public CamundaRestClient camundaRestClient(CamundaRestClientProperties props) {
        var builder = org.springframework.web.client.RestClient.builder().baseUrl(props.baseUrl());
        if (props.bearerToken() != null && !props.bearerToken().isBlank()) {
            builder.defaultHeaders(h -> h.setBearerAuth(props.bearerToken()));
        } else if (props.username() != null && !props.username().isBlank()) {
            builder.defaultHeaders(h -> h.setBasicAuth(props.username(), props.password() == null ? "" : props.password()));
        }
        return new CamundaRestClient(builder.build());
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
    public ExternalBlobHistoryStore externalBlobHistoryStore(AgenticBlobStore blobStore, AgenticHistoryCodec codec) {
        return new ExternalBlobHistoryStore(blobStore, codec);
    }

    @Bean
    @ConditionalOnMissingBean
    public HistoryStoreSelector historyStoreSelector(List<AgenticHistoryStore> stores,
                                                     AgenticHistoryProperties props) {
        return new HistoryStoreSelector(stores, props.store());
    }

    // --- External task client subscriptions ----------------------------------------------------

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
