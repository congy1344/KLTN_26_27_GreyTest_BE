package com.greytest.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Ghi nhận việc cấp quyền truy cập khẩn cấp (Break-Glass) cho Admin vào project của khách hàng
 * nhằm hỗ trợ kỹ thuật hoặc xử lý sự cố có kiểm toán.
 * Áp dụng nguyên tắc Four-Eyes (Separation of Duty): một Admin yêu cầu, một Admin khác phê duyệt.
 */
@Entity
@Table(name = "admin_break_glass_grant")
@Getter
@Setter
public class BreakGlassGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long adminUserId;

    @Column(nullable = false)
    private Long approverUserId;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(nullable = false)
    private Integer durationMinutes;

    @Column(nullable = false, length = 20)
    private String status; // PENDING, APPROVED, REJECTED, REVOKED

    private LocalDateTime grantedAt;

    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
