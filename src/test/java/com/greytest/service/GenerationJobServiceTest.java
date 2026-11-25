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

    private static Runnable partialRound(GenerationProgressService progress, java.util.concurrent.atomic.AtomicInteger rounds) {
        return () -> {
            rounds.incrementAndGet();
            progress.start(1L, GenerationProgressStage.TEST_CASE, 1, "Round");
            progress.partialAfterCommit(1L, GenerationProgressStage.TEST_CASE, "Hoàn tất một phần: còn case thiếu.");
        };
    }

    @Test
    void aPartialStageIsResumedAutomaticallyAtMostTwice() {
        var progress = new GenerationProgressService();
        var jobs = new GenerationJobService(Runnable::run, progress);
        var first = new java.util.concurrent.atomic.AtomicInteger();
        var resumed = new java.util.concurrent.atomic.AtomicInteger();

        jobs.submit(1L, 7L, GenerationProgressStage.TEST_CASE, partialRound(progress, first), partialRound(progress, resumed));

        assertThat(first).hasValue(1);
        assertThat(resumed).hasValue(2);
        assertThat(progress.get(1L, GenerationProgressStage.TEST_CASE).status()).isEqualTo(GenerationProgressStatus.PARTIAL);    }

    @Test
    void autoResumeStopsOnceTheStageCompletes() {
        var progress = new GenerationProgressService();
        var jobs = new GenerationJobService(Runnable::run, progress);
        var resumed = new java.util.concurrent.atomic.AtomicInteger();

        jobs.submit(1L, 7L, GenerationProgressStage.TEST_CASE, partialRound(progress, new java.util.concurrent.atomic.AtomicInteger()), () -> {
            resumed.incrementAndGet();
            progress.start(1L, GenerationProgressStage.TEST_CASE, 1, "Resume");
            progress.complete(1L, GenerationProgressStage.TEST_CASE, "Done");
        });

        assertThat(resumed).hasValue(1);
        assertThat(progress.get(1L, GenerationProgressStage.TEST_CASE).status()).isEqualTo(GenerationProgressStatus.COMPLETED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ai", "quota", "other", "pause"})
    void onlyAiFailuresAreResumedAutomatically(String outcome) {
        var progress = new GenerationProgressService();
        var jobs = new GenerationJobService(Runnable::run, progress);
        var resumed = new java.util.concurrent.atomic.AtomicInteger();

        jobs.submit(1L, 7L, GenerationProgressStage.BUSINESS_RULE, () -> {
            progress.start(1L, GenerationProgressStage.BUSINESS_RULE, 1, "Round");
            switch (outcome) {
                case "ai" -> throw new LlmResponseException("Generation validation gate failed");
                case "quota" -> throw new com.greytest.exception.UsageQuotaExceededException("hết quota");
                case "other" -> throw new IllegalStateException("Chỉ sinh sau khi đã duyệt");
                default -> jobs.pause(1L, GenerationProgressStage.BUSINESS_RULE);
            }
        }, () -> {
            resumed.incrementAndGet();
            progress.start(1L, GenerationProgressStage.BUSINESS_RULE, 1, "Resume");
            progress.complete(1L, GenerationProgressStage.BUSINESS_RULE, "Done");
        });

        assertThat(resumed).hasValue(outcome.equals("ai") ? 1 : 0);
    }

    @Test
    void stalePreparingWorkerCannotFailOrFinishReplacementQueuedAfterPause() {
        var executor = new ManualExecutor();
        var progress = new GenerationProgressService();
        var jobs = new GenerationJobService(executor, progress);
        jobs.submit(1L, GenerationProgressStage.UNIT_TEST, () -> {
            jobs.pause(1L, GenerationProgressStage.UNIT_TEST);
            jobs.submit(1L, GenerationProgressStage.UNIT_TEST, () -> {
                progress.resume(1L, GenerationProgressStage.UNIT_TEST, java.util.List.of("New"), java.util.Set.of(), "New worker");
                progress.complete(1L, GenerationProgressStage.UNIT_TEST, "Done");
            });
            assertThatThrownBy(() -> progress.assertCurrentMeasurement(1L, GenerationProgressStage.UNIT_TEST))
                    .isInstanceOf(GenerationInProgressException.class);
            assertThatThrownBy(() -> progress.completeAfterCommit(1L, GenerationProgressStage.UNIT_TEST, "Stale completion"))
                    .isInstanceOf(GenerationInProgressException.class);
            progress.resume(1L, GenerationProgressStage.UNIT_TEST, java.util.List.of("Stale"), java.util.Set.of(), "Stale worker");
        });
        executor.runNext();
        assertThat(progress.get(1L, GenerationProgressStage.UNIT_TEST).status()).isEqualTo(GenerationProgressStatus.QUEUED);
        assertThat(progress.get(1L, GenerationProgressStage.UNIT_TEST).busy()).isTrue();
        executor.runNext();
        assertThat(progress.get(1L, GenerationProgressStage.UNIT_TEST).status()).isEqualTo(GenerationProgressStatus.COMPLETED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"pause", "cancel", "reset"})
    void stoppedWorkerCannotRestartProgressButNewQueuedWorkerCan(String stop) {
        var progress = new GenerationProgressService();
        var jobs = new GenerationJobService(Runnable::run, progress);
        jobs.submit(1L, GenerationProgressStage.UNIT_TEST, () -> {
            progress.start(1L, GenerationProgressStage.UNIT_TEST, 2, "First round");
            if (stop.equals("pause")) progress.pause(1L, GenerationProgressStage.UNIT_TEST, "Pause");
            else if (stop.equals("cancel")) progress.cancel(1L, GenerationProgressStage.UNIT_TEST, "Cancel");
            else progress.reset(1L, GenerationProgressStage.UNIT_TEST);
            assertThatThrownBy(() -> progress.resume(1L, GenerationProgressStage.UNIT_TEST,
                    java.util.List.of("Stale"), java.util.Set.of(), "Auto resume"))
                    .isInstanceOf(GenerationInProgressException.class);
            assertThat(progress.get(1L, GenerationProgressStage.UNIT_TEST).status())
                    .isNotEqualTo(GenerationProgressStatus.RUNNING);
        });
        jobs.submit(1L, GenerationProgressStage.UNIT_TEST, () -> {
            progress.resume(1L, GenerationProgressStage.UNIT_TEST, java.util.List.of("New"), java.util.Set.of(), "Resume");
            progress.complete(1L, GenerationProgressStage.UNIT_TEST, "Done");
        });
        assertThat(progress.get(1L, GenerationProgressStage.UNIT_TEST).status()).isEqualTo(GenerationProgressStatus.COMPLETED);
    }

    @Test
    void internalUnitResumeKeepsMeasurementWhileExternalResumeIsRejected() {
        var progress = new GenerationProgressService();
        var jobs = new GenerationJobService(Runnable::run, progress);
        jobs.submit(1L, GenerationProgressStage.UNIT_TEST, () -> GenerationProgressService.deferCompletion(() -> {
            progress.start(1L, GenerationProgressStage.UNIT_TEST, 2, "First round");
            String runId = progress.get(1L, GenerationProgressStage.UNIT_TEST).runMetrics().runId();
            progress.resume(1L, GenerationProgressStage.UNIT_TEST, java.util.List.of("Remaining"), java.util.Set.of(), "Auto resume");
            progress.resume(1L, GenerationProgressStage.UNIT_TEST, java.util.List.of("Last"), java.util.Set.of(), "Auto resume again");
            assertThat(progress.get(1L, GenerationProgressStage.UNIT_TEST).runMetrics().runId()).isEqualTo(runId);
            assertThatThrownBy(() -> java.util.concurrent.CompletableFuture.runAsync(() ->
                    progress.resume(1L, GenerationProgressStage.UNIT_TEST, java.util.List.of("External"), java.util.Set.of(), "External")).join())
                    .hasCauseInstanceOf(GenerationInProgressException.class);
            progress.completeAfterCommit(1L, GenerationProgressStage.UNIT_TEST, "Done");
        }));
        assertThat(progress.get(1L, GenerationProgressStage.UNIT_TEST).status()).isEqualTo(GenerationProgressStatus.COMPLETED);
    }

    @Test
    void allowsOnlyMutationsFromTheActiveJobAndPreservesActorAndLogger() {
        var progress = new GenerationProgressService();
        var activity = mock(UserActivityService.class);
        var jobs = new GenerationJobService(Runnable::run, progress, null, activity, null);
        jobs.submit(1L, 7L, GenerationProgressStage.UNIT_TEST, () -> {
            var worker = GenerationJobContext.wrap(() -> jobs.executeAiMutation(1L, 7L,
                    ActivityAction.GENERATE_UNIT_TEST, () -> {
                        assertThat(GenerationJobContext.actorUserId()).isEqualTo(7L);
                        GenerationJobContext.log("repair");
                        return jobs.executeMutation(1L, () -> "nested");
                    }));
            assertThat(java.util.concurrent.CompletableFuture.supplyAsync(worker).join()).isEqualTo("nested");
            assertThatThrownBy(() -> java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> jobs.executeMutation(1L, () -> "external")).join())
                    .hasCauseInstanceOf(GenerationInProgressException.class);
            assertThat(GenerationJobContext.actorUserId()).isEqualTo(7L);
        });
        assertThat(progress.get(1L, GenerationProgressStage.UNIT_TEST).logs())
                .extracting(item -> item.message()).contains("repair");
        assertThat(GenerationJobContext.actorUserId()).isNull();
    }

    @Test
    void attributesGenerationAndWorkerContextToAuthenticatedActor() {
        ManualExecutor executor = new ManualExecutor();
        UserActivityService activity = mock(UserActivityService.class);
        ProjectRepository projectRepo = mock(ProjectRepository.class);
        com.greytest.entity.Project project = new com.greytest.entity.Project();
        project.setId(1L);
        project.setOwnerUserId(99L);
        org.mockito.Mockito.when(projectRepo.findById(1L)).thenReturn(java.util.Optional.of(project));
        GenerationJobService jobs = new GenerationJobService(
                executor, new GenerationProgressService(), projectRepo, activity);
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

        jobs.submit(1L, GenerationProgressStage.TEST_PLAN, () -> {
        });

        assertThatThrownBy(() -> jobs.submit(1L, GenerationProgressStage.TEST_CASE, () -> {
        }))
                .isInstanceOf(GenerationInProgressException.class)
                .hasMessageContaining("Log");
    }

    @Test
    void rejectsArtifactMutationWhileProjectJobIsQueued() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);
        jobs.submit(1L, GenerationProgressStage.TEST_PLAN, () -> {
        });

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
                () -> jobs.submit(1L, GenerationProgressStage.BUSINESS_RULE, () -> {
                }))
                .isInstanceOf(GenerationInProgressException.class));

        assertThat(jobs.submit(1L, GenerationProgressStage.BUSINESS_RULE, () -> {
        }).status())
                .isEqualTo(GenerationProgressStatus.QUEUED);
    }

    @Test
    void completesQueuedJobWhenThereIsNoNewArtifactToGenerate() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);
        jobs.submit(1L, GenerationProgressStage.BUSINESS_RULE, () -> {
        });

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
                .contains("Hệ thống AI đang giới hạn")
                .doesNotContain("Gemini")
                .doesNotContain("raw-json");
    }

    @Test
    void preservesDetailedFailureWhenQuotaIsDetectedAfterArtifactServiceReportsIt() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);
        jobs.submit(1L, GenerationProgressStage.UNIT_TEST, () -> {
            progress.start(1L, GenerationProgressStage.UNIT_TEST, 2, "Đang sinh Unit Test.");
            progress.fail(1L, GenerationProgressStage.UNIT_TEST,
                    "Dừng ở batch 2. Các Unit Test đã sinh trước đó được lưu an toàn.");
            throw new com.greytest.exception.UsageQuotaExceededException("Hết quota");
        });

        executor.runNext();

        var snapshot = progress.get(1L, GenerationProgressStage.UNIT_TEST);
        assertThat(snapshot.steps().get(0).errorMessage())
                .contains("Dừng ở batch 2")
                .doesNotContain("nâng cấp lên gói PRO");
        assertThat(snapshot.logs().get(snapshot.logs().size() - 1).message())
                .contains("Dừng ở batch 2");
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

    @Test
    void pausesRunningJobAndReleasesProjectLock() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);

        jobs.submit(1L, GenerationProgressStage.TEST_PLAN, () -> {
            progress.start(1L, GenerationProgressStage.TEST_PLAN, 2, "Đang sinh batch 1.");
        });

        // Đang queued, project bị lock
        assertThatThrownBy(() -> jobs.executeMutation(1L, () -> "mutation"))
                .isInstanceOf(GenerationInProgressException.class);

        // Pause job
        jobs.pause(1L, GenerationProgressStage.TEST_PLAN);

        assertThat(progress.get(1L, GenerationProgressStage.TEST_PLAN).status())
                .isEqualTo(GenerationProgressStatus.PAUSED);

        // Lock đã được giải phóng, mutation chạy được
        assertThat(jobs.executeMutation(1L, () -> "mutation")).isEqualTo("mutation");
    }

    @Test
    void rejectsJobUpfrontWhenQuotaExceeded() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        UsageQuotaService quotaService = mock(UsageQuotaService.class);
        org.mockito.Mockito.doThrow(new com.greytest.exception.UsageQuotaExceededException("Hết quota"))
                .when(quotaService).checkQuotaAvailable(99L);
        GenerationJobService jobs = new GenerationJobService(executor, progress, quotaService);

        assertThatThrownBy(() -> jobs.submit(1L, 99L, GenerationProgressStage.BUSINESS_RULE, () -> {
        }))
                .isInstanceOf(com.greytest.exception.UsageQuotaExceededException.class)
                .hasMessageContaining("Hết quota");
    }

    @Test
    void recordsQuotaExhaustionAndUpgradeMessageWhenBackgroundFailsWithQuota() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);

        jobs.submit(1L, GenerationProgressStage.BUSINESS_RULE, () -> {
            progress.start(1L, GenerationProgressStage.BUSINESS_RULE, 2, "Đang sinh.");
            throw new com.greytest.exception.UsageQuotaExceededException("Hết quota");
        });

        executor.runNext();

        var snapshot = progress.get(1L, GenerationProgressStage.BUSINESS_RULE);
        assertThat(snapshot.status()).isEqualTo(GenerationProgressStatus.FAILED);
        assertThat(snapshot.logs().get(snapshot.logs().size() - 1).message())
                .contains("hết quota LLM")
                .contains("nâng cấp lên gói PRO");
    }

    @Test
    void skipsExecutionIfJobWasPausedWhileQueued() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean(false);

        jobs.submit(1L, GenerationProgressStage.TEST_PLAN, () -> ran.set(true));
        jobs.pause(1L, GenerationProgressStage.TEST_PLAN);

        executor.runNext();

        assertThat(ran.get()).isFalse();
        assertThat(progress.get(1L, GenerationProgressStage.TEST_PLAN).status())
                .isEqualTo(GenerationProgressStatus.PAUSED);
    }

    @Test
    void allowsResumedJobAfterQueuedJobWasPaused() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);
        java.util.concurrent.atomic.AtomicInteger executedJob = new java.util.concurrent.atomic.AtomicInteger(0);

        jobs.submit(1L, GenerationProgressStage.TEST_CASE, () -> executedJob.set(1));
        jobs.pause(1L, GenerationProgressStage.TEST_CASE);

        jobs.submit(1L, GenerationProgressStage.TEST_CASE, () -> {
            progress.start(1L, GenerationProgressStage.TEST_CASE, 1, "Chạy resume.");
            executedJob.set(2);
            progress.complete(1L, GenerationProgressStage.TEST_CASE, "Hoàn tất resume.");
        });

        // Run the first worker (was paused while queued)
        executor.runNext();
        assertThat(executedJob.get()).isEqualTo(0);

        // Run the resumed worker
        executor.runNext();
        assertThat(executedJob.get()).isEqualTo(2);
        assertThat(progress.get(1L, GenerationProgressStage.TEST_CASE).status())
                .isEqualTo(GenerationProgressStatus.COMPLETED);
    }

    @Test
    void resumeWhilePausedWorkerIsStillSavingItsBatchStartsWhenThatWorkerStops() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);
        java.util.concurrent.atomic.AtomicReference<Throwable> resumeError = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger resumedRuns = new java.util.concurrent.atomic.AtomicInteger();

        jobs.submit(1L, GenerationProgressStage.TEST_PLAN, () -> {
            progress.start(1L, GenerationProgressStage.TEST_PLAN, 2, "Đang sinh batch 1.");
            // Người dùng bấm Tạm dừng rồi Tiếp tục sinh trong lúc batch đang gọi AI vẫn chưa xong.
            jobs.pause(1L, GenerationProgressStage.TEST_PLAN);
            try {
                jobs.submit(1L, GenerationProgressStage.TEST_PLAN, () -> {
                    progress.start(1L, GenerationProgressStage.TEST_PLAN, 1, "Chạy tiếp.");
                    resumedRuns.incrementAndGet();
                    progress.complete(1L, GenerationProgressStage.TEST_PLAN, "Hoàn tất.");
                });
            } catch (Throwable error) {
                resumeError.set(error);
            }
        });

        executor.runNext();
        assertThat(resumeError.get()).isNull();
        executor.runNext();

        assertThat(resumedRuns.get()).isEqualTo(1);
        assertThat(progress.get(1L, GenerationProgressStage.TEST_PLAN).status()).isEqualTo(GenerationProgressStatus.COMPLETED);
    }

    @Test
    void cancellingDropsAResumeThatWasWaitingForTheOldWorker() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);
        java.util.concurrent.atomic.AtomicBoolean resumed = new java.util.concurrent.atomic.AtomicBoolean();

        jobs.submit(1L, GenerationProgressStage.TEST_PLAN, () -> {
            progress.start(1L, GenerationProgressStage.TEST_PLAN, 2, "Đang sinh batch 1.");
            jobs.pause(1L, GenerationProgressStage.TEST_PLAN);
            jobs.submit(1L, GenerationProgressStage.TEST_PLAN, () -> resumed.set(true));
            jobs.cancel(1L, GenerationProgressStage.TEST_PLAN);
        });
        executor.runNext();

        assertThat(executor.tasks).isEmpty();
        assertThat(resumed.get()).isFalse();
    }

    @Test
    void projectCanBeDeletedAfterCancelEvenWhileTheWorkerIsStillStopping() {
        ManualExecutor executor = new ManualExecutor();
        GenerationProgressService progress = new GenerationProgressService();
        GenerationJobService jobs = new GenerationJobService(executor, progress);
        java.util.concurrent.atomic.AtomicReference<Object> deletion = new java.util.concurrent.atomic.AtomicReference<>();

        jobs.submit(1L, GenerationProgressStage.BUSINESS_RULE, () -> {
            progress.start(1L, GenerationProgressStage.BUSINESS_RULE, 3, "Đang sinh batch 1.");
            // Lời xóa đến từ request HTTP khác, không phải từ chính worker. Đang chạy thật thì vẫn phải chặn.
            assertThatThrownBy(() -> java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> jobs.executeProjectDeletion(1L, () -> "deleted")).join())
                    .hasCauseInstanceOf(GenerationInProgressException.class);
            jobs.cancel(1L, GenerationProgressStage.BUSINESS_RULE);
            deletion.set(java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> jobs.executeProjectDeletion(1L, () -> "deleted")).join());
        });
        executor.runNext();

        assertThat(deletion.get()).isEqualTo("deleted");
        // Worker còn chạy dở phải tiếp tục thấy lệnh dừng sau khi project bị xóa.
        assertThat(progress.isPaused(1L, GenerationProgressStage.BUSINESS_RULE)).isTrue();
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
