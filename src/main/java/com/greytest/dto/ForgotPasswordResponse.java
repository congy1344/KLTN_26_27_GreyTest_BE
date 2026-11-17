package com.greytest.dto;

/** DTO phản hồi khi người dùng yêu cầu khôi phục mật khẩu qua email. */
public record ForgotPasswordResponse(
        String message,
        boolean mailSent,
        String resetUrl) {
}

