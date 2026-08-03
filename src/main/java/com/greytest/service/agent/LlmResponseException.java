package com.greytest.service.agent;

public class LlmResponseException extends RuntimeException {

    private final boolean retryable;

    public LlmResponseException(String message) {
        this(message, null, false);
    }

    public LlmResponseException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public LlmResponseException(String message, boolean retryable) {
        this(message, null, retryable);
    }

    public LlmResponseException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
