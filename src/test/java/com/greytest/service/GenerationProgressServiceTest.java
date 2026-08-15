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
                .satisfies(step -> assertThat(step.status()).isEqualTo(GenerationProgressStepStatus.WAITING));

        service.start(9L, GenerationProgressStage.TEST_PLAN, 2, "Worker bắt đầu.");
        assertThat(service.get(9L, GenerationProgressStage.TEST_PLAN).status())
                .isEqualTo(GenerationProgressStatus.RUNNING);
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
