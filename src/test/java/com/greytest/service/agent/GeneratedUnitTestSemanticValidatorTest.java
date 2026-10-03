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
    void rejectsRemovedMockitoVerifyZeroInteractionsApi() {
        String generatedSource = "class ServiceTest { @org.junit.Test public void testCase() { "
                + "org.mockito.Mockito.verifyZeroInteractions(new Object()); } }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response("testCase", generatedSource)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("verifyNoInteractions", "verifyZeroInteractions"));
    }

    @Test
    void rejectsInjectMocksWhenConstructorDependencyIsMissing() {
        String serviceSource = """
                package demo;
                class Service {
                    Service(Repository repository, Client client) {}
                    public void send() {}
                }
                """;
        String generatedSource = """
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock Repository repository;
                    @InjectMocks Service service;
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource),
                response(generatedSource))).hasValueSatisfying(message -> assertThat(message)
                        .contains("Client", "@Mock"));
    }

    @Test
    void acceptsInjectMocksWhenEveryConstructorDependencyIsMocked() {
        String serviceSource = """
                package demo;
                class Service {
                    Service(Repository repository, Client client) {}
                    public void send() {}
                }
                """;
        String generatedSource = """
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock Repository repository;
                    @Mock Client client;
                    @InjectMocks Service service;
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource),
                response(generatedSource))).isEmpty();
    }

    @Test
    void rejectsImportFromSiblingMicroservice() {
        String serviceSource = """
                package com.hospital.doctor.service;
                class DoctorService { public void send() {} }
                """;
        String generatedSource = """
                import com.hospital.billing.dto.InvoiceDto;
                class DoctorServiceTest {}
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("com.hospital.doctor.service.DoctorService", "send",
                        "public void send() {}", serviceSource), response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("another microservice", "billing"));
    }

    @Test
    void acceptsManualConstructionWithNonNullConfigurationValue() {
        String serviceSource = """
                package demo;
                class Service {
                    Service(Repository repository, String baseUrl) {}
                    public void send() {}
                }
                """;
        String generatedSource = """
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock Repository repository;
                    Service service;
                    @BeforeEach void setUp() { service = new Service(repository, "https://example.test"); }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource),
                response(generatedSource))).isEmpty();
    }

    @Test
    void rejectsInjectMocksWhenLargestConstructorDependencyIsMissing() {
        String serviceSource = """
                package demo;
                class Service {
                    @Autowired Service(Repository repository) {}
                    Service(Repository repository, Client client) {}
                    public void send() {}
                }
                """;
        String generatedSource = """
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock Repository repository;
                    @InjectMocks Service service;
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource),
                response(generatedSource))).hasValueSatisfying(message -> assertThat(message).contains("Client"));
    }

    @Test
    void acceptsRequiredArgsConstructorWithInitializedFinalAndNonNullFields() {
        String serviceSource = """
                package demo;
                @RequiredArgsConstructor
                class Service {
                    private final Repository repository;
                    private final Map cache = new HashMap<>();
                    @NonNull private Client client;
                    public void send() {}
                }
                """;
        String generatedSource = """
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                class ServiceTest {
                    @Mock Repository repository;
                    @Mock Client client;
                    @InjectMocks Service service;
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("demo.Service", "send", "public void send() {}", serviceSource),
                response(generatedSource))).isEmpty();
    }

    @Test
    void acceptsImportAlreadyUsedByTargetService() {
        String serviceSource = """
                package com.hospital.doctor.service;
                import com.hospital.common.dto.DoctorProfileDto;
                class DoctorService { public void send() {} }
                """;
        String generatedSource = """
                import com.hospital.common.dto.DoctorProfileDto;
                class DoctorServiceTest {}
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                contextWithServiceSource("com.hospital.doctor.service.DoctorService", "send",
                        "public void send() {}", serviceSource), response(generatedSource))).isEmpty();
    }

    @Test
    void rejectsVerifyNoInteractionsAfterPositiveVerificationOfSameMock() {
        String generatedSource = """
                class ServiceTest {
                    Repository repository;
                    void testCase() {
                        org.mockito.Mockito.verify(repository).findById(1L);
                        org.mockito.Mockito.verifyNoInteractions(repository);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("verifyNoInteractions(repository)", "verifyNoMoreInteractions"));
    }

    @Test
    void acceptsVerifyNoMoreInteractionsAndNoInteractionsOnDifferentMock() {
        String generatedSource = """
                class ServiceTest {
                    Repository repository;
                    Client client;
                    void testCase() {
                        org.mockito.Mockito.when(repository.findById(1L)).thenReturn(null);
                        org.mockito.Mockito.verify(repository).findById(1L);
                        org.mockito.Mockito.verifyNoMoreInteractions(repository);
                        org.mockito.Mockito.verifyNoInteractions(client);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource))).isEmpty();
    }

    @Test
    void acceptsVerifyNoInteractionsAfterStubbingAndZeroCountVerification() {
        String generatedSource = """
                class ServiceTest {
                    Repository repository;
                    void testCase() {
                        org.mockito.Mockito.when(repository.findById(1L)).thenReturn(null);
                        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).deleteById(1L);
                        org.mockito.Mockito.verifyNoInteractions(repository);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource))).isEmpty();
    }

    @Test
    void acceptsVerifyNoInteractionsOnAlternativeBranch() {
        String generatedSource = """
                class ServiceTest {
                    Repository repository;
                    void testCase(boolean shouldSave) {
                        if (shouldSave) {
                            org.mockito.Mockito.verify(repository).save();
                        } else {
                            org.mockito.Mockito.verifyNoInteractions(repository);
                        }
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource))).isEmpty();
    }

    @Test
    void acceptsVerifyNoInteractionsAfterClearingMockHistory() {
        String generatedSource = """
                class ServiceTest {
                    Repository repository;
                    void testCase() {
                        org.mockito.Mockito.verify(repository).save();
                        org.mockito.Mockito.clearInvocations(repository);
                        org.mockito.Mockito.verifyNoInteractions(repository);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource))).isEmpty();
    }

    @Test
    void acceptsVerifyNoInteractionsWhenAnonymousCallbackIsNotInvoked() {
        String generatedSource = """
                class ServiceTest {
                    Repository repository;
                    void testCase() {
                        Runnable callback = new Runnable() {
                            @Override public void run() {
                                org.mockito.Mockito.verify(repository).save();
                            }
                        };
                        org.mockito.Mockito.verifyNoInteractions(repository);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource))).isEmpty();
    }

    @Test
    void acceptsVerifyNoInteractionsOnDifferentHolderField() {
        String generatedSource = """
                class ServiceTest {
                    Holder first;
                    Holder second;
                    void testCase() {
                        org.mockito.Mockito.verify(first.repository).save();
                        org.mockito.Mockito.verifyNoInteractions(second.repository);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(generatedSource))).isEmpty();
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

    @Test
    void rejectsStubbingOrVerifyingOnInjectMocks() {
        String stubbedSut = """
                class ServiceTest {
                    @org.mockito.InjectMocks
                    Service service;
                    @org.junit.Test public void testCase() {
                        org.mockito.Mockito.when(service.internalMethod()).thenReturn("mocked");
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("send", "public void send() {}"), response(stubbedSut)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("Do not stub methods on the @InjectMocks instance 'service'"));
    }

    @Test
    void rejectsDirectInvocationOfPrivateMethod() {
        MethodContextDto publicMethod = new MethodContextDto(
                10L, "demo.Service", "publicMethod", "void", List.of(), List.of(),
                "PUBLIC", "public void publicMethod() {}", 1, 5, List.of(), List.of(), List.of());
        MethodContextDto privateMethod = new MethodContextDto(
                11L, "demo.Service", "privateHelper", "void", List.of(), List.of(),
                "PRIVATE", "private void privateHelper() {}", 6, 10, List.of(), List.of(), List.of());
        ClassContextDto javaClass = new ClassContextDto(
                1L, "demo", "Service", "demo.Service", "SERVICE",
                "src/main/java/demo/Service.java", null, List.of(), List.of(publicMethod, privateMethod));
        BusinessRuleContextDto rule = new BusinessRuleContextDto(
                20L, 11L, "BR-001", "rule", null, "AI", "APPROVED", false, "STMT-1");
        TestPlanContextItemDto plan = new TestPlanContextItemDto(
                30L, 20L, List.of(20L), "TP-001", "plan", "plan", "NORMAL", "APPROVED", false);
        TestCaseContextItemDto testCase = new TestCaseContextItemDto(
                40L, 30L, "TC-001", "NORMAL", "case", "setup", java.util.Map.of(),
                "result", "HIGH", "BR-001 -> TP-001", "APPROVED", false);
        UnitTestContextDto ctx = new UnitTestContextDto(null, null, List.of(javaClass), List.of(rule), List.of(plan),
                List.of(testCase), List.of(), List.of(), List.of());

        String callingPrivate = """
                class ServiceTest {
                    @org.mockito.InjectMocks
                    Service service;
                    @org.junit.Test public void testCase() {
                        service.privateHelper();
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(callingPrivate)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("Method 'privateHelper' is private in Service"));
    }

    @Test
    void rejectsAnonymousSubclassOverrideOfPrivateMethod() {
        MethodContextDto publicMethod = new MethodContextDto(
                10L, "demo.Service", "publicMethod", "void", List.of(), List.of(),
                "PUBLIC", "public void publicMethod() {}", 1, 5, List.of(), List.of(), List.of());
        ClassContextDto javaClass = new ClassContextDto(
                1L, "demo", "Service", "demo.Service", "SERVICE",
                "src/main/java/demo/Service.java", """
                        class Service {
                            public void publicMethod() {}
                            private Object privateHelper() { return null; }
                        }
                        """, List.of(), List.of(publicMethod));
        BusinessRuleContextDto rule = new BusinessRuleContextDto(
                20L, 10L, "BR-001", "rule", null, "AI", "APPROVED", false, "STMT-1");
        TestPlanContextItemDto plan = new TestPlanContextItemDto(
                30L, 20L, List.of(20L), "TP-001", "plan", "plan", "NORMAL", "APPROVED", false);
        TestCaseContextItemDto testCase = new TestCaseContextItemDto(
                40L, 30L, "TC-001", "NORMAL", "case", "setup", java.util.Map.of(),
                "result", "HIGH", "BR-001 -> TP-001", "APPROVED", false);
        UnitTestContextDto ctx = new UnitTestContextDto(null, null, List.of(javaClass), List.of(rule), List.of(plan),
                List.of(testCase), List.of(), List.of(), List.of());
        String anonymousOverride = """
                import demo.Service;
                class ServiceTest {
                    void testCase() {
                        Service service = new Service() {
                            @Override
                            Object privateHelper() { return null; }
                        };
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(anonymousOverride)))
                .hasValueSatisfying(message -> assertThat(message)
                        .contains("anonymous subclass", "privateHelper", "private"));
    }

    @Test
    void acceptsAnonymousSubclassOfNonSutCollaborator() {
        UnitTestContextDto base = context("publicMethod", "public void publicMethod() {}");
        MethodContextDto privateMethod = new MethodContextDto(
                11L, "demo.Collaborator", "privateHelper", "void", List.of(), List.of(),
                "PRIVATE", "private void privateHelper() {}", 1, 5, List.of(), List.of(), List.of());
        ClassContextDto collaborator = new ClassContextDto(
                2L, "demo", "Collaborator", "demo.Collaborator", "OTHER",
                "src/main/java/demo/Collaborator.java", null, List.of(), List.of(privateMethod));
        UnitTestContextDto contextWithCollaborator = new UnitTestContextDto(
                base.project(), base.analysis(), List.of(base.classes().get(0), collaborator),
                base.approvedBusinessRules(), base.approvedTestPlans(), base.approvedTestCases(),
                base.existingApprovedTestCases(), base.previousGeneratedUnitTests(), base.existingTests());
        String collaboratorOverride = """
                class ServiceTest {
                    void testCase() {
                        Collaborator collaborator = new Collaborator() {
                            @Override
                            void privateHelper() {}
                        };
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(contextWithCollaborator, response(collaboratorOverride)))
                .isEmpty();
    }

    @Test
    void acceptsAnonymousSubclassOfSameNamedTypeFromAnotherPackage() {
        String collaboratorOverride = """
                class ServiceTest {
                    void testCase() {
                        other.Service collaborator = new other.Service() {
                            @Override
                            void privateHelper() {}
                        };
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("publicMethod", "public void publicMethod() {}"), response(collaboratorOverride)))
                .isEmpty();
    }

    @Test
    void rejectsMockWithoutRunnerOrExtension() {
        String testWithoutRunner = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.mockito.Mock;
                import org.mockito.InjectMocks;
                public class ServiceTest {
                    @Mock
                    private Repo repo;
                    @InjectMocks
                    private Service service;
                    @Test
                    void testMethod() {}
                }
                """;
        var ctx = context("test", "public void test() {}");
        var error = GeneratedUnitTestSemanticValidator.validate(ctx, response(testWithoutRunner), TestFramework.JUNIT5);
        assertThat(error).hasValueSatisfying(msg -> assertThat(msg)
                .contains("@ExtendWith(MockitoExtension.class)"));

        var errorJunit4 = GeneratedUnitTestSemanticValidator.validate(ctx, response(testWithoutRunner), TestFramework.JUNIT4);
        assertThat(errorJunit4).hasValueSatisfying(msg -> assertThat(msg)
                .contains("@RunWith(MockitoJUnitRunner.class)"));
    }

    @Test
    void acceptsMockWithExtendWithOrOpenMocks() {
        String testWithExtendWith = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                public class ServiceTest {
                    @Mock
                    private Repo repo;
                    @Test
                    void testMethod() {}
                }
                """;
        var ctx = context("test", "public void test() {}");
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(testWithExtendWith), TestFramework.JUNIT5)).isEmpty();

        String testWithOpenMocks = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.BeforeEach;
                import org.mockito.Mock;
                import org.mockito.MockitoAnnotations;
                public class ServiceTest {
                    @Mock
                    private Repo repo;
                    @BeforeEach
                    void setup() { MockitoAnnotations.openMocks(this); }
                    @Test
                    void testMethod() {}
                }
                """;
        assertThat(GeneratedUnitTestSemanticValidator.validate(ctx, response(testWithOpenMocks), TestFramework.JUNIT5)).isEmpty();
    }

    @Test
    void rejectsGeneratedTestMarkedAsSkipped() {
        String skippedTest = """
                class ServiceTest {
                    // SKIPPED: missing entity details
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(skippedTest)))
                .hasValueSatisfying(message -> assertThat(message).contains("must not skip"));
    }

    @Test
    void rejectsDisabledGeneratedTest() {
        String disabledTest = """
                import org.junit.jupiter.api.Disabled;
                class ServiceTest {
                    @Disabled
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(disabledTest)))
                .hasValueSatisfying(message -> assertThat(message).contains("must not skip"));
    }

    @Test
    void rejectsSkippedCommentWithoutWhitespaceOrUppercase() {
        String skippedTest = """
                class ServiceTest {
                    //skipped because context is incomplete
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(skippedTest)))
                .hasValueSatisfying(message -> assertThat(message).contains("must not skip"));
    }

    @Test
    void rejectsCommentedOutTestAnnotation() {
        String commentedOutTest = """
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                    // @Test
                    // void omittedTestCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(commentedOutTest)))
                .hasValueSatisfying(message -> assertThat(message).contains("must not skip"));
    }

    @Test
    void acceptsDisabledMarkerInsideStringFixture() {
        String validTest = """
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        String fixture = "@Disabled";
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(validTest))).isEmpty();
    }

    @Test
    void rejectsLenientStubbing() {
        String lenientTest = """
                class ServiceTest {
                    Repository repository;
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.lenient().when(repository.findById(1L)).thenReturn(null);
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientTest)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsGeneratedMethodWithoutTestAnnotation() {
        String missingAnnotation = """
                class ServiceTest {
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(missingAnnotation)))
                .hasValueSatisfying(message -> assertThat(message).contains("must declare @Test"));
    }

    @Test
    void acceptsTraceCommentThatMentionsSkippedRecords() {
        String validTest = """
                class ServiceTest {
                    // GreyTest trace: process | skipped records | BR-001 -> TP-001 -> TC-001
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(validTest))).isEmpty();
    }

    @Test
    void rejectsMockAnnotationThatDisablesStrictStubbing() {
        String lenientMock = """
                class ServiceTest {
                    @org.mockito.Mock(lenient = true)
                    Repository repository;
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientMock)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsMockitoSettingsThatDisableStrictStubbing() {
        String lenientSettings = """
                @org.mockito.junit.jupiter.MockitoSettings(
                    strictness = org.mockito.quality.Strictness.LENIENT)
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientSettings)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsMalformedGeneratedTestSource() {
        String malformedSource = "class ServiceTest { void testCase( }";

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(malformedSource)))
                .hasValueSatisfying(message -> assertThat(message).contains("valid Java test source"));
    }

    @Test
    void rejectsMockStrictnessThatDisablesStrictStubbing() {
        String lenientMock = """
                class ServiceTest {
                    @org.mockito.Mock(strictness = org.mockito.quality.Strictness.LENIENT)
                    Repository repository;
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientMock)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsMockitoSessionWithLenientStrictness() {
        String lenientSession = """
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.mockitoSession()
                            .strictness(org.mockito.quality.Strictness.LENIENT)
                            .startMocking();
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientSession)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsTestFactoryInsteadOfTestMethod() {
        String testFactory = """
                class ServiceTest {
                    @org.junit.jupiter.api.TestFactory
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(testFactory)))
                .hasValueSatisfying(message -> assertThat(message).contains("must declare @Test"));
    }

    @Test
    void rejectsLenientMockSettings() {
        String lenientSettings = """
                import static org.mockito.Mockito.withSettings;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.mock(Repository.class, withSettings().lenient());
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientSettings)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsStaticallyImportedLenientStrictness() {
        String lenientMock = """
                import static org.mockito.quality.Strictness.LENIENT;
                class ServiceTest {
                    @org.mockito.Mock(strictness = LENIENT)
                    Repository repository;
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientMock)))
                .hasValueSatisfying(message -> assertThat(message).contains("Do not use lenient"));
    }

    @Test
    void rejectsWarnMockitoSettings() {
        String warnSettings = """
                @org.mockito.junit.jupiter.MockitoSettings(
                    strictness = org.mockito.quality.Strictness.WARN)
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(warnSettings)))
                .hasValueSatisfying(message -> assertThat(message).contains("strict stubbing"));
    }

    @Test
    void rejectsJUnit4SilentMockitoRunner() {
        String silentRunner = """
                import org.junit.runner.RunWith;
                import org.mockito.Mock;
                import org.mockito.junit.MockitoJUnitRunner;
                @RunWith(MockitoJUnitRunner.Silent.class)
                class ServiceTest {
                    @Mock Repository repository;
                    @org.junit.Test void testCase() {}
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(silentRunner), TestFramework.JUNIT4))
                .hasValueSatisfying(message -> assertThat(message).contains("Silent", "strict stubbing"));
    }

    @Test
    void rejectsStaticallyImportedLenientMockitoSession() {
        String lenientSession = """
                import static org.mockito.quality.Strictness.LENIENT;
                class ServiceTest {
                    @org.junit.jupiter.api.Test
                    void testCase() {
                        org.mockito.Mockito.mockitoSession().strictness(LENIENT).startMocking();
                    }
                }
                """;

        assertThat(GeneratedUnitTestSemanticValidator.validate(
                context("testCase", "public void testCase() {}"), response(lenientSession)))
                .hasValueSatisfying(message -> assertThat(message).contains("strict stubbing"));
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

    private UnitTestContextDto contextWithServiceSource(
            String qualifiedName, String methodName, String methodSource, String serviceSource) {
        UnitTestContextDto base = context(methodName, methodSource);
        ClassContextDto previous = base.classes().get(0);
        int lastDot = qualifiedName.lastIndexOf('.');
        String packageName = lastDot < 0 ? "" : qualifiedName.substring(0, lastDot);
        String className = lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
        MethodContextDto method = new MethodContextDto(
                previous.methods().get(0).id(), qualifiedName, methodName, previous.methods().get(0).returnType(),
                previous.methods().get(0).parameters(), previous.methods().get(0).throwsList(),
                previous.methods().get(0).visibility(), methodSource, previous.methods().get(0).lineStart(),
                previous.methods().get(0).lineEnd(), previous.methods().get(0).annotations(),
                previous.methods().get(0).endpoints(), previous.methods().get(0).branches());
        ClassContextDto service = new ClassContextDto(
                previous.id(), packageName, className, qualifiedName, previous.classType(), previous.filePath(),
                serviceSource, previous.annotations(), List.of(method));
        return new UnitTestContextDto(
                base.project(), base.analysis(), List.of(service), base.approvedBusinessRules(),
                base.approvedTestPlans(), base.approvedTestCases(), base.existingApprovedTestCases(),
                base.previousGeneratedUnitTests(), base.existingTests());
    }

    private UnitTestResponseDto response(String source) {
        return response("testCase", source);
    }

    private UnitTestResponseDto response(String methodName, String source) {
        return new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(
                40L, "ServiceTest", methodName, "demo", "NEW_TEST", source)));
    }
}
