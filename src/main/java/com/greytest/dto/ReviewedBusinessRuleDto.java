package com.greytest.dto;

public record ReviewedBusinessRuleDto(
        Long ruleId,
        String verdict,
        String suggestedDescription,
        String reason) {
}
