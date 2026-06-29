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
        RuleSource source,
        ReviewStatus status,
        Boolean isModified,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
