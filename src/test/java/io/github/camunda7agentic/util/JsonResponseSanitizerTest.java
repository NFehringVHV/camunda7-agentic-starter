/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonResponseSanitizerTest {

    @Test
    void plainJsonRemainsUnchanged() {
        assertThat(JsonResponseSanitizer.extractJson("{\"a\":1}"))
                .isEqualTo("{\"a\":1}");
    }

    @Test
    void jsonInProseIsExtracted() {
        assertThat(JsonResponseSanitizer.extractJson("before {\"x\":2} after"))
                .isEqualTo("{\"x\":2}");
    }

    @Test
    void jsonFenceWithMarker() {
        assertThat(JsonResponseSanitizer.extractJson("```json\n{\"a\":1}\n```"))
                .isEqualTo("{\"a\":1}");
    }

    @Test
    void jsonFenceWithUppercaseMarker() {
        assertThat(JsonResponseSanitizer.extractJson("```JSON\n{\"c\":3}\n```"))
                .isEqualTo("{\"c\":3}");
    }

    @Test
    void jsonFenceWithoutMarker() {
        assertThat(JsonResponseSanitizer.extractJson("```\n{\"b\":2}\n```"))
                .isEqualTo("{\"b\":2}");
    }

    @Test
    void jsonFenceWithProseBeforeAndAfter() {
        assertThat(JsonResponseSanitizer.extractJson(
                "Here is the result:\n```json\n{\"a\":1}\n```\nDone."))
                .isEqualTo("{\"a\":1}");
    }

    @Test
    void unclosedFence() {
        assertThat(JsonResponseSanitizer.extractJson("```json\n{\"d\":4}"))
                .isEqualTo("{\"d\":4}");
    }

    @Test
    void nullInputYieldsEmptyString() {
        assertThat(JsonResponseSanitizer.extractJson(null)).isEmpty();
    }

    @Test
    void textWithoutJsonIsKept() {
        assertThat(JsonResponseSanitizer.extractJson("just prose, no json"))
                .isEqualTo("just prose, no json");
    }

    @Test
    void firstBalancedObjectIsReturnedWhenModelSimulatesWholeTranscript() {
        String hallucinated = """
                {
                  "agenticDone": false,
                  "reasoning": "check weather first",
                  "toolCall": { "name": "getWeather", "arguments": { "location": "Berlin" } },
                  "finalAnswer": null,
                  "abortReason": null,
                  "nextStepPlan": "decide next"
                }

                Tool: {"location": "Berlin", "temperature": 23, "condition": "Sunny"}

                {
                  "agenticDone": true,
                  "reasoning": "done",
                  "toolCall": null,
                  "finalAnswer": "sent",
                  "abortReason": null,
                  "nextStepPlan": null
                }
                """;
        String extracted = JsonResponseSanitizer.extractJson(hallucinated);
        assertThat(extracted).startsWith("{").endsWith("}");
        assertThat(extracted).contains("\"getWeather\"");
        assertThat(extracted).doesNotContain("Tool:");
        assertThat(extracted).doesNotContain("\"finalAnswer\": \"sent\"");
    }

    @Test
    void bracesInsideStringsDoNotBreakExtraction() {
        assertThat(JsonResponseSanitizer.extractJson("{\"body\":\"a } b { c\"}"))
                .isEqualTo("{\"body\":\"a } b { c\"}");
    }

    @Test
    void validJsonWithFencedContentIsNotMangled() {
        String json = "{\"agenticDone\":true,\"reasoning\":\"r\","
                + "\"finalAnswer\":\"Run this:\\n```bash\\nls -la\\n```\\nDone.\"}";
        assertThat(JsonResponseSanitizer.extractJson(json)).isEqualTo(json);
    }

    @Test
    void fencedWrapperAroundJsonWithFencedContentKeepsInnerObject() {
        String inner = "{\"finalAnswer\":\"```bash\\nls\\n```\"}";
        String wrapped = "```json\n" + inner + "\n```";
        assertThat(JsonResponseSanitizer.extractJson(wrapped)).isEqualTo(inner);
    }
}
