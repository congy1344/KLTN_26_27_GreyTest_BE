package com.greytest.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.CoverageRefinementDto;
import com.greytest.exception.InvalidProjectStatusException;

/**
 * Điều phối một vòng bổ sung Test Case và Unit Test từ coverage gap mới nhất.
 */
@Service
public class CoverageRefinementService {

    private final CoverageService coverage;
    private final TestCaseService testCases;
    private final UnitTestService unitTests;

    public CoverageRefinementService(
            CoverageService coverage,
            TestCaseService testCases,
            UnitTestService unitTests) {
        this.coverage = coverage;
        this.testCases = testCases;
        this.unitTests = unitTests;
    }

    @Transactional
    public CoverageRefinementDto start(Long projectId) {
        var report = coverage.latest(projectId)
                .orElseThrow(() -> new InvalidProjectStatusException("Chưa có JaCoCo report để bắt đầu vòng mới."));
        var refinableGaps = report.gaps().stream().filter(gap -> gap.refinable()).toList();
        if (refinableGaps.isEmpty()) {
            throw new InvalidProjectStatusException(
                    "Không còn coverage gap thuộc phạm vi Service Unit Test của GreyTest.");
        }
        int round = report.round() + 1;
        var cases = testCases.generateSupplemental(projectId, refinableGaps, round);
        var tests = unitTests.generateSupplemental(projectId, cases.stream().map(caseDto -> caseDto.id()).toList());
        return new CoverageRefinementDto(round, cases, tests);
    }
}
