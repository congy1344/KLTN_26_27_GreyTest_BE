package com.greytest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.greytest.dto.CoverageRefinementDto;
import com.greytest.dto.UnitTestDto;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.exception.GenerationInProgressException;
import com.greytest.service.AuthService;
import com.greytest.service.CoverageRefinementService;
import com.greytest.service.CoverageService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.ProjectService;
import com.greytest.service.coverage.SandboxTestRunService;
import com.greytest.entity.AuthUser;

class CoverageControllerTest {

    @Test
    void rejectsCoverageMutationsWhileAnAiJobOwnsTheProject() {
        CoverageService coverage = mock(CoverageService.class);
        GenerationJobService jobs = mock(GenerationJobService.class);
        AuthService auth = mock(AuthService.class);
        SandboxTestRunService sandbox = mock(SandboxTestRunService.class);
        AuthUser actor = new AuthUser();
        actor.setId(10L);
        when(auth.currentUser("Bearer token")).thenReturn(actor);
        CoverageController controller = new CoverageController(
                coverage,
                auth,
                mock(ProjectService.class),
                mock(CoverageRefinementService.class),
                jobs,
                sandbox);
        when(jobs.executeMutation(eq(1L), any(Supplier.class)))
                .thenThrow(new GenerationInProgressException("AI đang chạy."));
        when(jobs.executeAiMutation(
                eq(1L), eq(10L), eq(com.greytest.entity.enums.ActivityAction.COVERAGE_REFINEMENT),
                any(Supplier.class)))
                .thenThrow(new GenerationInProgressException("AI đang chạy."));

        var jacoco = new MockMultipartFile("file", "jacoco.xml", "application/xml", new byte[] {1});
        assertThatThrownBy(() -> controller.upload(1L, jacoco, "account-service", "Bearer token"))
                .isInstanceOf(GenerationInProgressException.class);
        assertThatThrownBy(() -> controller.refine(1L, "account-service", "Bearer token"))
                .isInstanceOf(GenerationInProgressException.class);
        verifyNoInteractions(sandbox);
    }

    @Test
    void verifiesSupplementalTestsInSandboxOnlyAfterTheyAreSaved() {
        GenerationJobService jobs = mock(GenerationJobService.class);
        SandboxTestRunService sandbox = mock(SandboxTestRunService.class);
        var result = new CoverageRefinementDto(2, List.of(), List.of(mock(UnitTestDto.class)));

        assertThat(refine(jobs, sandbox, result)).isSameAs(result);

        // Sandbox cần khóa project để nạp coverage nên chỉ được gọi khi lượt sinh đã nhả khóa.
        var order = inOrder(jobs, sandbox);
        order.verify(jobs).executeAiMutation(eq(1L), eq(10L), eq(ActivityAction.COVERAGE_REFINEMENT), any(Supplier.class));
        order.verify(sandbox).verifyInBackground(1L, "account-service", 10L);
    }

    @Test
    void doesNotOpenASandboxWhenNoSupplementalTestWasSaved() {
        SandboxTestRunService sandbox = mock(SandboxTestRunService.class);

        refine(mock(GenerationJobService.class), sandbox, new CoverageRefinementDto(2, List.of(), List.of()));

        verifyNoInteractions(sandbox);
    }

    private static CoverageRefinementDto refine(
            GenerationJobService jobs, SandboxTestRunService sandbox, CoverageRefinementDto result) {
        AuthService auth = mock(AuthService.class);
        AuthUser actor = new AuthUser();
        actor.setId(10L);
        when(auth.currentUser("Bearer token")).thenReturn(actor);
        when(jobs.executeAiMutation(eq(1L), eq(10L), eq(ActivityAction.COVERAGE_REFINEMENT), any(Supplier.class)))
                .thenReturn(result);
        return new CoverageController(mock(CoverageService.class), auth, mock(ProjectService.class),
                mock(CoverageRefinementService.class), jobs, sandbox)
                .refine(1L, "account-service", "Bearer token");
    }
}
