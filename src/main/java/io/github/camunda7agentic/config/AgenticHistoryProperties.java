/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.config;

import io.github.camunda7agentic.history.CamundaByteArrayHistoryStore.ByteArrayType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * History persistence settings (prefix {@code agentic.c7.history}).
 *
 * @param store         active history store: {@code inline}, {@code camunda-bytearray} or
 *                      {@code external}. {@code external} requires an {@code AgenticBlobStore} bean.
 * @param byteArrayType wire type used by the {@code camunda-bytearray} store: {@code bytes}
 *                      (dependency-free) or {@code json} (Cockpit-readable, requires camunda-spin).
 */
@ConfigurationProperties("agentic.c7.history")
public record AgenticHistoryProperties(
        @DefaultValue("camunda-bytearray") String store,
        @DefaultValue("bytes") ByteArrayType byteArrayType) {
}
