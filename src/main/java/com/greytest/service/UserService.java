package com.greytest.service;

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
 * Service xử lý các nghiệp vụ tài khoản người dùng: nâng cấp gói Tier (Free -> Pro), xem hạn mức quota.
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
     * Nâng cấp tài khoản hiện tại từ FREE lên PRO và tăng quota lên mức gói Pro (1.000 lượt/tháng).
     */
    @Transactional
    public UserQuotaResponse upgradeToPro(AuthUser currentUser) {
        AuthUser user = users.findById(currentUser.getId()).orElse(currentUser);
        UserTier previousTier = user.getTier() != null ? user.getTier() : UserTier.FREE;
        user.setTier(UserTier.PRO);
        users.save(user);

        UsageQuota quota = quotaService.upgradeToPro(user.getId());

        activityService.record(user.getId(), ActivityAction.USER_UPGRADE_TIER, null,
                Map.of("previousTier", previousTier.name(), "newTier", UserTier.PRO.name()));

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

    private UserQuotaResponse toQuotaResponse(UserTier tier, UsageQuota quota) {
        Integer limit = quota.getQuotaLimit();
        Integer remaining = limit == null ? null : Math.max(limit - quota.getQuotaUsed(), 0);
        return new UserQuotaResponse(
                tier != null ? tier : UserTier.FREE,
                limit,
                quota.getQuotaUsed(),
                remaining,
                quota.getPeriodStart(),
                quota.getPeriodStart().plusMonths(1),
                limit != null && quota.getQuotaUsed() >= limit
        );
    }
}
