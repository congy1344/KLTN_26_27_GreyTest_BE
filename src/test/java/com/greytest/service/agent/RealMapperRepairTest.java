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
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;
import jakarta.validation.Validation;

class RealMapperRepairTest {
    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @ParameterizedTest
    @ValueSource(strings = {"PrescriptionMapper thật", "mapper thật", "real PrescriptionMapper"})
    void missingDependencyFeedbackAlreadyRequiresTheApprovedRealMapper(String role) throws Exception {
        var context = withPreconditions(context(), "PrescriptionService với mock repositories và " + role + ".");
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response("missing")))
                .hasValueSatisfying(message -> assertThat(message).contains("Test case 3839", "PrescriptionMapper",
                        "must be real", "@Spy", "not @Mock"));
    }

    @Test
    void mockedMapperStillViolatesTheApprovedRealMapperContract() throws Exception {
        assertThat(GeneratedUnitTestSemanticValidator.validate(context(), response("mocked")))
                .hasValueSatisfying(message -> assertThat(message).contains("Test case 3839", "PrescriptionMapper", "must be real"));
    }

    @Test
    void realSpyPreservesEveryApprovedMappedField() throws Exception {
        assertThat(GeneratedUnitTestSemanticValidator.validate(context(), correctedResponse())).isEmpty();
    }

    @Test
    void doesNotRequireRealWhenApprovedSetupAllowsMocking() throws Exception {
        var context = withPreconditions(context(), "PrescriptionMapper thật hoặc mock đều được phép; repositories là mock.");
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response("missing")))
                .hasValueSatisfying(message -> assertThat(message).contains("@Mock or @Spy").doesNotContain("must be real"));
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response("mocked"))).isEmpty();
    }

    @Test
    void firstCorrectionKeepsApprovedRealRolesAheadOfGenericMockWiring() throws Exception {
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildUnitTestContext(1L)).thenReturn(context());
        var prompts = mock(PromptManager.class);
        when(prompts.render(anyString(), anyMap())).thenReturn("APPROVED_REAL_MAPPER_CONTEXT");
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any())).thenReturn(json.writeValueAsString(response("missing")),
                json.writeValueAsString(correctedResponse()));
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var agent = new AIAgentService(contexts, prompts, client,
                    new GenerationResponseParser(json, validators.getValidator()), mock(AiContextLogService.class));
            assertThat(agent.generateUnitTests(1L).unitTests()).singleElement()
                    .satisfies(test -> assertThat(test.sourceCode()).contains("new PrescriptionMapper()", "Uống sau bữa ăn"));
            var attempts = ArgumentCaptor.forClass(String.class);
            verify(client, times(2)).complete(attempts.capture(), any());
            assertThat(attempts.getAllValues().get(1)).contains("3839", "PrescriptionMapper", "must be real",
                    "take precedence", "@Spy", "not @Mock");
        }
    }

    private UnitTestContextDto context() throws Exception {
        var c = json.treeToValue(fixture().get("context"), UnitTestContextDto.class);
        var classes = c.classes().stream().map(type -> new ClassContextDto(type.id(), type.packageName(), type.className(),
                type.qualifiedName(), type.classType(), type.filePath(), type.sourceCode(), type.annotations(), type.methods())).toList();
        return new UnitTestContextDto(c.project(), c.analysis(), classes, c.approvedBusinessRules(), c.approvedTestPlans(),
                c.approvedTestCases(), c.existingApprovedTestCases(), c.previousGeneratedUnitTests(), c.existingTests(), c.supportingResources(), c.contextWarnings());
    }

    private UnitTestContextDto withPreconditions(UnitTestContextDto c, String preconditions) {
        var t = c.approvedTestCases().get(0);
        var changed = new TestCaseContextItemDto(t.id(), t.testPlanId(), t.caseCode(), t.testType(), t.description(),
                preconditions, t.testData(), t.expectedResult(), t.priority(), t.traceSource(), t.status(), t.isModified());
        return new UnitTestContextDto(c.project(), c.analysis(), c.classes(), c.approvedBusinessRules(), c.approvedTestPlans(),
                List.of(changed), c.existingApprovedTestCases(), c.previousGeneratedUnitTests(), c.existingTests(), c.supportingResources(), c.contextWarnings());
    }

    private UnitTestResponseDto response(String name) throws Exception {
        return json.treeToValue(fixture().get(name), UnitTestResponseDto.class);
    }

    private UnitTestResponseDto correctedResponse() throws Exception {
        var original = response("missing").unitTests().get(0);
        try (var stream = getClass().getResourceAsStream("/agent/prescription-real-mapper-corrected.java.txt")) {
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(original.caseId(), original.testClassName(),
                    original.testMethodName(), original.packageName(), original.generationType(), source)));
        }
    }

    private com.fasterxml.jackson.databind.JsonNode fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/agent/prescription-real-mapper.json")) {
            return json.readTree(stream);
        }
    }
}
