package com.greytest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Email(message = "Email không hợp lệ")
        @NotBlank(message = "Email là bắt buộc")
        String email,
        @NotBlank(message = "Mật khẩu là bắt buộc")
        @Size(min = 6, message = "Mật khẩu phải có tối thiểu 6 ký tự")
        String password,
        @NotBlank(message = "Họ tên là bắt buộc")
        String fullName) {
}
