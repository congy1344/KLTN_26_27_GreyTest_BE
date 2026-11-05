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
        String json = prompt.substring(start).trim();
        com.fasterxml.jackson.databind.JsonNode actual = mapper.readTree(mapper.createParser(json));
        int sharedStart = prompt.indexOf("# Shared source evidence\n") + "# Shared source evidence\n".length();
        com.fasterxml.jackson.databind.JsonNode shared = mapper.readTree(mapper.createParser(prompt.substring(sharedStart)));

        assertThat(shared.at(actual.at("/classes/0/sourceCodeRef").asText().substring(1)).asText()).isEqualTo(source);
        assertThat(actual.at("/approvedTestCases/0/testData/input/amount").isNull()).isTrue();
        assertThat(actual.at("/approvedTestCases/0/testData/input/enabled").asBoolean()).isFalse();
        assertThat(actual.at("/approvedTestCases/0/testData/input/text").asText()).isEqualTo(input.get("text"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) actual.at("/classes/0")).remove("sourceCodeRef");
        ((com.fasterxml.jackson.databind.node.ObjectNode) actual.at("/classes/0")).put("sourceCode", source);
        assertThat(actual).isEqualTo(mapper.valueToTree(context));
        assertThat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThan(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(context).length);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"business-rule", "business-rule-review", "test-plan", "test-case", "unit-test"})
    void keepsSharedPrefixIdenticalWhenOnlySelectedBatchChanges(String stage) throws Exception {
        var mapper = new ObjectMapper();
        var manager = new PromptManager(mapper);
        String source = "package demo; class Service { int first(){return 1;} int second(){return 2;} }";
        var one = Map.of("classes", List.of(Map.of("sourceCode", source, "methods",
                List.of(Map.of("id", 1, "methodName", "first", "sourceCode", "int first() { return 1; }")))));
        var two = Map.of("classes", List.of(Map.of("sourceCode", source, "methods",
                List.of(Map.of("id", 2, "methodName", "second", "sourceCode", "int second() { return 2; }")))));
        var original = mapper.valueToTree(one);
        var first = manager.renderPrompt(stage, Map.of("context_json", one));
        var second = manager.renderPrompt(stage, Map.of("context_json", two));

        assertThat(first.cacheablePrefixes()).hasSize(2).isEqualTo(second.cacheablePrefixes());
        assertThat(first.text()).startsWith(String.join("", first.cacheablePrefixes()));
        assertThat(first.text()).isNotEqualTo(second.text());
        assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(one)).isEqualTo(original);
        assertThat(first.text()).contains("sourceCodeRef", "# Source contract shared");
        assertThat(manager.render(stage, Map.of("context_json", one))).isEqualTo(first.text());
    }

    @Test
    void removesDuplicateClassMethodAndCalleeSourcesButRetainsUnmatchedEvidence() throws Exception {
        var mapper = new ObjectMapper();
        String source = "class Service { String run() { return \"a b\"; } }";
        var context = mapper.createObjectNode();
        var owner = context.putArray("classes").addObject().put("sourceCode", source);
        var methods = owner.putArray("methods");
        methods.addObject().put("sourceCode", "String run(){return \"a b\";}");
        methods.addObject().put("sourceCode", "String run(){return \"ab\";}");
        methods.addObject().put("sourceCode", "String truncated(){ return");
        methods.addObject().putNull("sourceCode");
        methods.addObject().put("sourceCode", "/** Additional business evidence. */ String run(){return \"a b\";}");
        methods.addObject().put("sourceCode", "String run(){ /* Additional business evidence. */ return \"a b\";}");
        context.putArray("dependencyCalls").addObject().put("calleeServiceSourceCode", source);
        context.withArray("classes").addObject().put("sourceCode", source);
        var shared = PromptSourceContext.extract(context);

        assertThat(shared.path("sources")).hasSize(1);
        assertThat(shared.at("/sources/0").asText()).isEqualTo(source);
        assertThat(methods.get(0).has("sourceCode")).isFalse();
        assertThat(methods.get(0).path("sourceCodeRef").asText()).isEqualTo("#/sources/0");
        assertThat(methods.get(1).path("sourceCode").asText()).contains("\"ab\"");
        assertThat(methods.get(2).path("sourceCode").asText()).endsWith("return");
        assertThat(methods.get(3).path("sourceCode").isNull()).isTrue();
        assertThat(methods.get(4).path("sourceCode").asText()).contains("Additional business evidence");
        assertThat(methods.get(5).path("sourceCode").asText()).contains("Additional business evidence");
        assertThat(context.at("/dependencyCalls/0/calleeServiceSourceCodeRef").asText()).isEqualTo("#/sources/0");
    }

    @Test
    void retainsMethodWhenClassCannotBeParsedAndHonorsJsonIgnore() {
        var mapper = new ObjectMapper();
        var context = mapper.createObjectNode();
        context.putArray("classes").addObject().put("sourceCode", "class Broken {")
                .putArray("methods").addObject().put("sourceCode", "void run() {}");
        var original = context.deepCopy();
        var manager = new PromptManager(mapper);
        var rendered = manager.renderPrompt("unit-test", Map.of("context_json", context));
        assertThat(rendered.text()).contains("void run() {}");
        assertThat(context).isEqualTo(original);
        var owner = new com.greytest.dto.agent.GenerationContextDtos.ClassContextDto(
                1L, "demo", "Service", "demo.Service", "SERVICE", "Service.java", "class Service {}",
                List.of(), List.of(), "VALIDATION_ONLY_SECRET");
        assertThat(manager.render("unit-test", Map.of("context_json", Map.of("classes", List.of(owner)))))
                .contains("class Service {}").doesNotContain("VALIDATION_ONLY_SECRET");
    }

    @Test
    void reducesCompleteRequestForRepeatedSourceWithoutDroppingCode() throws Exception {
        var mapper = new ObjectMapper();
        String method = "public String run() {\n" + java.util.stream.IntStream.range(0, 150)
                .mapToObj(index -> "int value" + index + " = " + index + ";\n")
                .collect(java.util.stream.Collectors.joining()) + "return \"giữ nguyên\";\n}";
        String source = "package demo; public class Service {\n" + method + "\n}";
        var context = Map.of("classes", List.of(Map.of("sourceCode", source, "methods", List.of(
                Map.of("methodName", "run", "sourceCode", method)))), "dependencyCalls", List.of(
                Map.of("calleeServiceSourceCode", source), Map.of("calleeServiceSourceCode", source)));
        var manager = new PromptManager(mapper);
        com.fasterxml.jackson.databind.node.ObjectNode optimized = mapper.valueToTree(context);
        PromptSourceContext.extract(optimized);
        assertThat(optimized.at("/classes/0/methods/0/sourceCode").isMissingNode()).isTrue();
        String before = manager.load("business-rule").replace("{{context_json}}", mapper.writeValueAsString(context))
                + "\n\n" + manager.load("source-contract");
        String after = manager.render("business-rule", Map.of("context_json", context));
        assertThat(after.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThan(before.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        - 2 * source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(after).contains(mapper.writeValueAsString(source));
    }
}
