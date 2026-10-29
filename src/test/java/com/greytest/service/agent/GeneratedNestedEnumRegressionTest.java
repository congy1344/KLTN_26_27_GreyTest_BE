package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;

import jakarta.validation.Validation;

class GeneratedNestedEnumRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void acceptsUnchangedHospitalBatchThatImportsAppointment() throws Exception {
        var fixture = hospitalFixture();
        assertThat(fixture.tests()).hasSize(8);
        assertThat(GeneratedUnitTestSemanticValidator.validate(fixture.context(), new UnitTestResponseDto(fixture.tests()))).isEmpty();
    }

    @Test
    void returnsHospitalBatchOnFirstAttemptWithoutChangingGeneratedTests(@TempDir Path logs) throws Exception {
        var fixture = hospitalFixture();
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildUnitTestContext(811L)).thenReturn(fixture.context());
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any(LlmRequestOptions.class)))
                .thenReturn(mapper.writeValueAsString(new UnitTestResponseDto(fixture.tests())));
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var agent = new AIAgentService(contexts, new PromptManager(mapper), client,
                    new GenerationResponseParser(mapper, validation.getValidator()), new AiContextLogService(mapper, logs.toString()));
            assertThat(agent.generateUnitTests(811L).unitTests()).isEqualTo(fixture.tests());
            verify(client, times(1)).complete(anyString(), any(LlmRequestOptions.class));
        }
    }

    private Fixture hospitalFixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/generated-contract/hospital-nested-enums.json")) {
            var json = mapper.readTree(input);
            var context = mapper.treeToValue(json.get("context"), UnitTestContextDto.class);
            var classes = context.classes().stream().map(c -> new ClassContextDto(c.id(), c.packageName(), c.className(),
                    c.qualifiedName(), c.classType(), c.filePath(), c.sourceCode(), c.annotations(), c.methods())).toList();
            context = new UnitTestContextDto(context.project(), context.analysis(), classes, context.approvedBusinessRules(),
                    context.approvedTestPlans(), context.approvedTestCases(), List.of(), List.of(), List.of());
            var tests = mapper.readerForListOf(GeneratedUnitTestDto.class).<List<GeneratedUnitTestDto>>readValue(json.get("tests"));
            return new Fixture(context, tests);
        }
    }

    @Test
    void acceptsNestedEnumTypeConstantAndValueOfThroughOuterImport() {
        assertThat(validate("demo", "import model.Appointment;", "Appointment.AppointmentStatus", "SCHEDULED")).isEmpty();
    }

    @Test
    void acceptsOuterClassInTheSamePackage() {
        assertThat(validate("model", "", "Appointment.AppointmentStatus", "SCHEDULED")).isEmpty();
    }

    @Test
    void acceptsImportedNestedEnum() {
        assertThat(validate("demo", "import model.Appointment.AppointmentStatus;", "AppointmentStatus", "SCHEDULED")).isEmpty();
    }

    @Test
    void acceptsFullyQualifiedNestedEnum() {
        assertThat(validate("demo", "", "model.Appointment.AppointmentStatus", "SCHEDULED")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "import wrong.Appointment;", "import model.Appointment.AppointmentStatus;"})
    void rejectsOuterNameWithoutTheCorrectOuterImport(String imports) {
        assertThat(validate("demo", imports, "Appointment.AppointmentStatus", "SCHEDULED"))
                .hasValueSatisfying(message -> assertThat(message).contains("Enum type", "model.Appointment.AppointmentStatus"));
    }

    @Test
    void rejectsDifferentOuterClassEvenWithCorrectImport() {
        assertThat(validate("demo", "import model.Appointment;", "Other.AppointmentStatus", "SCHEDULED"))
                .hasValueSatisfying(message -> assertThat(message).contains("Enum type", "Other.AppointmentStatus"));
    }

    @Test
    void stillRejectsUnknownConstantThroughImportedOuterClass() {
        assertThat(validate("demo", "import model.Appointment;", "Appointment.AppointmentStatus", "MADE_UP"))
                .hasValueSatisfying(message -> assertThat(message).contains("Enum constant 'MADE_UP'", "model.Appointment.AppointmentStatus")
                        .doesNotContain("Enum type"));
    }

    @Test
    void doesNotTreatEnclosingClassAsAPackageForUnqualifiedEnum() {
        assertThat(validate("model.Appointment", "", "AppointmentStatus", "SCHEDULED"))
                .hasValueSatisfying(message -> assertThat(message).contains("import model.Appointment.AppointmentStatus"));
    }

    @Test
    void resolvesImportedOuterClassWhenTwoNestedEnumsShareTheSameSimpleName() {
        var base = context();
        var other = new ClassContextDto(4L, "other", "AppointmentStatus", "other.Appointment.AppointmentStatus", "ENUM",
                "Other.java", "package other; public class Appointment { public enum AppointmentStatus { OTHER } }",
                List.of(), List.of());
        var classes = new ArrayList<>(base.classes());
        classes.add(other);
        var context = new UnitTestContextDto(base.project(), base.analysis(), classes, base.approvedBusinessRules(),
                base.approvedTestPlans(), base.approvedTestCases(), List.of(), List.of(), List.of());
        String source = "package demo; import model.Appointment; class ServiceTest { @org.junit.jupiter.api.Test void testCase() { "
                + "Appointment.AppointmentStatus status = Appointment.AppointmentStatus.SCHEDULED; "
                + "org.junit.jupiter.api.Assertions.assertEquals(status, Appointment.AppointmentStatus.valueOf(\"SCHEDULED\")); } }";
        var test = new GeneratedUnitTestDto(40L, "ServiceTest", "testCase", "demo", "NEW_TEST", source);
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, new UnitTestResponseDto(List.of(test)))).isEmpty();
    }

    @Test
    void distinguishesSameNamedNestedEnumsInOneSourceFile() {
        var base = context();
        String production = "package model; public class Appointment { public enum Status { A } "
                + "public static class Audit { public enum Status { B } } }";
        var first = new ClassContextDto(4L, "model", "Status", "model.Appointment.Status", "ENUM", "Appointment.java",
                production, List.of(), List.of());
        var second = new ClassContextDto(5L, "model", "Status", "model.Appointment.Audit.Status", "ENUM", "Appointment.java",
                production, List.of(), List.of());
        var classes = new ArrayList<>(base.classes());
        classes.add(first);
        classes.add(second);
        var context = new UnitTestContextDto(base.project(), base.analysis(), classes, base.approvedBusinessRules(),
                base.approvedTestPlans(), base.approvedTestCases(), List.of(), List.of(), List.of());
        String source = "package demo; import model.Appointment; class ServiceTest { @org.junit.jupiter.api.Test void testCase() { "
                + "Appointment.Audit.Status status = Appointment.Audit.Status.B; "
                + "org.junit.jupiter.api.Assertions.assertEquals(status, Appointment.Audit.Status.valueOf(\"B\")); } }";
        var test = new GeneratedUnitTestDto(40L, "ServiceTest", "testCase", "demo", "NEW_TEST", source);
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, new UnitTestResponseDto(List.of(test)))).isEmpty();
        var wrong = new GeneratedUnitTestDto(40L, "ServiceTest", "testCase", "demo", "NEW_TEST",
                source.replace("Status.B", "Status.A").replace("\"B\"", "\"A\""));
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, new UnitTestResponseDto(List.of(wrong))))
                .hasValueSatisfying(message -> assertThat(message).contains("Enum constant 'A'", "model.Appointment.Audit.Status"));
    }

    private java.util.Optional<String> validate(String packageName, String imports, String enumType, String constant) {
        String source = "package " + packageName + "; " + imports + " class ServiceTest {"
                + "@org.junit.jupiter.api.Test void testCase() { " + enumType + " status = " + enumType + "." + constant + "; "
                + "org.junit.jupiter.api.Assertions.assertEquals(status, " + enumType + ".valueOf(\"" + constant + "\")); } }";
        var test = new GeneratedUnitTestDto(40L, "ServiceTest", "testCase", packageName, "NEW_TEST", source);
        return GeneratedUnitTestSemanticValidator.validate(context(), new UnitTestResponseDto(List.of(test)));
    }

    private UnitTestContextDto context() {
        var method = new MethodContextDto(10L, "demo.Service", "send", "void", List.of(), List.of(),
                "public", "public void send() {}", 1, 1, List.of(), List.of(), List.of());
        var service = new ClassContextDto(1L, "demo", "Service", "demo.Service", "SERVICE", "Service.java",
                "package demo; public class Service { public void send() {} }", List.of(), List.of(method));
        var status = new ClassContextDto(2L, "model", "AppointmentStatus", "model.Appointment.AppointmentStatus", "ENUM",
                "Appointment.java", "package model; public class Appointment { public enum AppointmentStatus { SCHEDULED } }",
                List.of(), List.of());
        var appointment = new ClassContextDto(3L, "model", "Appointment", "model.Appointment", "ENTITY", "Appointment.java",
                status.sourceCode(), List.of(), List.of());
        var rule = new BusinessRuleContextDto(20L, 10L, "BR-001", "rule", null, "AI", "APPROVED", false, null);
        var plan = new TestPlanContextItemDto(30L, 20L, List.of(20L), "TP-001", "plan", "plan", "HAPPY_PATH", "APPROVED", false);
        var testCase = new TestCaseContextItemDto(40L, 30L, "TC-001", "HAPPY_PATH", "case", "setup", java.util.Map.of(),
                "result", "HIGH", "BR-001 -> TP-001", "APPROVED", false);
        return new UnitTestContextDto(null, null, List.of(service, appointment, status), List.of(rule), List.of(plan),
                List.of(testCase), List.of(), List.of(), List.of());
    }

    private record Fixture(UnitTestContextDto context, List<GeneratedUnitTestDto> tests) { }
}
