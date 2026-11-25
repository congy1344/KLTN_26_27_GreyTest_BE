package com.greytest.dto;

/** DTO phản hồi khi người dùng yêu cầu khôi phục mật khẩu qua email / OTP. */
public record ForgotPasswordResponse(
        String message,
        boolean mailSent,
        String resetUrl,
        String devOtp) {

    public ForgotPasswordResponse(String message, boolean mailSent, String resetUrl) {
        this(message, mailSent, resetUrl, null);
    }
}
