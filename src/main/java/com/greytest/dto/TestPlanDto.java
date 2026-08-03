package com.greytest.dto;

import java.time.LocalDateTime;
import java.util.List;

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
        LocalDateTime createdAt,
        List<Long> coveredRuleIds) {

    public TestPlanDto(
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
        this(id, projectId, businessRuleId, planCode, title, description,
                testType, status, isModified, createdAt, List.of(businessRuleId));
    }
}
