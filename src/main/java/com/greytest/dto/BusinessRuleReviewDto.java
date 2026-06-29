package com.greytest.dto;

import java.util.List;

public record BusinessRuleReviewDto(
        List<ReviewedBusinessRuleDto> reviewedRules,
        List<BusinessRuleDto> suggestedRules) {
}
