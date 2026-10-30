package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto;
import com.greytest.service.LlmBatchExecutor;

class AIAgentServiceRetryTest {

    @ParameterizedTest
    @ValueSource(strings = {"test-plan", "test-case", "unit-test"})
    void boundsProviderCallsAcrossAgentAndBatchRetries(String stage) {
        var contexts = mock(GenerationContextBuilder.class);
        var project = new ProjectContextDto(1L, "demo", "ANALYZED");
        when(contexts.buildTestPlanContext(1L)).thenReturn(
                new TestPlanContextDto(project, null,
                        List.of(), List.of(), List.of()));
        when(contexts.buildTestCaseContext(1L)).thenReturn(
                new TestCaseContextDto(project, null,
                        List.of(), List.of(), List.of(), List.of()));
        when(contexts.buildUnitTestContext(1L)).thenReturn(
                new UnitTestContextDto(project, null,
                        List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()));
        var prompts = mock(PromptManager.class);
        when(prompts.render(anyString(), anyMap()))
                .thenReturn("prompt");
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any()))
                .thenThrow(new LlmResponseException("OpenAI API HTTP 504", true));
        var agent = new AIAgentService(contexts, prompts, client,
                mock(GenerationResponseParser.class), mock(AiContextLogService.class));
        var batches = new LlmBatchExecutor(1, 2, 0);
        try {
            assertThatThrownBy(() -> batches.map(List.of(1), ignored -> switch (stage) {
                case "test-plan" -> agent.generateTestPlan(1L);
                case "test-case" -> agent.generateTestCases(1L);
                default -> agent.generateUnitTests(1L);
            })).hasRootCauseMessage("OpenAI API HTTP 504");
            verify(client, times(3))
                    .complete(anyString(), any());
        } finally {
            ReflectionTestUtils.invokeMethod(batches, "shutdown");
        }
    }
    @Test
    void retainsSuccessfulBatchesAndAcceptsRecoveryOnLastProviderAttempt() {
        var contexts = mock(GenerationContextBuilder.class);
        var project = new ProjectContextDto(1L, "demo", "ANALYZED");
        when(contexts.buildTestCaseContext(1L)).thenReturn(new TestCaseContextDto(project, null,
                List.of(), List.of(), List.of(), List.of()));
        var prompts = mock(PromptManager.class);
        when(prompts.render(anyString(), anyMap())).thenReturn("prompt");
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any()))
                .thenReturn("{\"cases\":[]}")
                .thenThrow(new LlmResponseException("HTTP 504", true))
                .thenThrow(new LlmResponseException("HTTP 504", true))
                .thenReturn("{\"cases\":[]}");
        var parsed = new TestCaseResponseDto(List.of());
        var parser = mock(GenerationResponseParser.class);
        when(parser.parse(anyString(), eq(TestCaseResponseDto.class))).thenReturn(parsed);
        var agent = new AIAgentService(contexts, prompts, client, parser, mock(AiContextLogService.class));
        var batches = new LlmBatchExecutor(1, 2, 0);
        var saved = new java.util.ArrayList<Integer>();
        try {
            assertThat(batches.map(List.of(1, 2), ignored -> agent.generateTestCases(1L),
                    (batch, output) -> saved.add(batch))).containsExactly(parsed, parsed);
            assertThat(saved).containsExactly(1, 2);
            verify(client, times(4)).complete(anyString(), any());
        } finally {
            ReflectionTestUtils.invokeMethod(batches, "shutdown");
        }
    }
    @Test
    void sharedProviderPoolExhaustionWaitsThirtySecondsAndAllowsThreeAttempts() {
        LlmResponseException exhausted = new LlmResponseException(
                "OpenAI API loi HTTP 429: All available accounts exhausted", true);

        assertThat(AIAgentService.maxAttemptsFor(exhausted)).isEqualTo(3);
        assertThat(AIAgentService.retryDelayMillis(1, exhausted, 250L)).isEqualTo(30_250L);
        assertThat(AIAgentService.retryDelayMillis(2, exhausted, 750L)).isEqualTo(30_750L);
    }

    @Test
    void otherRetryableErrorsKeepTheExistingFastTwoAttemptPolicy() {
        LlmResponseException transientError = new LlmResponseException(
                "OpenAI API loi HTTP 429: rate limit", true);

        assertThat(AIAgentService.maxAttemptsFor(transientError)).isEqualTo(2);
        assertThat(AIAgentService.retryDelayMillis(1, transientError, 250L)).isEqualTo(1_250L);
    }

    @Test
    void hugeRetryAfterIsCappedWithoutOverflow() {
        LlmResponseException transientError = new LlmResponseException(
                "OpenAI API loi HTTP 429: rate limit", true, Long.MAX_VALUE);

        assertThat(AIAgentService.retryDelayMillis(1, transientError, 250L))
                .isEqualTo(60_000L);
    }
}
