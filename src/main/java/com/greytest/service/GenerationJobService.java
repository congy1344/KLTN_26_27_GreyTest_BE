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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.greytest.dto.GenerationJobAcceptedDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStatus;
import com.greytest.entity.Project;
import com.greytest.exception.AuthException;
import com.greytest.exception.GenerationInProgressException;
import com.greytest.exception.ProjectNotFoundException;
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
    private final BreakGlassAccessService breakGlassAccessService;
    @Autowired(required = false)
    private com.greytest.service.storage.workspace.StorageWorkspaceService storageWorkspaceService;

    public void setStorageWorkspaceService(com.greytest.service.storage.workspace.StorageWorkspaceService storageWorkspaceService) {
        this.storageWorkspaceService = storageWorkspaceService;
    }
    private static final Object MANUAL_MUTATION = new Object();
    private static final Object JOB_LOCK = new Object();
    private record ProjectStageLock(GenerationProgressStage stage, long runId) {}
    private final ConcurrentMap<Long, Object> activeProjects = new ConcurrentHashMap<>();
    /** Yêu cầu "Tiếp tục sinh" gửi lúc worker đã tạm dừng/hủy nhưng còn lưu nốt batch đang gọi AI. */
    private record PendingResume(Long actorUserId, GenerationProgressStage stage, Runnable task) {}
    private final ConcurrentMap<Long, PendingResume> pendingResumes = new ConcurrentHashMap<>();
    /** Số lần tối đa hệ thống tự "Tiếp tục sinh" sau một lần người dùng bấm sinh, như khi người dùng tự bấm lại. */
    static final int MAX_AUTO_RESUMES = 2;
    private record AutoResume(GenerationProgressStage stage, Runnable resumeTask, int used) {}
    private final ConcurrentMap<Long, AutoResume> autoResumes = new ConcurrentHashMap<>();

    @Autowired
    public GenerationJobService(
            @Qualifier("generationExecutor") Executor executor,
            GenerationProgressService progressService,
            ProjectRepository projectRepository,
            UserActivityService activityService,
            @Autowired(required = false) UsageQuotaService quotaService,
            @Autowired(required = false) BreakGlassAccessService breakGlassAccessService) {
        this.executor = executor;
        this.progressService = progressService;
        this.projectRepository = projectRepository;
        this.activityService = activityService;
        this.quotaService = quotaService;
        this.breakGlassAccessService = breakGlassAccessService;
    }

    GenerationJobService(Executor executor, GenerationProgressService progressService) {
        this(executor, progressService, null, null, null, null);
    }

    public GenerationJobService(
            Executor executor,
            GenerationProgressService progressService,
            ProjectRepository projectRepository,
            UserActivityService activityService) {
        this(executor, progressService, projectRepository, activityService, null, null);
    }

    public GenerationJobService(
            Executor executor,
            GenerationProgressService progressService,
            ProjectRepository projectRepository,
            UserActivityService activityService,
            BreakGlassAccessService breakGlassAccessService) {
        this(executor, progressService, projectRepository, activityService, null, breakGlassAccessService);
    }

    GenerationJobService(Executor executor, GenerationProgressService progressService, UsageQuotaService quotaService) {
        this(executor, progressService, null, null, quotaService, null);
    }

    public GenerationJobAcceptedDto submit(Long projectId, GenerationProgressStage stage, Runnable task) {
        Long actorUserId = projectRepository == null ? null : projectRepository.findById(projectId)
                .map(project -> project.getOwnerUserId()).orElse(null);
        return submit(projectId, actorUserId, stage, task);
    }

    /**
     * Như {@link #submit(Long, Long, GenerationProgressStage, Runnable)}, kèm tác vụ "Tiếp tục sinh" mà hệ thống tự chạy
     * (tối đa {@link #MAX_AUTO_RESUMES} lần) khi stage kết thúc một phần hoặc lỗi do AI, để người dùng không phải bấm lại.
     */
    public GenerationJobAcceptedDto submit(
            Long projectId, Long actorUserId, GenerationProgressStage stage, Runnable task, Runnable resumeTask) {
        if (resumeTask == null) return submit(projectId, actorUserId, stage, task);
        AutoResume chain = new AutoResume(stage, resumeTask, 0);
        autoResumes.put(projectId, chain);
        try {
            return enqueue(projectId, actorUserId, stage, task);
        } catch (RuntimeException rejected) {
            autoResumes.remove(projectId, chain);
            throw rejected;
        }
    }

    public GenerationJobAcceptedDto submit(
            Long projectId, Long actorUserId, GenerationProgressStage stage, Runnable task) {
        // Lượt người dùng tự bấm thay cho chuỗi tự tiếp tục cũ.
        autoResumes.remove(projectId);
        return enqueue(projectId, actorUserId, stage, task);
    }

    private GenerationJobAcceptedDto enqueue(
            Long projectId, Long actorUserId, GenerationProgressStage stage, Runnable task) {
        return enqueue(projectId, actorUserId, stage, task, "Tác vụ đã vào hàng đợi và sẽ tự chạy nền.");
    }

    private GenerationJobAcceptedDto enqueue(
            Long projectId, Long actorUserId, GenerationProgressStage stage, Runnable task, String queuedMessage) {
        // Kiểm tra quyền sở hữu project của actor trước khi cho phép chạy background job
        if (projectRepository != null && actorUserId != null) {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new ProjectNotFoundException(projectId));
            if (project.getOwnerUserId() == null || !project.getOwnerUserId().equals(actorUserId)) {
                if (breakGlassAccessService == null || !breakGlassAccessService.hasActiveBreakGlass(projectId, actorUserId)) {
                    throw new AuthException("FORBIDDEN", "Actor không có quyền thực thi job trên project này", HttpStatus.FORBIDDEN);
                }
            }
        }

        // Kiểm tra hạn mức quota của người dùng trước khi xếp hàng tác vụ
        if (actorUserId != null && quotaService != null) {
            quotaService.checkQuotaAvailable(actorUserId);
        }

        ProjectStageLock lock = new ProjectStageLock(stage, System.nanoTime());
        Object activeWork = activeProjects.putIfAbsent(projectId, lock);
        if (activeWork != null) {
            // Worker cũ đã nhận lệnh dừng nhưng batch đang gọi AI có thể mất vài phút mới xong:
            // ghi nhận yêu cầu và tự chạy khi worker đó nhả khóa, thay vì bắt người dùng bấm lại.
            if (activeWork instanceof ProjectStageLock stopping && progressService.isPaused(projectId, stopping.stage())) {
                String accepted = "Đã nhận yêu cầu tiếp tục. Tác vụ sẽ tự chạy ngay khi batch đang xử lý được lưu xong.";
                pendingResumes.put(projectId, new PendingResume(actorUserId, stage, task));
                progressService.logWhilePaused(projectId, stage, accepted);
                if (!activeProjects.containsKey(projectId)) startPendingResume(projectId);
                return new GenerationJobAcceptedDto(stage, GenerationProgressStatus.QUEUED, accepted);
            }
            throw new GenerationInProgressException(
                    "Một tác vụ sinh AI đang chạy cho project này. Vui lòng theo dõi trong Log.");
        }

        try {
            progressService.queue(projectId, stage, queuedMessage);
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
        synchronized (progressService) {
        pendingResumes.remove(projectId);
        autoResumes.remove(projectId);
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
    }

    public void cancel(Long projectId, GenerationProgressStage stage) {
        pendingResumes.remove(projectId);
        autoResumes.remove(projectId);
        progressService.cancel(projectId, stage, "Đã hủy tác vụ sinh AI.");
    }

    /** Chạy yêu cầu tiếp tục đang chờ; remove() bảo đảm chỉ một luồng khởi động nó. */
    private void startPendingResume(Long projectId) {
        PendingResume pending = pendingResumes.remove(projectId);
        if (pending == null) return;
        try {
            submit(projectId, pending.actorUserId(), pending.stage(), pending.task());
        } catch (RuntimeException exception) {
            log.warn("Could not start pending resume for project {} stage {}: {}", projectId, pending.stage(), exception.getMessage());
            progressService.fail(projectId, pending.stage(), "Không thể tiếp tục sinh: " + exception.getMessage());
        }
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
        Object jobLock = GenerationJobContext.scopedValue(JOB_LOCK);
        // Chỉ worker mang đúng khóa của job mới được sửa lồng nhau trong lúc xác minh.
        if (jobLock != null && jobLock == activeProjects.get(projectId)) return mutation.get();
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

    /**
     * Xóa project. Nếu tác vụ AI đã bị tạm dừng/hủy thì không bắt chờ worker lưu nốt batch đang gọi AI.
     * ponytail: worker đang dừng có thể ghi nốt một batch sau khi project bị xóa; lần ghi đó lỗi khóa ngoại
     * và bị bỏ qua vì tiến trình đã ở trạng thái dừng.
     */
    public <T> T executeProjectDeletion(Long projectId, Supplier<T> deletion) {
        Object active = activeProjects.get(projectId);
        autoResumes.remove(projectId);
        if (active instanceof ProjectStageLock stopping && progressService.isPaused(projectId, stopping.stage())) {
            pendingResumes.remove(projectId);
            // Giữ trạng thái dừng (không reset) để worker vẫn thấy lệnh dừng và không gọi AI tiếp cho project đã xóa.
            return deletion.get();
        }
        return executeMutation(projectId, deletion);
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
            try (var scope = GenerationJobContext.openScope(actorUserId)) {
                return GenerationJobContext.withActor(actorUserId, mutation);
            }
        });
    }

    private void run(Long projectId, Long actorUserId, ProjectStageLock lock, Locale locale, Runnable task) {
        Locale previousLocale = LocaleContextHolder.getLocale();
        GenerationProgressStage stage = lock.stage();
        GenerationRunMetrics measurement = null;
        boolean aiFailure = false;
        boolean threw = false;
        try {
            LocaleContextHolder.setLocale(locale);
            GenerationJobContext.bind(actorUserId, message -> progressService.log(projectId, stage, message));
            synchronized (progressService) {
                if (!lock.equals(activeProjects.get(projectId)) || progressService.isPaused(projectId, stage)) return;
                measurement = progressService.beginMeasurement(projectId, stage, GenerationJobContext.metricsSupplier());
            }
            // SEC-3.5-01: Kiểm tra lại quyền của actor ngay trước khi chạy tác vụ (xử lý Break-Glass bị thu hồi/hết hạn in-flight)
            if (projectRepository != null && actorUserId != null) {
                Project project = projectRepository.findById(projectId).orElse(null);
                if (project != null && (project.getOwnerUserId() == null || !project.getOwnerUserId().equals(actorUserId))) {
                    if (breakGlassAccessService == null || !breakGlassAccessService.hasActiveBreakGlass(projectId, actorUserId)) {
                        throw new AuthException("FORBIDDEN", "Quyền Break-Glass đã hết hạn hoặc bị thu hồi khi đang chờ thực thi", HttpStatus.FORBIDDEN);
                    }
                }
            }
            GenerationRunMetrics currentMeasurement = measurement;
            com.greytest.service.storage.workspace.StorageWorkspace ws = (storageWorkspaceService != null && projectRepository != null)
                    ? projectRepository.findById(projectId).filter(p -> Boolean.TRUE.equals(p.getStorageEncrypted()))
                        .map(storageWorkspaceService::acquireWorkspace).orElse(null)
                    : null;
            try {
                GenerationJobContext.withValue(JOB_LOCK, lock, () -> {
                    GenerationProgressService.withMeasurementOwner(projectId, stage, currentMeasurement, task);
                    return null;
                });
            } finally {
                if (ws != null) {
                    ws.close();
                }
            }
            synchronized (progressService) {
            if (lock.equals(activeProjects.get(projectId))) progressService.completeIfActive(
                    projectId,
                    stage,
                    "Hoàn tất: không có dữ liệu mới cần sinh.");
            }
        } catch (RuntimeException exception) {
            threw = true;
            aiFailure = !isUsageQuotaExceeded(exception) && hasCause(exception, LlmResponseException.class);
            synchronized (progressService) {
            if (!lock.equals(activeProjects.get(projectId))) return;
            log.warn("Background AI generation failed for project {} stage {}: {}",
                    projectId, stage, exception.getMessage());
            if (isUsageQuotaExceeded(exception)) {
                progressService.failIfActive(projectId, stage,
                        "Bạn đã sử dụng hết quota LLM trong tháng này. Vui lòng nâng cấp lên gói PRO để tiếp tục.");
            } else {
                progressService.failIfActive(projectId, stage, userMessage(exception));
            }
            }
        } finally {
            if (measurement != null) measurement.finish();
            GenerationJobContext.clear();
            LocaleContextHolder.setLocale(previousLocale);
            boolean released = false;
            synchronized (progressService) {
            if (lock.equals(activeProjects.get(projectId))) {
            if (progressService.isPaused(projectId, stage)) {
                progressService.log(projectId, stage,
                        "Đã tạm dừng tác vụ. Các batch đã sinh được lưu an toàn. Bạn có thể nhấn 'Tiếp tục sinh' để tiếp tục.");
            }
            progressService.markWorkerFinished(projectId, stage);
            released = activeProjects.remove(projectId, lock);
            }
            }
            if (released) {
                startPendingResume(projectId);
                autoResume(projectId, actorUserId, stage, aiFailure || !threw);
            }
        }
    }

    /**
     * Stage kết thúc một phần (còn case bị chặn/chưa sinh được) hoặc lỗi do output/provider AI thì tự chạy "Tiếp tục sinh",
     * hoặc service tự báo thất bại mà không ném lỗi (còn plan/case chưa sinh được), vì AI thường trả kết quả hợp lệ ở lượt sau.
     * Hết quota, tạm dừng, hủy (kể cả quản trị viên hủy) hay lỗi dữ liệu đầu vào thì không tự chạy.
     */
    private void autoResume(Long projectId, Long actorUserId, GenerationProgressStage stage, boolean resumableFailure) {
        AutoResume chain = autoResumes.get(projectId);
        if (chain == null || chain.stage() != stage || activeProjects.containsKey(projectId)) return;
        GenerationProgressStatus status = progressService.get(projectId, stage).status();
        boolean retry = status == GenerationProgressStatus.PARTIAL || status == GenerationProgressStatus.FAILED && resumableFailure;
        if (!retry || chain.used() >= MAX_AUTO_RESUMES) {
            autoResumes.remove(projectId, chain);
            return;
        }
        AutoResume next = new AutoResume(stage, chain.resumeTask(), chain.used() + 1);
        if (!autoResumes.replace(projectId, chain, next)) return;
        try {
            // Thông báo đi cùng lệnh xếp hàng: queue() mở lượt mới nên log ghi trước đó không còn hiển thị.
            enqueue(projectId, actorUserId, stage, chain.resumeTask(), "Tự động tiếp tục sinh (lần " + next.used() + "/"
                    + MAX_AUTO_RESUMES + ") cho phần còn thiếu, không cần bấm 'Tiếp tục sinh'.");
        } catch (RuntimeException exception) {
            autoResumes.remove(projectId, next);
            log.warn("Could not auto-resume project {} stage {}: {}", projectId, stage, exception.getMessage());
        }
    }

    /** Dừng chuỗi tự "Tiếp tục sinh" của project (ví dụ quản trị viên hủy tác vụ). */
    public void stopAutoResume(Long projectId) {
        autoResumes.remove(projectId);
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
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
