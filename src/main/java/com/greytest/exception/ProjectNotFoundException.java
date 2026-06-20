package com.greytest.exception;

public class ProjectNotFoundException extends RuntimeException {
    public ProjectNotFoundException(Long id) {
        super("Không tìm thấy project với ID: " + id);
    }
}
