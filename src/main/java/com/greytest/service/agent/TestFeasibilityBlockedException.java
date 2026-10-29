package com.greytest.service.agent;

/** Chặn Apply khi source ứng viên còn Test Case không thể sinh Unit Test theo bằng chứng tĩnh. */
public class TestFeasibilityBlockedException extends LlmResponseException {
    public TestFeasibilityBlockedException(String message) {
        this(message, null);
    }

    public TestFeasibilityBlockedException(String message, Throwable cause) {
        super(message, cause, false);
    }
}
