/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.util;

/**
 * Extracts the actual JSON payload from a raw LLM response.
 *
 * <p>LLMs frequently return "structured" answers wrapped in prose and/or markdown code fences, e.g.:
 * <pre>
 *   Here is the result:
 *   ```json
 *   { "a": 1 }
 *   ```
 *   Done.
 * </pre>
 *
 * <p>This utility strips {@code ```json} code fences (also plain {@code ```} without a language
 * marker and {@code ```JSON} in upper case), including prose before and after. As a last safety
 * net the outermost {@code { ... }} object is cut out. If the fence is left open (missing closing
 * ``` at the end), at least the leading prose is removed.
 *
 * <p>No JSON parsing is performed &ndash; the caller validates the result as needed (e.g. via
 * Jackson). If neither fence nor brace is found, the trimmed input is returned.
 */
public final class JsonResponseSanitizer {

    private JsonResponseSanitizer() {
    }

    /**
     * Removes code fences and wrapping prose around the JSON.
     *
     * @param raw raw LLM response (may be {@code null})
     * @return JSON substring (best effort) or {@code ""} for {@code null} input.
     */
    public static String extractJson(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        int fenceStart = s.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = fenceStart + 3;
            int nl = s.indexOf('\n', contentStart);
            if (nl > contentStart) {
                String marker = s.substring(contentStart, nl).trim();
                if (marker.isEmpty() || marker.matches("(?i)[a-z0-9_+-]+")) {
                    contentStart = nl + 1;
                }
            }
            int fenceEnd = s.indexOf("```", contentStart);
            if (fenceEnd > contentStart) {
                s = s.substring(contentStart, fenceEnd).trim();
            } else {
                s = s.substring(contentStart).trim();
            }
        }
        int firstBrace = s.indexOf('{');
        if (firstBrace < 0) {
            return s;
        }
        // Return the FIRST balanced top-level object. LLMs sometimes append extra content after the
        // JSON (prose, or even a whole hallucinated transcript with several JSON objects). Using the
        // last '}' would span all of it and produce invalid JSON, so we brace-count instead while
        // ignoring braces inside strings.
        boolean inString = false;
        boolean escape = false;
        int depth = 0;
        for (int i = firstBrace; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(firstBrace, i + 1);
                }
            }
        }
        // Unbalanced (e.g. truncated response): fall back to the outermost braces.
        int lastBrace = s.lastIndexOf('}');
        if (lastBrace > firstBrace) {
            return s.substring(firstBrace, lastBrace + 1);
        }
        return s.substring(firstBrace);
    }
}
