package com.greytest.service.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Chuẩn hóa token usage giữa các LLM provider để phục vụ đo lường trong môi trường dev.
 */
public record LlmTokenUsage(
        long inputTokens,
        long cachedInputTokens,
        long outputTokens,
        long reasoningTokens,
        long totalTokens,
        UsageSource source) {

    public enum UsageSource {
        PROVIDER,
        ESTIMATED
    }

    public static LlmTokenUsage fromOpenAi(JsonNode root, String prompt, String output) {
        JsonNode usage = root.path("usage");
        if (!usage.isObject()) return estimated(prompt, output);

        long input = firstLong(usage, "input_tokens", "prompt_tokens");
        long outputCount = firstLong(usage, "output_tokens", "completion_tokens");
        long cached = firstLong(usage.path("input_tokens_details"), "cached_tokens");
        if (cached == 0) cached = firstLong(usage.path("prompt_tokens_details"), "cached_tokens");
        long reasoning = firstLong(usage.path("output_tokens_details"), "reasoning_tokens");
        if (reasoning == 0) reasoning = firstLong(usage.path("completion_tokens_details"), "reasoning_tokens");
        long total = usage.path("total_tokens").asLong(input + outputCount);
        if (input == 0 && outputCount == 0 && total == 0) return estimated(prompt, output);
        return new LlmTokenUsage(input, cached, outputCount, reasoning, total, UsageSource.PROVIDER);
    }

    public static LlmTokenUsage fromGoogle(JsonNode root, String prompt, String output) {
        JsonNode usage = firstObject(root.path("usageMetadata"), root.path("usage_metadata"), root.path("usage"));
        if (usage == null) return estimated(prompt, output);

        long input = firstLong(usage, "promptTokenCount", "prompt_token_count", "input_tokens", "total_input_tokens");
        long cached = firstLong(usage, "cachedContentTokenCount", "cached_content_token_count", "cached_tokens", "total_cached_tokens");
        long outputCount = firstLong(usage, "candidatesTokenCount", "candidates_token_count", "output_tokens", "total_output_tokens");
        long reasoning = firstLong(usage, "thoughtsTokenCount", "thoughts_token_count", "reasoning_tokens", "total_thought_tokens");
        long total = firstLong(usage, "totalTokenCount", "total_token_count", "total_tokens");
        if (input == 0 && outputCount == 0 && reasoning == 0 && total == 0) return estimated(prompt, output);
        if (total == 0) total = input + outputCount + reasoning;
        return new LlmTokenUsage(input, cached, outputCount, reasoning, total, UsageSource.PROVIDER);
    }

    private static LlmTokenUsage estimated(String prompt, String output) {
        long input = estimateTextTokens(prompt);
        long outputCount = estimateTextTokens(output);
        return new LlmTokenUsage(input, 0, outputCount, 0, input + outputCount, UsageSource.ESTIMATED);
    }

    private static JsonNode firstObject(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate.isObject()) return candidate;
        }
        return null;
    }

    private static long firstLong(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.has(field)) return node.path(field).asLong(0);
        }
        return 0;
    }

    private static long estimateTextTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, (long) Math.ceil(text.length() / 4.0));
    }
}
