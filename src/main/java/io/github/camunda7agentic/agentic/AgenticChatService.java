/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

import io.github.camunda7agentic.config.AgenticProperties;
import io.github.camunda7agentic.util.JsonResponseSanitizer;
import io.github.camunda7agentic.util.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the chat message list, calls the (provider-neutral) Spring AI {@link ChatModel} and parses
 * the structured LLM response.
 *
 * <p>This service is deliberately vendor-neutral: it only depends on the portable Spring AI
 * {@link ChatModel} abstraction. Bring your own model implementation (OpenAI, Bedrock, Ollama, ...)
 * by putting the corresponding Spring AI starter on the classpath &ndash; no code change required.
 * Per-call {@code model}/{@code temperature} overrides are applied through the portable
 * {@link ChatOptions}.
 */
@Component
public class AgenticChatService {

    private static final Logger log = LoggerFactory.getLogger(AgenticChatService.class);

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final AgenticProperties props;

    public AgenticChatService(ChatModel chatModel, ObjectMapper objectMapper, AgenticProperties props) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public AgenticCallResult call(String systemPrompt,
                                  String userPrompt,
                                  List<AgenticHistoryEntry> history,
                                  String modelOverride,
                                  Double temperature) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage("=== TASK INPUT (authoritative task / business data) ===\n" + userPrompt));

        List<AgenticHistoryEntry> trimmed = trim(history);
        int fullResultTurns = props.promptFullToolResultTurns();
        int firstFullIdx = fullResultTurns <= 0 ? 0 : Math.max(0, trimmed.size() - fullResultTurns);
        for (int i = 0; i < trimmed.size(); i++) {
            AgenticHistoryEntry entry = trimmed.get(i);
            messages.add(new AssistantMessage(toJson(entry.assistant())));
            // Only embed tool results for the most recent turns. For older turns the takeaway is
            // already captured in the reasoning/nextStepPlan of the following turn.
            if (entry.toolCallResult() != null && i >= firstFullIdx) {
                String toolCallName = entry.assistant() != null && entry.assistant().toolCall() != null
                        ? entry.assistant().toolCall().name()
                        : "tool";
                messages.add(new UserMessage(
                        "=== TOOL RESULT from '" + toolCallName + "' (observation to evaluate) ===\n"
                                + truncate(toJson(entry.toolCallResult()))));
            }
        }

        ChatOptions options = buildOptions(modelOverride, temperature);
        Prompt prompt = options == null ? new Prompt(messages) : new Prompt(messages, options);
        ChatResponse response = chatModel.call(prompt);
        var llmResult = response == null ? null : response.getResult();
        var llmOutput = llmResult == null ? null : llmResult.getOutput();
        if (llmOutput == null) {
            throw new IllegalStateException("LLM returned an empty response.");
        }
        String raw = llmOutput.getText();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("LLM returned an empty response.");
        }
        TokenUsage tokens = TokenUsage.from(response.getMetadata());
        return new AgenticCallResult(parse(raw), tokens.total(), tokens.input(), tokens.output());
    }

    private ChatOptions buildOptions(String modelOverride, Double temperature) {
        boolean hasModel = modelOverride != null && !modelOverride.isBlank();
        if (!hasModel && temperature == null) {
            return null;
        }
        ChatOptions.Builder builder = ChatOptions.builder();
        if (hasModel) {
            builder.model(modelOverride);
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    AgenticOutput parse(String raw) {
        String json = extractJson(raw);
        try {
            return objectMapper.readValue(json, AgenticOutput.class);
        } catch (JacksonException e) {
            log.warn("LLM response is not valid JSON. Raw=\n{}", raw);
            throw new IllegalStateException("LLM returned invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** Tolerates ```json code fences (also with surrounding prose) and wrapping text around the JSON. */
    static String extractJson(String raw) {
        return JsonResponseSanitizer.extractJson(raw);
    }

    private List<AgenticHistoryEntry> trim(List<AgenticHistoryEntry> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int max = props.historyMaxTurns();
        if (max <= 0 || history.size() <= max) {
            return history;
        }
        return history.subList(history.size() - max, history.size());
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        int max = props.toolCallResultMaxChars();
        if (max <= 0 || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "\n…[truncated " + (value.length() - max) + " chars]";
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }
}
