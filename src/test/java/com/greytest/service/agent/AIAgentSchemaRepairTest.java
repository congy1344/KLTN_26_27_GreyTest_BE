package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.LlmBatchExecutor;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

class AIAgentSchemaRepairTest {
    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final LlmClient client = mock(LlmClient.class);
    private ValidatorFactory validators;
    private AIAgentService agent;
    private String invalid;
    private String repaired;
    private String expectedResult;

    @BeforeEach
    void setUp() throws Exception {
        try (var stream = getClass().getResourceAsStream("/agent/schema/nested-expected-result.json")) {
            invalid = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var response = json.readTree(invalid);
        var third = (ObjectNode) response.path("cases").get(2);
        expectedResult = third.path("test_data").path("expected_result").asText();
        third.put("expected_result", expectedResult);
        ((ObjectNode) third.get("test_data")).remove("expected_result");
        long repairedPlan = third.path("plan_id").asLong();
        var onlyRepaired = json.createObjectNode();
        var repairedCases = onlyRepaired.putArray("cases");
        response.path("cases").forEach(item -> { if (item.path("plan_id").asLong() == repairedPlan) repairedCases.add(item); });
        repaired = json.writeValueAsString(onlyRepaired);
        var plans = java.util.stream.StreamSupport.stream(response.path("cases").spliterator(), false)
                .map(item -> item.path("plan_id").asLong()).distinct()
                .map(id -> new TestPlanContextItemDto(id, id, List.of(id), "TP-" + id,
                        "Approved plan", "Approved behavior", "HAPPY_PATH", "APPROVED", false)).toList();
        var context = context(plans);
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildTestCaseContext(1L)).thenReturn(context);
        when(contexts.buildTestCaseContext(eq(1L), anySet())).thenAnswer(call -> {
            java.util.Set<Long> ids = call.getArgument(1);
            return context(plans.stream().filter(plan -> ids.contains(plan.id())).toList());
        });
        var prompts = mock(PromptManager.class);
        when(prompts.renderPrompt(anyString(), anyMap())).thenReturn(
                new PromptManager.RenderedPrompt("APPROVED_CONTEXT_AND_ORIGINAL_SCHEMA", List.of()));
        validators = Validation.buildDefaultValidatorFactory();
        var parser = new GenerationResponseParser(json, validators.getValidator());
        agent = new AIAgentService(contexts, prompts, client, parser, mock(AiContextLogService.class));
    }

    @AfterEach
    void cleanUp() {
        validators.close();
        GenerationJobContext.clear();
    }

    @Test
    void repairsOnlyThePlanWithTheRejectedSchemaPathAndKeepsTheOtherPlans() throws Exception {
        when(client.complete(anyString(), any())).thenReturn(invalid, repaired);

        var result = agent.generateTestCases(1L);

        assertThat(result.cases()).hasSize(10);
        var fixed = result.cases().stream().filter(item -> expectedResult.equals(item.expectedResult())).findFirst().orElseThrow();
        assertThat(fixed.planId()).isEqualTo(3002L);
        assertThat(fixed.testData()).doesNotContainKey("expected_result");
        var prompts = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(prompts.capture(), any());
        assertThat(prompts.getAllValues().get(1))
                .contains("APPROVED_CONTEXT_AND_ORIGINAL_SCHEMA", "cases[2].expectedResult", "must not be blank",
                        "expected_result", "test_data", "sibling", "same", "plan_id");
    }

    @Test
    void keepsValidPlansAndRetriesOnlyThePlanWithTheInvalidCase() throws Exception {
        var plans = List.of(plan(1L), plan(2L));
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildTestCaseContext(eq(1L), eq(java.util.Set.of(1L, 2L)))).thenReturn(context(plans));
        when(contexts.buildTestCaseContext(eq(1L), eq(java.util.Set.of(2L)))).thenReturn(context(List.of(plans.get(1))));
        var prompts = mock(PromptManager.class);
        when(prompts.renderPrompt(anyString(), anyMap())).thenAnswer(call -> {
            Object scope = ((java.util.Map<?, ?>) call.getArgument(1)).get("context_json");
            return new PromptManager.RenderedPrompt("PLANS=" + ((TestCaseContextDto) scope).approvedTestPlans().size(), List.of());
        });
        var keeping = new AIAgentService(contexts, prompts, client, new GenerationResponseParser(json, validators.getValidator()), mock(AiContextLogService.class));
        when(client.complete(anyString(), any())).thenReturn(
                "{\"cases\":[" + validCase(1L, "kept") + "," + validCase(2L, "").replace("\"expected_result\":\"ok\"", "\"expected_result\":\"\"") + "]}",
                "{\"cases\":[" + validCase(2L, "repaired") + "]}");

        var result = keeping.generateTestCases(1L, java.util.Set.of(1L, 2L));

