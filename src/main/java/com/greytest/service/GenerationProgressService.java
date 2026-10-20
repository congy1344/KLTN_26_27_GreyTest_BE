package com.greytest.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.greytest.dto.GenerationProgressDto;
import com.greytest.dto.GenerationProgressLogDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStatus;
import com.greytest.dto.GenerationProgressStepDto;
import com.greytest.dto.GenerationProgressStepStatus;
import com.greytest.exception.GenerationInProgressException;

/**
 * Lưu snapshot tiến độ ngắn hạn của các tác vụ sinh AI để frontend polling.
 * Dữ liệu chỉ phục vụ giao diện runtime và không thay đổi artifact nghiệp vụ.
 */
@Service
public class GenerationProgressService {

    private static final int MAX_LOGS = 40;
    private static final Duration TERMINAL_TTL = Duration.ofMinutes(15);
    private final ConcurrentMap<ProgressKey, ProgressState> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public GenerationProgressService() {
        this(Clock.systemUTC());
    }

    GenerationProgressService(Clock clock) {
        this.clock = clock;
    }

    public synchronized void start(Long projectId, GenerationProgressStage stage, List<String> stepLabels, String message) {
        cleanupExpired();
        ProgressKey requestedKey = new ProgressKey(projectId, stage);
        boolean projectHasRunningGeneration = states.entrySet().stream()
                .anyMatch(entry -> entry.getKey().projectId().equals(projectId)
                        && entry.getValue().isActive()
                        && (!entry.getKey().equals(requestedKey) || entry.getValue().isRunning()));
        if (projectHasRunningGeneration) {
            throw new GenerationInProgressException(
                    "Một tác vụ sinh AI đang chạy cho project này. Vui lòng chờ hoàn tất.");
        }
        states.compute(requestedKey, (key, current) -> {
            return new ProgressState(stage, stepLabels, message, clock.instant());
        });
    }

    public synchronized void resume(
            Long projectId,
            GenerationProgressStage stage,
            List<String> stepLabels,
            java.util.Set<Integer> completedStepIndexes,
            String message) {
        cleanupExpired();
        ProgressKey requestedKey = new ProgressKey(projectId, stage);
        boolean projectHasRunningGeneration = states.entrySet().stream()
                .anyMatch(entry -> entry.getKey().projectId().equals(projectId)
                        && entry.getValue().isActive()
                        && (!entry.getKey().equals(requestedKey) || entry.getValue().isRunning()));
        if (projectHasRunningGeneration) {
            throw new GenerationInProgressException(
                    "Một tác vụ sinh AI đang chạy cho project này. Vui lòng chờ hoàn tất.");
        }
        states.compute(requestedKey, (key, current) -> {
            ProgressState state = new ProgressState(
                    stage, stepLabels, message, clock.instant(), GenerationProgressStatus.RUNNING, false);
            if (completedStepIndexes != null) {
                for (int idx : completedStepIndexes) {
                    if (idx >= 0 && idx < state.stepStatuses.size()) {
                        state.stepStatuses.set(idx, GenerationProgressStepStatus.COMPLETED);
                    }
                }
                state.completedSteps = (int) state.stepStatuses.stream()
                        .filter(s -> s == GenerationProgressStepStatus.COMPLETED).count();
            }
            if (current != null && !current.logs.isEmpty()) {
                // Giữ lại các log cũ của những batch trước đó để hiển thị liền mạch
                List<GenerationProgressLogDto> oldLogs = new ArrayList<>(current.logs);
                state.logs.addAll(0, oldLogs);
                while (state.logs.size() > MAX_LOGS) state.logs.remove(0);
            }
            return state;
        });
    }

    public synchronized void resume(
            Long projectId,
            GenerationProgressStage stage,
            List<String> stepLabels,
            int completedSteps,
            String message) {
        java.util.Set<Integer> completedSet = java.util.stream.IntStream.range(0, completedSteps)
                .boxed().collect(java.util.stream.Collectors.toSet());
        resume(projectId, stage, stepLabels, completedSet, message);
    }

    public synchronized void start(Long projectId, GenerationProgressStage stage, int totalSteps, String message) {
        start(projectId, stage, ProgressState.buildStepLabels(stage, totalSteps), message);
    }

    /** Ghi nhận ngay tác vụ đã vào hàng đợi để frontend không phải giữ request HTTP. */
    public synchronized void queue(Long projectId, GenerationProgressStage stage, String message) {
        cleanupExpired();
        boolean projectHasActiveGeneration = states.entrySet().stream()
                .anyMatch(entry -> entry.getKey().projectId().equals(projectId) && entry.getValue().isActive());
        if (projectHasActiveGeneration) {
            throw new GenerationInProgressException(
                    "Một tác vụ sinh AI đang chạy cho project này. Vui lòng chờ hoàn tất.");
        }
        ProgressKey key = new ProgressKey(projectId, stage);
        states.put(key,
                new ProgressState(stage, List.of("Đang chờ worker xử lý"), message, clock.instant(), GenerationProgressStatus.QUEUED));
    }

