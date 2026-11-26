package com.greytest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStatus;
import com.greytest.dto.UnitTestDto;
import com.greytest.entity.AuthUser;
import com.greytest.service.*;
import com.greytest.service.coverage.SandboxTestRunService;

class UnitTestControllerGenerationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void generationJobDefersCompletionUntilVerificationReturnsIncludingResume(boolean resume) throws Exception {
        var progress = new GenerationProgressService();
        var jobs = new GenerationJobService(Runnable::run, progress, null, null, null);
        var units = mock(UnitTestService.class);
        var sandbox = mock(SandboxTestRunService.class);
        var auth = mock(AuthService.class);
        var projects = mock(ProjectService.class);
        var actor = new AuthUser(); actor.setId(7L);
        when(auth.currentUser("token")).thenReturn(actor);
        when(units.generate(1L, "shop", resume)).thenAnswer(call -> {
            progress.start(1L, GenerationProgressStage.UNIT_TEST, 1, "Đang sinh.");
            progress.completeAfterCommit(1L, GenerationProgressStage.UNIT_TEST, "Hoàn tất: Đã lưu 1 Unit Test.");
            return List.of(mock(UnitTestDto.class));
        });
        var verifying = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(call.<Supplier<Boolean>>getArgument(3).get()).isTrue();
            assertThat(GenerationJobContext.actorUserId()).isEqualTo(7L);
            verifying.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            GenerationJobContext.log("Đã xác minh Unit Test.");
            return null;
        }).when(sandbox).generateAndVerify(eq(1L), eq("shop"), eq(resume), any());
        var controller = new UnitTestController(units, auth, projects, jobs, sandbox);
        var job = java.util.concurrent.CompletableFuture.runAsync(() -> controller.generate(1L, "shop", resume, "token"));
        try {
            assertThat(verifying.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(job).isNotDone();
            var running = progress.get(1L, GenerationProgressStage.UNIT_TEST);
            assertThat(running.status()).isEqualTo(GenerationProgressStatus.RUNNING);
            assertThat(running.percent()).isLessThan(100);
            assertThat(running.logs()).extracting(log -> log.message()).contains("Đã lưu Unit Test; đang chờ xác minh tự động.");
        } finally { finish.countDown(); }
        job.get(5, TimeUnit.SECONDS);
        assertThat(progress.get(1L, GenerationProgressStage.UNIT_TEST).status()).isEqualTo(GenerationProgressStatus.COMPLETED);
        assertThat(jobs.executeMutation(1L, () -> "released")).isEqualTo("released");
    }
}
