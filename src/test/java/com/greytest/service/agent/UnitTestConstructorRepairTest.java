package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import jakarta.validation.Validation;

class UnitTestConstructorRepairTest {
    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void invalidStaticImportReportsTheCaseAndCorrectTypeWitnessSyntax() throws Exception {
        var invalid = invalidResponse();
        assertThat(GeneratedUnitTestSemanticValidator.validate(context(), invalid))
                .hasValueSatisfying(message -> assertThat(message).contains("3723"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "(DoctorRepository) null", "((DoctorRepository) null)"})
    void nullDependenciesReportTheCaseAndEveryArgumentPosition(String argument) throws Exception {
        String source = correctedSource().replace("new DoctorService(doctorRepository, appointmentRepository, departmentRepository, doctorMapper)",
                "new DoctorService(" + argument + ", appointmentRepository, null, doctorMapper)");
        assertThat(GeneratedUnitTestSemanticValidator.validate(context(), response(source)))
                .hasValueSatisfying(message -> assertThat(message).contains("constructor"));
    }

    @Test
    void correctedWiringStillAllowsTheApprovedNullMethodInput() throws Exception {
        assertThat(GeneratedUnitTestSemanticValidator.validate(context(), response(correctedSource()))).isEmpty();
    }

    @Test
    void correctionPromptTargetsAllWiringAndImportErrorsWithoutChangingTheOracle() throws Exception {
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildUnitTestContext(1L)).thenReturn(context());
        var prompts = mock(PromptManager.class);
        when(prompts.render(anyString(), anyMap())).thenReturn("APPROVED_NULL_INPUT_AND_ASSERTIONS");
        String invalid = json.writeValueAsString(invalidResponse());
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any())).thenReturn(invalid, json.writeValueAsString(response(correctedSource())));
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var agent = new AIAgentService(contexts, prompts, client,
                    new GenerationResponseParser(json, validators.getValidator()), mock(AiContextLogService.class));
            var result = agent.generateUnitTests(1L);
            assertThat(result.unitTests()).singleElement().satisfies(test -> assertThat(test.sourceCode())
                    .contains("Long appointmentId = null", "appointmentId cannot be empty", "ArgumentMatchers.<Long>any()"));
            var attempts = ArgumentCaptor.forClass(String.class);
            verify(client, times(2)).complete(attempts.capture(), any());
            assertThat(attempts.getAllValues().get(1)).contains(invalid, "3723", "constructor");
        }
    }

    private UnitTestContextDto context() throws Exception {
        var context = json.treeToValue(fixture().get("context"), UnitTestContextDto.class);
        // Source nội bộ bị @JsonIgnore khi ghi log; khôi phục như context được builder tạo trong runtime.
        var classes = context.classes().stream().map(type -> new ClassContextDto(type.id(), type.packageName(),
                type.className(), type.qualifiedName(), type.classType(), type.filePath(), type.sourceCode(),
                type.annotations(), type.methods())).toList();
        return new UnitTestContextDto(context.project(), context.analysis(), classes, context.approvedBusinessRules(),
                context.approvedTestPlans(), context.approvedTestCases(), context.existingApprovedTestCases(),
                context.previousGeneratedUnitTests(), context.existingTests(), context.supportingResources(), context.contextWarnings());
    }

    private UnitTestResponseDto invalidResponse() throws Exception {
        return json.treeToValue(fixture().get("response"), UnitTestResponseDto.class);
    }

    private UnitTestResponseDto response(String source) throws Exception {
        var original = invalidResponse().unitTests().get(0);
        return new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(original.caseId(), original.testClassName(),
                original.testMethodName(), original.packageName(), original.generationType(), source)));
    }

    private com.fasterxml.jackson.databind.JsonNode fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/agent/doctor-constructor-import.json")) {
            return json.readTree(stream);
        }
    }

    private String correctedSource() throws Exception {
        try (var stream = getClass().getResourceAsStream("/agent/doctor-null-input-corrected.java.txt")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
