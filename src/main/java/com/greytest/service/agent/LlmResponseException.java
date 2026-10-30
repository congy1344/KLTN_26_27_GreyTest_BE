package com.greytest.service.agent;

public class LlmResponseException extends RuntimeException {

    /** Phân loại lỗi của pipeline để quyết định xử lý đúng tầng, không retry LLM khi lỗi thuộc resolver/cache. */
    public enum ErrorClassification {
        LLM_OUTPUT_INVALID,
        SOURCE_ANALYSIS_UNRESOLVED,
        DEPENDENCY_RESOLUTION_FAILED,
        CACHE_PROVENANCE_INCOMPLETE,
        VALIDATOR_INTERNAL_ERROR,
        COMPILATION_FAILURE,
        RUNTIME_TEST_FAILURE;

        public boolean isLlmRetryable() {
            return this == LLM_OUTPUT_INVALID;
        }
    }

    private final boolean retryable;
    private final long retryAfterMillis;
    private final ErrorClassification classification;

    public LlmResponseException(String message) {
        this(message, null, false, 0, ErrorClassification.VALIDATOR_INTERNAL_ERROR);
    }

    public LlmResponseException(String message, Throwable cause) {
        this(message, cause, false, 0, ErrorClassification.VALIDATOR_INTERNAL_ERROR);
    }

    public LlmResponseException(String message, boolean retryable) {
        this(message, null, retryable, 0, retryable ? ErrorClassification.LLM_OUTPUT_INVALID : ErrorClassification.VALIDATOR_INTERNAL_ERROR);
    }

    public LlmResponseException(String message, ErrorClassification classification) {
        this(message, null, classification != null && classification.isLlmRetryable(), 0, classification);
    }

    public LlmResponseException(String message, Throwable cause, boolean retryable) {
        this(message, cause, retryable, 0, retryable ? ErrorClassification.LLM_OUTPUT_INVALID : ErrorClassification.VALIDATOR_INTERNAL_ERROR);
    }

    public LlmResponseException(String message, boolean retryable, long retryAfterMillis) {
        this(message, null, retryable, retryAfterMillis, retryable ? ErrorClassification.LLM_OUTPUT_INVALID : ErrorClassification.VALIDATOR_INTERNAL_ERROR);
    }

    public LlmResponseException(String message, Throwable cause, boolean retryable, long retryAfterMillis) {
        this(message, cause, retryable, retryAfterMillis, retryable ? ErrorClassification.LLM_OUTPUT_INVALID : ErrorClassification.VALIDATOR_INTERNAL_ERROR);
    }

    public LlmResponseException(String message, Throwable cause, boolean retryable, long retryAfterMillis, ErrorClassification classification) {
        super(message, cause);
        this.classification = classification != null ? classification : (retryable ? ErrorClassification.LLM_OUTPUT_INVALID : ErrorClassification.VALIDATOR_INTERNAL_ERROR);
        this.retryable = retryable && this.classification.isLlmRetryable();
        this.retryAfterMillis = Math.max(retryAfterMillis, 0);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }

    public ErrorClassification getClassification() {
        return classification;
    }
}
