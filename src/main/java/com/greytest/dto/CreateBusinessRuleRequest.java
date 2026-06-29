package com.greytest.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateBusinessRuleRequest(
        Long methodId,
        @NotBlank(message = "Mo ta business rule la bat buoc")
        String description) {
}
