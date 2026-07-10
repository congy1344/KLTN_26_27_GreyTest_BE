package com.greytest.dto;

import java.time.LocalDateTime;

import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.TestType;

public record TestPlanDto(
        Long id,
        Long projectId,
        Long businessRuleId,
        String planCode,
        String title,
        String description,
        TestType testType,
        ReviewStatus status,
        Boolean isModified,
        LocalDateTime createdAt) {
}
