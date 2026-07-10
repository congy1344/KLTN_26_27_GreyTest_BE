package com.greytest.dto;

import com.greytest.entity.enums.TestType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateTestPlanRequest(
        @NotNull(message = "Business Rule la bat buoc")
        Long businessRuleId,
        @NotBlank(message = "Tieu de Test Plan la bat buoc")
        String title,
        @NotBlank(message = "Mo ta Test Plan la bat buoc")
        String description,
        @NotNull(message = "Loai Test Plan la bat buoc")
        TestType testType) {
}
