package com.greytest.dto.admin;

import com.greytest.entity.enums.UserRole;
import com.greytest.entity.enums.UserTier;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        String email,

        @NotBlank(message = "Mật khẩu không được để trống")
        @Size(min = 6, message = "Mật khẩu phải có ít nhất 6 ký tự")
        String password,

        String fullName,
        UserRole role,
        UserTier tier,
        @Min(value = 0, message = "Quota không được âm")
        Integer quotaLimit) {}

