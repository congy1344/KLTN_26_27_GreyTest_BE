package com.greytest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** DTO đổi mật khẩu dành cho người dùng đã đăng nhập trong trang thông tin cá nhân. */
public record ChangePasswordRequest(
        @NotBlank(message = "Mật khẩu hiện tại không được để trống")
        String currentPassword,

        @NotBlank(message = "Mật khẩu mới không được để trống")
        @Size(min = 6, max = 72, message = "Mật khẩu mới phải từ 6 đến 72 ký tự")
        @Pattern(
                regexp = "^(?=.*[A-Za-z0-9])\\S{6,72}$",
                message = "Mật khẩu mới phải từ 6 đến 72 ký tự và không chứa khoảng trắng"
        )
        String newPassword,

        @NotBlank(message = "Xác nhận mật khẩu mới không được để trống")
        String confirmPassword) {
}

