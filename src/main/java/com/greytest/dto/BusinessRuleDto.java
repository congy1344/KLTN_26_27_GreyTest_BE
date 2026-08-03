package com.greytest.dto;

import java.time.LocalDateTime;

import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;

public record BusinessRuleDto(
        Long id,
        Long projectId,
        Long methodId,
        String ruleCode,
        String description,
        String reviewNote,
        String suggestedDescription,
        RuleSource source,
        ReviewStatus status,
        Boolean isModified,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String sourceBranchId) {

    public BusinessRuleDto(
            Long id,
            Long projectId,
            Long methodId,
            String ruleCode,
            String description,
            String reviewNote,
            String suggestedDescription,
            RuleSource source,
            ReviewStatus status,
            Boolean isModified,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this(id, projectId, methodId, ruleCode, description, reviewNote, suggestedDescription,
                source, status, isModified, createdAt, updatedAt, null);
    }
}
