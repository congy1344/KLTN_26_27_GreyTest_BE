package com.greytest.exception;

/** Báo hiệu một loại tác vụ AI của project đang chạy và không thể khởi động trùng. */
public class GenerationInProgressException extends RuntimeException {

    public GenerationInProgressException(String message) {
        super(message);
    }
}
