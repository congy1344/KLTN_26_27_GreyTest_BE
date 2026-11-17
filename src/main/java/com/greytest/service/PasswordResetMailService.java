package com.greytest.service;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * Service gửi email khôi phục mật khẩu tới hộp thư Gmail của người dùng.
 * Khi hệ thống đã cấu hình {@code SPRING_MAIL_USERNAME} và {@code SPRING_MAIL_PASSWORD} (App Password Gmail),
 * hệ thống gửi email HTML kèm đường dẫn đặt lại mật khẩu trực tiếp qua SMTP.
 * Trong môi trường phát triển local chưa cấu hình SMTP, đường dẫn được ghi log rõ ràng để phục vụ kiểm thử.
 */
@Slf4j
@Service
public class PasswordResetMailService {

    private final JavaMailSender mailSender;
    private final String mailUsername;
    private final String mailPassword;
    private final String frontendUrl;

    @Autowired
    public PasswordResetMailService(
            @Autowired(required = false) JavaMailSender mailSender,
            @Value("${spring.mail.username:}") String mailUsername,
            @Value("${spring.mail.password:}") String mailPassword,
            @Value("${greytest.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.mailSender = mailSender;
        this.mailUsername = mailUsername != null ? mailUsername.trim() : "";
        this.mailPassword = mailPassword != null ? mailPassword.trim() : "";
        this.frontendUrl = normalizeFrontendUrl(frontendUrl);
    }

    public String buildResetUrl(String resetToken) {
        return frontendUrl + "/reset-password?token=" + resetToken;
    }

    public boolean isSmtpConfigured() {
        return mailSender != null && !mailUsername.isEmpty() && !mailPassword.isEmpty();
    }

    /**
     * Gửi email chứa đường dẫn đặt lại mật khẩu về địa chỉ email đã đăng ký.
     *
     * @return {@code true} nếu đã gửi thành công qua SMTP; {@code false} nếu chưa cấu hình SMTP hoặc gặp lỗi mạng.
     */
    public boolean sendPasswordResetEmail(String recipientEmail, String fullName, String resetUrl) {
        if (!isSmtpConfigured()) {
            log.info("[DEV-MAIL] Chưa cấu hình SPRING_MAIL_USERNAME/SPRING_MAIL_PASSWORD. Đường dẫn đặt lại mật khẩu cho {}: {}",
                    recipientEmail, resetUrl);
            return false;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(mailUsername, "GreyTest AI QA System");
            helper.setTo(recipientEmail);
            helper.setSubject("[GreyTest] Yêu cầu đặt lại mật khẩu tài khoản");
            helper.setText(buildHtmlContent(fullName, resetUrl), true);
            mailSender.send(message);
            log.info("Đã gửi email đặt lại mật khẩu tới {}", recipientEmail);
            return true;
        } catch (Exception ex) {
            log.warn("Không thể gửi email qua SMTP tới {}: {}. Đường dẫn dự phòng: {}",
                    recipientEmail, ex.getMessage(), resetUrl);
            return false;
        }
    }

    private String buildHtmlContent(String fullName, String resetUrl) {
        String displayName = (fullName != null && !fullName.isBlank()) ? fullName.trim() : "Bạn";
        return """
                <div style="font-family:Inter,Segoe UI,Arial,sans-serif;max-width:560px;margin:0 auto;padding:24px;border:1px solid #e2e8f0;border-radius:12px;background:#ffffff;color:#0f172a;">
                  <h2 style="margin:0 0 12px;font-size:20px;color:#0f172a;">GreyTest — Đặt lại mật khẩu</h2>
                  <p style="font-size:14px;line-height:1.6;margin:0 0 16px;">Xin chào <strong>%s</strong>,</p>
                  <p style="font-size:14px;line-height:1.6;margin:0 0 20px;">
                    Hệ thống nhận được yêu cầu đặt lại mật khẩu cho tài khoản GreyTest của bạn.
                    Vui lòng nhấn vào nút bên dưới để nhập mật khẩu mới (đường dẫn có hiệu lực trong <strong>15 phút</strong> và chỉ sử dụng 1 lần):
                  </p>
                  <div style="margin:24px 0;">
                    <a href="%s" style="display:inline-block;padding:12px 24px;background:#2563eb;color:#ffffff;text-decoration:none;border-radius:8px;font-weight:600;font-size:14px;">
                      Đặt lại mật khẩu mới
                    </a>
                  </div>
                  <p style="font-size:12px;line-height:1.5;color:#475569;margin:0 0 8px;">
                    Hoặc sao chép đường dẫn sau vào trình duyệt:
                  </p>
                  <p style="font-size:12px;word-break:break-all;color:#2563eb;margin:0 0 20px;">%s</p>
                  <hr style="border:none;border-top:1px solid #e2e8f0;margin:20px 0;" />
                  <p style="font-size:12px;color:#64748b;margin:0;">
                    Nếu bạn không thực hiện yêu cầu này, vui lòng bỏ qua email này. Mật khẩu hiện tại của bạn vẫn an toàn.
                  </p>
                </div>
                """.formatted(escapeHtml(displayName), resetUrl, resetUrl);
    }

    private static String escapeHtml(String input) {
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String normalizeFrontendUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "http://localhost:3000";
        }
        String trimmed = rawUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}

