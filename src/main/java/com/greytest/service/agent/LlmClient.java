package com.greytest.service.agent;

public interface LlmClient {

    /** Goi LLM va tra ve raw JSON text. */
    String complete(String prompt);

    /** Gọi LLM với cấu hình riêng; client cũ vẫn dùng được qua cấu hình mặc định. */
    default String complete(String prompt, LlmRequestOptions options) {
        return complete(prompt);
    }
}
