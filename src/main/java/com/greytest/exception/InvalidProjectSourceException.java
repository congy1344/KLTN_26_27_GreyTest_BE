package com.greytest.exception;

public class InvalidProjectSourceException extends RuntimeException {
    public InvalidProjectSourceException(String reason) {
        super(reason);
    }
}
