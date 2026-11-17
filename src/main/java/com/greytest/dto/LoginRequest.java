package com.greytest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** DTO yêu cầu đăng nhập với kiểm tra định dạng Email, giới hạn độ dài và Regex mật khẩu. */
public record LoginRequest(
        @NotBlank(message = "Email không được để trống")
        @Size(max = 254, message = "Email không được vượt quá 254 ký tự")
        @Email(message = "Email không đúng định dạng")
        @Pattern(
                regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$",
                message = "Email không đúng định dạng"
        )
        String email,

        @NotBlank(message = "Mật khẩu không được để trống")
        @Size(min = 6, max = 72, message = "Mật khẩu phải từ 6 đến 72 ký tự")
        @Pattern(
                regexp = "^(?=.*[A-Za-z0-9])\\S{6,72}$",
                message = "Mật khẩu phải từ 6 đến 72 ký tự và không chứa khoảng trắng"
        )
        String password) {
}