    public void setStepStatus(Long projectId, GenerationProgressStage stage, int stepIndex, GenerationProgressStepStatus status) {
        ProgressState state = states.get(new ProgressKey(projectId, stage));
        if (state != null) state.setStepStatus(stepIndex, status, clock.instant());
    }

    public void advanceStep(Long projectId, GenerationProgressStage stage, int stepIndex, String message) {
        ProgressState state = states.get(new ProgressKey(projectId, stage));
        if (state != null) state.advanceStep(stepIndex, message, clock.instant());
    }

    public void failStep(Long projectId, GenerationProgressStage stage, int stepIndex, String errorMessage) {
        ProgressState state = states.get(new ProgressKey(projectId, stage));
        if (state != null) state.failStep(stepIndex, errorMessage, clock.instant());
    }

    public void advance(Long projectId, GenerationProgressStage stage, String message) {
        ProgressState state = states.get(new ProgressKey(projectId, stage));
        if (state != null) state.advance(message, clock.instant());
    }

    public void complete(Long projectId, GenerationProgressStage stage, String message) {
        ProgressState state = states.get(new ProgressKey(projectId, stage));
        if (state != null) state.complete(message, clock.instant());
    }

    public void completeIfActive(Long projectId, GenerationProgressStage stage, String message) {
        ProgressState state = states.get(new ProgressKey(projectId, stage));
        if (state != null) state.completeIfActive(message, clock.instant());
    }

