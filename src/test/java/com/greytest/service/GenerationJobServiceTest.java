package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStatus;
import com.greytest.exception.GenerationInProgressException;
import com.greytest.service.agent.LlmResponseException;
import com.greytest.repository.ProjectRepository;
import com.greytest.entity.enums.ActivityAction;

class GenerationJobServiceTest {

    @Test
    void attributesGenerationAndWorkerContextToAuthenticatedActor() {
        ManualExecutor executor = new ManualExecutor();
        UserActivityService activity = mock(UserActivityService.class);
        GenerationJobService jobs = new GenerationJobService(
                executor, new GenerationProgressService(), mock(ProjectRepository.class), activity);
        java.util.concurrent.atomic.AtomicReference<Long> actorInWorker = new java.util.concurrent.atomic.AtomicReference<>();

        jobs.submit(1L, 99L, GenerationProgressStage.TEST_PLAN,
                () -> actorInWorker.set(GenerationJobContext.actorUserId()));
        executor.runNext();

        assertThat(actorInWorker.get()).isEqualTo(99L);
        verify(activity).record(
                99L, ActivityAction.GENERATE_TEST_PLAN, 1L,
                java.util.Map.of("stage", "TEST_PLAN"));
    }

    @Test
    void returnsImmediatelyAndRunsQueuedTaskLater() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);

        var accepted = jobs.submit(1L, GenerationProgressStage.TEST_PLAN, () -> {
            progress.start(1L, GenerationProgressStage.TEST_PLAN, 1, "Đang chạy.");
            progress.complete(1L, GenerationProgressStage.TEST_PLAN, "Hoàn tất.");
        });

        assertThat(accepted.status()).isEqualTo(GenerationProgressStatus.QUEUED);
        assertThat(progress.get(1L, GenerationProgressStage.TEST_PLAN).status())
                .isEqualTo(GenerationProgressStatus.QUEUED);

        executor.runNext();

        assertThat(progress.get(1L, GenerationProgressStage.TEST_PLAN).status())
                .isEqualTo(GenerationProgressStatus.COMPLETED);
    }

    @Test
    void rejectsSecondJobForTheSameProjectWhileFirstIsQueued() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);

        jobs.submit(1L, GenerationProgressStage.TEST_PLAN, () -> {});

        assertThatThrownBy(() -> jobs.submit(1L, GenerationProgressStage.TEST_CASE, () -> {}))
                .isInstanceOf(GenerationInProgressException.class)
                .hasMessageContaining("Log");
    }

    @Test
    void rejectsArtifactMutationWhileProjectJobIsQueued() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);
        jobs.submit(1L, GenerationProgressStage.TEST_PLAN, () -> {});

        assertThatThrownBy(() -> jobs.executeMutation(1L, () -> "stale change"))
                .isInstanceOf(GenerationInProgressException.class)
                .hasMessageContaining("thay đổi dữ liệu");

        executor.runNext();
        assertThat(jobs.executeMutation(1L, () -> "safe change")).isEqualTo("safe change");
    }

    @Test
    void mutationMarkerPreventsJobFromStartingUntilMutationCompletes() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);

        jobs.executeMutation(1L, () -> assertThatThrownBy(
                () -> jobs.submit(1L, GenerationProgressStage.BUSINESS_RULE, () -> {}))
                .isInstanceOf(GenerationInProgressException.class));

        assertThat(jobs.submit(1L, GenerationProgressStage.BUSINESS_RULE, () -> {}).status())
                .isEqualTo(GenerationProgressStatus.QUEUED);
    }

    @Test
    void completesQueuedJobWhenThereIsNoNewArtifactToGenerate() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);
        jobs.submit(1L, GenerationProgressStage.BUSINESS_RULE, () -> {});

        executor.runNext();

        var snapshot = progress.get(1L, GenerationProgressStage.BUSINESS_RULE);
        assertThat(snapshot.status()).isEqualTo(GenerationProgressStatus.COMPLETED);
        assertThat(snapshot.logs().get(snapshot.logs().size() - 1).message())
                .contains("không có dữ liệu mới");
    }

    @Test
    void keepsDetailedFailureRecordedByArtifactService() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);
        jobs.submit(1L, GenerationProgressStage.BUSINESS_RULE, () -> {
            progress.start(1L, GenerationProgressStage.BUSINESS_RULE, 2, "Đang sinh.");
            progress.fail(1L, GenerationProgressStage.BUSINESS_RULE,
                    "Dừng ở batch 2. Đã lưu 1 Business Rule từ các batch trước.");
            throw new IllegalStateException("database unavailable");
        });

        executor.runNext();

        var snapshot = progress.get(1L, GenerationProgressStage.BUSINESS_RULE);
        assertThat(snapshot.status()).isEqualTo(GenerationProgressStatus.FAILED);
        assertThat(snapshot.steps().get(0).errorMessage())
                .contains("Đã lưu 1 Business Rule từ các batch trước.");
        assertThat(snapshot.logs().get(snapshot.logs().size() - 1).message())
                .doesNotContain("Tác vụ AI không thể hoàn tất");
    }

    @Test
    void convertsRawQuotaFailureIntoFriendlyProgressMessage() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);
        jobs.submit(1L, GenerationProgressStage.UNIT_TEST, () -> {
            progress.start(1L, GenerationProgressStage.UNIT_TEST, 1, "Đang gọi Gemini.");
            throw new LlmResponseException("HTTP 429 quota raw-json", true);
        });

        executor.runNext();

        var snapshot = progress.get(1L, GenerationProgressStage.UNIT_TEST);
        assertThat(snapshot.status()).isEqualTo(GenerationProgressStatus.FAILED);
        assertThat(snapshot.steps().get(0).errorMessage())
                .contains("Gemini đang giới hạn")
                .doesNotContain("raw-json");
    }

    @Test
    void keepsActionableUnitTestRecoveryFailureInProgressMessage() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);
        jobs.submit(1L, GenerationProgressStage.UNIT_TEST, () -> {
            progress.start(1L, GenerationProgressStage.UNIT_TEST, 1, "Dang sinh Unit Test.");
            throw new LlmResponseException(
                    "Khong the sinh Unit Test hop le cho Test Case ID 258 sau khi da tu chia batch va thu lai.");
        });

        executor.runNext();

        var snapshot = progress.get(1L, GenerationProgressStage.UNIT_TEST);
        assertThat(snapshot.steps().get(0).errorMessage())
                .contains("Test Case ID 258")
                .doesNotContain("chua phan hoi on dinh");
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.remove().run();
        }
    }
}
