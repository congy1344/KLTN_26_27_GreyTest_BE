package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ProjectContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;

/** Hồi quy assertion tiền theo hợp đồng đã duyệt của từng project, không ép scale chung. */
class GeneratedBigDecimalAssertionRegressionTest {
    @Test void aDifferentFieldsFormatDoesNotAuthorizeDecimalRepresentationAssertions() {
        assertThat(validate("BigDecimal.ZERO", "assertEquals(new BigDecimal(\"0.0\"), result.getPaidAmount());",
                "The invoiceNumber has format INV-yyyyMMddHHmmss; paidAmount has the expected numeric value"))
                .hasValueSatisfying(message -> assertThat(message).contains("BigDecimal", "compareTo"));
    }

    @Test void rejectsStringBasedScaleAssertionsForNumericOnlyExpectations() {
        assertThat(validate("BigDecimal.ZERO", "assertEquals(\"0.0\", result.getPaidAmount().toPlainString());",
                "The returned paidAmount has the expected numeric value"))
                .hasValueSatisfying(message -> assertThat(message).contains("BigDecimal", "compareTo"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"paidAmount's textual representation must be exactly 0.00",
            "The text representation of paidAmount is 0.00", "The paid amount must be formatted as 0.00"})
    void preservesAnExplicitTextRepresentationOracle(String requirement) {
        assertThat(validate("BigDecimal.ZERO", "assertEquals(\"0.00\", result.getPaidAmount().toPlainString());", requirement)).isEmpty();
    }

    @Test void preservesAnExplicitRepresentationReadThroughALocalAlias() {
        assertThat(validate("BigDecimal.ZERO", "BigDecimal actual = result.getPaidAmount(); assertEquals(\"0.00\", actual.toPlainString());",
                "paidAmount's textual representation must be exactly 0.00")).isEmpty();
    }

    @Test void preservesAnExplicitVietnameseFractionalDigitRequirement() {
        assertThat(validate("BigDecimal.ZERO", "assertEquals(\"0.00\", result.getPaidAmount().toPlainString());",
                "Số tiền phải có đúng 2 chữ số sau dấu phẩy")).isEmpty();
    }
    static Stream<Arguments> decimalAssertions() {
        return Stream.of(
                Arguments.of("BigDecimal.ZERO", "new BigDecimal(\"0.0\")"),
                Arguments.of("new BigDecimal(\"100.0\")", "new BigDecimal(\"100.00\")"),
                Arguments.of("new BigDecimal(\"0.00\")", "BigDecimal.ZERO"),
                Arguments.of("new BigDecimal(\"100.000\")", "BigDecimal.valueOf(100)"));
    }

    @ParameterizedTest
    @MethodSource("decimalAssertions")
    void rejectsAnInventedScaleConstraintForNumericOnlyExpectations(String initializer, String expected) {
        assertThat(validate(initializer, "assertEquals(" + expected + ", result.getPaidAmount());",
                "The returned paidAmount has the expected numeric value"))
                .hasValueSatisfying(message -> assertThat(message).contains("BigDecimal", "scale", "compareTo"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BigDecimal.ZERO", "new BigDecimal(\"100.0\")", "new BigDecimal(\"100.00\")"})
    void acceptsNumericComparisonsAcrossDifferentSourceRepresentations(String initializer) {
        assertThat(validate(initializer,
                "assertNotNull(result.getPaidAmount()); assertEquals(0, " + initializer
                        + ".compareTo(result.getPaidAmount()));",
                "The returned paidAmount has the expected numeric value")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "The returned paidAmount must have scale 2",
            "The amount must have exactly two decimal places",
            "Số tiền phải có đúng 2 chữ số sau dấu thập phân",
            "Số tiền được làm tròn đến hai chữ số thập phân",
            "The default is exactly BigDecimal.ZERO"})
    void preservesApprovedRepresentationRequirementsEvenWhenSourceWouldFail(String requirement) {
        assertThat(validate("BigDecimal.ZERO", "assertEquals(new BigDecimal(\"0.00\"), result.getPaidAmount());",
                requirement)).isEmpty();
    }

    @Test
    void recognizesScaleRequirementsOnTheLinkedApprovedBusinessRule() {
        var base = context("BigDecimal.ZERO", "The returned paidAmount is 0.00");
        var rule = new BusinessRuleContextDto(20L, 10L, "BR-001", "Return the amount with scale 2",
                null, "USER", "APPROVED", false, null);
        var linked = new UnitTestContextDto(base.project(), null, base.classes(), List.of(rule), base.approvedTestPlans(),
                base.approvedTestCases(), List.of(), List.of(), List.of());

        assertThat(validate(linked, "assertEquals(new BigDecimal(\"0.00\"), result.getPaidAmount());")).isEmpty();
    }

    @Test
    void preservesScaleRequirementsDefinedOnlyInApprovedTestData() {
        var base = context("BigDecimal.ZERO", "The paidAmount matches the expected test data");
        var approved = base.approvedTestCases().get(0);
        var withScale = new TestCaseContextItemDto(approved.id(), approved.testPlanId(), approved.caseCode(),
                approved.testType(), approved.description(), approved.preconditions(),
                Map.of("expected", Map.of("paidAmount", "0.00 with scale 2")), approved.expectedResult(),
                approved.priority(), approved.traceSource(), approved.status(), approved.isModified());
        var context = new UnitTestContextDto(base.project(), null, base.classes(), base.approvedBusinessRules(),
                base.approvedTestPlans(), List.of(withScale), List.of(), List.of(), List.of());

        assertThat(validate(context, "assertEquals(new BigDecimal(\"0.00\"), result.getPaidAmount());")).isEmpty();
    }

    @Test
    void doesNotApplyAnotherCasesScaleRequirementToThisCase() {
        var base = context("BigDecimal.ZERO", "The returned paidAmount is zero");
        var otherRule = new BusinessRuleContextDto(99L, 99L, "BR-099", "Return an amount with scale 2",
                null, "USER", "APPROVED", false, null);
        var otherCase = new TestCaseContextItemDto(99L, 99L, "TC-099", "HAPPY_PATH", "Other scenario", "",
                Map.of(), "An amount with scale 2", "HIGH", "", "APPROVED", false);
        var linked = new UnitTestContextDto(base.project(), null, base.classes(), List.of(base.approvedBusinessRules().get(0), otherRule), base.approvedTestPlans(),
                base.approvedTestCases(), List.of(otherCase), List.of(), List.of());

        assertThat(validate(linked, "assertEquals(new BigDecimal(\"0.0\"), result.getPaidAmount());"))
                .hasValueSatisfying(message -> assertThat(message).contains("BigDecimal", "compareTo"));
    }

    @Test
    void acceptsMatchingFixtureAssertionsWithoutInventingADecimalLiteral() {
        assertThat(validate("BigDecimal.ZERO", "Receipt expected = new Receipt(); assertEquals(expected.getPaidAmount(), result.getPaidAmount());",
                "The returned paidAmount has the expected numeric value")).isEmpty();
    }

    @Test
    void doesNotClassifyCustomAssertionsAsJUnitEquality() {
        assertThat(validate("BigDecimal.ZERO", "custom.assertEquals(new BigDecimal(\"0.0\"), result.getPaidAmount());",
                "The returned paidAmount is zero")).isEmpty();
    }

    @Test
    void recognizesJUnit4MessageFirstAssertions() {
        var test = response("assertEquals(\"paid amount\", new BigDecimal(\"0.0\"), result.getPaidAmount());")
                .unitTests().get(0);
        String junit4 = test.sourceCode().replace("org.junit.jupiter.api.Assertions", "org.junit.Assert")
                .replace("@org.junit.jupiter.api.Test", "@org.junit.Test");
        var output = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(test.caseId(), test.testClassName(),
                test.testMethodName(), test.packageName(), test.generationType(), junit4)));

        assertThat(GeneratedUnitTestSemanticValidator.validate(context("BigDecimal.ZERO", "The paid amount is zero"), output))
                .hasValueSatisfying(message -> assertThat(message).contains("BigDecimal", "compareTo"));
    }

    @Test
    void recognizesJUnitAssertionsAlongsideMockitoWildcardImportsUsedInHospitalTests() {
        var test = response("assertEquals(new BigDecimal(\"0.0\"), result.getPaidAmount());").unitTests().get(0);
        String source = test.sourceCode().replace("import java.math.BigDecimal;",
                "import java.math.BigDecimal; import static org.mockito.Mockito.*;");
        var output = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(test.caseId(), test.testClassName(),
                test.testMethodName(), test.packageName(), test.generationType(), source)));

