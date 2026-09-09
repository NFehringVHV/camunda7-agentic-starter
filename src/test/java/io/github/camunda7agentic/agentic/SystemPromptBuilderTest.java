/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptBuilderTest {

    private SystemPromptBuilder builder;

    @BeforeEach
    void setUp() {
        ByteArrayResource res = new ByteArrayResource(
                "DEFAULT-PROMPT".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "default-system-prompt.txt";
            }
        };
        builder = new SystemPromptBuilder(new ObjectMapper(), res);
    }

    @Test
    void buildsPromptWithDefaultUseCaseAndTools() {
        String prompt = builder.build("You are a travel assistant.",
                List.of(new ToolDefinition("getWeather", "Get the weather",
                        List.of(new ToolDefinition.Argument("location", "Location")))));

        assertThat(prompt).contains("DEFAULT-PROMPT");
        assertThat(prompt).contains("BUSINESS CONTEXT");
        assertThat(prompt).contains("travel assistant");
        assertThat(prompt).contains("AVAILABLE TOOLS");
        assertThat(prompt).contains("getWeather");
        assertThat(prompt).contains("\"location\"");
    }

    @Test
    void buildsPromptWithoutUseCaseAndWithoutTools() {
        String prompt = builder.build(null, List.of());
        assertThat(prompt).contains("DEFAULT-PROMPT");
        assertThat(prompt).doesNotContain("BUSINESS CONTEXT");
        assertThat(prompt).contains("(none)");
    }
}
