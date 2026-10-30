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
        repaired = json.writeValueAsString(response);
        var plans = java.util.stream.StreamSupport.stream(response.path("cases").spliterator(), false)
                .map(item -> item.path("plan_id").asLong()).distinct()
                .map(id -> new TestPlanContextItemDto(id, id, List.of(id), "TP-" + id,
                        "Approved plan", "Approved behavior", "HAPPY_PATH", "APPROVED", false)).toList();
        var context = new TestCaseContextDto(new ProjectContextDto(1L, "schema-repair", "PLAN_APPROVED"),
                null, List.of(), List.of(), plans, List.of());
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildTestCaseContext(1L)).thenReturn(context);
        var prompts = mock(PromptManager.class);
        when(prompts.render(anyString(), anyMap())).thenReturn("APPROVED_CONTEXT_AND_ORIGINAL_SCHEMA");
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
    void retriesWithTheRejectedResponseSchemaPathAndCorrectFieldLevel() throws Exception {
        when(client.complete(anyString(), any())).thenReturn(invalid, repaired);

        var result = agent.generateTestCases(1L);

        assertThat(result.cases()).hasSize(10);
        assertThat(result.cases().get(2).planId()).isEqualTo(3002L);
        assertThat(result.cases().get(2).expectedResult()).isEqualTo(expectedResult);
        assertThat(result.cases().get(2).testData()).doesNotContainKey("expected_result");
        var prompts = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(prompts.capture(), any());
        assertThat(prompts.getAllValues().get(1))
                .contains("APPROVED_CONTEXT_AND_ORIGINAL_SCHEMA", invalid, "cases[2].expectedResult", "must not be blank",
                        "expected_result", "test_data", "sibling", "same", "plan_id");
    }

    @Test
    void rejectsTheStillInvalidResponseWithoutRestartingAnotherBatchRetryCycle() {
        when(client.complete(anyString(), any())).thenReturn(invalid);
        var batches = new LlmBatchExecutor(1, 2, 0);
        try {
            assertThatThrownBy(() -> batches.map(List.of(1), ignored -> agent.generateTestCases(1L)))
                    .hasMessageContaining("cases[2].expectedResult").hasMessageContaining("must not be blank");
            verify(client, times(2)).complete(anyString(), any());
        } finally {
            ReflectionTestUtils.invokeMethod(batches, "shutdown");
        }
    }
}
