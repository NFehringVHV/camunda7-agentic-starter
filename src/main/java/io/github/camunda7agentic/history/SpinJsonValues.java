/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.history;

/**
 * Reflection-only bridge to camunda-spin's {@code SpinValues.jsonValue(...).create()} so the starter
 * can produce a Cockpit-readable {@code Json}-typed process variable <strong>without</strong> a
 * compile-time dependency on camunda-spin. camunda-spin is only required at runtime when the
 * {@code json} byte-array history type is selected.
 */
final class SpinJsonValues {

    private SpinJsonValues() {
    }

    /**
     * Builds a SPIN {@code JsonValue} (a Camunda {@code TypedValue}) for the given JSON string.
     *
     * @throws IllegalStateException if camunda-spin is not on the classpath.
     */
    static Object jsonValue(String json) {
        try {
            Class<?> spinValues = Class.forName("org.camunda.spin.plugin.variable.SpinValues");
            Object builder = spinValues.getMethod("jsonValue", String.class).invoke(null, json);
            return builder.getClass().getMethod("create").invoke(builder);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "agentic.c7.history.byte-array-type=json requires camunda-spin-dataformat-json(-jackson) "
                            + "on the worker classpath. Add that dependency or use byte-array-type=bytes.", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to build a SPIN json value", e);
        }
    }
}
