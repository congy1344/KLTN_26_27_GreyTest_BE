package com.greytest.service.agent;

public class LlmResponseException extends RuntimeException {

    private final boolean retryable;
    private final long retryAfterMillis;

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
        this(message, cause, retryable, 0);
    }

    public LlmResponseException(String message, boolean retryable, long retryAfterMillis) {
        this(message, null, retryable, retryAfterMillis);
    }

    public LlmResponseException(String message, Throwable cause, boolean retryable, long retryAfterMillis) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfterMillis = Math.max(retryAfterMillis, 0);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }
}
