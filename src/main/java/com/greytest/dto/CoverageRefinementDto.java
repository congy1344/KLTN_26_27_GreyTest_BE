package com.greytest.dto;

import java.util.List;

public record CoverageRefinementDto(
        int round,
        List<TestCaseDto> testCases,
        List<UnitTestDto> unitTests) {
}
