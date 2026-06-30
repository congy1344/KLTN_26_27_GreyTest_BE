package com.greytest.service.agent;

public class LlmResponseException extends RuntimeException {

    public LlmResponseException(String message) {
        super(message);
    }

    public LlmResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
