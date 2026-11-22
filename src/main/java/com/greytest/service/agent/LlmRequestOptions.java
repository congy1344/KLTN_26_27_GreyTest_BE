package com.greytest.service.agent;

/**
 * Cấu hình riêng cho một lần gọi LLM. responseSchema (tuỳ chọn) là JSON Schema strict của stage; provider hỗ trợ
 * Structured Outputs dùng nó thay cho json_object để output luôn đúng schema.
 */
public record LlmRequestOptions(int maxTokens, java.util.List<String> cacheablePrefixes,
        String schemaName, com.fasterxml.jackson.databind.JsonNode responseSchema) {
    public LlmRequestOptions(int maxTokens) {
        this(maxTokens, java.util.List.of());
    }
    public LlmRequestOptions(int maxTokens, java.util.List<String> cacheablePrefixes) {
        this(maxTokens, cacheablePrefixes, null, null);
    }
    public LlmRequestOptions withCacheablePrefixes(java.util.List<String> prefixes) {
        return new LlmRequestOptions(maxTokens, prefixes, schemaName, responseSchema);
    }
    public LlmRequestOptions withResponseSchema(String name, com.fasterxml.jackson.databind.JsonNode schema) {
        return new LlmRequestOptions(maxTokens, cacheablePrefixes, schema == null ? null : name, schema);
    }
    public LlmRequestOptions {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens phải lớn hơn 0.");
        }
        cacheablePrefixes = java.util.List.copyOf(cacheablePrefixes);
        if (cacheablePrefixes.size() > 2 || cacheablePrefixes.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("Prompt chỉ hỗ trợ tối đa hai prefix không rỗng.");
        }
    }
}
