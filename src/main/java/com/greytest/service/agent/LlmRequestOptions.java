package com.greytest.service.agent;

/** Cấu hình riêng cho một lần gọi LLM. */
public record LlmRequestOptions(int maxTokens, java.util.List<String> cacheablePrefixes) {

    public LlmRequestOptions(int maxTokens) {
        this(maxTokens, java.util.List.of());
    }

    public LlmRequestOptions withCacheablePrefixes(java.util.List<String> prefixes) {
        return new LlmRequestOptions(maxTokens, prefixes);
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