        assertThat(result.cases()).extracting(item -> item.planId()).containsExactly(1L, 2L);
        assertThat(result.cases()).extracting(item -> item.description()).containsExactly("kept", "repaired");
        var sent = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(sent.capture(), any());
        assertThat(sent.getAllValues().get(1)).contains("PLANS=1").doesNotContain("kept");
    }

    @Test
    void returnsTheKeptPlansWhenRepairAttemptsRunOut() throws Exception {
        var plans = List.of(plan(1L), plan(2L));
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildTestCaseContext(eq(1L), eq(java.util.Set.of(1L, 2L)))).thenReturn(context(plans));
        when(contexts.buildTestCaseContext(eq(1L), eq(java.util.Set.of(2L)))).thenReturn(context(List.of(plans.get(1))));
        var prompts = mock(PromptManager.class);
        when(prompts.renderPrompt(anyString(), anyMap())).thenReturn(new PromptManager.RenderedPrompt("P", List.of()));
        var keeping = new AIAgentService(contexts, prompts, client, new GenerationResponseParser(json, validators.getValidator()), mock(AiContextLogService.class));
        when(client.complete(anyString(), any())).thenReturn(
                "{\"cases\":[" + validCase(1L, "kept") + "]}",
                "{\"cases\":[" + validCase(2L, "bad").replace("\"expected_result\":\"ok\"", "\"expected_result\":\"\"") + "]}");

        var result = keeping.generateTestCases(1L, java.util.Set.of(1L, 2L));

        assertThat(result.cases()).extracting(item -> item.planId()).containsExactly(1L);
        verify(client, times(2)).complete(anyString(), any());
    }

    /**
     * Response thật (project course-registration, 15/11/2026): khoá mock có dấu phẩy làm AI đóng object sớm, phần còn lại
     * của case thành chuỗi rời trong mảng cases. JSON vẫn đúng cú pháp, nên đây là lỗi schema chứ không phải JSON hỏng.
     */
    @Test
    void keepsValidPlansWhenAMockKeyWithACommaBreaksOneCaseObject() throws Exception {
        var plans = List.of(plan(1L), plan(2L), plan(3L));
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildTestCaseContext(eq(1L), eq(java.util.Set.of(1L, 2L, 3L)))).thenReturn(context(plans));
        when(contexts.buildTestCaseContext(eq(1L), eq(java.util.Set.of(2L)))).thenReturn(context(List.of(plans.get(1))));
        var prompts = mock(PromptManager.class);
        when(prompts.renderPrompt(anyString(), anyMap())).thenAnswer(call -> {
            Object scope = ((java.util.Map<?, ?>) call.getArgument(1)).get("context_json");
            return new PromptManager.RenderedPrompt("PLANS=" + ((TestCaseContextDto) scope).approvedTestPlans().size(), List.of());
        });
        var keeping = new AIAgentService(contexts, prompts, client, new GenerationResponseParser(json, validators.getValidator()), mock(AiContextLogService.class));
        String broken = "{\"plan_id\":2,\"test_type\":\"EXCEPTION\",\"description\":\"x\",\"preconditions\":\"ready\","
                + "\"test_data\":{\"input\":{\"studentId\":102},\"mocks\":{\"enrollmentRepository.existsByStudentIdAndStatus(102, \" : \"ENROLLED)\"}}}"
                + ",\"expected_result\",\"priority\",\"trace_source\"";
        when(client.complete(anyString(), any())).thenReturn(
                "{\"cases\":[" + validCase(1L, "kept-1") + "," + broken + "," + validCase(3L, "kept-3") + "]}",
                "{\"cases\":[" + validCase(2L, "repaired") + "]}");

        var result = keeping.generateTestCases(1L, java.util.Set.of(1L, 2L, 3L));

        assertThat(result.cases()).extracting(item -> item.description()).containsExactlyInAnyOrder("kept-1", "kept-3", "repaired");
        var sent = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(sent.capture(), any());
        assertThat(sent.getAllValues().get(1)).contains("PLANS=1", "cases[2]", "must be a JSON object", "test_data.mocks")
                .doesNotContain("kept-1", "không phải JSON hợp lệ");
    }

    @Test
    void reportsATypeMismatchAsSchemaErrorWithItsPathAndKeepsSyntaxErrorsAsInvalidJson() {
        var parser = new GenerationResponseParser(json, validators.getValidator());
        assertThatThrownBy(() -> parser.parse("{\"cases\":[" + validCase(1L, "a") + ",\"expected_result\"]}",
                com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto.class))
                .hasMessageStartingWith("LLM response không đúng schema:").hasMessageContaining("cases[1]");
        assertThatThrownBy(() -> parser.parse("{\"cases\":[", com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto.class))
                .hasMessageContaining("không phải JSON hợp lệ");
    }

    private TestPlanContextItemDto plan(long id) {
        return new TestPlanContextItemDto(id, id, List.of(id), "TP-" + id, "Approved plan", "Approved behavior", "HAPPY_PATH", "APPROVED", false);
    }

    private TestCaseContextDto context(List<TestPlanContextItemDto> plans) {
        return new TestCaseContextDto(new ProjectContextDto(1L, "schema-repair", "PLAN_APPROVED"), null, List.of(), List.of(), plans, List.of());
    }

    private String validCase(long planId, String description) {
        return "{\"plan_id\":" + planId + ",\"test_type\":\"HAPPY_PATH\",\"description\":\"" + (description.isEmpty() ? "x" : description)
                + "\",\"preconditions\":\"ready\",\"test_data\":{},\"expected_result\":\"ok\",\"priority\":\"HIGH\",\"trace_source\":\"TP\"}";
    }

    @Test
    void rejectsTheStillInvalidResponseWithoutRestartingAnotherBatchRetryCycle() throws Exception {
        var everyCaseInvalid = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(invalid);
        everyCaseInvalid.path("cases").forEach(item -> ((ObjectNode) item).remove("expected_result"));
        when(client.complete(anyString(), any())).thenReturn(json.writeValueAsString(everyCaseInvalid));
        var batches = new LlmBatchExecutor(1, 2, 0);
        try {
            assertThatThrownBy(() -> batches.map(List.of(1), ignored -> agent.generateTestCases(1L)))
                    .hasMessageContaining("expectedResult").hasMessageContaining("must not be blank");
            verify(client, times(2)).complete(anyString(), any());
        } finally {
            ReflectionTestUtils.invokeMethod(batches, "shutdown");
        }
    }
}
