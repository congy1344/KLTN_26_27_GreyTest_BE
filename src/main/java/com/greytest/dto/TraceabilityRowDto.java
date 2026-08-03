package com.greytest.dto;

public record TraceabilityRowDto(
        Long ruleId,
        String ruleCode,
        String ruleDescription,
        Long planId,
        String planCode,
        String planTitle,
        String testType,
        Long caseId,
        String caseCode,
        String caseDescription,
        Long unitTestId,
        String unitTestName) {
}
