/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright the camunda7-agentic contributors.
 */
package io.github.camunda7agentic.agentic;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnToolExtractorTest {

    private final BpmnToolExtractor extractor = new BpmnToolExtractor();

    @Test
    void extractsToolsFromDemoBpmn() throws Exception {
        String xml = new ClassPathResource("bpmn/agentic-demo.bpmn")
                .getContentAsString(StandardCharsets.UTF_8);

        List<ToolDefinition> tools = extractor.extract(xml);

        assertThat(tools).extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder("getWeather", "sendEmail");

        ToolDefinition weather = tools.stream().filter(t -> t.name().equals("getWeather"))
                .findFirst().orElseThrow();
        assertThat(weather.description()).contains("weather");
        assertThat(weather.arguments()).extracting(ToolDefinition.Argument::name)
                .containsExactly("location");
        assertThat(weather.arguments().get(0).description()).contains("Location");
        assertThat(weather.arguments().get(0).type()).isEqualTo("string");

        ToolDefinition email = tools.stream().filter(t -> t.name().equals("sendEmail"))
                .findFirst().orElseThrow();
        assertThat(email.description()).contains("email");
        assertThat(email.arguments()).extracting(ToolDefinition.Argument::name)
                .containsExactly("to", "subject", "body");
    }

    @Test
    void ignoresSubProcessWithoutTriggeredByEvent() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                  targetNamespace="http://x">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:subProcess id="normalSub" triggeredByEvent="false">
                      <bpmn:startEvent id="s1"/>
                    </bpmn:subProcess>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        assertThat(extractor.extract(xml)).isEmpty();
    }
}
