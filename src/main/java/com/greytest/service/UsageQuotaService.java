package com.greytest.service;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import com.greytest.entity.UsageQuota;
import com.greytest.exception.UsageQuotaExceededException;
import com.greytest.repository.UsageQuotaRepository;
import com.greytest.repository.UserActivityLogRepository;
import com.greytest.entity.UserActivityLog;
import com.greytest.entity.enums.ActivityAction;
import java.util.Map;

/** Quản lý quota LLM theo tháng; reset được thực hiện lười ở lần truy cập đầu tháng. */
@Service
public class UsageQuotaService {

    private final UsageQuotaRepository repository;
    private final int defaultLimit;
    private final int plusLimit;
    private final int proLimit;
    private final Clock clock;
    private final UserActivityLogRepository activityRepository;
    private final String llmProvider;

    @Autowired
    public UsageQuotaService(
            UsageQuotaRepository repository,
            UserActivityLogRepository activityRepository,
            @Value("${greytest.usage.default-monthly-llm-quota:100}") int defaultLimit,
            @Value("${greytest.usage.plus-weekly-llm-quota:200}") int plusLimit,
            @Value("${greytest.usage.pro-monthly-llm-quota:300}") int proLimit,
            @Value("${llm.provider:mock}") String llmProvider) {
        this(repository, activityRepository, defaultLimit, plusLimit, proLimit, Clock.systemDefaultZone(), llmProvider);
    }

    public UsageQuotaService(
            UsageQuotaRepository repository,
            UserActivityLogRepository activityRepository,
            int defaultLimit,
            int plusLimit,
            int proLimit,
            Clock clock,
            String llmProvider) {
        this.repository = repository;
        this.activityRepository = activityRepository;
        this.defaultLimit = Math.max(defaultLimit, 0);
        this.plusLimit = Math.max(plusLimit, 0);
        this.proLimit = Math.max(proLimit, 0);
        this.clock = clock;
        this.llmProvider = llmProvider != null ? llmProvider.trim().toLowerCase() : "mock";
    }

    public UsageQuotaService(
            UsageQuotaRepository repository,
            UserActivityLogRepository activityRepository,
            int defaultLimit,
            int proLimit,
            Clock clock,
            String llmProvider) {
        this(repository, activityRepository, defaultLimit, 200, proLimit, clock, llmProvider);
    }

    UsageQuotaService(UsageQuotaRepository repository, int defaultLimit, Clock clock) {
        this(repository, null, defaultLimit, 200, 300, clock, "real");
    }

    UsageQuotaService(
            UsageQuotaRepository repository,
            UserActivityLogRepository activityRepository,
            int defaultLimit,
            Clock clock) {
        this(repository, activityRepository, defaultLimit, 200, 300, clock, "real");
    }

    UsageQuotaService(
            UsageQuotaRepository repository,
            UserActivityLogRepository activityRepository,
            int defaultLimit,
            Clock clock,
            String llmProvider) {
        this(repository, activityRepository, defaultLimit, 200, 300, clock, llmProvider);
    }

    @Transactional
    public synchronized UsageQuota upgradeToPlus(Long userId) {
        UsageQuota quota = currentForUpdate(userId);
        quota.setQuotaLimit(plusLimit);
        quota.setPeriodStart(LocalDate.now(clock));
        quota.setQuotaUsed(0);
        return repository.save(quota);
    }

    @Transactional
    public synchronized UsageQuota upgradeToPro(Long userId) {
        UsageQuota quota = currentForUpdate(userId);
        quota.setQuotaLimit(proLimit);
        quota.setPeriodStart(LocalDate.now(clock));
        quota.setQuotaUsed(0);
        return repository.save(quota);
    }

    @Transactional
    public synchronized UsageQuota upgradeToTier(Long userId, com.greytest.entity.enums.UserTier tier) {
        if (tier == com.greytest.entity.enums.UserTier.PRO) {
            return upgradeToPro(userId);
        } else if (tier == com.greytest.entity.enums.UserTier.PLUS) {
            return upgradeToPlus(userId);
        } else {
            return downgradeToFree(userId);
        }
    }

    @Transactional
    public synchronized UsageQuota downgradeToFree(Long userId) {
        UsageQuota quota = currentForUpdate(userId);
        quota.setQuotaLimit(defaultLimit);
        // Chuẩn hóa về tháng để lần đọc kế tiếp không vô tình xóa số lượt đã dùng.
        quota.setPeriodStart(LocalDate.now(clock).withDayOfMonth(1));
        return repository.save(quota);
    }

    public int getPlusLimit() {
        return plusLimit;
    }

