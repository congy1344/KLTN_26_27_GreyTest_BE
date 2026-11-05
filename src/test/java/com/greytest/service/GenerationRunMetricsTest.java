package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStatus;
import com.greytest.service.agent.LlmTokenUsage;
import com.greytest.service.agent.LlmUsageRecorder;

class GenerationRunMetricsTest {
    private final GenerationProgressService progress = new GenerationProgressService();
    private final GenerationJobService jobs = new GenerationJobService(Runnable::run, progress);
    private final LlmUsageRecorder recorder = new LlmUsageRecorder(
            new ObjectMapper(), false, ".", "metrics-test", "GREYTEST");

    @Test
    void measuresFromQueuedZeroUntilCompletedHundredAndFreezesBeforeWorkerCleanup() {
        var worker = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        var delayedJobs = new GenerationJobService(worker::set, progress);
        var stage = GenerationProgressStage.TEST_PLAN;
        var atCompletion = new java.util.concurrent.atomic.AtomicReference<com.greytest.dto.GenerationRunMetricsDto>();
        delayedJobs.submit(1L, stage, () -> {
            progress.start(1L, stage, 1, "Start");
            // Latency của các phản hồi không được cộng vào đồng hồ thời gian thực.
            recorder.record("test-plan", "test", "test",
                    new LlmTokenUsage(20, 0, 10, 0, 30, LlmTokenUsage.UsageSource.PROVIDER), 600_000);
            progress.advance(1L, stage, "Batch saved");
            assertThat(progress.get(1L, stage).percent()).isEqualTo(99);
            progress.complete(1L, stage, "Done");
            var completed = progress.get(1L, stage);
            assertThat(completed.percent()).isEqualTo(100);
            assertThat(completed.runMetrics().finishedAt()).isNotNull();
            atCompletion.set(completed.runMetrics());
        });

        var queued = progress.get(1L, stage);
        assertThat(queued.percent()).isZero();
        assertThat(queued.runMetrics().finishedAt()).isNull();
        worker.get().run();
        var finished = progress.get(1L, stage).runMetrics();
        assertThat(finished.runId()).isEqualTo(queued.runMetrics().runId());
        assertThat(finished.startedAt()).isEqualTo(queued.runMetrics().startedAt());
        assertThat(finished.elapsedMillis()).isEqualTo(atCompletion.get().elapsedMillis()).isLessThan(600_000);
        assertThat(finished.finishedAt()).isEqualTo(atCompletion.get().finishedAt());
        assertThat(finished.totalTokens()).isEqualTo(30);
    }

    @Test
    void aggregatesParallelResponsesWithoutFileLoggingAndSurvivesLogTruncation() {
        var stage = GenerationProgressStage.TEST_PLAN;
        jobs.submit(1L, stage, () -> {
            progress.start(1L, stage, 2, "Start");
            var executor = new LlmBatchExecutor(2);
            try {
                executor.map(List.of(1, 2), ignored -> {
                    record(LlmTokenUsage.UsageSource.PROVIDER);
                    return ignored;
                });
            } finally { executor.shutdown(); }
            for (int i = 0; i < 50; i++) progress.log(1L, stage, "Log " + i);
            var live = CompletableFuture.supplyAsync(() -> progress.get(1L, stage)).join();
            assertThat(live.runMetrics().finishedAt()).isNull();
            assertThat(live.runMetrics().totalTokens()).isEqualTo(60);
            progress.complete(1L, stage, "Done");
        });

        var result = progress.get(1L, stage);
        assertThat(result.runMetrics().recordedCalls()).isEqualTo(2);
        assertThat(result.runMetrics().inputTokens()).isEqualTo(40);
        assertThat(result.runMetrics().outputTokens()).isEqualTo(20);
        assertThat(result.runMetrics().estimatedCalls()).isZero();
        assertThat(result.runMetrics().finishedAt()).isNotNull();
        assertThat(result.runMetrics().elapsedMillis()).isGreaterThanOrEqualTo(0);
        assertThat(GenerationJobContext.metrics()).isEmpty();
    }

    @Test
    void keepsFailedRunUsageAndResetsItForTheNextRunAndOtherStage() {
        var stage = GenerationProgressStage.TEST_CASE;
        jobs.submit(1L, stage, () -> {
            progress.resume(1L, stage, List.of("Saved", "Pending"), 1, "Resume");
            record(LlmTokenUsage.UsageSource.PROVIDER);
            record(LlmTokenUsage.UsageSource.ESTIMATED);
            throw new IllegalStateException("failed after responses");
        });
        var failed = progress.get(1L, stage);
        assertThat(failed.status()).isEqualTo(GenerationProgressStatus.FAILED);
        assertThat(failed.runMetrics().totalTokens()).isEqualTo(60);
        assertThat(failed.runMetrics().estimatedCalls()).isEqualTo(1);
        assertThat(failed.runMetrics().finishedAt()).isNotNull();

        jobs.submit(1L, stage, () -> {
            progress.resume(1L, stage, List.of("Saved", "Pending"), 1, "Resume again");
            record(LlmTokenUsage.UsageSource.PROVIDER);
        });
        var next = progress.get(1L, stage).runMetrics();
        assertThat(next.runId()).isNotEqualTo(failed.runMetrics().runId());
        assertThat(next.totalTokens()).isEqualTo(30);
        assertThat(next.estimatedCalls()).isZero();

        jobs.submit(1L, GenerationProgressStage.UNIT_TEST, () -> {});
        assertThat(progress.get(1L, GenerationProgressStage.UNIT_TEST).runMetrics().recordedCalls()).isZero();
        assertThat(progress.get(1L, stage).runMetrics().totalTokens()).isEqualTo(30);
    }

    private void record(LlmTokenUsage.UsageSource source) {
        recorder.record("# Prompt: test-plan", "test", "test",
                new LlmTokenUsage(20, 0, 10, 0, 30, source), 10);
    }
}
