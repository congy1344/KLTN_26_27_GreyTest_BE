package com.greytest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Email(message = "Email không hợp lệ")
        @NotBlank(message = "Email là bắt buộc")
        String email,
        @NotBlank(message = "Mật khẩu là bắt buộc")
        String password) {
}
