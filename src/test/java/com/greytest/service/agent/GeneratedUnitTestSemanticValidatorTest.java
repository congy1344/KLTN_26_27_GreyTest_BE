package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.service.agent.ProjectJavaVersionDetector.TestFramework;

class GeneratedUnitTestSemanticValidatorTest {

    @Test
    void rejectsMockedEnumAndJavaMailContentByteArrayCast() {
        String generatedSource = """
                import javax.mail.*;
                class ServiceTest {
                    Part part;
                    void testCase() throws Exception {
                        NotificationType type = org.mockito.Mockito.mock(NotificationType.class);
                        byte[] bytes = (byte[]) part.getContent();
                    }
                }
                """;
        UnitTestContextDto base = context("send", "public void send() {}");
        ClassContextDto enumType = new ClassContextDto(
                2L, "demo", "NotificationType", "demo.NotificationType", "ENUM",
                "src/main/java/demo/NotificationType.java", "enum NotificationType { BACKUP, REMIND }",
                List.of(), List.of());
        UnitTestContextDto withEnum = new UnitTestContextDto(
                base.project(), base.analysis(), List.of(base.classes().get(0), enumType),
                base.approvedBusinessRules(), base.approvedTestPlans(), base.approvedTestCases(),
                base.existingApprovedTestCases(), base.previousGeneratedUnitTests(), base.existingTests());

        var error = GeneratedUnitTestSemanticValidator.validate(withEnum, response(generatedSource));

        assertThat(error).hasValueSatisfying(message -> assertThat(message)
                .contains("Do not mock enum NotificationType", "getInputStream()", "byte[]"));
    }

