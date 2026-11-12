package com.greytest.service;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import com.greytest.dto.GenerationJobAcceptedDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStatus;
import com.greytest.exception.GenerationInProgressException;
import com.greytest.exception.UsageQuotaExceededException;
import com.greytest.service.agent.LlmResponseException;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.repository.ProjectRepository;

/** Xếp hàng và chạy nền tác vụ sinh AI, đồng thời cô lập lỗi khỏi request của người dùng. */
@Service
public class GenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobService.class);
    private final Executor executor;
    private final GenerationProgressService progressService;
    private final ProjectRepository projectRepository;
    private final UserActivityService activityService;
    private final UsageQuotaService quotaService;
    private static final Object MANUAL_MUTATION = new Object();
    private record ProjectStageLock(GenerationProgressStage stage, long runId) {}
    private final ConcurrentMap<Long, Object> activeProjects = new ConcurrentHashMap<>();

    @Autowired
    public GenerationJobService(
            @Qualifier("generationExecutor") Executor executor,
            GenerationProgressService progressService,
            ProjectRepository projectRepository,
            UserActivityService activityService,
            @Autowired(required = false) UsageQuotaService quotaService) {
        this.executor = executor;
        this.progressService = progressService;
        this.projectRepository = projectRepository;
        this.activityService = activityService;
        this.quotaService = quotaService;
    }

    GenerationJobService(Executor executor, GenerationProgressService progressService) {
        this(executor, progressService, null, null, null);
    }

    GenerationJobService(
            Executor executor,
            GenerationProgressService progressService,
            ProjectRepository projectRepository,
            UserActivityService activityService) {
        this(executor, progressService, projectRepository, activityService, null);
    }

    GenerationJobService(Executor executor, GenerationProgressService progressService, UsageQuotaService quotaService) {
        this(executor, progressService, null, null, quotaService);
    }

    public GenerationJobAcceptedDto submit(Long projectId, GenerationProgressStage stage, Runnable task) {
        Long actorUserId = projectRepository == null ? null : projectRepository.findById(projectId)
                .map(project -> project.getOwnerUserId()).orElse(null);
        return submit(projectId, actorUserId, stage, task);
    }

    public GenerationJobAcceptedDto submit(
            Long projectId, Long actorUserId, GenerationProgressStage stage, Runnable task) {
        // Kiểm tra hạn mức quota của người dùng trước khi xếp hàng tác vụ
        if (actorUserId != null && quotaService != null) {
            quotaService.checkQuotaAvailable(actorUserId);
        }

        ProjectStageLock lock = new ProjectStageLock(stage, System.nanoTime());
        Object activeWork = activeProjects.putIfAbsent(projectId, lock);
        if (activeWork != null) {
            boolean acquired = false;
            if (progressService.isPaused(projectId, stage)) {
                // Worker cũ đang hoàn tất lưu batch cuối trước khi dừng hoàn toàn.
                // Chờ ngắn để worker cũ giải phóng lock thay vì báo lỗi ngay.
                for (int i = 0; i < 20; i++) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (activeProjects.putIfAbsent(projectId, lock) == null) {
                        acquired = true;
                        break;
                    }
                }
            }
            if (!acquired) {
                if (progressService.isPaused(projectId, stage)) {
                    throw new GenerationInProgressException(
                            "Tác vụ đang lưu nốt batch hiện tại trước khi tạm dừng. Vui lòng đợi vài giây rồi nhấn 'Tiếp tục sinh'.");
                }
                throw new GenerationInProgressException(
                        "Một tác vụ sinh AI đang chạy cho project này. Vui lòng theo dõi trong Log.");
            }
        }

        try {
            progressService.queue(projectId, stage, "Tác vụ đã vào hàng đợi và sẽ tự chạy nền.");
            recordGenerationRequest(projectId, actorUserId, stage);
            Locale locale = LocaleContextHolder.getLocale();
            executor.execute(() -> run(projectId, actorUserId, lock, locale, task));
        } catch (RuntimeException exception) {
            activeProjects.remove(projectId, lock);
            progressService.fail(projectId, stage, "Không thể đưa tác vụ vào hàng đợi. Vui lòng thử lại.");
            throw exception;
        }

        return new GenerationJobAcceptedDto(
                stage,
                GenerationProgressStatus.QUEUED,
                "Tác vụ AI đang chạy nền. Bạn có thể tiếp tục sử dụng trang và theo dõi bằng nút Log.");
    }

    public void pause(Long projectId, GenerationProgressStage stage) {
        GenerationProgressStatus currentStatus = progressService.get(projectId, stage).status();
        progressService.pause(projectId, stage, "Đang tạm dừng tác vụ: hệ thống đang lưu nốt các batch đang xử lý...");
        if (currentStatus == GenerationProgressStatus.QUEUED) {
            // Tác vụ chưa được worker lấy chạy, có thể giải phóng lock ngay lập tức
            Object currentLock = activeProjects.get(projectId);
            if (currentLock instanceof ProjectStageLock psl && psl.stage() == stage) {
                activeProjects.remove(projectId, currentLock);
            }
            progressService.markWorkerFinished(projectId, stage);
        }
    }

    public void cancel(Long projectId, GenerationProgressStage stage) {
        progressService.cancel(projectId, stage, "Đã hủy tác vụ sinh AI.");
    }

    private void recordGenerationRequest(Long projectId, Long actorUserId, GenerationProgressStage stage) {
        if (actorUserId == null || activityService == null) return;
        activityService.record(actorUserId, actionFor(stage), projectId, java.util.Map.of("stage", stage.name()));
    }

    private ActivityAction actionFor(GenerationProgressStage stage) {
        return switch (stage) {
            case BUSINESS_RULE -> ActivityAction.GENERATE_BUSINESS_RULE;
            case TEST_PLAN -> ActivityAction.GENERATE_TEST_PLAN;
            case TEST_CASE -> ActivityAction.GENERATE_TEST_CASE;
            case UNIT_TEST -> ActivityAction.GENERATE_UNIT_TEST;
        };
    }

    /**
     * Thực hiện một thay đổi thủ công khi project không có worker AI hoạt động.
     * Marker được giữ đến khi service hoàn tất để loại bỏ race giữa kiểm tra và ghi dữ liệu.
     */
    public <T> T executeMutation(Long projectId, Supplier<T> mutation) {
        if (activeProjects.putIfAbsent(projectId, MANUAL_MUTATION) != null) {
            throw new GenerationInProgressException(
                    "Một tác vụ sinh AI đang chạy cho project này. Vui lòng chờ hoàn tất trước khi thay đổi dữ liệu.");
        }
        try {
            return mutation.get();
        } finally {
            activeProjects.remove(projectId, MANUAL_MUTATION);
        }
    }

    public void executeMutation(Long projectId, Runnable mutation) {
        executeMutation(projectId, () -> {
            mutation.run();
            return null;
        });
    }

    /** Thực hiện AI đồng bộ và giữ actor để quota không bị tính nhầm cho chủ project. */
    public <T> T executeAiMutation(
            Long projectId, Long actorUserId, ActivityAction action, Supplier<T> mutation) {
        return executeMutation(projectId, () -> {
            if (activityService != null) {
                activityService.record(actorUserId, action, projectId, java.util.Map.of());
            }
            try {
                GenerationJobContext.bind(actorUserId, message -> {});
                return mutation.get();
            } finally {
                GenerationJobContext.clear();
            }
        });
    }

    private void run(Long projectId, Long actorUserId, ProjectStageLock lock, Locale locale, Runnable task) {
        Locale previousLocale = LocaleContextHolder.getLocale();
        GenerationProgressStage stage = lock.stage();
        GenerationRunMetrics measurement = null;
        try {
            LocaleContextHolder.setLocale(locale);
            GenerationJobContext.bind(actorUserId, message -> progressService.log(projectId, stage, message));
            if (!lock.equals(activeProjects.get(projectId)) || progressService.isPaused(projectId, stage)) {
                return;
            }
            measurement = progressService.beginMeasurement(projectId, stage, GenerationJobContext.metricsSupplier());
            task.run();
            progressService.completeIfActive(
                    projectId,
                    stage,
                    "Hoàn tất: không có dữ liệu mới cần sinh.");
        } catch (RuntimeException exception) {
            log.warn("Background AI generation failed for project {} stage {}: {}",
                    projectId, stage, exception.getMessage());
            if (isUsageQuotaExceeded(exception)) {
                progressService.failIfActive(projectId, stage,
                        "Bạn đã sử dụng hết quota LLM trong tháng này. Vui lòng nâng cấp lên gói PRO để tiếp tục.");
            } else {
                progressService.failIfActive(projectId, stage, userMessage(exception));
            }
        } finally {
            if (measurement != null) measurement.finish();
            GenerationJobContext.clear();
            LocaleContextHolder.setLocale(previousLocale);
            if (progressService.isPaused(projectId, stage)) {
                progressService.log(projectId, stage,
                        "Đã tạm dừng tác vụ. Các batch đã sinh được lưu an toàn. Bạn có thể nhấn 'Tiếp tục sinh' để tiếp tục.");
            }
            progressService.markWorkerFinished(projectId, stage);
            activeProjects.remove(projectId, lock);
        }
    }

    private boolean isUsageQuotaExceeded(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof UsageQuotaExceededException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String userMessage(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof UsageQuotaExceededException) {
                return "Bạn đã sử dụng hết quota LLM trong tháng này. Vui lòng nâng cấp lên gói PRO để tiếp tục.";
            }
            if (current instanceof LlmResponseException) {
                String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
                if (message.contains("unit test") && message.contains("test case id")
                        && message.contains("chia batch")) {
                    return current.getMessage();
                }
                if (message.contains("429") || message.contains("quota") || message.contains("too_many_requests")) {
                    return "Hệ thống AI đang giới hạn lượt gọi. Hệ thống đã tự chờ và thử lại nhưng quota vẫn chưa khả dụng. Vui lòng thử lại sau ít phút.";
                }
                return "Dịch vụ AI tạm thời chưa phản hồi ổn định. Vui lòng thử lại sau ít phút.";
            }
            current = current.getCause();
        }
        return "Tác vụ AI không thể hoàn tất. Vui lòng kiểm tra dữ liệu đầu vào hoặc thử lại.";
    }
}
