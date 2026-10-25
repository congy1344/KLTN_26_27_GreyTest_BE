package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import com.github.javaparser.JavaParser;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;

/** Hồi quy theo khai báo kiểu dữ liệu, không phụ thuộc tên class của dự án đầu vào. */
class SourceValueContractTest {
    static Stream<Arguments> primitiveAssertions() {
        return Stream.of("byte", "short", "int", "long", "float", "double", "boolean", "char")
                .flatMap(type -> Stream.of(
                        Arguments.of(type, "org.junit.jupiter.api.Assertions.assertNull(value.getCount());"),
                        Arguments.of(type, "org.junit.Assert.assertNull(\"count\", value.getCount());"),
                        Arguments.of(type, "org.junit.jupiter.api.Assertions.assertEquals(null, value.getCount());"),
                        Arguments.of(type, "org.junit.Assert.assertEquals(\"count\", null, value.getCount());")));
    }

    @ParameterizedTest
    @MethodSource("primitiveAssertions")
    void rejectsNullAssertionsOnEveryPrimitiveDeclaredGetter(String type, String assertion) {
        assertThat(validate("public " + type + " getCount() { throw new UnsupportedOperationException(); }", assertion))
                .anySatisfy(problem -> assertThat(problem).contains("primitive", type, "null"));
    }

    static Stream<Arguments> nullableOrUnknownValues() {
        return Stream.of(
                Arguments.of("public Integer getCount() { return null; }", "assertNull(value.getCount());"),
                Arguments.of("public Boolean getCount() { return null; }", "assertNull(value.getCount());"),
                Arguments.of("public Object getCount() { return null; }", "assertNull(value.getCount());"),
                Arguments.of("public int getCount() { return 0; }", "assertEquals(0, value.getCount());"),
                Arguments.of("public int getCount() { return 0; }", "assertNull((Integer) value.getCount());"),
                Arguments.of("public int getCount() { return 0; }", "assertNull(other.getCount());"),
                Arguments.of("public int getCount() { return 0; }", "custom.assertNull(value.getCount());"),
                Arguments.of("int count(int a, int b) { return 0; } Integer count(String... args) { return null; }",
                        "assertNull(value.count(\"a\", \"b\"));"),
                Arguments.of("public int getCount() { return 0; }", "helper(value.getCount());"));
    }

    @ParameterizedTest
    @MethodSource("nullableOrUnknownValues")
    void doesNotGuessNullabilityOfReferenceValuesOrUnknownAssertions(String declaration, String assertion) {
        assertThat(validate(declaration, assertion)).isEmpty();
    }

    static Stream<Arguments> lombokValues() {
        return Stream.of(
                Arguments.of("@lombok.Data class Sample { private int count; }", "assertNull(value.getCount());", true),
                Arguments.of("class Sample { @lombok.Getter private long count; }", "assertNull(value.getCount());", true),
                Arguments.of("@lombok.Getter class Sample { private boolean active; }", "assertNull(value.isActive());", true),
                Arguments.of("@lombok.Data class Sample { private Integer count; }", "assertNull(value.getCount());", false),
                Arguments.of("@lombok.Data class Sample { private int count; public Integer getCount() { return null; } }",
                        "assertNull(value.getCount());", false),
                Arguments.of("class Sample { private int count; }", "assertNull(value.getCount());", false));
    }

    @ParameterizedTest
    @MethodSource("lombokValues")
    void respectsExplicitAndLombokGetterDeclarations(String declaration, String assertion, boolean rejected) {
        assertThat(problems("package sample; " + declaration, assertion).isEmpty()).isEqualTo(!rejected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"value.getDetail().getCount()", "value.getDetails().get(0).getCount()",
            "value.getDetailsById().get(\"id\").getCount()"})
    void resolvesPrimitiveGettersThroughSourceDeclaredDtoAndCollectionTypes(String expression) {
        var detail = new ClassContextDto(2L, "sample.model", "Detail", "sample.model.Detail", "OTHER", "Detail.java",
                "package sample.model; @lombok.Data class Detail { private int count; }", List.of(), List.of());
        assertThat(problems("package sample; import sample.model.Detail; import java.util.List; import java.util.Map; "
                + "@lombok.Data class Sample { private Detail detail; private List<Detail> details; "
                + "private Map<String, Detail> detailsById; }", "assertNull(" + expression + ");", List.of(detail)))
                .anySatisfy(problem -> assertThat(problem).contains("primitive", "int", expression));
    }

