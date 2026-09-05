package com.greytest.service.agent;

/** Cấu hình riêng cho một lần gọi LLM. */
public record LlmRequestOptions(int maxTokens) {

    public LlmRequestOptions {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens phai lon hon 0.");
        }
    }
}
