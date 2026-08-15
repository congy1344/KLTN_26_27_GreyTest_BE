package com.greytest.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.greytest.exception.GenerationInProgressException;
import com.greytest.service.AuthService;
import com.greytest.service.CoverageRefinementService;
import com.greytest.service.CoverageService;
import com.greytest.service.GenerationJobService;
import com.greytest.service.ProjectService;

class CoverageControllerTest {

    @Test
    void rejectsCoverageMutationsWhileAnAiJobOwnsTheProject() {
        CoverageService coverage = mock(CoverageService.class);
        GenerationJobService jobs = mock(GenerationJobService.class);
        CoverageController controller = new CoverageController(
                coverage,
                mock(AuthService.class),
                mock(ProjectService.class),
                mock(CoverageRefinementService.class),
                jobs);
        when(jobs.executeMutation(eq(1L), any(Supplier.class)))
                .thenThrow(new GenerationInProgressException("AI đang chạy."));

        var jacoco = new MockMultipartFile("file", "jacoco.xml", "application/xml", new byte[] {1});
        assertThatThrownBy(() -> controller.upload(1L, jacoco, "Bearer token"))
                .isInstanceOf(GenerationInProgressException.class);
        assertThatThrownBy(() -> controller.refine(1L, "Bearer token"))
                .isInstanceOf(GenerationInProgressException.class);
    }
}
