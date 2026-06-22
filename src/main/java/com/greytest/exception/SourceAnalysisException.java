package com.greytest.exception;

/** Báo lỗi khi source Java không thể được phân tích đầy đủ và tin cậy. */
public class SourceAnalysisException extends RuntimeException {

    public SourceAnalysisException(String message) {
        super(message);
    }

    public SourceAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
