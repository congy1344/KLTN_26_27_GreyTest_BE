package com.greytest.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.greytest.dto.CoverageRefinementDto;
import com.greytest.exception.InvalidProjectStatusException;

/**
 * Điều phối một vòng bổ sung Test Case và Unit Test từ coverage gap mới nhất.
 * Không bọc transaction: batch Unit Test chạy ở luồng khác nên Test Case bổ sung phải commit trước;
 * nếu sinh Unit Test lỗi, Test Case vẫn được giữ và người dùng chọn "Tiếp tục sinh".
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

    public CoverageRefinementDto start(Long projectId, String servicePath) {
        var report = coverage.latest(projectId, servicePath)
                .orElseThrow(() -> new InvalidProjectStatusException("Chưa có JaCoCo report để bắt đầu vòng mới."));
        var refinableGaps = report.gaps().stream().filter(gap -> gap.refinable()).toList();
        if (refinableGaps.isEmpty()) {
            throw new InvalidProjectStatusException(
                    "Không còn coverage gap thuộc phạm vi Service Unit Test của GreyTest.");
        }
        int round = report.round() + 1;
        var cases = testCases.generateSupplemental(projectId, servicePath, refinableGaps, round);
        var tests = unitTests.generateSupplemental(
                projectId, servicePath, cases.stream().map(caseDto -> caseDto.id()).toList());
        return new CoverageRefinementDto(round, cases, tests);
    }
}
