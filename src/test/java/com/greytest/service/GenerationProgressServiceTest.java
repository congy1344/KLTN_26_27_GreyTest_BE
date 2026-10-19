package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.greytest.dto.GenerationProgressDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStatus;
import com.greytest.dto.GenerationProgressStepStatus;
import com.greytest.exception.GenerationInProgressException;

class GenerationProgressServiceTest {

    private final GenerationProgressService service = new GenerationProgressService();

    @Test
    void exposesQueuedStateBeforeTheWorkerStarts() {
        service.queue(9L, GenerationProgressStage.TEST_PLAN, "Đã vào hàng đợi.");

        GenerationProgressDto queued = service.get(9L, GenerationProgressStage.TEST_PLAN);
        assertThat(queued.status()).isEqualTo(GenerationProgressStatus.QUEUED);
        assertThat(queued.steps()).singleElement()
                .satisfies(step -> {
                    assertThat(step.label()).isEqualTo("Đang chờ worker xử lý");
                    assertThat(step.status()).isEqualTo(GenerationProgressStepStatus.WAITING);
                });

        service.start(9L, GenerationProgressStage.TEST_PLAN, 2, "Worker bắt đầu.");
        assertThat(service.get(9L, GenerationProgressStage.TEST_PLAN).status())
                .isEqualTo(GenerationProgressStatus.RUNNING);
    }

    @Test
    void queueStartsFreshStateAfterPreviousGenerationCompleted() {
        service.start(9L, GenerationProgressStage.TEST_PLAN, 2, "Lần chạy trước.");
        service.complete(9L, GenerationProgressStage.TEST_PLAN, "Đã xong.");

        service.queue(9L, GenerationProgressStage.TEST_PLAN, "Lần chạy mới.");

        GenerationProgressDto queued = service.get(9L, GenerationProgressStage.TEST_PLAN);
        assertThat(queued.status()).isEqualTo(GenerationProgressStatus.QUEUED);
        assertThat(queued.percent()).isZero();
        assertThat(queued.steps()).singleElement()
                .satisfies(step -> assertThat(step.status()).isEqualTo(GenerationProgressStepStatus.WAITING));
    }

    @Test
    void renamesQueuedStepWhenWorkerFailsBeforeStarting() {
        service.queue(9L, GenerationProgressStage.TEST_PLAN, "Đã vào hàng đợi.");
        service.fail(9L, GenerationProgressStage.TEST_PLAN, "Tác vụ AI không thể hoàn tất.");

        GenerationProgressDto failed = service.get(9L, GenerationProgressStage.TEST_PLAN);

        assertThat(failed.steps()).singleElement().satisfies(step -> {
            assertThat(step.label()).isEqualTo("Khởi tạo tác vụ");
            assertThat(step.status()).isEqualTo(GenerationProgressStepStatus.FAILED);
            assertThat(step.percent()).isZero();
        });
    }

    @Test
    void tracksRealCompletedStepsAndKeepsDetailedLogs() {
        service.start(9L, GenerationProgressStage.TEST_CASE, 4, "Đã chuẩn bị 3 batch Test Plan.");
        service.advance(9L, GenerationProgressStage.TEST_CASE, "Đã xử lý batch 1/3.");
        service.advance(9L, GenerationProgressStage.TEST_CASE, "Đã xử lý batch 2/3.");

        GenerationProgressDto progress = service.get(9L, GenerationProgressStage.TEST_CASE);

        assertThat(progress.status()).isEqualTo(GenerationProgressStatus.RUNNING);
        assertThat(progress.completedSteps()).isEqualTo(2);
        assertThat(progress.totalSteps()).isEqualTo(4);
        assertThat(progress.percent()).isEqualTo(50);
        assertThat(progress.steps()).extracting(step -> step.status())
                .containsExactly(
                        GenerationProgressStepStatus.COMPLETED,
                        GenerationProgressStepStatus.COMPLETED,
                        GenerationProgressStepStatus.RUNNING,
                        GenerationProgressStepStatus.WAITING);
        assertThat(progress.steps()).extracting(step -> step.label())
                .containsExactly(
                        "Sinh Test Case - batch 1/3",
                        "Sinh Test Case - batch 2/3",
                        "Sinh Test Case - batch 3/3",
                        "Kiểm tra và lưu Test Case");
        assertThat(progress.logs()).extracting(log -> log.message())
                .containsExactly(
                        "Đã chuẩn bị 3 batch Test Plan.",
                        "Đã xử lý batch 1/3.",
                        "Đã xử lý batch 2/3.");
    }

