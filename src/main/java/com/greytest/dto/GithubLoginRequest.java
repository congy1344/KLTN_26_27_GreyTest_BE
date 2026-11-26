package com.greytest.dto;

import jakarta.validation.constraints.NotBlank;

public record GithubLoginRequest(
        @NotBlank(message = "Mã xác thực GitHub code không được để trống")
        String code,
        @NotBlank(message = "Thiếu state chống giả mạo của GitHub OAuth")
        String state) {
}