    @Test
    void doesNotResolveGenericTypeParametersAsAnUnrelatedProductionClass() {
        var box = new ClassContextDto(1L, "sample", "Box", "sample.Box", "OTHER", "Box.java",
                "package sample; class Box<Payload> { Payload getItem() { return null; } }", List.of(), List.of());
        var payload = new ClassContextDto(2L, "sample", "Payload", "sample.Payload", "OTHER", "Payload.java",
                "package sample; class Payload { int getCount() { return 0; } }", List.of(), List.of());
        var nullable = new ClassContextDto(3L, "sample", "NullablePayload", "sample.NullablePayload", "OTHER", "NullablePayload.java",
                "package sample; class NullablePayload { Integer getCount() { return null; } }", List.of(), List.of());
        var context = new UnitTestContextDto(null, null, List.of(box, payload, nullable), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        var test = new JavaParser().parse("package sample; class BoxTest { Box<NullablePayload> box; "
                + "void check() { org.junit.jupiter.api.Assertions.assertNull(box.getItem().getCount()); } }")
                .getResult().orElseThrow();

        assertThat(GeneratedTestApiValidator.validate(context, test)).isEmpty();
    }

    @Test
    void doesNotUseInnerPrimitiveFieldForAnExplicitOuterReference() {
        var context = new UnitTestContextDto(null, null, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        var test = new JavaParser().parse("package sample; class SampleTest { Integer count; "
                + "class Inner { int count; void check() { "
                + "org.junit.jupiter.api.Assertions.assertNull(SampleTest.this.count); } } }").getResult().orElseThrow();

        assertThat(GeneratedTestApiValidator.validate(context, test)).isEmpty();
    }

    @Test
    void doesNotConfuseNestedNullableTypeWithTopLevelPrimitiveType() {
        String source = "package sample; class Envelope { Detail getDetail() { return null; } "
                + "static class Detail { Integer getCount() { return null; } } }";
        var envelope = new ClassContextDto(1L, "sample", "Envelope", "sample.Envelope", "OTHER", "Envelope.java",
                source, List.of(), List.of());
        var nested = new ClassContextDto(2L, "sample", "Detail", "sample.Envelope.Detail", "OTHER", "Envelope.java",
                source, List.of(), List.of());
        var topLevel = new ClassContextDto(3L, "sample", "Detail", "sample.Detail", "OTHER", "Detail.java",
                "package sample; class Detail { int getCount() { return 0; } }", List.of(), List.of());
        var context = new UnitTestContextDto(null, null, List.of(envelope, nested, topLevel), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        var test = new JavaParser().parse("package sample; class EnvelopeTest { Envelope envelope; "
                + "void check() { org.junit.jupiter.api.Assertions.assertNull(envelope.getDetail().getCount()); } }")
                .getResult().orElseThrow();

        assertThat(GeneratedTestApiValidator.validate(context, test)).isEmpty();
    }

    static Stream<String> unresolvedScopes() {
        return Stream.of(
                "class Check<Payload extends NullablePayload> { Payload value; void check() { org.junit.jupiter.api.Assertions.assertNull(value.getCount()); } }",
                "class Check { int count; void check() { new Object() { Integer count; void run() { org.junit.jupiter.api.Assertions.assertNull(this.count); } }.run(); } }",
                "class Check extends BaseCheck { int count; void check() { assertNull(count); } }",
                "class Check { Envelope envelope; void check() { org.junit.jupiter.api.Assertions.assertNull(envelope.getDetail().getCount()); } }");
    }

    @ParameterizedTest
    @MethodSource("unresolvedScopes")
    void doesNotInventTypesOrJUnitOwnersForUnresolvedScopes(String testBody) {
        var definitions = List.of(
                new String[] {"Payload", "class Payload { int getCount() { return 0; } }"},
                new String[] {"NullablePayload", "class NullablePayload { Integer getCount() { return null; } }"},
                new String[] {"BaseCheck", "class BaseCheck { void assertNull(int value) {} }"},
                new String[] {"Base", "class Base { static class Detail { Integer getCount() { return null; } } }"},
                new String[] {"Envelope", "class Envelope extends Base { Detail getDetail() { return new Detail(); } }"},
                new String[] {"Detail", "class Detail { int getCount() { return 0; } }"});
        var types = definitions.stream().map(definition -> new ClassContextDto(1L, "sample", definition[0],
                "sample." + definition[0], "OTHER", definition[0] + ".java", "package sample; " + definition[1], List.of(), List.of())).toList();
        var context = new UnitTestContextDto(null, null, types, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var test = new JavaParser().parse("package sample; import static org.junit.jupiter.api.Assertions.*; " + testBody)
                .getResult().orElseThrow();

        assertThat(GeneratedTestApiValidator.validate(context, test)).isEmpty();
    }

    private List<String> validate(String declaration, String assertion) {
        return problems("package sample; class Sample { " + declaration + " }", assertion);
    }

    private List<String> problems(String production, String assertion) {
        return problems(production, assertion, List.of());
    }

    private List<String> problems(String production, String assertion, List<ClassContextDto> supporting) {
        var type = new ClassContextDto(1L, "sample", "Sample", "sample.Sample", "OTHER", "Sample.java",
                production, List.of(), List.of());
        var types = new java.util.ArrayList<>(supporting);
        types.add(type);
        var context = new UnitTestContextDto(null, null, types, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var test = new JavaParser().parse("package sample; import static org.junit.jupiter.api.Assertions.*; "
                + "class SampleTest { Sample value; Object other; Object custom; @org.junit.jupiter.api.Test void check() { "
                + assertion + " } }").getResult().orElseThrow();
        return GeneratedTestApiValidator.validate(context, test);
    }
}