    @Test
    void supportsCustomStepLabelsForDetailedBatchDescriptions() {
        java.util.List<String> labels = java.util.List.of(
                "Sinh Business Rule - batch 1/2: getDoctor, saveDoctor",
                "Sinh Business Rule - batch 2/2: deleteDoctor",
                "Kiểm tra và lưu Business Rule vào CSDL");
        service.start(10L, GenerationProgressStage.BUSINESS_RULE, labels, "Bắt đầu sinh 3 methods.");
        service.advance(10L, GenerationProgressStage.BUSINESS_RULE, "Đã xong batch 1.");

        GenerationProgressDto dto = service.get(10L, GenerationProgressStage.BUSINESS_RULE);
        assertThat(dto.steps()).extracting(step -> step.label())
                .containsExactlyElementsOf(labels);
        assertThat(dto.steps().get(0).status()).isEqualTo(GenerationProgressStepStatus.COMPLETED);
        assertThat(dto.steps().get(1).status()).isEqualTo(GenerationProgressStepStatus.RUNNING);
        assertThat(dto.steps().get(2).status()).isEqualTo(GenerationProgressStepStatus.WAITING);
    }

    @Test
    void completesAtOneHundredPercentAndReportsFailureWithoutLosingHistory() {
        service.start(9L, GenerationProgressStage.UNIT_TEST, 2, "Bắt đầu sinh Unit Test.");
        service.advance(9L, GenerationProgressStage.UNIT_TEST, "Đã xử lý batch 1/1.");
        service.complete(9L, GenerationProgressStage.UNIT_TEST, "Đã lưu Unit Test.");

        GenerationProgressDto completed = service.get(9L, GenerationProgressStage.UNIT_TEST);
        assertThat(completed.status()).isEqualTo(GenerationProgressStatus.COMPLETED);
        assertThat(completed.percent()).isEqualTo(100);
        assertThat(completed.completedSteps()).isEqualTo(2);

        service.start(9L, GenerationProgressStage.UNIT_TEST, 3, "Sinh lại Unit Test.");
        service.fail(9L, GenerationProgressStage.UNIT_TEST, "LLM không phản hồi.");

        GenerationProgressDto failed = service.get(9L, GenerationProgressStage.UNIT_TEST);
        assertThat(failed.status()).isEqualTo(GenerationProgressStatus.FAILED);
        assertThat(failed.percent()).isZero();
        assertThat(failed.steps().get(0).status()).isEqualTo(GenerationProgressStepStatus.FAILED);
        assertThat(failed.steps().get(0).label()).isEqualTo("Sinh Unit Test - batch 1/2");
        assertThat(failed.steps().get(0).errorMessage()).isEqualTo("LLM không phản hồi.");
        assertThat(failed.logs()).extracting(log -> log.message())
                .containsExactly("Sinh lại Unit Test.", "LLM không phản hồi.");
    }

    @Test
    void returnsIdleSnapshotWhenNoGenerationHasStarted() {
        GenerationProgressDto progress = service.get(100L, GenerationProgressStage.BUSINESS_RULE);

        assertThat(progress.status()).isEqualTo(GenerationProgressStatus.IDLE);
        assertThat(progress.percent()).isZero();
        assertThat(progress.logs()).isEmpty();
    }

    @Test
    void rejectsConcurrentStartWithoutOverwritingCurrentProgress() {
        service.start(9L, GenerationProgressStage.TEST_PLAN, 3, "Lượt đầu tiên.");

        assertThatThrownBy(() -> service.start(
                9L, GenerationProgressStage.TEST_PLAN, 8, "Lượt thứ hai."))
                .isInstanceOf(GenerationInProgressException.class);

        GenerationProgressDto progress = service.get(9L, GenerationProgressStage.TEST_PLAN);
        assertThat(progress.totalSteps()).isEqualTo(3);
        assertThat(progress.logs()).extracting(log -> log.message()).containsExactly("Lượt đầu tiên.");
    }

    @Test
    void rejectsAnotherGenerationStageForTheSameProject() {
        service.start(9L, GenerationProgressStage.BUSINESS_RULE, 3, "Đang sinh Business Rule.");

        assertThatThrownBy(() -> service.start(
                9L, GenerationProgressStage.TEST_PLAN, 2, "Đang sinh Test Plan."))
                .isInstanceOf(GenerationInProgressException.class);

        assertThat(service.get(9L, GenerationProgressStage.TEST_PLAN).status())
                .isEqualTo(GenerationProgressStatus.IDLE);
    }

    @Test
    void removesTerminalProgressAfterRetentionWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-14T00:00:00Z"));
        GenerationProgressService expiringService = new GenerationProgressService(clock);
        expiringService.start(9L, GenerationProgressStage.BUSINESS_RULE, 1, "Bắt đầu.");
        expiringService.complete(9L, GenerationProgressStage.BUSINESS_RULE, "Hoàn tất.");

