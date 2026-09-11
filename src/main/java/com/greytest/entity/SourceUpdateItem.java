package com.greytest.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.greytest.entity.enums.SourceUpdateAction;
import com.greytest.entity.enums.SourceUpdateReviewStatus;
import com.greytest.entity.enums.SourceUpdateTargetType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Từng đề xuất thay đổi cụ thể cho BR/TP/TC/UT trong bản nháp cập nhật.
 */
@Entity
@Table(name = "source_update_item")
@Getter
@Setter
public class SourceUpdateItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sourceUpdateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceUpdateTargetType targetType;

    private Long targetId;

    private String targetKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceUpdateAction action;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String beforeData;

    @Column(columnDefinition = "TEXT")
    private String afterData;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceUpdateReviewStatus reviewStatus = SourceUpdateReviewStatus.PENDING;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
