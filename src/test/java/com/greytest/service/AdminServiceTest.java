package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.greytest.dto.admin.AdminDtos.PageDto;
import com.greytest.dto.admin.AdminDtos.QuotaDto;
import com.greytest.dto.admin.AdminDtos.UserSummaryDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.UsageQuota;
import com.greytest.entity.UserActivityLog;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.UserRole;
import com.greytest.entity.enums.UserTier;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.repository.UsageQuotaRepository;
import com.greytest.repository.UserActivityLogRepository;

class AdminServiceTest {

    private AuthUserRepository users;
    private ProjectRepository projects;
    private UnitTestRepository unitTests;
    private UserActivityLogRepository activities;
    private UsageQuotaRepository quotas;
    private UsageQuotaService quotaService;
    private UserActivityService activityService;
    private AdminService service;

    @BeforeEach
    void setUp() {
        users = mock(AuthUserRepository.class);
        projects = mock(ProjectRepository.class);
        unitTests = mock(UnitTestRepository.class);
        activities = mock(UserActivityLogRepository.class);
        quotas = mock(UsageQuotaRepository.class);
        quotaService = mock(UsageQuotaService.class);
        activityService = mock(UserActivityService.class);
        service = new AdminService(users, projects, unitTests, activities, quotas, quotaService, activityService);
    }