        clock.advanceSeconds(901);

        assertThat(expiringService.get(9L, GenerationProgressStage.BUSINESS_RULE).status())
                .isEqualTo(GenerationProgressStatus.IDLE);
    }

    @Test
    void keepsQueuedProgressAfterTerminalRetentionWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-14T00:00:00Z"));
        GenerationProgressService queuedService = new GenerationProgressService(clock);
        queuedService.queue(9L, GenerationProgressStage.TEST_PLAN, "Đang chờ worker.");

        clock.advanceSeconds(901);

        assertThat(queuedService.get(9L, GenerationProgressStage.TEST_PLAN).status())
                .isEqualTo(GenerationProgressStatus.QUEUED);
    }

    @Test
    void publishesCompletionOnlyAfterTransactionCommit() {
        service.start(9L, GenerationProgressStage.TEST_PLAN, 1, "Bắt đầu.");
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.completeAfterCommit(9L, GenerationProgressStage.TEST_PLAN, "Đã commit.");
            assertThat(service.get(9L, GenerationProgressStage.TEST_PLAN).status())
                    .isEqualTo(GenerationProgressStatus.RUNNING);

            TransactionSynchronization synchronization =
                    TransactionSynchronizationManager.getSynchronizations().get(0);
            synchronization.afterCommit();
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

            assertThat(service.get(9L, GenerationProgressStage.TEST_PLAN).status())
                    .isEqualTo(GenerationProgressStatus.COMPLETED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void marksProgressFailedWhenTransactionRollsBack() {
        service.start(9L, GenerationProgressStage.UNIT_TEST, 1, "Bắt đầu.");
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.completeAfterCommit(9L, GenerationProgressStage.UNIT_TEST, "Đã lưu.");
            TransactionSynchronization synchronization =
                    TransactionSynchronizationManager.getSynchronizations().get(0);

            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            GenerationProgressDto snapshot = service.get(9L, GenerationProgressStage.UNIT_TEST);
            assertThat(snapshot.status()).isEqualTo(GenerationProgressStatus.FAILED);
            assertThat(snapshot.steps()).singleElement().satisfies(step -> {
                assertThat(step.status()).isEqualTo(GenerationProgressStepStatus.FAILED);
                assertThat(step.errorMessage()).contains("hoàn tác");
            });
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void pausesRunningProgressAndAllowsCheckingIsPaused() {
        service.start(9L, GenerationProgressStage.TEST_CASE, 3, "Bắt đầu sinh Test Case.");
        service.advance(9L, GenerationProgressStage.TEST_CASE, "Đã xử lý batch 1/2.");

        assertThat(service.isPaused(9L, GenerationProgressStage.TEST_CASE)).isFalse();

        service.pause(9L, GenerationProgressStage.TEST_CASE, "Đã tạm dừng tác vụ.");

        assertThat(service.isPaused(9L, GenerationProgressStage.TEST_CASE)).isTrue();
        GenerationProgressDto paused = service.get(9L, GenerationProgressStage.TEST_CASE);
        assertThat(paused.status()).isEqualTo(GenerationProgressStatus.PAUSED);
        assertThat(paused.completedSteps()).isEqualTo(1);
        assertThat(paused.logs()).extracting(log -> log.message())
                .contains("Đã tạm dừng tác vụ.");
    }

    @Test
    void resumesWithNonContiguousCompletedStepsAndPreservesExistingLogs() {
        service.start(9L, GenerationProgressStage.UNIT_TEST, 4, "Lần chạy đầu tiên.");
        service.advance(9L, GenerationProgressStage.UNIT_TEST, "Batch 1 đã xong.");
        service.pause(9L, GenerationProgressStage.UNIT_TEST, "Tạm dừng sau batch 1 và batch 3.");

        java.util.List<String> labels = java.util.List.of("Batch 1/4", "Batch 2/4", "Batch 3/4", "Batch 4/4");
        // Giả sử batch 1 (index 0) và batch 3 (index 2) đã có trong DB
        service.resume(9L, GenerationProgressStage.UNIT_TEST, labels, java.util.Set.of(0, 2), "Tiếp tục sinh batch 2 và 4.");

        GenerationProgressDto progress = service.get(9L, GenerationProgressStage.UNIT_TEST);
        assertThat(progress.completedSteps()).isEqualTo(2);
        assertThat(progress.totalSteps()).isEqualTo(4);
        assertThat(progress.percent()).isEqualTo(50);
        assertThat(progress.steps().get(0).status()).isEqualTo(GenerationProgressStepStatus.COMPLETED);
        assertThat(progress.steps().get(1).status()).isEqualTo(GenerationProgressStepStatus.WAITING);
        assertThat(progress.steps().get(2).status()).isEqualTo(GenerationProgressStepStatus.COMPLETED);
        assertThat(progress.steps().get(3).status()).isEqualTo(GenerationProgressStepStatus.WAITING);
        assertThat(progress.logs()).extracting(l -> l.message())
                .contains("Lần chạy đầu tiên.", "Batch 1 đã xong.", "Tạm dừng sau batch 1 và batch 3.", "Tiếp tục sinh batch 2 và 4.");
    }

    @Test
    void capsPreservedLogsAfterResume() {
        service.start(9L, GenerationProgressStage.UNIT_TEST, 2, "Bắt đầu.");
        for (int index = 0; index < 38; index++) {
            service.log(9L, GenerationProgressStage.UNIT_TEST, "Log cũ " + index);
        }
        service.pause(9L, GenerationProgressStage.UNIT_TEST, "Tạm dừng.");

        service.resume(9L, GenerationProgressStage.UNIT_TEST,
                java.util.List.of("Batch 1/2", "Batch 2/2"), java.util.Set.of(0), "Tiếp tục.");

        assertThat(service.get(9L, GenerationProgressStage.UNIT_TEST).logs()).hasSizeLessThanOrEqualTo(40);
    }

    @Test
    void tracksGranularStepStatusesForParallelExecution() {
        java.util.List<String> labels = java.util.List.of("Batch 1/4", "Batch 2/4", "Batch 3/4", "Batch 4/4");
        service.start(9L, GenerationProgressStage.UNIT_TEST, labels, "Bắt đầu sinh 4 batch.");

        // Worker chạy song song batch 2 (index 1) và batch 4 (index 3)
        service.setStepStatus(9L, GenerationProgressStage.UNIT_TEST, 1, GenerationProgressStepStatus.RUNNING);
        service.setStepStatus(9L, GenerationProgressStage.UNIT_TEST, 3, GenerationProgressStepStatus.RUNNING);

        GenerationProgressDto dto = service.get(9L, GenerationProgressStage.UNIT_TEST);
        assertThat(dto.steps().get(1).status()).isEqualTo(GenerationProgressStepStatus.RUNNING);
        assertThat(dto.steps().get(3).status()).isEqualTo(GenerationProgressStepStatus.RUNNING);

        // Batch 4 hoàn thành trước batch 2
        service.advanceStep(9L, GenerationProgressStage.UNIT_TEST, 3, "Batch 4 đã lưu.");

        GenerationProgressDto afterBatch4 = service.get(9L, GenerationProgressStage.UNIT_TEST);
        assertThat(afterBatch4.steps().get(3).status()).isEqualTo(GenerationProgressStepStatus.COMPLETED);
        assertThat(afterBatch4.steps().get(1).status()).isEqualTo(GenerationProgressStepStatus.RUNNING);
        assertThat(afterBatch4.completedSteps()).isEqualTo(1);
    }

    @Test
    void ignoresLateRunningUpdateAfterPause() {
        java.util.List<String> labels = java.util.List.of("Batch 1/2", "Batch 2/2");
        service.start(9L, GenerationProgressStage.UNIT_TEST, labels, "Bắt đầu sinh 2 batch.");
        service.pause(9L, GenerationProgressStage.UNIT_TEST, "Đã tạm dừng tác vụ.");

        service.setStepStatus(9L, GenerationProgressStage.UNIT_TEST, 0, GenerationProgressStepStatus.RUNNING);

        assertThat(service.get(9L, GenerationProgressStage.UNIT_TEST).steps().get(0).status())
                .isEqualTo(GenerationProgressStepStatus.WAITING);
    }

    @Test
    void marksAllRunningBatchesFailedWhenParallelGenerationFails() {
        java.util.List<String> labels = java.util.List.of("Batch 1/3", "Batch 2/3", "Batch 3/3");
        service.start(9L, GenerationProgressStage.UNIT_TEST, labels, "Bắt đầu sinh 3 batch.");
        service.setStepStatus(9L, GenerationProgressStage.UNIT_TEST, 0, GenerationProgressStepStatus.RUNNING);
        service.setStepStatus(9L, GenerationProgressStage.UNIT_TEST, 2, GenerationProgressStepStatus.RUNNING);

        service.fail(9L, GenerationProgressStage.UNIT_TEST, "Sinh batch thất bại.");

        GenerationProgressDto progress = service.get(9L, GenerationProgressStage.UNIT_TEST);
        assertThat(progress.steps().get(0).status()).isEqualTo(GenerationProgressStepStatus.FAILED);
        assertThat(progress.steps().get(2).status()).isEqualTo(GenerationProgressStepStatus.FAILED);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
