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

/**
 * Quản lý quota LLM theo tháng; reset được thực hiện lười ở lần truy cập đầu
 * tháng.
 */
@Service
public class UsageQuotaService {

    private final UsageQuotaRepository repository;
    private volatile int defaultLimit;
    private volatile int plusLimit;
    private volatile int proLimit;
    private final Clock clock;
    private final UserActivityLogRepository activityRepository;
    private final String llmProvider;

    @Autowired(required = false)
    private com.greytest.repository.TierPackageRepository tierPackageRepository;

    public void setTierPackageRepository(com.greytest.repository.TierPackageRepository tierPackageRepository) {
        this.tierPackageRepository = tierPackageRepository;
    }

    @Autowired(required = false)
    private com.greytest.repository.AuthUserRepository userRepository;

    public void setUserRepository(com.greytest.repository.AuthUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Chu kỳ reset quota lấy từ trường resetCycle của gói user đang dùng (admin sửa được
     * trong trang Gói). Hỗ trợ DAILY (hàng ngày), WEEKLY (hàng tuần), MONTHLY (hàng tháng).
     * Khi chưa có dữ liệu gói (unit test dựng tay, DB trống) thì giữ cách suy luận cũ theo hạn mức.
     */
    public String resolveResetCycle(Long userId) {
        String configured = configuredResetCycle(userId);
        if (configured != null) {
            return configured;
        }
        return "MONTHLY";
    }

    public String resolveResetCycle(UsageQuota quota) {
        if (quota == null) return "MONTHLY";
        String configured = configuredResetCycle(quota.getUserId());
        if (configured != null) {
            return configured;
        }
        boolean isWeekly = quota.getQuotaLimit() != null
                && (quota.getQuotaLimit().equals(getPlusLimit()) || quota.getQuotaLimit().equals(getProLimit())
                        || quota.getQuotaLimit() > defaultLimit);
        return isWeekly ? "WEEKLY" : "MONTHLY";
    }

    private String configuredResetCycle(Long userId) {
        if (userRepository == null || tierPackageRepository == null || userId == null)
            return null;
        com.greytest.entity.enums.UserTier tier = userRepository.findById(userId)
                .map(com.greytest.entity.AuthUser::getTier)
                .orElse(com.greytest.entity.enums.UserTier.FREE);
        if (tier == null)
            tier = com.greytest.entity.enums.UserTier.FREE;
        return tierPackageRepository.findById(tier.name())
                .map(pkg -> {
                    if (pkg.getResetCycle() != null && !pkg.getResetCycle().isBlank()) {
                        return pkg.getResetCycle().trim().toUpperCase();
                    }
                    if (pkg.getCycle() != null && !pkg.getCycle().isBlank()) {
                        return pkg.getCycle().trim().toUpperCase();
                    }
                    return null;
                })
                .orElse(null);
    }

    private void applyCycleRollOver(UsageQuota quota, LocalDate today) {
        String resetCycle = resolveResetCycle(quota);
        if ("DAILY".equals(resetCycle)) {
            if (quota.getPeriodStart() == null || !today.isBefore(quota.getPeriodStart().plusDays(1))) {
                quota.setPeriodStart(today);
                quota.setQuotaUsed(0);
            }
        } else if ("WEEKLY".equals(resetCycle)) {
            if (quota.getPeriodStart() == null || !today.isBefore(quota.getPeriodStart().plusWeeks(1))) {
                quota.setPeriodStart(today);
                quota.setQuotaUsed(0);
            }
        } else {
            // MONTHLY
            LocalDate periodStart = today.withDayOfMonth(1);
            if (quota.getPeriodStart() == null || quota.getPeriodStart().isBefore(periodStart)) {
                quota.setPeriodStart(periodStart);
                quota.setQuotaUsed(0);
            }
        }
    }

    private int resolveTierLimit(String tierCode, int fallback) {
        if (tierPackageRepository != null) {
            return tierPackageRepository.findById(tierCode)
                    .map(com.greytest.entity.TierPackage::getQuotaLimit)
                    .orElse(fallback);
        }
        return fallback;
    }

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
    public UsageQuota upgradeToPlus(Long userId) {
        UsageQuota quota = currentForUpdate(userId);
        quota.setQuotaLimit(resolveTierLimit("PLUS", plusLimit));
        quota.setPeriodStart(LocalDate.now(clock));
        quota.setQuotaUsed(0);
        return repository.save(quota);
    }

    @Transactional
    public UsageQuota upgradeToPro(Long userId) {
        UsageQuota quota = currentForUpdate(userId);
        quota.setQuotaLimit(resolveTierLimit("PRO", proLimit));
        quota.setPeriodStart(LocalDate.now(clock));
        quota.setQuotaUsed(0);
        return repository.save(quota);
    }

    @Transactional
    public UsageQuota upgradeToTier(Long userId, com.greytest.entity.enums.UserTier tier) {
        if (tier == com.greytest.entity.enums.UserTier.PRO) {
            return upgradeToPro(userId);
        } else if (tier == com.greytest.entity.enums.UserTier.PLUS) {
            return upgradeToPlus(userId);
        } else {
            return downgradeToFree(userId);
        }
    }

    @Transactional
    public UsageQuota downgradeToFree(Long userId) {
        UsageQuota quota = currentForUpdate(userId);
        quota.setQuotaLimit(resolveTierLimit("FREE", defaultLimit));
        // Chuẩn hóa về tháng để lần đọc kế tiếp không vô tình xóa số lượt đã dùng.
        quota.setPeriodStart(LocalDate.now(clock).withDayOfMonth(1));
        return repository.save(quota);
    }

    /**
     * Đặt lại số lượt đã sử dụng về 0 cho người dùng mà không làm hỏng chu kỳ
     * tuần/tháng hiện tại.
     */
    @Transactional
    public UsageQuota resetUsage(Long userId) {
        UsageQuota quota = currentForUpdate(userId);
        quota.setQuotaUsed(0);
        String resetCycle = resolveResetCycle(quota);
        if (!"DAILY".equals(resetCycle) && !"WEEKLY".equals(resetCycle)) {
            quota.setPeriodStart(LocalDate.now(clock).withDayOfMonth(1));
        }
        return repository.save(quota);
    }

    public int getPlusLimit() {
        return resolveTierLimit("PLUS", plusLimit);
    }

    public int getProLimit() {
        return resolveTierLimit("PRO", proLimit);
    }

    public int getDefaultLimit() {
        return resolveTierLimit("FREE", defaultLimit);
    }

    /**
     * Cập nhật hạn mức quota động theo cấu hình gói của quản trị viên.
     */
    public synchronized void setTierLimit(com.greytest.entity.enums.UserTier tier, int limit) {
        if (tier == com.greytest.entity.enums.UserTier.FREE) {
            this.defaultLimit = limit;
        } else if (tier == com.greytest.entity.enums.UserTier.PLUS) {
            this.plusLimit = limit;
        } else if (tier == com.greytest.entity.enums.UserTier.PRO) {
            this.proLimit = limit;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UsageQuota consumeLlmCall(Long userId) {
        return consumeLlmCall(userId, null, 1, Map.of());
    }

    /** Giữ quota và ghi log trong cùng transaction trước khi gọi provider. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UsageQuota consumeLlmCall(
            Long userId, Long projectId, Map<String, Object> metadata) {
        return consumeLlmCall(userId, projectId, 1, metadata);
    }

    /**
     * Giữ quota theo số lượng đơn vị kiểm thử (units) được xử lý trong đợt gọi.
     * 1 đơn vị tương ứng 1 Test Case, 1 Method hoặc 1 Business Rule.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UsageQuota consumeLlmCall(
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
            java.util.Map<String, Object> enrichedMeta = new java.util.HashMap<>(
                    metadata == null ? Map.of() : metadata);
            enrichedMeta.put("units", amount);
            activity.setMetadata(Map.copyOf(enrichedMeta));
            activityRepository.save(activity);
        }
        return saved;
    }

    /**
     * Kiểm tra người dùng còn hạn mức quota trước khi tiến hành xử lý hay không.
     * Dùng findByUserId thông thường (read-only, KHÔNG dùng khóa PESSIMISTIC_WRITE)
     * để tránh deadlock giữa transaction ngoài và transaction trừ quota
     * REQUIRES_NEW.
     */
    @Transactional(readOnly = true)
    public void checkQuotaAvailable(Long userId) {
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
    public UsageQuota updateLimit(Long userId, Integer limit) {
        UsageQuota quota = currentForUpdate(userId);
        if (limit != null && limit < 0)
            throw new IllegalArgumentException("Quota không được âm");
        quota.setQuotaLimit(limit);
        return repository.save(quota);
    }

    @Transactional
    public UsageQuota current(Long userId) {
        return currentForUpdate(userId);
    }

    /**
     * Đọc snapshot quota hiện tại theo chế độ chỉ đọc (không lấy row lock
     * PESSIMISTIC_WRITE),
     * dùng cho danh sách quản trị để tránh khóa chồng chéo giữa nhiều request đồng
     * thời.
     */
    @Transactional(readOnly = true)
    public UsageQuota currentReadOnly(Long userId) {
        LocalDate today = LocalDate.now(clock);
        UsageQuota existing = repository.findByUserId(userId).orElse(null);
        if (existing == null) {
            return newQuota(userId, today.withDayOfMonth(1));
        }
        UsageQuota snapshot = new UsageQuota();
        snapshot.setId(existing.getId());
        snapshot.setUserId(existing.getUserId());
        snapshot.setQuotaLimit(existing.getQuotaLimit());
        snapshot.setQuotaUsed(existing.getQuotaUsed());
        snapshot.setPeriodStart(existing.getPeriodStart());
        applyCycleRollOver(snapshot, today);
        return snapshot;
    }

    private UsageQuota currentForUpdate(Long userId) {
        LocalDate today = LocalDate.now(clock);
        LocalDate periodStart = today.withDayOfMonth(1);
        // `synchronized` không đủ: transaction commit sau khi rời method, nên hai luồng có thể cùng thấy "chưa có dòng"
        // và cùng insert. Upsert ở DB rồi mới khoá dòng.
        // Transaction read-only (màn hình chỉ đọc) không được chạy INSERT, kể cả ON CONFLICT DO NOTHING.
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            repository.insertIfMissing(userId, resolveTierLimit("FREE", defaultLimit), periodStart);
        }
        UsageQuota quota = repository.findByUserIdForUpdate(userId)
                .orElseGet(() -> newQuota(userId, periodStart));

        applyCycleRollOver(quota, today);
        return repository.save(quota);
    }

    private UsageQuota newQuota(Long userId, LocalDate periodStart) {
        UsageQuota quota = new UsageQuota();
        quota.setUserId(userId);
        quota.setQuotaLimit(resolveTierLimit("FREE", defaultLimit));
        quota.setQuotaUsed(0);
        quota.setPeriodStart(periodStart);
        return quota;
    }
}
