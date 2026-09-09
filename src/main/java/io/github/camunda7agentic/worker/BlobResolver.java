/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.worker;

import io.github.camunda7agentic.history.AgenticBlobStore;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Resolves worker text inputs that may optionally be provided as an external blob rather than inline.
 *
 * <p>Convention: if a {@code <name>BlobId} variable is set, the blob wins over a same-named inline
 * variable and is loaded through the optional {@link AgenticBlobStore} SPI, decoded as UTF-8 (no
 * truncation). This allows inputs above the Camunda {@code varchar(4000)} String variable limit.
 * If no {@link AgenticBlobStore} bean is configured, blob ids cannot be resolved.
 */
@Component
public class BlobResolver {

    private static final String ERR_INPUT_MISSING = "LLM_INPUT_MISSING";
    private static final String ERR_INPUT_BLOB_UNREADABLE = "LLM_INPUT_BLOB_UNREADABLE";

    private final ObjectProvider<AgenticBlobStore> blobStoreProvider;
    private final WorkerErrorHandler errorHandler;

    public BlobResolver(ObjectProvider<AgenticBlobStore> blobStoreProvider, WorkerErrorHandler errorHandler) {
        this.blobStoreProvider = blobStoreProvider;
        this.errorHandler = errorHandler;
    }

    /**
     * Resolves the mandatory {@code userPrompt}/{@code userPromptBlobId} input. Fires a BPMN error
     * ({@code LLM_INPUT_MISSING} or {@code LLM_INPUT_BLOB_UNREADABLE}) and returns {@code null} if
     * the input cannot be resolved.
     *
     * @return the user prompt, or {@code null} if a BPMN error was already raised.
     */
    public String resolveUserPromptOrHandleError(ExternalTask task,
                                                 ExternalTaskService service,
                                                 Logger workerLog,
                                                 String workerLabel) {
        String inline = task.getVariable("userPrompt");
        String blobId = task.getVariable("userPromptBlobId");
        if (blobId == null || blobId.isBlank()) {
            if (inline == null || inline.isBlank()) {
                errorHandler.handleBusinessError(service, task, ERR_INPUT_MISSING,
                        "Variable 'userPrompt' is missing or empty (and no 'userPromptBlobId' is set).");
                return null;
            }
            return inline;
        }
        if (inline != null && !inline.isBlank()) {
            workerLog.debug("userPromptBlobId set - inline variable 'userPrompt' is ignored (task={}, blobId={}).",
                    task.getId(), blobId);
        }
        try {
            byte[] bytes = requireBlobStore().read(blobId);
            String prompt = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
            if (prompt.isBlank()) {
                errorHandler.handleBusinessError(service, task, ERR_INPUT_MISSING,
                        "userPromptBlobId '" + blobId + "' resolved to an empty prompt.");
                return null;
            }
            return prompt;
        } catch (RuntimeException ex) {
            workerLog.warn("{} userPrompt blob not readable: task={} blobId={}",
                    workerLabel, task.getId(), blobId, ex);
            errorHandler.handleBusinessError(service, task, ERR_INPUT_BLOB_UNREADABLE,
                    "userPromptBlobId '" + blobId + "' not readable: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Resolves an <em>optional</em> blob-backed text input (blob wins over inline). Returns
     * {@code null} if neither the inline variable nor the blob id is set. If a blob id is set but the
     * blob cannot be read, the underlying {@link RuntimeException} propagates &ndash; the calling
     * worker treats it as a technical incident (a missing prompt blob is a technical defect, not a
     * business branch).
     */
    public String resolveOptionalText(ExternalTask task, String inlineVar, String blobIdVar, Logger workerLog) {
        String inline = task.getVariable(inlineVar);
        String blobId = task.getVariable(blobIdVar);
        if (blobId == null || blobId.isBlank()) {
            return inline;
        }
        if (inline != null && !inline.isBlank()) {
            workerLog.debug("{} set - inline variable '{}' is ignored (task={}, blobId={}).",
                    blobIdVar, inlineVar, task.getId(), blobId);
        }
        byte[] bytes = requireBlobStore().read(blobId);
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Resolves {@code toolCallResult}/{@code toolCallResultBlobId}. If the tool set a blob id, the
     * blob is loaded, decoded as UTF-8 and used <b>untruncated</b>; if an inline {@code toolCallResult}
     * summary is additionally set, both are combined with a {@code ---} separator. Any length limiting
     * for the LLM context window happens later while building the input prompt.
     */
    public Object resolveToolCallResult(ExternalTask task, Logger workerLog) {
        Object inline = task.getVariable("toolCallResult");
        String blobId = task.getVariable("toolCallResultBlobId");
        if (blobId == null || blobId.isBlank()) {
            return inline;
        }
        byte[] bytes;
        try {
            bytes = requireBlobStore().read(blobId);
        } catch (RuntimeException ex) {
            workerLog.warn("toolCallResult blob not readable (id={}), using inline value only.", blobId, ex);
            return inline;
        }
        String content = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        if (inline == null) {
            return content;
        }
        return "Summary: " + inline + "\n---\n" + content;
    }

    private AgenticBlobStore requireBlobStore() {
        AgenticBlobStore store = blobStoreProvider.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException(
                    "A *BlobId variable was set but no AgenticBlobStore bean is configured. "
                            + "Provide an AgenticBlobStore @Bean to use blob-backed inputs.");
        }
        return store;
    }
}
