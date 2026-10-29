package com.greytest.dto;

import com.greytest.entity.enums.TestType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateTestPlanRequest(
        @NotNull(message = "Business Rule là bắt buộc")
        Long businessRuleId,
        @NotBlank(message = "Tiêu đề Test Plan là bắt buộc")
        String title,
        @NotBlank(message = "Mô tả Test Plan là bắt buộc")
        String description,
        @NotNull(message = "Loại Test Plan là bắt buộc")
        TestType testType) {
}
