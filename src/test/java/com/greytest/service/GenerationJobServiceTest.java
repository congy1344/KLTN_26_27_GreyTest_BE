package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStatus;
import com.greytest.exception.GenerationInProgressException;
import com.greytest.service.agent.LlmResponseException;

class GenerationJobServiceTest {

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
