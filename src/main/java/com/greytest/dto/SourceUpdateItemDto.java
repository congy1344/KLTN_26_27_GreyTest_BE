package com.greytest.dto;

import java.time.LocalDateTime;

import com.greytest.entity.enums.SourceUpdateAction;
import com.greytest.entity.enums.SourceUpdateReviewStatus;
import com.greytest.entity.enums.SourceUpdateTargetType;

public record SourceUpdateItemDto(
        Long id,
        Long sourceUpdateId,
        SourceUpdateTargetType targetType,
        Long targetId,
        String targetKey,
        SourceUpdateAction action,
        String reason,
        String beforeData,
        String afterData,
        SourceUpdateReviewStatus reviewStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version,
        Long userRevision) {
    public SourceUpdateItemDto(Long id, Long sourceUpdateId, SourceUpdateTargetType targetType, Long targetId,
            String targetKey, SourceUpdateAction action, String reason, String beforeData, String afterData,
            SourceUpdateReviewStatus reviewStatus, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, sourceUpdateId, targetType, targetId, targetKey, action, reason, beforeData, afterData,
                reviewStatus, createdAt, updatedAt, null, null);
    }
}
