package com.greytest.dto;

/** DTO trả về cấu hình Google Sign-In công khai cho Frontend. */
public record GoogleConfigResponse(
        boolean enabled,
        String clientId) {
}
