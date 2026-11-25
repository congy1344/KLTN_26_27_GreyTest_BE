package com.greytest.dto;

import jakarta.validation.constraints.NotBlank;

/** DTO nhận Google ID Token từ Google Identity Services (GIS) ở Frontend. */
public record GoogleLoginRequest(
        @NotBlank(message = "Google ID token không được để trống")
        String credential) {
}
