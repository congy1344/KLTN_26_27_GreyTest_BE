package com.greytest.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ExportReportDto(
        String projectName,
        String status,
        LocalDateTime exportedAt,
        BigDecimal requirementCoverage,
        BigDecimal lineCoverage,
        BigDecimal branchCoverage,
        int totalBusinessRules,
        int totalTestPlans,
        int totalTestCases,
        int totalUnitTests,
        List<BusinessRuleItem> businessRules,
        List<TestPlanItem> testPlans,
        List<TestCaseItem> testCases,
        List<UnitTestItem> unitTests,
        List<TraceabilityRowDto> traceability,
        List<CoverageGapDto> coverageGaps,
        List<String> uncoveredRuleCodes) {

    public record BusinessRuleItem(String ruleCode, String description, String status) {
    }

    public record TestPlanItem(String planCode, String title, String description, String testType, String status) {
    }

    public record TestCaseItem(
            String caseCode,
            String planCode,
            String testType,
            String description,
            String preconditions,
            Map<String, Object> testData,
            String expectedResult,
            String priority,
            String traceSource,
            String status) {
    }

    public record UnitTestItem(
            String caseCode,
            String testClassName,
            String testMethodName,
            String packageName,
            String generationType,
            String filePath) {
    }
}
