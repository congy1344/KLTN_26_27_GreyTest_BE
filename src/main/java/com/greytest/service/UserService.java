package com.greytest.service;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.UserQuotaResponse;
import com.greytest.entity.AuthUser;
import com.greytest.entity.UsageQuota;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.UserTier;
import com.greytest.repository.AuthUserRepository;

/**
 * Quản lý nâng cấp, hủy gói và hạn mức AI của tài khoản người dùng.
 */
@Service
public class UserService {

    private final AuthUserRepository users;
    private final UsageQuotaService quotaService;
    private final UserActivityService activityService;

    public UserService(
            AuthUserRepository users,
            UsageQuotaService quotaService,
            UserActivityService activityService) {
        this.users = users;
        this.quotaService = quotaService;
        this.activityService = activityService;
    }

    /**
     * Nâng cấp tài khoản hiện tại từ FREE lên PRO và tăng quota lên mức gói Pro (300 lượt/tuần).
     */
    @Transactional
    public UserQuotaResponse upgradeToPro(AuthUser currentUser) {
        return upgradeToTier(currentUser, UserTier.PRO);
    }

    /**
     * Nâng cấp tài khoản người dùng lên gói chỉ định (FREE, PLUS, PRO).
     */
    @Transactional
    public UserQuotaResponse upgradeToTier(AuthUser currentUser, UserTier targetTier) {
        AuthUser user = users.findById(currentUser.getId()).orElse(currentUser);
        UserTier previousTier = user.getTier() != null ? user.getTier() : UserTier.FREE;
        user.setTier(targetTier);
        users.save(user);

        UsageQuota quota = quotaService.upgradeToTier(user.getId(), targetTier);

        activityService.record(user.getId(), ActivityAction.USER_UPGRADE_TIER, null,
                Map.of("previousTier", previousTier.name(), "newTier", targetTier.name()));

        return toQuotaResponse(user.getTier(), quota);
    }

    /**
     * Lấy thông tin hạn mức quota LLM hiện tại của người dùng.
     */
    @Transactional
    public UserQuotaResponse currentQuota(AuthUser currentUser) {
        AuthUser user = users.findById(currentUser.getId()).orElse(currentUser);
        UsageQuota quota = quotaService.current(user.getId());
        return toQuotaResponse(user.getTier(), quota);
    }

    /** Hủy Plus/Pro về Free; yêu cầu lặp lại không cấp lại quota hoặc ghi thêm thay đổi. */
    @Transactional
    public UserQuotaResponse cancelSubscription(AuthUser currentUser) {
        AuthUser user = users.findById(currentUser.getId()).orElse(currentUser);
        UserTier previousTier = user.getTier() != null ? user.getTier() : UserTier.FREE;
        if (previousTier == UserTier.FREE) {
            return toQuotaResponse(UserTier.FREE, quotaService.current(user.getId()));
        }
        user.setTier(UserTier.FREE);
        users.save(user);
        UsageQuota quota = quotaService.downgradeToFree(user.getId());
        activityService.record(user.getId(), ActivityAction.USER_CANCEL_TIER, null,
                Map.of("previousTier", previousTier.name(), "newTier", UserTier.FREE.name()));
        return toQuotaResponse(UserTier.FREE, quota);
    }

    private UserQuotaResponse toQuotaResponse(UserTier tier, UsageQuota quota) {
        Integer limit = quota.getQuotaLimit();
        Integer remaining = limit == null ? null : Math.max(limit - quota.getQuotaUsed(), 0);
        boolean isWeekly = tier == UserTier.PRO || tier == UserTier.PLUS || (limit != null && limit > quotaService.getDefaultLimit());
        LocalDate resetDate = isWeekly
                ? (quota.getPeriodStart() != null ? quota.getPeriodStart().plusWeeks(1) : null)
                : (quota.getPeriodStart() != null ? quota.getPeriodStart().plusMonths(1) : null);
        return new UserQuotaResponse(
                tier != null ? tier : (isWeekly ? UserTier.PLUS : UserTier.FREE),
                limit,
                quota.getQuotaUsed(),
                remaining,
                quota.getPeriodStart(),
                resetDate,
                limit != null && quota.getQuotaUsed() >= limit
        );
    }
}
