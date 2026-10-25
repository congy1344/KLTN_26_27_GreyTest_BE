package com.greytest.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateBusinessRuleRequest(
        Long methodId,
        @NotBlank(message = "Mô tả business rule là bắt buộc")
        String description) {
}