    @Test
    void rejectsLockingTheLastEnabledAdmin() {
        AuthUser target = target(UserRole.ADMIN, true);
        when(users.findEnabledAdminsForUpdate()).thenReturn(List.of(target));
        when(users.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(2L);

        assertThatThrownBy(() -> service.updateStatus(actor(), 2L, false, "Vi phạm quy định"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("admin");

        assertThat(target.getEnabled()).isTrue();
        verify(users, never()).save(any());
        var order = inOrder(users);
        order.verify(users).findEnabledAdminsForUpdate();
        order.verify(users).findById(2L);
        verify(users, never()).countByRoleAndEnabledTrue(any());
    }

    @Test
    void rejectsDemotingTheLastEnabledAdmin() {
        AuthUser target = target(UserRole.ADMIN, true);
        when(users.findEnabledAdminsForUpdate()).thenReturn(List.of(target));
        when(users.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(2L);

        assertThatThrownBy(() -> service.updateRole(actor(), 2L, UserRole.USER))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("admin");

        assertThat(target.getRole()).isEqualTo(UserRole.ADMIN);
        verify(users, never()).save(any());
        var order = inOrder(users);
        order.verify(users).findEnabledAdminsForUpdate();
        order.verify(users).findById(2L);
        verify(users, never()).countByRoleAndEnabledTrue(any());
    }

    @Test
    void rejectsSelfLockAndSelfDemotion() {
        when(users.findById(1L)).thenReturn(Optional.of(actor()));

        assertThatThrownBy(() -> service.updateStatus(actor(), 1L, false, "Thử nghiệm"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tự khóa");
        assertThatThrownBy(() -> service.updateRole(actor(), 1L, UserRole.USER))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tự hạ quyền");
        verify(users, never()).save(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void requiresReasonWhenLocking(String reason) {
        AuthUser target = target(UserRole.USER, true);

        assertThatThrownBy(() -> service.updateStatus(actor(), 2L, false, reason))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lý do");

        assertThat(target.getEnabled()).isTrue();
        verify(users, never()).save(any());
    }

    @Test
    void recordsStatusBeforeAndAfterValues() {
        target(UserRole.USER, true);

        UserSummaryDto result = service.updateStatus(actor(), 2L, false, "Tài khoản thử nghiệm");

        assertThat(result.enabled()).isFalse();
        verify(activityService).record(eq(1L), eq(ActivityAction.ADMIN_STATUS_CHANGE), isNull(),
                argThat(metadata -> metadata.equals(Map.of(
                        "targetUserId", 2L, "previousEnabled", true, "newEnabled", false,
                        "reason", "Tài khoản thử nghiệm"))));
    }

    @Test
    void unlocksWithoutRequiringReason() {
        target(UserRole.USER, false);

        assertThat(service.updateStatus(actor(), 2L, true, null).enabled()).isTrue();
        verify(activityService).record(eq(1L), eq(ActivityAction.ADMIN_STATUS_CHANGE), isNull(),
                argThat(metadata -> metadata.equals(Map.of(
                        "targetUserId", 2L, "previousEnabled", false, "newEnabled", true, "reason", ""))));
    }

    @Test
    void recordsRoleBeforeAndAfterValues() {
        target(UserRole.USER, true);

        assertThat(service.updateRole(actor(), 2L, UserRole.ADMIN).role()).isEqualTo(UserRole.ADMIN);
        verify(activityService).record(eq(1L), eq(ActivityAction.ADMIN_ROLE_CHANGE), isNull(),
                argThat(metadata -> metadata.equals(Map.of(
                        "targetUserId", 2L, "previousRole", "USER", "newRole", "ADMIN"))));
    }

    @Test
    void allowsChangingAnAdminWhenAnotherEnabledAdminExists() {
        AuthUser target = target(UserRole.ADMIN, true);
        when(users.findEnabledAdminsForUpdate()).thenReturn(List.of(actor(), target));

        assertThat(service.updateRole(actor(), 2L, UserRole.USER).role()).isEqualTo(UserRole.USER);
    }

    @Test
    void locksSharedAdminRowsBeforeDisablingAnAdmin() {
        AuthUser target = target(UserRole.ADMIN, true);
        when(users.findEnabledAdminsForUpdate()).thenReturn(List.of(actor(), target));

        assertThat(service.updateStatus(actor(), 2L, false, "Thử nghiệm").enabled()).isFalse();

        var order = inOrder(users);
        order.verify(users).findEnabledAdminsForUpdate();
        order.verify(users).findById(2L);
        order.verify(users).save(target);
        verify(users, never()).countByRoleAndEnabledTrue(any());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,true", "false,true", "true,false"})
    void sharedLockedSnapshotProtectsReciprocalStatusRoleAndMixedRemovals(
            boolean firstDemotes, boolean secondDemotes) {
        AuthUser first = actor();
        AuthUser second = target(UserRole.ADMIN, true);
        when(users.findById(1L)).thenReturn(Optional.of(first));
        when(users.findEnabledAdminsForUpdate()).thenReturn(List.of(first, second)).thenReturn(List.of(first));

        if (firstDemotes) service.updateRole(first, 2L, UserRole.USER);
        else service.updateStatus(first, 2L, false, "Thử nghiệm");

        assertThatThrownBy(() -> {
            if (secondDemotes) service.updateRole(second, 1L, UserRole.USER);
            else service.updateStatus(second, 1L, false, "Thử nghiệm");
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("admin");

        assertThat(first.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(first.getEnabled()).isTrue();
        verify(users, times(2)).findEnabledAdminsForUpdate();
        verify(users, never()).save(first);
        verify(users, never()).countByRoleAndEnabledTrue(any());
    }

    @Test
    void allowsDemotingAnAlreadyDisabledAdmin() {
        target(UserRole.ADMIN, false);

        assertThat(service.updateRole(actor(), 2L, UserRole.USER).role()).isEqualTo(UserRole.USER);
        verify(users, never()).countByRoleAndEnabledTrue(any());
    }

    @Test
    void listsGenerationRequestsLatestActivityAndQuotaResetDate() {
        AuthUser target = target(UserRole.USER, true);
        UserActivityLog latest = new UserActivityLog();
        latest.setCreatedAt(LocalDateTime.of(2026, 8, 20, 10, 0));
        when(users.findAll(ArgumentMatchers.<Specification<AuthUser>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(target)));
        when(activities.countByUserIdAndActionTypeIn(eq(2L), anyList())).thenReturn(4L);
        when(activities.findFirstByUserIdOrderByCreatedAtDesc(2L)).thenReturn(Optional.of(latest));

        PageDto<UserSummaryDto> result = service.listUsers(null, null, null, 0, 10, "createdAt", "desc");

        UserSummaryDto summary = result.content().get(0);
        assertThat(summary.totalGenerationRequests()).isEqualTo(4L);
        assertThat(summary.lastActivityAt()).isEqualTo(latest.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
        assertThat(summary.quota().remaining()).isEqualTo(7);
        assertThat(summary.quota().resetDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        verify(activities).countByUserIdAndActionTypeIn(2L, List.of(
                ActivityAction.GENERATE_BUSINESS_RULE, ActivityAction.GENERATE_TEST_PLAN,
                ActivityAction.GENERATE_TEST_CASE, ActivityAction.GENERATE_UNIT_TEST,
                ActivityAction.COVERAGE_REFINEMENT));
        verify(activities, never()).countByUserId(2L);
    }

    @Test
    void listsUnlimitedQuotaAndNoActivity() {
        AuthUser target = target(UserRole.USER, true);
        when(users.findAll(ArgumentMatchers.<Specification<AuthUser>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(target)));
        when(quotaService.current(2L)).thenReturn(quota(null, 100));

        UserSummaryDto summary = service.listUsers(null, null, null, 0, 10, "createdAt", "desc").content().get(0);

        assertThat(summary.totalGenerationRequests()).isZero();
        assertThat(summary.lastActivityAt()).isNull();
        assertThat(summary.quota().limit()).isNull();
        assertThat(summary.quota().remaining()).isNull();
        assertThat(summary.quota().exceeded()).isFalse();
    }

    @Test
    void recordsUnlimitedQuotaAsAnExplicitNull() {
        target(UserRole.USER, true);
        when(quotaService.updateLimit(2L, null)).thenReturn(quota(null, 3));
        service = new AdminService(users, projects, unitTests, activities, quotas, quotaService,
                new UserActivityService(activities));

        QuotaDto result = service.updateQuota(actor(), 2L, null);

        assertThat(result.limit()).isNull();
        assertThat(result.remaining()).isNull();
        assertThat(result.exceeded()).isFalse();
        verify(activities).save(argThat(activity -> activity.getUserId().equals(1L)
                && activity.getActionType() == ActivityAction.ADMIN_QUOTA_CHANGE
                && activity.getMetadata().get("targetUserId").equals(2L)
                && activity.getMetadata().containsKey("quotaLimit")
                && activity.getMetadata().get("quotaLimit") == null));
    }

    @Test
    void finiteQuotaClampsRemainingAtZeroWhenExceeded() {
        target(UserRole.USER, true);
        when(quotaService.updateLimit(2L, 2)).thenReturn(quota(2, 3));

        QuotaDto result = service.updateQuota(actor(), 2L, 2);

        assertThat(result.remaining()).isZero();
        assertThat(result.exceeded()).isTrue();
    }

    private AuthUser target(UserRole role, boolean enabled) {
        AuthUser target = user(2L, role, enabled);
        when(users.findById(2L)).thenReturn(Optional.of(target));
        when(users.save(target)).thenReturn(target);
        when(quotaService.current(2L)).thenReturn(quota(10, 3));
        return target;
    }

    private AuthUser actor() {
        return user(1L, UserRole.ADMIN, true);
    }

    private AuthUser user(Long id, UserRole role, boolean enabled) {
        AuthUser user = new AuthUser();
        user.setId(id);
        user.setRole(role);
        user.setEnabled(enabled);
        user.setEmail("user" + id + "@greytest.dev");
        user.setCreatedAt(LocalDateTime.of(2026, 8, 1, 0, 0));
        return user;
    }

    private UsageQuota quota(Integer limit, int used) {
        UsageQuota quota = new UsageQuota();
        quota.setQuotaLimit(limit);
        quota.setQuotaUsed(used);
        quota.setPeriodStart(LocalDate.of(2026, 8, 1));
        return quota;
    }

    @Test
    void updatesUserTierAndSynchronizesQuota() {
        AuthUser target = target(UserRole.USER, true);

        UserSummaryDto updated = service.updateTier(actor(), 2L, UserTier.PRO);

        assertThat(target.getTier()).isEqualTo(UserTier.PRO);
        assertThat(updated.tier()).isEqualTo(UserTier.PRO);
        verify(quotaService).upgradeToPro(2L);
        verify(users).save(target);
        verify(activityService).record(eq(1L), eq(ActivityAction.ADMIN_TIER_CHANGE), isNull(), any());
    }

    @Test
    void sortsUsersByTotalGenerationRequestsDescending() {
        AuthUser user1 = user(1L, UserRole.USER, true);
        AuthUser user2 = user(2L, UserRole.USER, true);
        when(users.findAll(ArgumentMatchers.<Specification<AuthUser>>any())).thenReturn(List.of(user1, user2));
        when(activities.countByUserIdAndActionTypeIn(eq(1L), anyList())).thenReturn(2L);
        when(activities.countByUserIdAndActionTypeIn(eq(2L), anyList())).thenReturn(10L);
        when(quotaService.current(any())).thenReturn(quota(100, 0));

        PageDto<UserSummaryDto> result = service.listUsers(null, null, null, 0, 10, "totalGenerationRequests", "desc");

        assertThat(result.content()).hasSize(2);
        assertThat(result.content().get(0).id()).isEqualTo(2L);
        assertThat(result.content().get(1).id()).isEqualTo(1L);
    }

    @Test
    void sortsUsersByLastActivityAtAscending() {
        AuthUser user1 = user(1L, UserRole.USER, true);
        AuthUser user2 = user(2L, UserRole.USER, true);
        UserActivityLog log1 = new UserActivityLog();
        log1.setCreatedAt(LocalDateTime.of(2026, 8, 20, 10, 0));
        UserActivityLog log2 = new UserActivityLog();
        log2.setCreatedAt(LocalDateTime.of(2026, 8, 25, 10, 0));

        when(users.findAll(ArgumentMatchers.<Specification<AuthUser>>any())).thenReturn(List.of(user2, user1));
        when(activities.findFirstByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(log1));
        when(activities.findFirstByUserIdOrderByCreatedAtDesc(2L)).thenReturn(Optional.of(log2));
        when(quotaService.current(any())).thenReturn(quota(100, 0));

        PageDto<UserSummaryDto> result = service.listUsers(null, null, null, 0, 10, "lastActivityAt", "asc");

        assertThat(result.content()).hasSize(2);
        assertThat(result.content().get(0).id()).isEqualTo(1L);
        assertThat(result.content().get(1).id()).isEqualTo(2L);
    }

    @Test
    void sortsUsersByQuotaUsedDescending() {
        AuthUser user1 = user(1L, UserRole.USER, true);
        AuthUser user2 = user(2L, UserRole.USER, true);
        when(users.findAll(ArgumentMatchers.<Specification<AuthUser>>any())).thenReturn(List.of(user1, user2));
        when(quotaService.current(1L)).thenReturn(quota(100, 15));
        when(quotaService.current(2L)).thenReturn(quota(100, 80));

        PageDto<UserSummaryDto> result = service.listUsers(null, null, null, 0, 10, "quota", "desc");

        assertThat(result.content()).hasSize(2);
        assertThat(result.content().get(0).id()).isEqualTo(2L);
        assertThat(result.content().get(1).id()).isEqualTo(1L);
    }
}
