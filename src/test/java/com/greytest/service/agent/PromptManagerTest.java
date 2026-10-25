package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

class PromptManagerTest {
    @Test
    void sendsCompactContextWhilePreservingSourceAndExplicitNullTestInputs() throws Exception {
        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        var input = new LinkedHashMap<String, Object>();
        input.put("amount", null);
        input.put("enabled", false);
        input.put("text", "giữ nguyên \"source\"\nline two");
        String source = "package demo;\npublic class Service {\n    public void run() {}\n}\n";
        Map<String, Object> context = Map.of(
                "project", Map.of("id", 811, "name", "sample"),
                "classes", List.of(Map.of("filePath", "src/main/java/demo/Service.java", "sourceCode", source)),
                "approvedTestCases", List.of(Map.of("id", 40, "testData", Map.of("input", input))));
        var prompt = new PromptManager(mapper).render("unit-test", Map.of("context_json", context));
        int start = prompt.indexOf("Context:") + "Context:".length();
        String json = prompt.substring(start, prompt.lastIndexOf("# Source contract shared")).trim();
        var actual = mapper.readTree(json);

        assertThat(actual.at("/classes/0/sourceCode").asText()).isEqualTo(source);
        assertThat(actual.at("/approvedTestCases/0/testData/input/amount").isNull()).isTrue();
        assertThat(actual.at("/approvedTestCases/0/testData/input/enabled").asBoolean()).isFalse();
        assertThat(actual.at("/approvedTestCases/0/testData/input/text").asText()).isEqualTo(input.get("text"));
        assertThat(actual).isEqualTo(mapper.valueToTree(context));
        assertThat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThan(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(context).length);
    }
}