    public int getProLimit() {
        return proLimit;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized UsageQuota consumeLlmCall(Long userId) {
        return consumeLlmCall(userId, null, 1, Map.of());
    }

    /** Giữ quota và ghi log trong cùng transaction trước khi gọi provider. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized UsageQuota consumeLlmCall(
            Long userId, Long projectId, Map<String, Object> metadata) {
        return consumeLlmCall(userId, projectId, 1, metadata);
    }

    /**
     * Giữ quota theo số lượng đơn vị kiểm thử (units) được xử lý trong đợt gọi.
     * 1 đơn vị tương ứng 1 Test Case, 1 Method hoặc 1 Business Rule.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized UsageQuota consumeLlmCall(
            Long userId, Long projectId, int units, Map<String, Object> metadata) {
        UsageQuota quota = currentForUpdate(userId);
        int amount = Math.max(units, 1);
        // Giới hạn null biểu thị unlimited nhưng vẫn ghi nhận số lần gọi LLM.
        if (!"mock".equalsIgnoreCase(llmProvider)
                && quota.getQuotaLimit() != null
                && quota.getQuotaUsed() >= quota.getQuotaLimit()) {
            throw new UsageQuotaExceededException(
                    "Bạn đã sử dụng hết quota LLM tháng này. Vui lòng liên hệ quản trị viên.");
        }
        quota.setQuotaUsed(quota.getQuotaUsed() + amount);
        UsageQuota saved = repository.save(quota);
        if (activityRepository != null) {
            UserActivityLog activity = new UserActivityLog();
            activity.setUserId(userId);
            activity.setActionType(ActivityAction.LLM_CALL);
            activity.setRelatedProjectId(projectId);
            java.util.Map<String, Object> enrichedMeta = new java.util.HashMap<>(metadata == null ? Map.of() : metadata);
            enrichedMeta.put("units", amount);
            activity.setMetadata(Map.copyOf(enrichedMeta));
            activityRepository.save(activity);
        }
        return saved;
    }

    /**
     * Kiểm tra người dùng còn hạn mức quota trước khi tiến hành xử lý hay không.
     * Dùng findByUserId thông thường (read-only, KHÔNG dùng khóa PESSIMISTIC_WRITE)
     * để tránh deadlock giữa transaction ngoài và transaction trừ quota REQUIRES_NEW.
     */
    @Transactional(readOnly = true)
    public synchronized void checkQuotaAvailable(Long userId) {
        if ("mock".equalsIgnoreCase(llmProvider)) {
            return;
        }
        UsageQuota quota = repository.findByUserId(userId)
                .orElse(null);
        if (quota != null && quota.getQuotaLimit() != null && quota.getQuotaUsed() >= quota.getQuotaLimit()) {
            throw new UsageQuotaExceededException(
                    "Bạn đã sử dụng hết quota LLM. Vui lòng nâng cấp gói hoặc liên hệ quản trị viên.");
        }
    }

    @Transactional
    public synchronized UsageQuota updateLimit(Long userId, Integer limit) {
        UsageQuota quota = currentForUpdate(userId);
        if (limit != null && limit < 0) throw new IllegalArgumentException("Quota không được âm");
        quota.setQuotaLimit(limit);
        return repository.save(quota);
    }

    @Transactional
    public synchronized UsageQuota current(Long userId) {
        return currentForUpdate(userId);
    }

    private UsageQuota currentForUpdate(Long userId) {
        LocalDate today = LocalDate.now(clock);
        UsageQuota quota = repository.findByUserIdForUpdate(userId)
                .orElseGet(() -> newQuota(userId, today.withDayOfMonth(1)));

        boolean isWeekly = quota.getQuotaLimit() != null && quota.getQuotaLimit() > defaultLimit;
        if (isWeekly) {
            LocalDate resetDate = quota.getPeriodStart().plusWeeks(1);
            if (!today.isBefore(resetDate)) {
                quota.setPeriodStart(today);
                quota.setQuotaUsed(0);
            }
        } else {
            LocalDate periodStart = today.withDayOfMonth(1);
            if (!periodStart.equals(quota.getPeriodStart())) {
                quota.setPeriodStart(periodStart);
                quota.setQuotaUsed(0);
            }
        }
        return repository.save(quota);
    }

    private UsageQuota newQuota(Long userId, LocalDate periodStart) {
        UsageQuota quota = new UsageQuota();
        quota.setUserId(userId);
        quota.setQuotaLimit(defaultLimit);
        quota.setQuotaUsed(0);
        quota.setPeriodStart(periodStart);
        return quota;
    }
}
