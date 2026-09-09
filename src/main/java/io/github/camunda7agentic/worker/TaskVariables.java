/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.worker;

/**
 * Shared converters for Camunda process variables (which arrive as {@code Object} from the external
 * task) plus small worker helpers. Bundles utility methods that would otherwise be duplicated in
 * every worker.
 */
final class TaskVariables {

    private TaskVariables() {
    }

    static int readInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to fallback
            }
        }
        return fallback;
    }

    static long readLong(Object value, long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to fallback
            }
        }
        return fallback;
    }

    static boolean readBool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b.booleanValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s.trim());
        }
        return fallback;
    }

    static Double readDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to null
            }
        }
        return null;
    }

    static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static String stackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
