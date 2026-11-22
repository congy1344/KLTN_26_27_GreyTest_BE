package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;

/** Hồi quy NEXUS: giữ plan đúng và chỉ yêu cầu AI sinh rule còn thiếu/sai method. */
class TestPlanPartialRecoveryTest {
    @Test
    void rejectsNullPlanAndNullCoveredRuleAsRetryableSchemaErrors() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var parser = new GenerationResponseParser(new com.fasterxml.jackson.databind.ObjectMapper(), factory.getValidator());
            for (String output : List.of("{\"plans\":[null]}",
                    "{\"plans\":[{\"method_id\":107,\"rule_id\":140,\"covered_rule_ids\":[140,142,null],"
                    + "\"title\":\"Plan\",\"description\":\"Approved\",\"test_type\":\"HAPPY_PATH\"}]}")) {
                assertThatThrownBy(() -> parser.parse(output, TestPlanResponseDto.class))
                        .isInstanceOfSatisfying(LlmResponseException.class, error -> {
                            assertThat(error.isRetryable()).isTrue();
                            assertThat(error.getMessage()).contains("schema");
                        });
            }
        }
    }

    @Test
    void preservesValidPlansAndNarrowsRepairWithoutReassigningRules() {
        var contexts = mock(GenerationContextBuilder.class);
        var rules = List.of(rule(140L, 107L), rule(141L, 107L), rule(142L, 108L));
        when(contexts.buildTestPlanContext(1L)).thenReturn(new TestPlanContextDto(null, null, List.of(), rules, List.of()));
        var prompts = mock(PromptManager.class);
        when(prompts.renderPrompt(anyString(), anyMap())).thenReturn(new PromptManager.RenderedPrompt("context", List.of()));
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any())).thenReturn("first", "repair");
        var parser = mock(GenerationResponseParser.class);
        var kept = plan(108L, 142L, List.of(142L));
        when(parser.parse("first", TestPlanResponseDto.class)).thenReturn(new TestPlanResponseDto(List.of(
                kept, plan(108L, 140L, List.of(140L, 141L)))));
        when(parser.parse("repair", TestPlanResponseDto.class)).thenReturn(new TestPlanResponseDto(List.of(
                plan(107L, 140L, List.of(140L, 141L)))));
        var agent = new AIAgentService(contexts, prompts, client, parser, mock(AiContextLogService.class));

        var result = agent.generateTestPlan(1L);

        assertThat(result.plans()).containsExactly(kept, plan(107L, 140L, List.of(140L, 141L)));
        var sent = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(sent.capture(), any());
        assertThat(sent.getAllValues().get(1)).contains("140 -> 107", "141 -> 107").doesNotContain("142 -> 108");
    }

    @Test
    void refusesToReturnIncompletePlansAfterRepairBudgetIsExhausted() {
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildTestPlanContext(1L)).thenReturn(new TestPlanContextDto(null, null, List.of(),
                List.of(rule(140L, 107L)), List.of()));
        var prompts = mock(PromptManager.class);
        when(prompts.renderPrompt(anyString(), anyMap())).thenReturn(new PromptManager.RenderedPrompt("context", List.of()));
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any())).thenReturn("bad");
        var parser = mock(GenerationResponseParser.class);
        when(parser.parse("bad", TestPlanResponseDto.class)).thenReturn(new TestPlanResponseDto(List.of()));
        var agent = new AIAgentService(contexts, prompts, client, parser, mock(AiContextLogService.class));
        assertThatThrownBy(() -> agent.generateTestPlan(1L)).isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("140");
        verify(client, times(2)).complete(anyString(), any());
    }

    private static BusinessRuleContextDto rule(long id, long method) {
        return new BusinessRuleContextDto(id, method, "BR-" + id, "Approved rule", null, "AI_GENERATED", "APPROVED", false, null);
    }

    private static GeneratedTestPlanDto plan(long method, long rule, List<Long> ids) {
        return new GeneratedTestPlanDto(method, rule, ids, "Plan", "Approved behavior", "HAPPY_PATH");
    }
}
