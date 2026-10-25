package com.greytest.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateBusinessRuleRequest(
        Long methodId,
        @NotBlank(message = "Mô tả business rule là bắt buộc")
        String description,
        String sourceBranchId) {

    public CreateBusinessRuleRequest(Long methodId, String description) {
        this(methodId, description, null);
    }
}
