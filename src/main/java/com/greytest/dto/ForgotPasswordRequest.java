package com.greytest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** DTO yêu cầu gửi email đặt lại mật khẩu về hộp thư Gmail đã đăng ký. */
public record ForgotPasswordRequest(
        @NotBlank(message = "Email không được để trống")
        @Size(max = 254, message = "Email không được vượt quá 254 ký tự")
        @Email(message = "Email không đúng định dạng")
        @Pattern(
                regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$",
                message = "Email không đúng định dạng"
        )
        String email) {
}