    /** Chỉ công bố hoàn tất sau khi transaction ngoài cùng commit thành công. */
    public void completeAfterCommit(Long projectId, GenerationProgressStage stage, String message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            complete(projectId, stage, message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                complete(projectId, stage, message);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    fail(projectId, stage, "Không thể lưu kết quả. Tác vụ đã được hoàn tác.");
                }
            }
        });
    }

    public void fail(Long projectId, GenerationProgressStage stage, String message) {
        ProgressState state = states.get(new ProgressKey(projectId, stage));
        if (state != null) state.fail(message, clock.instant());
    }

    /** Chỉ ghi lỗi nếu worker chưa nhận được trạng thái kết thúc từ service nghiệp vụ. */
    public void failIfActive(Long projectId, GenerationProgressStage stage, String message) {
        ProgressState state = states.get(new ProgressKey(projectId, stage));
        if (state != null) state.failIfActive(message, clock.instant());
    }

    public synchronized void pause(Long projectId, GenerationProgressStage stage, String message) {
        ProgressState state = states.get(new ProgressKey(projectId, stage));
        if (state != null) state.pause(message, clock.instant());
    }

    public boolean isPaused(Long projectId, GenerationProgressStage stage) {
        ProgressState state = states.get(new ProgressKey(projectId, stage));
        return state != null && state.isPaused();
    }

    public void log(Long projectId, GenerationProgressStage stage, String message) {
        ProgressState state = states.get(new ProgressKey(projectId, stage));
        if (state != null) state.note(message, clock.instant());
    }

    public GenerationProgressDto get(Long projectId, GenerationProgressStage stage) {
        cleanupExpired();
        ProgressState state = states.get(new ProgressKey(projectId, stage));
        return state == null
                ? new GenerationProgressDto(stage, GenerationProgressStatus.IDLE, 0, 0, 0, List.of(), List.of())
                : state.snapshot();
    }

    private void cleanupExpired() {
        Instant threshold = clock.instant().minus(TERMINAL_TTL);
        states.entrySet().removeIf(entry -> entry.getValue().isTerminalBefore(threshold));
    }

    private record ProgressKey(Long projectId, GenerationProgressStage stage) {}

    private static final class ProgressState {
        private final GenerationProgressStage stage;
        private final int totalSteps;
        private final List<String> stepLabels;
        private final List<GenerationProgressStepStatus> stepStatuses = new ArrayList<>();
        private final List<String> stepErrors = new ArrayList<>();
        private final List<GenerationProgressLogDto> logs = new ArrayList<>();
        private final boolean autoPromoteFirstWaitingStep;
        private GenerationProgressStatus status = GenerationProgressStatus.RUNNING;
        private int completedSteps;
        private String failureMessage;
        private Instant updatedAt;

        private ProgressState(GenerationProgressStage stage, List<String> stepLabels, String message, Instant now) {
            this(stage, stepLabels, message, now, GenerationProgressStatus.RUNNING, true);
        }

        private ProgressState(
                GenerationProgressStage stage,
                List<String> customStepLabels,
                String message,
                Instant now,
                GenerationProgressStatus initialStatus) {
            this(stage, customStepLabels, message, now, initialStatus, true);
        }

        private ProgressState(
                GenerationProgressStage stage,
                List<String> customStepLabels,
                String message,
                Instant now,
                GenerationProgressStatus initialStatus,
                boolean autoPromoteFirstWaitingStep) {
            this.stage = stage;
            this.autoPromoteFirstWaitingStep = autoPromoteFirstWaitingStep;
            this.stepLabels = initialStatus == GenerationProgressStatus.QUEUED
                    ? List.of("Đang chờ worker xử lý")
                    : (customStepLabels != null && !customStepLabels.isEmpty()
                            ? List.copyOf(customStepLabels)
                            : buildStepLabels(stage, 1));
            this.totalSteps = this.stepLabels.size();
            for (int i = 0; i < this.totalSteps; i++) {
                this.stepStatuses.add(GenerationProgressStepStatus.WAITING);
                this.stepErrors.add(null);
            }
            this.status = initialStatus;
            this.updatedAt = now;
            addLog(message, now);
        }

        private synchronized void toQueued(String message, Instant now) {
            this.status = GenerationProgressStatus.QUEUED;
            this.updatedAt = now;
            addLog(message, now);
        }

        private synchronized void setStepStatus(int stepIndex, GenerationProgressStepStatus newStatus, Instant now) {
            if (status != GenerationProgressStatus.RUNNING && status != GenerationProgressStatus.QUEUED) return;
            if (stepIndex >= 0 && stepIndex < stepStatuses.size()) {
                stepStatuses.set(stepIndex, newStatus);
                if (newStatus == GenerationProgressStepStatus.COMPLETED) {
                    completedSteps = (int) stepStatuses.stream().filter(s -> s == GenerationProgressStepStatus.COMPLETED).count();
                }
                updatedAt = now;
            }
        }

        private synchronized void advanceStep(int stepIndex, String message, Instant now) {
            if (status != GenerationProgressStatus.RUNNING && status != GenerationProgressStatus.QUEUED) return;
            status = GenerationProgressStatus.RUNNING;
            if (stepIndex >= 0 && stepIndex < stepStatuses.size()) {
                stepStatuses.set(stepIndex, GenerationProgressStepStatus.COMPLETED);
            }
            completedSteps = (int) stepStatuses.stream().filter(s -> s == GenerationProgressStepStatus.COMPLETED).count();
            updatedAt = now;
            addLog(message, now);
        }

        private synchronized void failStep(int stepIndex, String errorMessage, Instant now) {
            if (stepIndex >= 0 && stepIndex < stepStatuses.size()) {
                stepStatuses.set(stepIndex, GenerationProgressStepStatus.FAILED);
                stepErrors.set(stepIndex, errorMessage);
            }
            updatedAt = now;
        }

        private synchronized void advance(String message, Instant now) {
            if (status != GenerationProgressStatus.RUNNING) return;
            int targetIndex = -1;
            for (int i = 0; i < stepStatuses.size(); i++) {
                if (stepStatuses.get(i) != GenerationProgressStepStatus.COMPLETED) {
                    targetIndex = i;
                    break;
                }
            }
            if (targetIndex >= 0) {
                stepStatuses.set(targetIndex, GenerationProgressStepStatus.COMPLETED);
            }
            completedSteps = (int) stepStatuses.stream().filter(s -> s == GenerationProgressStepStatus.COMPLETED).count();
            updatedAt = now;
            addLog(message, now);
        }

        private synchronized void complete(String message, Instant now) {
            completedSteps = totalSteps;
            for (int i = 0; i < stepStatuses.size(); i++) {
                stepStatuses.set(i, GenerationProgressStepStatus.COMPLETED);
            }
            status = GenerationProgressStatus.COMPLETED;
            updatedAt = now;
            addLog(message, now);
        }

        private synchronized void completeIfActive(String message, Instant now) {
            if (isActive()) complete(message, now);
        }

        private synchronized void fail(String message, Instant now) {
            status = GenerationProgressStatus.FAILED;
            failureMessage = message == null || message.isBlank()
                    ? "Tác vụ thất bại."
                    : safeMessage(message);
            boolean markedFailure = false;
            for (int i = 0; i < stepStatuses.size(); i++) {
                if (stepStatuses.get(i) == GenerationProgressStepStatus.RUNNING) {
                    stepStatuses.set(i, GenerationProgressStepStatus.FAILED);
                    stepErrors.set(i, failureMessage);
                    markedFailure = true;
                } else if (stepStatuses.get(i) == GenerationProgressStepStatus.FAILED) {
                    markedFailure = true;
                }
            }
            if (!markedFailure) {
                for (int i = 0; i < stepStatuses.size(); i++) {
                    if (stepStatuses.get(i) != GenerationProgressStepStatus.COMPLETED) {
                        stepStatuses.set(i, GenerationProgressStepStatus.FAILED);
                        stepErrors.set(i, failureMessage);
                        break;
                    }
                }
            }
            updatedAt = now;
            addLog(message, now);
        }

        private synchronized void failIfActive(String message, Instant now) {
            if (isActive()) fail(message, now);
        }

        private synchronized void pause(String message, Instant now) {
            if (!isActive()) return;
            status = GenerationProgressStatus.PAUSED;
            updatedAt = now;
            addLog(message, now);
        }

        private synchronized boolean isPaused() {
            return status == GenerationProgressStatus.PAUSED;
        }

        private synchronized void note(String message, Instant now) {
            if (!isActive()) return;
            updatedAt = now;
            addLog(message, now);
        }

        private synchronized boolean isRunning() {
            return status == GenerationProgressStatus.RUNNING;
        }

        private synchronized boolean isActive() {
            return status == GenerationProgressStatus.QUEUED || status == GenerationProgressStatus.RUNNING;
        }

        private synchronized boolean isTerminalBefore(Instant threshold) {
            return (status == GenerationProgressStatus.COMPLETED
                    || status == GenerationProgressStatus.FAILED
                    || status == GenerationProgressStatus.PAUSED)
                    && updatedAt.isBefore(threshold);
        }

        private synchronized GenerationProgressDto snapshot() {
            int percent = totalSteps == 0 ? 0 : completedSteps * 100 / totalSteps;
            return new GenerationProgressDto(
                    stage, status, percent, completedSteps, totalSteps, stepSnapshots(), List.copyOf(logs));
        }

        private List<GenerationProgressStepDto> stepSnapshots() {
            List<GenerationProgressStepDto> snapshots = new ArrayList<>();
            boolean hasRunning = stepStatuses.stream().anyMatch(s -> s == GenerationProgressStepStatus.RUNNING);
            int firstWaitingIndex = stepStatuses.indexOf(GenerationProgressStepStatus.WAITING);

            for (int index = 0; index < totalSteps; index++) {
                GenerationProgressStepStatus stepStatus = index < stepStatuses.size()
                        ? stepStatuses.get(index)
                        : GenerationProgressStepStatus.WAITING;
                String errorMessage = index < stepErrors.size() ? stepErrors.get(index) : null;

                if (status == GenerationProgressStatus.COMPLETED) {
                    stepStatus = GenerationProgressStepStatus.COMPLETED;
                } else if (status == GenerationProgressStatus.RUNNING && autoPromoteFirstWaitingStep
                        && !hasRunning && index == firstWaitingIndex) {
                    stepStatus = GenerationProgressStepStatus.RUNNING;
                } else if (status == GenerationProgressStatus.FAILED && errorMessage != null) {
                    stepStatus = GenerationProgressStepStatus.FAILED;
                }

                int stepPercent = stepStatus == GenerationProgressStepStatus.COMPLETED ? 100 : 0;
                String stepLabel = status == GenerationProgressStatus.FAILED
                        && stepLabels.size() == 1
                        && "Đang chờ worker xử lý".equals(stepLabels.get(index))
                        ? "Khởi tạo tác vụ"
                        : stepLabels.get(index);
                snapshots.add(new GenerationProgressStepDto(
                        index + 1, stepLabel, stepStatus, stepPercent, errorMessage));
            }
            return List.copyOf(snapshots);
        }

        private static List<String> buildStepLabels(GenerationProgressStage stage, int totalSteps) {
            String artifact = switch (stage) {
                case BUSINESS_RULE -> "Business Rule";
                case TEST_PLAN -> "Test Plan";
                case TEST_CASE -> "Test Case";
                case UNIT_TEST -> "Unit Test";
            };
            List<String> labels = new ArrayList<>();
            int batchSteps = Math.max(totalSteps - 1, 0);
            for (int index = 1; index <= batchSteps; index++) {
                labels.add("Sinh " + artifact + " - batch " + index + "/" + batchSteps);
            }
            labels.add("Kiểm tra và lưu " + artifact);
            return List.copyOf(labels);
        }

        private void addLog(String message, Instant now) {
            if (message == null || message.isBlank()) return;
            String safeMessage = safeMessage(message);
            if (logs.size() >= MAX_LOGS) logs.remove(0);
            logs.add(new GenerationProgressLogDto(now, safeMessage));
        }

        private static String safeMessage(String message) {
            String safeMessage = message.strip();
            return safeMessage.length() > 500 ? safeMessage.substring(0, 497) + "..." : safeMessage;
        }
    }
}
