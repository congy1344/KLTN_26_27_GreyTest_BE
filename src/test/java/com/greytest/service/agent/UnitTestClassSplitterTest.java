package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;

/** AI viết một class chung cho cả batch; backend tách local thành từng test độc lập theo case. */
class UnitTestClassSplitterTest {
    private final ObjectMapper json = new ObjectMapper();

    private static final String SHARED = """
            package com.example;

            import static org.junit.jupiter.api.Assertions.assertEquals;
            import org.junit.jupiter.api.BeforeEach;
            import org.junit.jupiter.api.Test;

            class FooServiceTest {
                private FooService service;

                @BeforeEach
                void setUp() { service = new FooService(); }

                // GreyTest trace: add | IF-1-TRUE | BR-1 -> TP-1 -> TC-1
                @Test
                void addReturnsSum() { assertEquals(3, service.add(1, 2)); }

                // GreyTest trace: add | IF-1-FALSE | BR-2 -> TP-2 -> TC-2
                @Test
                void addRejectsNegative() { assertEquals(-1, service.add(-1, 0)); }

                private int helper() { return 1; }
            }
            """;

    private String grouped(String source, String... cases) throws Exception {
        var root = json.createObjectNode();
        var item = root.putArray("test_classes").addObject().put("test_class_name", "FooServiceTest")
                .put("package_name", "com.example").put("generation_type", "NEW_TEST").put("source_code", source);
        var list = item.putArray("cases");
        for (int i = 0; i < cases.length; i += 2) list.addObject().put("case_id", Long.parseLong(cases[i])).put("test_method_name", cases[i + 1]);
        return json.writeValueAsString(root);
    }

    @Test void splitsOneSharedClassIntoOneStandaloneTestPerCase() throws Exception {
        JsonNode tests = json.readTree(UnitTestClassSplitter.expand(grouped(SHARED, "1", "addReturnsSum", "2", "addRejectsNegative"), json))
                .path("unit_tests");
        assertThat(tests).hasSize(2);
        String first = tests.get(0).path("source_code").asText();
        assertThat(tests.get(0).path("case_id").asLong()).isEqualTo(1);
        assertThat(tests.get(0).path("test_method_name").asText()).isEqualTo("addReturnsSum");
        assertThat(tests.get(0).path("test_class_name").asText()).isEqualTo("FooServiceTest");
        assertThat(first).contains("import org.junit.jupiter.api.BeforeEach;", "void setUp()", "TC-1", "void addReturnsSum()", "helper()")
                .doesNotContain("addRejectsNegative", "TC-2");
        assertThat(tests.get(1).path("source_code").asText()).contains("void addRejectsNegative()", "TC-2").doesNotContain("addReturnsSum");
    }

    @Test void keepsLegacyUnitTestsPayloadUnchanged() throws Exception {
        String legacy = "{\"unit_tests\":[{\"case_id\":1,\"source_code\":\"class A {}\"}]}";
        assertThat(UnitTestClassSplitter.expand(legacy, json)).isEqualTo(legacy);
    }

    @Test void keepsWholeClassWhenTheMappedMethodIsMissingSoValidationCanReportIt() throws Exception {
        JsonNode tests = json.readTree(UnitTestClassSplitter.expand(grouped(SHARED, "3", "doesNotExist"), json)).path("unit_tests");
        assertThat(tests.get(0).path("source_code").asText()).isEqualTo(SHARED);
    }

    @Test void parserAddsMissingStaticImportsBeforeValidation() throws Exception {
        var parser = new GenerationResponseParser(json, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        String noImport = SHARED.replace("import static org.junit.jupiter.api.Assertions.assertEquals;\n", "");
        var response = parser.parse(grouped(noImport, "1", "addReturnsSum"), UnitTestResponseDto.class);
        assertThat(response.unitTests().get(0).sourceCode()).contains("import static org.junit.jupiter.api.Assertions.assertEquals;");
    }

    @Test void parserAcceptsStrictSchemaOutputWithNullAlternatives() throws Exception {
        // Structured Outputs strict bắt buộc có đủ key: nhánh không dùng trả null.
        var parser = new GenerationResponseParser(json, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        var ok = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(grouped(SHARED, "1", "addReturnsSum"));
        ok.putNull("generation_error");
        assertThat(parser.parse(json.writeValueAsString(ok), UnitTestResponseDto.class).unitTests()).hasSize(1);
        String blocked = "{\"test_classes\":null,\"generation_error\":{\"case_id\":7,\"reason\":\"MISSING_SOURCE\",\"details\":\"TC-7 needs Foo\"}}";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parse(blocked, UnitTestResponseDto.class))
                .isInstanceOf(GenerationResponseParser.UnitTestGenerationError.class);
    }

    @Test void onlyStagesWithFixedShapesHaveStrictSchemas() {
        // Unit Test có hai nhánh loại trừ nhau (test_classes | generation_error) mà strict không ép được ở root:
        // luna từng trả cả hai và làm hỏng cả batch, nên UT giữ json_object.
        assertThat(LlmResponseSchemas.forPrompt("unit-test")).isNull();
        assertThat(LlmResponseSchemas.forPrompt("business-rule").path("properties").has("rules")).isTrue();
        assertThat(LlmResponseSchemas.forPrompt("test-plan")).isNotNull();
        // test_data của Test Case là map tự do nên không biểu diễn được ở chế độ strict.
        assertThat(LlmResponseSchemas.forPrompt("test-case")).isNull();
    }

    @Test void parserAcceptsTheGroupedFormat() throws Exception {
        var parser = new GenerationResponseParser(json, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        var response = parser.parse(grouped(SHARED, "1", "addReturnsSum", "2", "addRejectsNegative"), UnitTestResponseDto.class);
        assertThat(response.unitTests()).extracting("caseId").containsExactly(1L, 2L);
        assertThat(response.unitTests().get(1).sourceCode()).contains("addRejectsNegative").doesNotContain("addReturnsSum()");
    }
}
