package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmStageTokenLimitsTest {

    @Test
    void returnsLimitConfiguredForEachStageAndFallback() {
        LlmStageTokenLimits limits = new LlmStageTokenLimits(900, 100, 200, 300, 400, 500, 600);

        assertThat(limits.optionsFor("business-rule").maxTokens()).isEqualTo(100);
        assertThat(limits.optionsFor("unit-test").maxTokens()).isEqualTo(500);
        assertThat(limits.optionsFor("unknown").maxTokens()).isEqualTo(900);
    }
}
