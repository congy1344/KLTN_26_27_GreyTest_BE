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

    @Test
    void defaultTimeoutLeavesRoomForLargestStageOutput() {
        var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yml"));
        var properties = yaml.getObject();

        // ~140 token/giây: 12288 token unit-test cần ~88 giây, nên mặc định phải đủ rộng.
        assertThat(properties.getProperty("llm.timeout-seconds")).isEqualTo("${LLM_TIMEOUT_SECONDS:180}");
        assertThat(properties.getProperty("llm.stage-max-tokens.unit-test")).isEqualTo("${LLM_MAX_TOKENS_UNIT_TEST:12288}");
    }
}
