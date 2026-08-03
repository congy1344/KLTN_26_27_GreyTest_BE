package com.greytest.dto;

import java.math.BigDecimal;
import java.util.List;

public record CoverageGapDto(
        Long methodId,
        String className,
        String methodName,
        BigDecimal lineCoverage,
        BigDecimal branchCoverage,
        List<Integer> missedLines,
        List<Integer> missedBranches,
        String risk,
        String suggestion,
        boolean refinable) {
}
