package com.greytest.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record CoverageReportDto(
        Long id,
        Long projectId,
        int round,
        BigDecimal lineCoverage,
        BigDecimal branchCoverage,
        BigDecimal requirementCoverage,
        BigDecimal previousLineCoverage,
        BigDecimal previousBranchCoverage,
        BigDecimal previousRequirementCoverage,
        Integer totalLines,
        Integer coveredLines,
        Integer totalBranches,
        Integer coveredBranches,
        LocalDateTime uploadedAt,
        List<CoverageGapDto> gaps) {
}
