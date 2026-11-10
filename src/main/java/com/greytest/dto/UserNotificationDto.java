package com.greytest.dto;

import java.time.LocalDateTime;

/**
 * DTO biểu diễn thông báo gửi tới người dùng.
 * Bao gồm các loại:
 * - ADMIN: Thông báo, phản hồi từ quản trị viên hoặc thay đổi quyền/hạn mức
 * - PAYMENT: Kết quả giao dịch thanh toán, nâng cấp hoặc hủy gói dịch vụ
 * - TASK: Các tác vụ sinh test, phân tích AST hoặc JaCoCo hoàn tất
 */
public record UserNotificationDto(
        String id,
        String type,
        String title,
        String message,
        String link,
        LocalDateTime createdAt,
        boolean read
) {
    public UserNotificationDto(String id, String type, String title, String message, String link, LocalDateTime createdAt) {
        this(id, type, title, message, link, createdAt, false);
    }
}

