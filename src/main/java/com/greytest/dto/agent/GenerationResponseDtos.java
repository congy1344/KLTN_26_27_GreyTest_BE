package com.greytest.dto.agent;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class GenerationResponseDtos {

    private GenerationResponseDtos() {
    }

    public record BusinessRuleResponseDto(
            @NotEmpty @Valid List<GeneratedBusinessRuleDto> rules) {
    }

    public record GeneratedBusinessRuleDto(
            @JsonProperty("method_id") @NotNull Long methodId,
            @NotBlank String description,
            @NotBlank @Pattern(regexp = "VALIDATION|BUSINESS_LOGIC|SIDE_EFFECT") String category) {
    }

    public record BusinessRuleReviewResponseDto(
            @JsonProperty("reviewed_rules") @NotNull @Valid List<ReviewedBusinessRuleSuggestionDto> reviewedRules,
            @JsonProperty("suggested_rules") @NotNull @Valid List<GeneratedBusinessRuleDto> suggestedRules) {
    }

    public record ReviewedBusinessRuleSuggestionDto(
            @JsonProperty("rule_id") @NotNull Long ruleId,
            @NotBlank @Pattern(regexp = "OK|NEEDS_REVISION|DUPLICATE|WRONG_METHOD|TOO_VAGUE") String verdict,
            @JsonProperty("suggested_description") String suggestedDescription,
            @NotBlank String reason) {
    }

    public record TestPlanResponseDto(
            @NotEmpty @Valid List<GeneratedTestPlanDto> plans) {
    }

    public record GeneratedTestPlanDto(
            @JsonProperty("rule_id") @NotNull Long ruleId,
            @NotBlank String title,
            @NotBlank String description,
            @JsonProperty("test_type")
            @NotBlank
            @Pattern(regexp = "HAPPY_PATH|BOUNDARY|EXCEPTION|EDGE")
            String testType) {
    }

    public record TestCaseResponseDto(
            @NotEmpty @Valid List<GeneratedTestCaseDto> cases) {
    }

    public record GeneratedTestCaseDto(
            @JsonProperty("plan_id") @NotNull Long planId,
            @JsonProperty("test_type") @NotBlank String testType,
            @NotBlank String description,
            @NotBlank String preconditions,
            @JsonProperty("test_data") @NotNull Map<String, Object> testData,
            @JsonProperty("expected_result") @NotBlank String expectedResult,
            @NotBlank @Pattern(regexp = "HIGH|MEDIUM|LOW") String priority,
            @JsonProperty("trace_source") @NotBlank String traceSource) {
    }

    public record UnitTestResponseDto(
            @JsonProperty("unit_tests") @NotEmpty @Valid List<GeneratedUnitTestDto> unitTests) {
    }

    public record GeneratedUnitTestDto(
            @JsonProperty("case_id") @NotNull Long caseId,
            @JsonProperty("test_class_name") @NotBlank String testClassName,
            @JsonProperty("test_method_name") @NotBlank String testMethodName,
            @JsonProperty("package_name") @NotBlank String packageName,
            @JsonProperty("generation_type")
            @NotBlank
            @Pattern(regexp = "NEW_TEST|IMPROVE_EXISTING_TEST|SUPPLEMENT_EXISTING_TEST")
            String generationType,
            @JsonProperty("source_code") @NotBlank String sourceCode) {
    }
}
