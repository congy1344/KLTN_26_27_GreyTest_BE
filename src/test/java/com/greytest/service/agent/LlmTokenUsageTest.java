package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class LlmTokenUsageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsOpenAiUsageDetails() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "usage": {
                    "input_tokens": 120,
                    "input_tokens_details": {"cached_tokens": 20},
                    "output_tokens": 45,
                    "output_tokens_details": {"reasoning_tokens": 15},
                    "total_tokens": 165
                  }
                }
                """);

        LlmTokenUsage usage = LlmTokenUsage.fromOpenAi(response, "prompt", "output");

        assertThat(usage.inputTokens()).isEqualTo(120);
        assertThat(usage.cachedInputTokens()).isEqualTo(20);
        assertThat(usage.outputTokens()).isEqualTo(45);
        assertThat(usage.reasoningTokens()).isEqualTo(15);
        assertThat(usage.totalTokens()).isEqualTo(165);
        assertThat(usage.source()).isEqualTo(LlmTokenUsage.UsageSource.PROVIDER);
    }

    @Test
    void skipsZeroResponsesFieldsWhenProxyAlsoSendsChatCompletionsFields() throws Exception {
        // vietapi trả cả input_tokens/output_tokens = 0 lẫn prompt_tokens/completion_tokens thật.
        JsonNode response = objectMapper.readTree("""
                {
                  "usage": {
                    "prompt_tokens": 2459,
                    "completion_tokens": 80,
                    "total_tokens": 2539,
                    "prompt_tokens_details": {"cached_tokens": 582},
                    "input_tokens": 0,
                    "output_tokens": 0,
                    "input_tokens_details": null
                  }
                }
                """);

        LlmTokenUsage usage = LlmTokenUsage.fromOpenAi(response, "prompt", "output");

        assertThat(usage.inputTokens()).isEqualTo(2459);
        assertThat(usage.outputTokens()).isEqualTo(80);
        assertThat(usage.cachedInputTokens()).isEqualTo(582);
        assertThat(usage.totalTokens()).isEqualTo(2539);
    }

    @Test
    void readsChatCompletionsUsageDetails() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "usage": {
                    "prompt_tokens": 120,
                    "prompt_tokens_details": {"cached_tokens": 20},
                    "completion_tokens": 45,
                    "completion_tokens_details": {"reasoning_tokens": 15},
                    "total_tokens": 165
                  }
                }
                """);

        LlmTokenUsage usage = LlmTokenUsage.fromOpenAi(response, "prompt", "output");

        assertThat(usage.inputTokens()).isEqualTo(120);
        assertThat(usage.cachedInputTokens()).isEqualTo(20);
        assertThat(usage.outputTokens()).isEqualTo(45);
        assertThat(usage.reasoningTokens()).isEqualTo(15);
        assertThat(usage.totalTokens()).isEqualTo(165);
        assertThat(usage.source()).isEqualTo(LlmTokenUsage.UsageSource.PROVIDER);
    }

    @Test
    void readsGoogleUsageMetadata() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "usageMetadata": {
                    "promptTokenCount": 90,
                    "cachedContentTokenCount": 10,
                    "candidatesTokenCount": 30,
                    "thoughtsTokenCount": 12,
                    "totalTokenCount": 132
                  }
                }
                """);

        LlmTokenUsage usage = LlmTokenUsage.fromGoogle(response, "prompt", "output");

        assertThat(usage.inputTokens()).isEqualTo(90);
        assertThat(usage.cachedInputTokens()).isEqualTo(10);
        assertThat(usage.outputTokens()).isEqualTo(30);
        assertThat(usage.reasoningTokens()).isEqualTo(12);
        assertThat(usage.totalTokens()).isEqualTo(132);
        assertThat(usage.source()).isEqualTo(LlmTokenUsage.UsageSource.PROVIDER);
    }

    @Test
    void marksFallbackCountAsEstimatedWhenProviderOmitsUsage() throws Exception {
        JsonNode response = objectMapper.readTree("{}");

        LlmTokenUsage usage = LlmTokenUsage.fromOpenAi(response, "12345678", "1234");

        assertThat(usage.inputTokens()).isEqualTo(2);
        assertThat(usage.outputTokens()).isEqualTo(1);
        assertThat(usage.totalTokens()).isEqualTo(3);
        assertThat(usage.source()).isEqualTo(LlmTokenUsage.UsageSource.ESTIMATED);
    }

    @Test
    void readsGoogleInteractionsUsage() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "usage": {
                    "total_input_tokens": 200,
                    "total_cached_tokens": 40,
                    "total_output_tokens": 60,
                    "total_thought_tokens": 25,
                    "total_tokens": 285
                  }
                }
                """);

        LlmTokenUsage usage = LlmTokenUsage.fromGoogle(response, "prompt", "output");

        assertThat(usage.inputTokens()).isEqualTo(200);
        assertThat(usage.cachedInputTokens()).isEqualTo(40);
        assertThat(usage.outputTokens()).isEqualTo(60);
        assertThat(usage.reasoningTokens()).isEqualTo(25);
        assertThat(usage.totalTokens()).isEqualTo(285);
        assertThat(usage.source()).isEqualTo(LlmTokenUsage.UsageSource.PROVIDER);
    }
}
