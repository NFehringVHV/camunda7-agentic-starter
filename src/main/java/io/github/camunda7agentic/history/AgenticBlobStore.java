/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.history;

/**
 * Service provider interface for an external, keyed blob store used to offload large agentic
 * payloads (conversation history, big tool results) out of the process engine database.
 *
 * <p>The starter ships <strong>no</strong> implementation of this interface. To use the
 * {@code external} history mode (or blob-backed prompts/tool results), provide your own
 * {@code @Bean} implementing this SPI &ndash; e.g. backed by S3, a database, or a REST blob
 * service. A user-provided bean automatically activates the {@code external} history store.
 *
 * <p>Reference implementation sketch (S3) is documented in the project README.
 */
public interface AgenticBlobStore {

    /**
     * Reads the content of a blob.
     *
     * @param blobId the blob id (must not be blank).
     * @return the blob content as a byte array, or {@code null} if empty.
     */
    byte[] read(String blobId);

    /**
     * Creates a blob or updates it if it already exists. Implementations should keep the id stable
     * across calls so history can be updated in place per process instance.
     *
     * @param blobId            deterministic blob id (must not be blank).
     * @param processInstanceId owning process instance id (must not be blank).
     * @param data              content to store.
     * @param metadata          optional metadata/label; may be {@code null}.
     * @return the id of the written blob.
     */
    String writeOrUpdate(String blobId, String processInstanceId, byte[] data, String metadata);
}
