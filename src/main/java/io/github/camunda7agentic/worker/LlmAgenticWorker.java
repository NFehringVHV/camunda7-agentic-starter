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
import io.github.camunda7agentic.agentic.ToolDefinition;
import io.github.camunda7agentic.camunda.CamundaBpmnLoader;
import io.github.camunda7agentic.config.AgenticProperties;
import io.github.camunda7agentic.history.AgenticHistoryStore;
import io.github.camunda7agentic.history.HistoryStoreSelector;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agentic worker (topic {@code llm-agentic}): performs a single turn of the loop.
 *
 * <p>The BPMN gateway/throw/receive structure drives the iteration; this worker is stateless per
 * invocation and reads the persisted {@code agenticHistory} via the configured
 * {@link AgenticHistoryStore}.
 *
 * <h3>Large payloads</h3>
 * <p>The history storage tier is selected via {@code agentic.c7.history.store} (or per process
 * instance via {@code agenticHistoryStore}). Prompts and tool results may optionally be provided as
 * blob ids ({@code userPromptBlobId}, {@code systemPromptUseCaseBlobId}, {@code toolCallResultBlobId})
 * resolved through an optional {@code AgenticBlobStore} SPI bean.
 */
@Component
public class LlmAgenticWorker implements ExternalTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(LlmAgenticWorker.class);

    private static final String VAR_AGENTIC_ITERATION = "agenticIteration";
    private static final String VAR_AGENTIC_TOKENS_USED = "agenticTokensUsed";
    private static final String VAR_AGENTIC_INPUT_TOKENS_USED = "agenticInputTokensUsed";
    private static final String VAR_AGENTIC_OUTPUT_TOKENS_USED = "agenticOutputTokensUsed";
    private static final String VAR_AGENTIC_DONE = "agenticDone";
    private static final String VAR_AGENTIC_FINAL_ANSWER = "agenticFinalAnswer";
    private static final String VAR_AGENTIC_ABORT_REASON = "agenticAbortReason";
    private static final String VAR_AGENTIC_NEXT_MESSAGE = "agenticNextMessage";
    private static final String VAR_TOOL_CALL_RESULT = "toolCallResult";
    private static final String VAR_TOOL_CALL_RESULT_BLOB_ID = "toolCallResultBlobId";

    private static final String ABORT_REASON_MAX_ITERATIONS = "max-iterations-exceeded";
    private static final String ABORT_REASON_MAX_TOKENS = "max-tokens-exceeded";

    private final CamundaBpmnLoader bpmnLoader;
    private final BpmnToolExtractor toolExtractor;
    private final SystemPromptBuilder systemPromptBuilder;
    private final AgenticChatService chatService;
    private final BlobResolver blobResolver;
    private final HistoryStoreSelector historyStoreSelector;
    private final WorkerErrorHandler errorHandler;
    private final AgenticProperties props;

    public LlmAgenticWorker(CamundaBpmnLoader bpmnLoader,
                            BpmnToolExtractor toolExtractor,
                            SystemPromptBuilder systemPromptBuilder,
                            AgenticChatService chatService,
                            BlobResolver blobResolver,
                            HistoryStoreSelector historyStoreSelector,
                            WorkerErrorHandler errorHandler,
                            AgenticProperties props) {
        this.bpmnLoader = bpmnLoader;
        this.toolExtractor = toolExtractor;
        this.systemPromptBuilder = systemPromptBuilder;
        this.chatService = chatService;
        this.blobResolver = blobResolver;
        this.historyStoreSelector = historyStoreSelector;
        this.errorHandler = errorHandler;
        this.props = props;
    }

    @Override
    public void execute(ExternalTask task, ExternalTaskService service) {
        try {
            executeTurn(task, service);
        } catch (RuntimeException ex) {
            log.error("llm-agentic error: task={}", task.getId(), ex);
            service.handleFailure(task, ex.getMessage(), TaskVariables.stackTrace(ex), 0, 0L);
        }
    }

    private void executeTurn(ExternalTask task, ExternalTaskService service) {
        String userPrompt = blobResolver.resolveUserPromptOrHandleError(task, service, log, "llm-agentic");
        if (userPrompt == null) {
            return;
        }

        String useCasePrompt = blobResolver.resolveOptionalText(
                task, "systemPromptUseCase", "systemPromptUseCaseBlobId", log);
        TurnLimits limits = readTurnLimits(task);
        AgenticHistoryStore historyStore = historyStoreSelector.select(task);

        int iteration = TaskVariables.readInt(task.getVariable(VAR_AGENTIC_ITERATION), 0) + 1;
        long tokensUsed = TaskVariables.readLong(task.getVariable(VAR_AGENTIC_TOKENS_USED), 0L);
        long inputTokensUsed = TaskVariables.readLong(task.getVariable(VAR_AGENTIC_INPUT_TOKENS_USED), 0L);
        long outputTokensUsed = TaskVariables.readLong(task.getVariable(VAR_AGENTIC_OUTPUT_TOKENS_USED), 0L);

        // Pre-check: iteration or token budget already exhausted? -> end the loop cleanly without
        // another LLM call.
        if (handlePreCheckAbort(task, service, limits, iteration,
                new TokenTotals(tokensUsed, inputTokensUsed, outputTokensUsed))) {
            return;
        }

        String bpmnXml = bpmnLoader.loadBpmnXml(task);
        List<ToolDefinition> tools = toolExtractor.extract(bpmnXml);
        log.info("llm-agentic turn: task={} processInstance={} iteration={}/{} tokens={}/{} tools={} historyStore={}",
                task.getId(), task.getProcessInstanceId(),
                iteration, limits.maxIterations(), tokensUsed, limits.maxTokens(),
                tools.size(), historyStore.name());

        String systemPrompt = systemPromptBuilder.build(useCasePrompt, tools);

        List<AgenticHistoryEntry> history = historyStore.load(task);
        // If the previous turn called a tool: merge its result as an observation.
        mergePreviousToolResult(history, blobResolver.resolveToolCallResult(task, log));

        String modelOverride = task.getVariable("model");
        Double temperature = TaskVariables.readDouble(task.getVariable("temperature"));
        AgenticCallResult result = chatService.call(systemPrompt, userPrompt, history, modelOverride, temperature);
        AgenticOutput output = result.output();
        long tokensThisTurn = result.totalTokens();
        long inputTokensThisTurn = result.inputTokens();
        long outputTokensThisTurn = result.outputTokens();
        tokensUsed += tokensThisTurn;
        inputTokensUsed += inputTokensThisTurn;
        outputTokensUsed += outputTokensThisTurn;
        history.add(new AgenticHistoryEntry(output, null));

        Map<String, Object> vars = new HashMap<>();
        vars.put(VAR_AGENTIC_DONE, output.agenticDone());
        vars.put("agenticReasoning", output.reasoning());
        historyStore.persist(task, history, vars);
        vars.put(VAR_AGENTIC_ITERATION, iteration);
        vars.put(VAR_AGENTIC_TOKENS_USED, tokensUsed);
        vars.put(VAR_AGENTIC_INPUT_TOKENS_USED, inputTokensUsed);
        vars.put(VAR_AGENTIC_OUTPUT_TOKENS_USED, outputTokensUsed);
        vars.put("agenticTokensLastTurn", tokensThisTurn);
        vars.put("agenticInputTokensLastTurn", inputTokensThisTurn);
        vars.put("agenticOutputTokensLastTurn", outputTokensThisTurn);
        if (output.nextStepPlan() != null) {
            vars.put("agenticNextStepPlan", output.nextStepPlan());
        }
        // Clear the previous turn's tool result from scope so the next throw event does not carry
        // stale values.
        vars.put(VAR_TOOL_CALL_RESULT, null);
        vars.put(VAR_TOOL_CALL_RESULT_BLOB_ID, null);

        if (applyPostCheckAbort(task, vars, limits, output, iteration, tokensUsed)) {
            service.complete(task, vars);
            return;
        }

        if (!applyOutcomeVars(task, service, vars, limits.toolCallVar(), output)) {
            return;
        }

        service.complete(task, vars);
    }

    private TurnLimits readTurnLimits(ExternalTask task) {
        String toolCallVar = TaskVariables.nonBlank(task.getVariable("toolCallVariableName"),
                props.defaultToolCallVariableName());
        int maxIterations = TaskVariables.readInt(task.getVariable("maxIterations"),
                props.defaultMaxIterations());
        long maxTokens = TaskVariables.readLong(task.getVariable("maxTokens"),
                props.defaultMaxTokens());
        return new TurnLimits(toolCallVar, maxIterations, maxTokens);
    }

    private boolean handlePreCheckAbort(ExternalTask task, ExternalTaskService service,
                                        TurnLimits limits, int iteration, TokenTotals tokens) {
        long tokensUsed = tokens.total();
        if (limits.maxIterations() > 0 && iteration > limits.maxIterations()) {
            log.info("llm-agentic abort due to max-iterations: task={} iteration={} max={}",
                    task.getId(), iteration, limits.maxIterations());
            completeAborted(task, service, new AbortContext(limits, iteration - 1, tokens,
                    ABORT_REASON_MAX_ITERATIONS,
                    "Maximum number of iterations (" + limits.maxIterations() + ") reached."));
            return true;
        }
        if (limits.maxTokens() > 0 && tokensUsed >= limits.maxTokens()) {
            log.info("llm-agentic abort due to max-tokens (pre-check): task={} tokensUsed={} max={}",
                    task.getId(), tokensUsed, limits.maxTokens());
            completeAborted(task, service, new AbortContext(limits, iteration - 1, tokens,
                    ABORT_REASON_MAX_TOKENS,
                    "Token budget (" + limits.maxTokens() + ") already exhausted."));
            return true;
        }
        return false;
    }

    private static void mergePreviousToolResult(List<AgenticHistoryEntry> history, Object toolCallResult) {
        if (toolCallResult == null || history.isEmpty()) {
            return;
        }
        AgenticHistoryEntry last = history.get(history.size() - 1);
        if (last.toolCallResult() != null
                || last.assistant() == null
                || last.assistant().toolCall() == null) {
            return;
        }
        history.set(history.size() - 1, new AgenticHistoryEntry(last.assistant(), toolCallResult));
    }

    private boolean applyPostCheckAbort(ExternalTask task, Map<String, Object> vars,
                                        TurnLimits limits, AgenticOutput output,
                                        int iteration, long tokensUsed) {
        // Post-check: did this turn exceed the token budget? -> keep reasoning but end the loop
        // immediately. Iteration limit as post-check: this turn was the last allowed one.
        boolean tokensExceeded = limits.maxTokens() > 0 && tokensUsed >= limits.maxTokens();
        boolean iterationsExhausted = limits.maxIterations() > 0 && iteration >= limits.maxIterations()
                && !output.agenticDone();
        if (!tokensExceeded && !iterationsExhausted) {
            return false;
        }
        String reason = tokensExceeded ? ABORT_REASON_MAX_TOKENS : ABORT_REASON_MAX_ITERATIONS;
        String detail = tokensExceeded
                ? "Token budget (" + limits.maxTokens() + ") exceeded after this turn (used=" + tokensUsed + ")."
                : "Maximum number of iterations (" + limits.maxIterations() + ") reached.";
        log.info("llm-agentic abort (post-check): task={} reason={} {}",
                task.getId(), reason, detail);
        vars.put(VAR_AGENTIC_DONE, true);
        vars.put(VAR_AGENTIC_FINAL_ANSWER, output.finalAnswer());
        vars.put(VAR_AGENTIC_ABORT_REASON, reason);
        vars.put(VAR_AGENTIC_NEXT_MESSAGE, null);
        vars.put(limits.toolCallVar(), null);
        return true;
    }

    private boolean applyOutcomeVars(ExternalTask task, ExternalTaskService service,
                                     Map<String, Object> vars, String toolCallVar,
                                     AgenticOutput output) {
        if (output.agenticDone()) {
            vars.put(VAR_AGENTIC_FINAL_ANSWER, output.finalAnswer());
            vars.put(VAR_AGENTIC_ABORT_REASON, output.abortReason());
            vars.put(VAR_AGENTIC_NEXT_MESSAGE, null);
            vars.put(toolCallVar, null);
            return true;
        }
        if (output.toolCall() == null || output.toolCall().name() == null) {
            errorHandler.handleBusinessError(service, task, "LLM_TOOLCALL_MISSING",
                    "agenticDone=false but toolCall is missing.");
            return false;
        }
        vars.put(VAR_AGENTIC_NEXT_MESSAGE, output.toolCall().name());
        vars.put(toolCallVar, output.toolCall().arguments() == null
                ? Map.of() : output.toolCall().arguments());
        vars.put(VAR_AGENTIC_FINAL_ANSWER, null);
        vars.put(VAR_AGENTIC_ABORT_REASON, null);
        return true;
    }

    private void completeAborted(ExternalTask task,
                                 ExternalTaskService service,
                                 AbortContext ctx) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(VAR_AGENTIC_DONE, true);
        vars.put("agenticReasoning", ctx.reasoning());
        vars.put(VAR_AGENTIC_ABORT_REASON, ctx.abortReason());
        vars.put(VAR_AGENTIC_FINAL_ANSWER, null);
        vars.put(VAR_AGENTIC_NEXT_MESSAGE, null);
        vars.put(VAR_AGENTIC_ITERATION, ctx.iteration());
        vars.put(VAR_AGENTIC_TOKENS_USED, ctx.tokens().total());
        vars.put(VAR_AGENTIC_INPUT_TOKENS_USED, ctx.tokens().input());
        vars.put(VAR_AGENTIC_OUTPUT_TOKENS_USED, ctx.tokens().output());
        vars.put(ctx.limits().toolCallVar(), null);
        vars.put(VAR_TOOL_CALL_RESULT, null);
        vars.put(VAR_TOOL_CALL_RESULT_BLOB_ID, null);
        service.complete(task, vars);
    }

    /**
     * Per-turn configuration: limits + mapping convention read once from the external task, shared by
     * several helper methods. Bundled to keep method signatures small.
     */
    record TurnLimits(String toolCallVar, int maxIterations, long maxTokens) {
    }

    /** Cumulative token counters (total + input/output) across all turns so far. */
    private record TokenTotals(long total, long input, long output) {
    }

    /** Parameter bag for {@link #completeAborted(ExternalTask, ExternalTaskService, AbortContext)}. */
    private record AbortContext(TurnLimits limits, int iteration, TokenTotals tokens,
                                String abortReason, String reasoning) {
    }
}