        assertThat(GeneratedUnitTestSemanticValidator.validate(context("BigDecimal.ZERO", "The paid amount is zero"), output))
                .hasValueSatisfying(message -> assertThat(message).contains("BigDecimal", "compareTo"));
    }

    @Test
    void recognizesSourceDeclaredLombokGetters() {
        var base = context("BigDecimal.ZERO", "The paid amount is zero");
        var receipt = new ClassContextDto(2L, "demo", "Receipt", "demo.Receipt", "OTHER", "Receipt.java",
                "package demo; @lombok.Data class Receipt { private java.math.BigDecimal paidAmount = java.math.BigDecimal.ZERO; }",
                List.of(), List.of());
        var lombok = new UnitTestContextDto(base.project(), null, List.of(base.classes().get(0), receipt), base.approvedBusinessRules(),
                base.approvedTestPlans(), base.approvedTestCases(), List.of(), List.of(), List.of());

        assertThat(validate(lombok, "assertEquals(new BigDecimal(\"0.0\"), result.getPaidAmount());"))
                .hasValueSatisfying(message -> assertThat(message).contains("BigDecimal", "compareTo"));
    }

    @Test
    void doesNotGuessTheTypeOfAnUnknownGetter() {
        var base = context("BigDecimal.ZERO", "The paid amount is zero");
        var receipt = new ClassContextDto(2L, "demo", "Receipt", "demo.Receipt", "OTHER", "Receipt.java",
                "package demo; class Receipt { private java.math.BigDecimal paidAmount; }", List.of(), List.of());
        var unknown = new UnitTestContextDto(base.project(), null, List.of(base.classes().get(0), receipt), base.approvedBusinessRules(),
                base.approvedTestPlans(), base.approvedTestCases(), List.of(), List.of(), List.of());

        assertThat(validate(unknown, "assertEquals(new BigDecimal(\"0.0\"), result.getPaidAmount());")).isEmpty();
    }

    @Test
    void keepsNumericBusinessFailuresVisible() {
        assertThat(validate("new BigDecimal(\"99.00\")",
                "assertEquals(0, new BigDecimal(\"100.00\").compareTo(result.getPaidAmount()));",
                "The returned paidAmount is 100.00")).isEmpty();
    }

    private Optional<String> validate(String initializer, String assertion, String expectedResult) {
        return validate(context(initializer, expectedResult), assertion);
    }

    private Optional<String> validate(UnitTestContextDto context, String assertion) {
        return GeneratedUnitTestSemanticValidator.validate(context, response(assertion));
    }

    static UnitTestContextDto context(String initializer, String expectedResult) {
        String body = "public Receipt create() { return new Receipt(); }";
        var method = new MethodContextDto(10L, "demo.Service", "create", "Receipt", List.of(), List.of(),
                "public", body, 1, 1, List.of(), List.of(), List.of());
        var service = new ClassContextDto(1L, "demo", "Service", "demo.Service", "SERVICE", "Service.java",
                "package demo; class Service { " + body + " }", List.of(), List.of(method));
        var receipt = new ClassContextDto(2L, "demo", "Receipt", "demo.Receipt", "OTHER", "Receipt.java",
                "package demo; import java.math.BigDecimal; class Receipt { private BigDecimal paidAmount = "
                        + initializer + "; public BigDecimal getPaidAmount() { return paidAmount; } }", List.of(), List.of());
        var rule = new BusinessRuleContextDto(20L, 10L, "BR-001", "Return a receipt with the expected paid amount",
                null, "USER", "APPROVED", false, null);
        var plan = new TestPlanContextItemDto(30L, 20L, List.of(20L), "TP-001", "Create receipt", "Valid receipt",
                "HAPPY_PATH", "APPROVED", false);
        var approved = new TestCaseContextItemDto(40L, 30L, "TC-001", "HAPPY_PATH", "Create receipt", "Valid request",
                Map.of(), expectedResult, "HIGH", "BR-001 -> TP-001", "APPROVED", false);
        return new UnitTestContextDto(new ProjectContextDto(1L, "demo", "ANALYZED"), null, List.of(service, receipt), List.of(rule), List.of(plan),
                List.of(approved), List.of(), List.of(), List.of());
    }

    static UnitTestResponseDto response(String assertion) {
        String source = "package demo; import java.math.BigDecimal; import static org.junit.jupiter.api.Assertions.*; "
                + "class ServiceTest { Service service = new Service(); Object custom; "
                + "@org.junit.jupiter.api.Test void createsReceipt() { Receipt result = service.create(); "
                + assertion + " } }";
        return new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(40L, "ServiceTest", "createsReceipt", "demo", "NEW_TEST", source)));
    }
}
