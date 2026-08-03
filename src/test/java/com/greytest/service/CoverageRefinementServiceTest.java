package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.greytest.dto.CoverageGapDto;
import com.greytest.dto.CoverageReportDto;
import com.greytest.dto.TestCaseDto;
import com.greytest.dto.UnitTestDto;

@ExtendWith(MockitoExtension.class)
class CoverageRefinementServiceTest {

    @Mock private CoverageService coverageService;
    @Mock private TestCaseService testCaseService;
    @Mock private UnitTestService unitTestService;
    @InjectMocks private CoverageRefinementService service;

    @Test
    void startsNextRoundByAddingCasesThenGeneratingOnlyTheirUnitTests() {
        CoverageGapDto gap = new CoverageGapDto(11L, "OrderService", "createOrder",
                BigDecimal.valueOf(40), BigDecimal.valueOf(50), List.of(12), List.of(12),
                "HIGH", "Cover missed branch", true);
        CoverageReportDto report = new CoverageReportDto(7L, 1L, 1,
                BigDecimal.valueOf(70), BigDecimal.valueOf(60), BigDecimal.valueOf(100),
                null, null, null, 100, 70, 20, 12, LocalDateTime.now(), List.of(gap));
        TestCaseDto testCase = org.mockito.Mockito.mock(TestCaseDto.class);
        UnitTestDto unitTest = org.mockito.Mockito.mock(UnitTestDto.class);
        when(testCase.id()).thenReturn(31L);
        when(coverageService.latest(1L)).thenReturn(java.util.Optional.of(report));
        when(testCaseService.generateSupplemental(1L, List.of(gap), 2)).thenReturn(List.of(testCase));
        when(unitTestService.generateSupplemental(1L, List.of(31L))).thenReturn(List.of(unitTest));

        var result = service.start(1L);

        assertThat(result.round()).isEqualTo(2);
        assertThat(result.testCases()).containsExactly(testCase);
        assertThat(result.unitTests()).containsExactly(unitTest);
        verify(testCaseService).generateSupplemental(1L, List.of(gap), 2);
        verify(unitTestService).generateSupplemental(1L, List.of(31L));
    }

    @Test
    void doesNotStartANewRoundForGapsOutsideServiceScope() {
        CoverageGapDto gap = new CoverageGapDto(12L, "UserController", "getById",
                BigDecimal.ZERO, BigDecimal.valueOf(100), List.of(30), List.of(),
                "HIGH", "Ngoài phạm vi sinh Service Unit Test của GreyTest", false);
        CoverageReportDto report = new CoverageReportDto(7L, 1L, 1,
                BigDecimal.valueOf(70), BigDecimal.valueOf(60), BigDecimal.valueOf(100),
                null, null, null, 100, 70, 20, 12, LocalDateTime.now(), List.of(gap));
        when(coverageService.latest(1L)).thenReturn(java.util.Optional.of(report));

        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(com.greytest.exception.InvalidProjectStatusException.class)
                .hasMessageContaining("Service Unit Test");
        verifyNoInteractions(testCaseService, unitTestService);
    }
}
