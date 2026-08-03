package com.greytest.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateBusinessRuleRequest(
        Long methodId,
        @NotBlank(message = "Mo ta business rule la bat buoc")
        String description,
        String sourceBranchId) {

    public CreateBusinessRuleRequest(Long methodId, String description) {
        this(methodId, description, null);
    }
}
