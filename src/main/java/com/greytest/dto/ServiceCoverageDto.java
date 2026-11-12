package com.greytest.dto;

import java.math.BigDecimal;

/**
 * Coverage chỉ tính trên các class Service, là phạm vi GreyTest sinh Unit Test.
 * branchCoverage là null khi các class Service không có nhánh nào.
 */
public record ServiceCoverageDto(
        int totalLines,
        int coveredLines,
        int totalBranches,
        int coveredBranches,
        BigDecimal lineCoverage,
        BigDecimal branchCoverage) {
}
