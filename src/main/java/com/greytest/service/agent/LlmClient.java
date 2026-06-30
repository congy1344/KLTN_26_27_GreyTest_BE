package com.greytest.service.agent;

public interface LlmClient {

    /** Goi LLM va tra ve raw JSON text. */
    String complete(String prompt);
}
