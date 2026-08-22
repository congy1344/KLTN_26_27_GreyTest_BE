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
    private final Clock clock;
    private final UserActivityLogRepository activityRepository;

    @Autowired
    public UsageQuotaService(
            UsageQuotaRepository repository,
            UserActivityLogRepository activityRepository,
            @Value("${greytest.usage.default-monthly-llm-quota:100}") int defaultLimit) {
        this(repository, activityRepository, defaultLimit, Clock.systemDefaultZone());
    }

    UsageQuotaService(UsageQuotaRepository repository, int defaultLimit, Clock clock) {
        this(repository, null, defaultLimit, clock);
    }

    UsageQuotaService(
            UsageQuotaRepository repository,
            UserActivityLogRepository activityRepository,
            int defaultLimit,
            Clock clock) {
        this.repository = repository;
        this.activityRepository = activityRepository;
        this.defaultLimit = Math.max(defaultLimit, 0);
        this.clock = clock;
    }

    @Transactional
    public synchronized UsageQuota consumeLlmCall(Long userId) {
        return consumeLlmCall(userId, null, Map.of());
    }

    /** Giữ quota và ghi log trong cùng transaction trước khi gọi provider. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized UsageQuota consumeLlmCall(
            Long userId, Long projectId, Map<String, Object> metadata) {
        UsageQuota quota = currentForUpdate(userId);
        if (quota.getQuotaUsed() >= quota.getQuotaLimit()) {
            throw new UsageQuotaExceededException(
                    "Bạn đã sử dụng hết quota LLM tháng này. Vui lòng liên hệ quản trị viên.");
        }
        quota.setQuotaUsed(quota.getQuotaUsed() + 1);
        UsageQuota saved = repository.save(quota);
        if (activityRepository != null) {
            UserActivityLog activity = new UserActivityLog();
            activity.setUserId(userId);
            activity.setActionType(ActivityAction.LLM_CALL);
            activity.setRelatedProjectId(projectId);
            activity.setMetadata(metadata == null ? Map.of() : Map.copyOf(metadata));
            activityRepository.save(activity);
        }
        return saved;
    }

    @Transactional
    public synchronized UsageQuota updateLimit(Long userId, int limit) {
        UsageQuota quota = currentForUpdate(userId);
        quota.setQuotaLimit(Math.max(limit, 0));
        return repository.save(quota);
    }

    @Transactional
    public synchronized UsageQuota current(Long userId) {
        return currentForUpdate(userId);
    }

    private UsageQuota currentForUpdate(Long userId) {
        LocalDate periodStart = LocalDate.now(clock).withDayOfMonth(1);
        UsageQuota quota = repository.findByUserIdForUpdate(userId)
                .orElseGet(() -> newQuota(userId, periodStart));
        if (!periodStart.equals(quota.getPeriodStart())) {
            quota.setPeriodStart(periodStart);
            quota.setQuotaUsed(0);
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
