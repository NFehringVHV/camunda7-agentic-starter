/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

import tools.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the final system prompt for the LLM:
 * default prompt (static, from {@code prompts/default-system-prompt.txt})
 * + optional use-case enrichment (BPMN service-task input)
 * + dynamic tool list extracted from the BPMN model.
 */
public class SystemPromptBuilder {

    private final ObjectMapper objectMapper;
    private final String defaultPrompt;

    public SystemPromptBuilder(ObjectMapper objectMapper,
                               Resource defaultPromptResource) {
        this.objectMapper = objectMapper;
        try {
            this.defaultPrompt = defaultPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Default system prompt not loadable", e);
        }
    }

    public String build(String useCasePrompt, List<ToolDefinition> tools) {
        StringBuilder sb = new StringBuilder(defaultPrompt.length() + 1024);
        sb.append(defaultPrompt.strip());
        if (useCasePrompt != null && !useCasePrompt.isBlank()) {
            sb.append("\n\n--- BUSINESS CONTEXT ---\n").append(useCasePrompt.strip());
        }
        sb.append("\n\n--- AVAILABLE TOOLS ---\n");
        if (tools == null || tools.isEmpty()) {
            sb.append("(none)\n");
        } else {
            sb.append(tools.stream().map(this::renderTool).collect(Collectors.joining("\n")));
        }
        return sb.toString();
    }

    private String renderTool(ToolDefinition tool) {
        try {
            return "- " + objectMapper.writeValueAsString(tool);
        } catch (Exception e) {
            return "- " + tool.name();
        }
    }
}
