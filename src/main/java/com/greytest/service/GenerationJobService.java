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
    private static final Object MANUAL_MUTATION = new Object();
    private final ConcurrentMap<Long, Object> activeProjects = new ConcurrentHashMap<>();

    @Autowired
    public GenerationJobService(
            @Qualifier("generationExecutor") Executor executor,
            GenerationProgressService progressService,
            ProjectRepository projectRepository,
            UserActivityService activityService) {
        this.executor = executor;
        this.progressService = progressService;
        this.projectRepository = projectRepository;
        this.activityService = activityService;
    }

    GenerationJobService(Executor executor, GenerationProgressService progressService) {
        this(executor, progressService, null, null);
    }

    public GenerationJobAcceptedDto submit(Long projectId, GenerationProgressStage stage, Runnable task) {
        Long actorUserId = projectRepository == null ? null : projectRepository.findById(projectId)
                .map(project -> project.getOwnerUserId()).orElse(null);
        return submit(projectId, actorUserId, stage, task);
    }

    public GenerationJobAcceptedDto submit(
            Long projectId, Long actorUserId, GenerationProgressStage stage, Runnable task) {
        Object activeWork = activeProjects.putIfAbsent(projectId, stage);
        if (activeWork != null) {
            throw new GenerationInProgressException(
                    "Một tác vụ sinh AI đang chạy cho project này. Vui lòng theo dõi trong Log.");
        }

        try {
            progressService.queue(projectId, stage, "Tác vụ đã vào hàng đợi và sẽ tự chạy nền.");
            recordGenerationRequest(projectId, actorUserId, stage);
            Locale locale = LocaleContextHolder.getLocale();
            executor.execute(() -> run(projectId, actorUserId, stage, locale, task));
        } catch (RuntimeException exception) {
            activeProjects.remove(projectId, stage);
            progressService.fail(projectId, stage, "Không thể đưa tác vụ vào hàng đợi. Vui lòng thử lại.");
            throw exception;
        }

        return new GenerationJobAcceptedDto(
                stage,
                GenerationProgressStatus.QUEUED,
                "Tác vụ AI đang chạy nền. Bạn có thể tiếp tục sử dụng trang và theo dõi bằng nút Log.");
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

    private void run(Long projectId, Long actorUserId, GenerationProgressStage stage, Locale locale, Runnable task) {
        Locale previousLocale = LocaleContextHolder.getLocale();
        try {
            LocaleContextHolder.setLocale(locale);
            GenerationJobContext.bind(actorUserId, message -> progressService.log(projectId, stage, message));
            task.run();
            progressService.completeIfActive(
                    projectId,
                    stage,
                    "Hoàn tất: không có dữ liệu mới cần sinh.");
        } catch (RuntimeException exception) {
            log.warn("Background AI generation failed for project {} stage {}: {}",
                    projectId, stage, exception.getMessage());
            progressService.fail(projectId, stage, userMessage(exception));
        } finally {
            GenerationJobContext.clear();
            LocaleContextHolder.setLocale(previousLocale);
            activeProjects.remove(projectId, stage);
        }
    }

    private String userMessage(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof UsageQuotaExceededException) {
                return "Bạn đã sử dụng hết quota LLM trong tháng này. Vui lòng liên hệ admin để điều chỉnh quota.";
            }
            if (current instanceof LlmResponseException) {
                String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
                if (message.contains("unit test") && message.contains("test case id")
                        && message.contains("chia batch")) {
                    return current.getMessage();
                }
                if (message.contains("429") || message.contains("quota") || message.contains("too_many_requests")) {
                    return "Gemini đang giới hạn lượt gọi. Hệ thống đã tự chờ và thử lại nhưng quota vẫn chưa khả dụng. Vui lòng thử lại sau ít phút.";
                }
                return "Dịch vụ AI tạm thời chưa phản hồi ổn định. Vui lòng thử lại sau ít phút.";
            }
            current = current.getCause();
        }
        return "Tác vụ AI không thể hoàn tất. Vui lòng kiểm tra dữ liệu đầu vào hoặc thử lại.";
    }
}
