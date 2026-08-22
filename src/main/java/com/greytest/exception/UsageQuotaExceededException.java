package com.greytest.exception;

public class UsageQuotaExceededException extends RuntimeException {
    public UsageQuotaExceededException(String message) {
        super(message);
    }
}
