package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AIAgentServiceRetryTest {

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
