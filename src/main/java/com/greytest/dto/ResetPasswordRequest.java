package com.greytest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** DTO đặt lại mật khẩu mới từ đường dẫn token hoặc mã OTP gửi qua Gmail. */
public record ResetPasswordRequest(
        String token,

        String email,

        String otp,

        @NotBlank(message = "Mật khẩu mới không được để trống")
        @Size(min = 6, max = 72, message = "Mật khẩu mới phải từ 6 đến 72 ký tự")
        @Pattern(
                regexp = "^(?=.*[A-Za-z0-9])\\S{6,72}$",
                message = "Mật khẩu mới phải từ 6 đến 72 ký tự và không chứa khoảng trắng"
        )
        String newPassword,

        @NotBlank(message = "Xác nhận mật khẩu mới không được để trống")
        String confirmPassword) {

    public ResetPasswordRequest(String token, String newPassword, String confirmPassword) {
        this(token, null, null, newPassword, confirmPassword);
    }

    public ResetPasswordRequest(String email, String otp, String newPassword, String confirmPassword) {
        this(null, email, otp, newPassword, confirmPassword);
    }
}