    @Test
    void ignoresTrailingMessageOnCustomHelperButRejectsJUnit4ImportInJunit5Project() {
        String customHelper = "class ServiceTest { void testCase() { assertEmail(actual, \"message\"); "
                + "byte[] bytes = (byte[]) domain.getContent(); } }";
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(customHelper), TestFramework.JUNIT4)).isEmpty();

        String junit4Wildcard = "import org.junit.*; class ServiceTest { @Test public void testCase() {} }";
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(junit4Wildcard), TestFramework.JUNIT5))
                .hasValueSatisfying(message -> assertThat(message).contains("project uses JUnit 5"));
    }

    @Test
    void rejectsJUnit5AndRemovedMockitoApisForJUnit4Project() {
        String generatedSource = """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class ServiceTest {
                    Service service;
                    @Test void testCase() {
                        assertThrows(IllegalArgumentException.class, () -> service.send(null));
                        assertNotNull(service, "message");
                        service.send(org.mockito.ArgumentMatchers.isNull(String.class));
                    }
                }
                """;

        var error = GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send(String value) {}"),
                response(generatedSource), TestFramework.JUNIT4);

        assertThat(error).hasValueSatisfying(message -> assertThat(message)
                .contains("JUnit 4", "org.junit.jupiter", "isNull(Class)"));
    }

    @Test
    void rejectsIllegalArgumentExpectationWhenNullReachesSwitchSelector() {
        String productionSource = """
                public String findReadyToNotify(NotificationType type) {
                    switch (type) {
                        case BACKUP: return "backup";
                        default: throw new IllegalArgumentException();
                    }
                }
                """;
        String generatedSource = """
                class RecipientServiceTest {
                    Service service;
                    @org.junit.Test(expected = IllegalArgumentException.class)
                    public void rejectsNull() { service.findReadyToNotify(null); }
                }
                """;

        var error = GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", productionSource), response("rejectsNull", generatedSource));

        assertThat(error).hasValueSatisfying(message -> assertThat(message)
                .contains("NullPointerException", "findReadyToNotify"));
    }

    @Test
    void acceptsNullPointerExpectationForNullSwitchSelector() {
        String productionSource = """
                public String findReadyToNotify(NotificationType type) {
                    switch (type) {
                        case BACKUP: return "backup";
                        default: throw new IllegalArgumentException();
                    }
                }
                """;
        String generatedSource = """
                class RecipientServiceTest {
                    @org.junit.Test(expected = NullPointerException.class)
                    public void rejectsNull() { service.findReadyToNotify(null); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", productionSource), response("rejectsNull", generatedSource))).isEmpty();
    }

    @Test
    void rejectsPlainContentAssertionWhenProductionAlwaysUsesMultipart() {
        String generatedSource = """
                import javax.mail.internet.MimeMessage;
                class EmailServiceTest {
                    @org.junit.Test public void sends() throws Exception {
                        String subject = "Nhắc nhở tài khoản";
                        MimeMessage message = capturedMessage();
                        org.junit.Assert.assertTrue(message.getContent() instanceof String);
                    }
                    MimeMessage capturedMessage() { return null; }
                }
                """;

        var error = GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() { new MimeMessageHelper(message, true); }"),
                response("sends", generatedSource));

        assertThat(error).hasValueSatisfying(message -> assertThat(message)
                .contains("multipart=true", "MimeMessage.getContent()"));
    }

    @Test
    void acceptsUnicodeWhenThereIsNoProvenMultipartMistake() {
        String generatedSource = """
                class ServiceTest {
                    Service service;
                    @org.junit.Test public void sends() {
                        org.junit.Assert.assertEquals("Nhắc nhở", service.send("Nhắc nhở"));
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public String send(String value) { return value; }"),
                response("sends", generatedSource))).isEmpty();
    }

    @Test
    void acceptsPlainContentAssertionWhenHelperIsNotMultipart() {
        String generatedSource = """
                import javax.mail.internet.MimeMessage;
                class ServiceTest {
                    @org.junit.Test public void sends() throws Exception {
                        MimeMessage message = capturedMessage();
                        org.junit.Assert.assertTrue(message.getContent() instanceof String);
                    }
                    MimeMessage capturedMessage() { return null; }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() { new MimeMessageHelper(message, false); }"),
                response("sends", generatedSource))).isEmpty();
    }

    @Test
    void acceptsPlainContentAssertionWhenProductionHasMixedMultipartModes() {
        String generatedSource = """
                import javax.mail.internet.MimeMessage;
                class ServiceTest {
                    @org.junit.Test public void sends() throws Exception {
                        MimeMessage message = capturedMessage();
                        org.junit.Assert.assertTrue(message.getContent() instanceof String);
                    }
                    MimeMessage capturedMessage() { return null; }
                }
                """;
        String productionSource = """
                public void send(boolean attachment) {
                    if (attachment) new MimeMessageHelper(message, true);
                    else new MimeMessageHelper(message, false);
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", productionSource), response("sends", generatedSource))).isEmpty();
    }

    @Test
    void doesNotCombineDifferentAssertThrowsBlocks() {
        String productionSource = """
                public String findReadyToNotify(NotificationType type) {
                    switch (type) { default: throw new IllegalArgumentException(); }
                }
                """;
        String generatedSource = """
                class ServiceTest {
                    Service service;
                    Other other;
                    @org.junit.Test public void rejectsNull() {
                        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                                () -> other.validate());
                        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                                () -> service.findReadyToNotify(null));
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", productionSource), response(generatedSource))).isEmpty();
    }

    @Test
    void rejectsIllegalArgumentExpectationForSwitchExpression() {
        String productionSource = """
                public String findReadyToNotify(NotificationType type) {
                    return switch (type) {
                        case BACKUP -> "backup";
                        default -> throw new IllegalArgumentException();
                    };
                }
                """;
        String generatedSource = """
                class ServiceTest {
                    Service service;
                    @org.junit.Test public void rejectsNull() {
                        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                                () -> service.findReadyToNotify(null));
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", productionSource),
                response("rejectsNull", generatedSource))).isPresent();
    }

    @Test
    void skipsCorrectionWhenNullGuardReturnsBeforeSwitch() {
        String productionSource = """
                public String findReadyToNotify(NotificationType type) {
                    if (type == null) return "fallback";
                    switch (type) { default: throw new IllegalArgumentException(); }
                }
                """;
        String generatedSource = """
                class ServiceTest {
                    Service service;
                    @org.junit.Test(expected = IllegalArgumentException.class)
                    public void rejectsNull() { service.findReadyToNotify(null); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", productionSource),
                response("rejectsNull", generatedSource))).isEmpty();
    }

    @Test
    void doesNotTreatConditionalNestedOrDifferentVariableChecksAsDominatingGuard() {
        String generatedSource = """
                class ServiceTest {
                    Service service;
                    @org.junit.Test(expected = IllegalArgumentException.class)
                    public void rejectsNull() { service.findReadyToNotify(null); }
                }
                """;
        String compoundGuard = """
                public String findReadyToNotify(NotificationType type, boolean enabled) {
                    if (type == null && enabled) return "fallback";
                    switch (type) { default: throw new IllegalArgumentException(); }
                }
                """;
        String differentVariable = """
                public String findReadyToNotify(NotificationType type) {
                    Object prototype = lookup();
                    if (prototype == null) return "fallback";
                    switch (type) { default: throw new IllegalArgumentException(); }
                }
                """;
        String nestedGuard = """
                public String findReadyToNotify(NotificationType type, boolean enabled) {
                    if (enabled) { if (type == null) return "fallback"; }
                    switch (type) { default: throw new IllegalArgumentException(); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", compoundGuard),
                response("rejectsNull", generatedSource))).isPresent();
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", differentVariable),
                response("rejectsNull", generatedSource))).isPresent();
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", nestedGuard),
                response("rejectsNull", generatedSource))).isPresent();
    }

    @Test
    void onlyTreatsDirectSpringAssertCallAsNullGuard() {
        String generatedSource = """
                class ServiceTest {
                    Service service;
                    @org.junit.Test(expected = IllegalArgumentException.class)
                    public void rejectsNull() { service.findReadyToNotify(null); }
                }
                """;
        String conditionalSpringAssert = """
                public String findReadyToNotify(NotificationType type, boolean enabled) {
                    if (enabled) { Assert.notNull(type, "type"); }
                    switch (type) { default: throw new IllegalArgumentException(); }
                }
                """;
        String customCheck = """
                public String findReadyToNotify(NotificationType type) {
                    Checks.notNull(type);
                    switch (type) { default: throw new IllegalArgumentException(); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", conditionalSpringAssert),
                response("rejectsNull", generatedSource))).isPresent();
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", customCheck),
                response("rejectsNull", generatedSource))).isPresent();
    }

    @Test
    void recognizesDominatingNullGuardFromAncestorBlock() {
        String productionSource = """
                public String findReadyToNotify(NotificationType type, boolean enabled) {
                    if (type == null) throw new IllegalArgumentException();
                    if (enabled) {
                        switch (type) { default: return "fallback"; }
                    }
                    return "disabled";
                }
                """;
        String generatedSource = """
                class ServiceTest {
                    Service service;
                    @org.junit.Test(expected = IllegalArgumentException.class)
                    public void rejectsNull() { service.findReadyToNotify(null, true); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", productionSource),
                response("rejectsNull", generatedSource))).isEmpty();
    }

    @Test
    void detectsThisFieldReceiverButIgnoresShadowFromAnotherMethod() {
        String productionSource = """
                public String findReadyToNotify(NotificationType type) {
                    switch (type) { default: throw new IllegalArgumentException(); }
                }
                """;
        String thisReceiver = """
                class ServiceTest {
                    Service service;
                    @org.junit.Test(expected = IllegalArgumentException.class)
                    public void rejectsNull() { this.service.findReadyToNotify(null); }
                }
                """;
        String shadowElsewhere = """
                class ServiceTest {
                    Other service;
                    @org.junit.Test(expected = IllegalArgumentException.class)
                    public void rejectsNull() { service.findReadyToNotify(null); }
                    void helper() { Service service = null; }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", productionSource),
                response("rejectsNull", thisReceiver))).isPresent();
        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("findReadyToNotify", productionSource),
                response("rejectsNull", shadowElsewhere))).isEmpty();
    }

    private UnitTestContextDto context(String methodName, String methodSource) {
        MethodContextDto method = new MethodContextDto(
                10L, "demo.Service", methodName, "Object", List.of(), List.of(),
                "public", methodSource, 1, 10, List.of(), List.of(), List.of());
        ClassContextDto javaClass = new ClassContextDto(
                1L, "demo", "Service", "demo.Service", "SERVICE",
                "src/main/java/demo/Service.java", null, List.of(), List.of(method));
        BusinessRuleContextDto rule = new BusinessRuleContextDto(
                20L, 10L, "BR-001", "rule", null, "AI", "APPROVED", false, "SWITCH-1");
        TestPlanContextItemDto plan = new TestPlanContextItemDto(
                30L, 20L, List.of(20L), "TP-001", "plan", "plan", "EXCEPTION", "APPROVED", false);
        TestCaseContextItemDto testCase = new TestCaseContextItemDto(
                40L, 30L, "TC-001", "EXCEPTION", "case", "setup", java.util.Map.of(),
                "throws", "HIGH", "BR-001 -> TP-001", "APPROVED", false);
        return new UnitTestContextDto(null, null, List.of(javaClass), List.of(rule), List.of(plan),
                List.of(testCase), List.of(), List.of(), List.of());
    }

    private UnitTestResponseDto response(String source) {
        return response("testCase", source);
    }

    private UnitTestResponseDto response(String methodName, String source) {
        return new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                40L, "ServiceTest", methodName, "demo", "NEW_TEST", source)));
    }
}
