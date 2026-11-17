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
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.CoverageReportRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.repository.UsageQuotaRepository;
import com.greytest.repository.UserActivityLogRepository;
import com.greytest.repository.UserFeedbackRepository;
import com.greytest.entity.CoverageReport;
import com.greytest.entity.UserFeedback;
import com.greytest.dto.admin.AdminDtos;

class AdminServiceTest {

    private AuthUserRepository users;
    private ProjectRepository projects;
    private UnitTestRepository unitTests;
    private UserActivityLogRepository activities;
    private UsageQuotaRepository quotas;
    private UsageQuotaService quotaService;
    private UserActivityService activityService;
    private BusinessRuleRepository businessRules;
    private TestPlanRepository testPlans;
    private TestCaseRepository testCases;
    private CoverageReportRepository coverageReports;
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
        businessRules = mock(BusinessRuleRepository.class);
        testPlans = mock(TestPlanRepository.class);
        testCases = mock(TestCaseRepository.class);
        coverageReports = mock(CoverageReportRepository.class);
        service = new AdminService(
                users, projects, unitTests, activities, quotas, quotaService, activityService,
                businessRules, testPlans, testCases, coverageReports);
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
        verify(quotaService).upgradeToTier(2L, UserTier.PRO);
        verify(users).save(target);
        verify(activityService).record(eq(1L), eq(ActivityAction.ADMIN_TIER_CHANGE), isNull(), any());
    }

    @Test
    void updatesUserTierToPlusAndSynchronizesQuota() {
        AuthUser target = target(UserRole.USER, true);

        UserSummaryDto updated = service.updateTier(actor(), 2L, UserTier.PLUS);

        assertThat(target.getTier()).isEqualTo(UserTier.PLUS);
        assertThat(updated.tier()).isEqualTo(UserTier.PLUS);
        verify(quotaService).upgradeToTier(2L, UserTier.PLUS);
        verify(users).save(target);
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

    @Test
    void calculatesOverviewMetricsAccuratelyWithLatestCoverageReports() {
        when(users.count()).thenReturn(10L);
        when(users.countByCreatedAtAfter(any())).thenReturn(2L);
        when(activities.countByActionTypeIn(anyList())).thenReturn(25L);
        when(activities.countByActionType(ActivityAction.LLM_CALL)).thenReturn(50L);
        when(activities.countByActionTypeInAndCreatedAtAfter(anyList(), any())).thenReturn(14L);
        when(activities.countByActionTypeAndCreatedAtAfter(eq(ActivityAction.LLM_CALL), any())).thenReturn(45L);
        when(quotas.countExceededInPeriod(any())).thenReturn(1L);

        when(projects.count()).thenReturn(3L);
        when(businessRules.count()).thenReturn(20L);
        when(testPlans.count()).thenReturn(5L);
        when(testCases.count()).thenReturn(40L);
        when(unitTests.count()).thenReturn(38L);

        // Giả lập 2 báo cáo cho cùng 1 project, id 2 là mới nhất (line: 85.0%, branch: 75.0%)
        CoverageReport oldReport = new CoverageReport();
        oldReport.setId(1L);
        oldReport.setProjectId(100L);
        oldReport.setServicePath(".");
        oldReport.setLineCoverage(new java.math.BigDecimal("50.0"));
        oldReport.setBranchCoverage(new java.math.BigDecimal("40.0"));

        CoverageReport latestReport = new CoverageReport();
        latestReport.setId(2L);
        latestReport.setProjectId(100L);
        latestReport.setServicePath(".");
        latestReport.setLineCoverage(new java.math.BigDecimal("85.0"));
        latestReport.setBranchCoverage(new java.math.BigDecimal("75.0"));

        when(coverageReports.findAll()).thenReturn(List.of(oldReport, latestReport));

        AdminDtos.OverviewDto dto = service.overview();

        assertThat(dto.totalUsers()).isEqualTo(10L);
        assertThat(dto.totalProjects()).isEqualTo(3L);
        assertThat(dto.totalBusinessRules()).isEqualTo(20L);
        assertThat(dto.totalTestPlans()).isEqualTo(5L);
        assertThat(dto.totalTestCases()).isEqualTo(40L);
        assertThat(dto.totalUnitTests()).isEqualTo(38L);
        assertThat(dto.avgLineCoverage()).isEqualTo(85.0);
        assertThat(dto.avgBranchCoverage()).isEqualTo(75.0);
        assertThat(dto.totalCoverageReports()).isEqualTo(2L);
        assertThat(dto.generationRequests7Days()).isEqualTo(14L);
        assertThat(dto.llmCalls7Days()).isEqualTo(45L);
    }

    @Test
    void returnsActiveJobsFromProgressService() {
        GenerationProgressService progressService = mock(GenerationProgressService.class);
        AdminService svcWithProgress = new AdminService(
                users, projects, unitTests, activities, quotas, quotaService, activityService,
                businessRules, testPlans, testCases, coverageReports, progressService);

        com.greytest.dto.GenerationProgressDto snap = new com.greytest.dto.GenerationProgressDto(
                com.greytest.dto.GenerationProgressStage.UNIT_TEST,
                com.greytest.dto.GenerationProgressStatus.RUNNING,
                50, 2, 4, List.of(),
                List.of(new com.greytest.dto.GenerationProgressLogDto(java.time.Instant.now(), "Running tests")));

        when(progressService.getActiveProgressItems()).thenReturn(List.of(
                new GenerationProgressService.ActiveProgressItem(99L, com.greytest.dto.GenerationProgressStage.UNIT_TEST, snap)));

        com.greytest.entity.Project p = new com.greytest.entity.Project();
        p.setName("My Project");
        p.setOwnerUserId(5L);
        when(projects.findById(99L)).thenReturn(java.util.Optional.of(p));

        AuthUser owner = new AuthUser();
        owner.setEmail("user@greytest.dev");
        when(users.findById(5L)).thenReturn(java.util.Optional.of(owner));

        List<AdminDtos.ActiveJobDto> active = svcWithProgress.activeJobs(null);

        assertThat(active).hasSize(1);
        assertThat(active.get(0).projectId()).isEqualTo(99L);
        assertThat(active.get(0).projectName()).isEqualTo("My Project");
        assertThat(active.get(0).userEmail()).isEqualTo("user@greytest.dev");
        assertThat(active.get(0).percent()).isEqualTo(50);
        assertThat(active.get(0).message()).isEqualTo("Running tests");

        // Verify stage filtering
        assertThat(svcWithProgress.activeJobs(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE)).isEmpty();
        assertThat(svcWithProgress.activeJobs(com.greytest.dto.GenerationProgressStage.UNIT_TEST)).hasSize(1);
    }

    @Test
    void createUserSavesUserAndInitializesQuota() {
        when(users.findByEmailIgnoreCase("newuser@greytest.dev")).thenReturn(Optional.empty());
        when(users.save(any(AuthUser.class))).thenAnswer(inv -> {
            AuthUser u = inv.getArgument(0);
            u.setId(77L);
            return u;
        });

        com.greytest.dto.admin.CreateUserRequest req = new com.greytest.dto.admin.CreateUserRequest(
                "newuser@greytest.dev", "secret123", "New User", UserRole.USER, UserTier.PLUS, 250);

        AdminDtos.UserSummaryDto created = service.createUser(actor(), req);

        assertThat(created.id()).isEqualTo(77L);
        assertThat(created.email()).isEqualTo("newuser@greytest.dev");
        assertThat(created.fullName()).isEqualTo("New User");
        assertThat(created.role()).isEqualTo(UserRole.USER);
        assertThat(created.tier()).isEqualTo(UserTier.PLUS);
        verify(quotaService).upgradeToPlus(77L);
        verify(quotaService).updateLimit(77L, 250);
    }

    @Test
    void createUserRejectsExistingEmail() {
        when(users.findByEmailIgnoreCase("existing@greytest.dev")).thenReturn(Optional.of(new AuthUser()));

        com.greytest.dto.admin.CreateUserRequest req = new com.greytest.dto.admin.CreateUserRequest(
                "existing@greytest.dev", "secret123", null, null, null, null);

        assertThatThrownBy(() -> service.createUser(actor(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("đã tồn tại");
    }

    @Test
    void updateUserDetailsAndSyncsQuota() {
        AuthUser existing = new AuthUser();
        existing.setId(20L);
        existing.setEmail("target@greytest.dev");
        existing.setFullName("Old Name");
        existing.setRole(UserRole.USER);
        existing.setTier(UserTier.FREE);

        when(users.findById(20L)).thenReturn(Optional.of(existing));
        when(users.save(any(AuthUser.class))).thenAnswer(inv -> inv.getArgument(0));

        com.greytest.dto.admin.UpdateUserRequest req = new com.greytest.dto.admin.UpdateUserRequest(
                "New Name", "newpass123", UserRole.ADMIN, UserTier.PRO, 500, false);

        AdminDtos.UserSummaryDto updated = service.updateUser(actor(), 20L, req);

        assertThat(updated.fullName()).isEqualTo("New Name");
        assertThat(updated.role()).isEqualTo(UserRole.ADMIN);
        assertThat(updated.tier()).isEqualTo(UserTier.PRO);
        verify(quotaService).upgradeToTier(20L, UserTier.PRO);
        verify(quotaService).updateLimit(20L, 500);
    }

    @Test
    void updateUserDetailsWithUnlimitedQuota() {
        AuthUser existing = new AuthUser();
        existing.setId(20L);
        existing.setEmail("target@greytest.dev");
        existing.setRole(UserRole.USER);
        existing.setTier(UserTier.FREE);

        when(users.findById(20L)).thenReturn(Optional.of(existing));
        when(users.save(any(AuthUser.class))).thenAnswer(inv -> inv.getArgument(0));

        com.greytest.dto.admin.UpdateUserRequest req = new com.greytest.dto.admin.UpdateUserRequest(
                null, null, null, null, null, true);

        service.updateUser(actor(), 20L, req);

        verify(quotaService).updateLimit(20L, null);
    }

    @Test
    void updateUserRejectsDemotingTheLastEnabledAdmin() {
        AuthUser target = target(UserRole.ADMIN, true);
        target.setId(20L);
        when(users.findById(20L)).thenReturn(Optional.of(target));
        when(users.findEnabledAdminsForUpdate()).thenReturn(List.of(target));

        com.greytest.dto.admin.UpdateUserRequest req = new com.greytest.dto.admin.UpdateUserRequest(
                null, null, UserRole.USER, null, null, false);

        assertThatThrownBy(() -> service.updateUser(actor(), 20L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("admin");
    }

    @Test
    void deleteUserRemovesUserAndAssociatedData() {
        AuthUser target = new AuthUser();
        target.setId(30L);
        target.setEmail("to-delete@greytest.dev");

        when(users.findById(30L)).thenReturn(Optional.of(target));

        service.deleteUser(actor(), 30L);

        verify(quotas).deleteByUserId(30L);
        verify(users).delete(target);
    }

    @Test
    void deleteUserRejectsDeletingLastEnabledAdmin() {
        AuthUser target = target(UserRole.ADMIN, true);
        target.setId(30L);
        when(users.findById(30L)).thenReturn(Optional.of(target));
        when(users.findEnabledAdminsForUpdate()).thenReturn(List.of(target));

        assertThatThrownBy(() -> service.deleteUser(actor(), 30L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("admin");
    }

    @Test
    void deleteUserRejectsSelfDeletion() {
        AuthUser admin = actor();
        admin.setId(1L);

        assertThatThrownBy(() -> service.deleteUser(admin, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tự xóa");
    }

    @Test
    void resetQuotaResetsUsageAndAudits() {
        AuthUser target = new AuthUser();
        target.setId(40L);
        when(users.findById(40L)).thenReturn(Optional.of(target));

        UsageQuota q = new UsageQuota();
        q.setUserId(40L);
        q.setQuotaLimit(100);
        q.setQuotaUsed(0);
        q.setPeriodStart(LocalDate.now());
        when(quotaService.resetUsage(40L)).thenReturn(q);

        AdminDtos.QuotaDto result = service.resetQuota(actor(), 40L);

        assertThat(result.used()).isZero();
        verify(quotaService).resetUsage(40L);
    }

    @Test
    void cancelActiveJobFailsProgressService() {
        GenerationProgressService progressService = mock(GenerationProgressService.class);
        AdminService svc = new AdminService(
                users, projects, unitTests, activities, quotas, quotaService, activityService,
                businessRules, testPlans, testCases, coverageReports, progressService);

        svc.cancelActiveJob(actor(), 88L, com.greytest.dto.GenerationProgressStage.UNIT_TEST);

        verify(progressService).fail(88L, com.greytest.dto.GenerationProgressStage.UNIT_TEST, "Tác vụ đã bị hủy bởi Quản trị viên.");
    }

    @Test
    void getAiRuntimeConfigReturnsSlotsAndLimits() {
        AdminDtos.AiRuntimeConfigDto config = service.getAiRuntimeConfig();

        assertThat(config).isNotNull();
        assertThat(config.keys()).hasSize(8);
        assertThat(config.keys().get(0).envVar()).isEqualTo("LLM_API_KEY");
    }

    @Test
    void updateAiKeysUpdatesSlotsAndAudits() {
        var req = new AdminDtos.UpdateAiKeysRequest(List.of(
                new AdminDtos.UpdateApiKeySlotRequest(0, "sk-test-new-key-12345"),
                new AdminDtos.UpdateApiKeySlotRequest(1, "sk-test-backup-key-67890")));
        // Khoá API giờ được lưu bền dạng mã hoá nên cần secret mã hoá.
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "settingsCipher", new SettingsCipher("test-settings-secret"));

        AdminDtos.AiRuntimeConfigDto updated = service.updateAiKeys(actor(), req);

        assertThat(updated.keys().get(0).maskedKey()).contains("••••••••");
        assertThat(updated.keys().get(0).configured()).isTrue();
        assertThat(updated.keys().get(1).maskedKey()).contains("••••••••");
        assertThat(updated.keys().get(1).configured()).isTrue();
        verify(activityService).record(eq(1L), eq(ActivityAction.ADMIN_STATUS_CHANGE), isNull(), any());
    }

    @Test
    void updateStageLimitsUpdatesLimitsAndAudits() {
        com.greytest.service.agent.LlmStageTokenLimits limits = com.greytest.service.agent.LlmStageTokenLimits.defaults();
        service.setStageTokenLimits(limits);

        var req = new AdminDtos.UpdateStageLimitsRequest(Map.of("unit-test", 16384, "test-plan", 8192));

        AdminDtos.AiRuntimeConfigDto updated = service.updateStageLimits(actor(), req);

        assertThat(updated.stageLimits()).containsEntry("unit-test", 16384);
        assertThat(limits.optionsFor("unit-test").maxTokens()).isEqualTo(16384);
        verify(activityService).record(eq(1L), eq(ActivityAction.ADMIN_STATUS_CHANGE), isNull(), any());
    }

    @Test
    void tierPackagesCrudLifecycle() {
        when(users.countByTier(UserTier.FREE)).thenReturn(10L);
        when(users.countByTier(UserTier.PLUS)).thenReturn(2L);
        when(users.countByTier(UserTier.PRO)).thenReturn(5L);

        List<AdminDtos.TierPackageDto> pkgs = service.getTierPackages();
        assertThat(pkgs).hasSize(3);
        assertThat(pkgs.get(0).code()).isEqualTo("FREE");
        assertThat(pkgs.get(0).activeUsersCount()).isEqualTo(10L);
        assertThat(pkgs.get(2).code()).isEqualTo("PRO");
        assertThat(pkgs.get(2).activeUsersCount()).isEqualTo(5L);

        // Create with features containing null/blank (sanitized)
        var createReq = new AdminDtos.SaveTierPackageRequest("ENT", "Enterprise", 50000L, "YEARLY", 500, 8, java.util.Arrays.asList("Dedicated Support", null, "  "), "Cho doanh nghiệp", true);
        var created = service.createTierPackage(actor(), createReq);
        assertThat(created.code()).isEqualTo("ENT");
        assertThat(created.price()).isEqualTo(50000L);
        assertThat(created.features()).containsExactly("Dedicated Support");

        // Update with null req throws IllegalArgumentException
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.updateTierPackage(actor(), "PRO", null));

        // Update
        var updateReq = new AdminDtos.SaveTierPackageRequest("PRO", "Gói PRO Nâng Cao", 25000L, "MONTHLY", 350, 4, List.of("Full AST"), "Mô tả mới", true);
        var updated = service.updateTierPackage(actor(), "PRO", updateReq);
        assertThat(updated.name()).isEqualTo("Gói PRO Nâng Cao");
        assertThat(updated.price()).isEqualTo(25000L);
        assertThat(updated.quotaLimit()).isEqualTo(350);

        // Delete non-existent package throws IllegalArgumentException
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.deleteTierPackage(actor(), "NON_EXISTING"));

        // Delete custom
        service.deleteTierPackage(actor(), "ENT");
        assertThat(service.getTierPackages().stream().anyMatch(p -> p.code().equals("ENT"))).isFalse();

        // Delete core tier disables active
        service.deleteTierPackage(actor(), "FREE");
        assertThat(service.getTierPackages().stream().filter(p -> p.code().equals("FREE")).findFirst().get().active()).isFalse();
    }

    @Test
    void feedbacksLifecycle() {
        // Đảm bảo không còn dữ liệu mock mặc định ban đầu
        assertThat(service.getFeedbacks(null, null, null)).isEmpty();
        assertThat(service.getFeedbackOverview().total()).isZero();

        // Create feedback
        var createReq = new AdminDtos.SaveFeedbackRequest("BUG_GENERATION", "Test Feedback", "Chi tiết phản hồi", 5, "MyProject");
        var created = service.createFeedback(actor(), createReq);
        assertThat(created.id()).isNotNull();
        assertThat(created.title()).isEqualTo("Test Feedback");
        assertThat(created.status()).isEqualTo("NEW");

        var list = service.getFeedbacks(null, null, null);
        assertThat(list).hasSize(1);

        var stats = service.getFeedbackOverview();
        assertThat(stats.total()).isEqualTo(1L);
        assertThat(stats.pending()).isEqualTo(1L);
        assertThat(stats.resolved()).isZero();

        // Filter by category
        var bugList = service.getFeedbacks(null, "BUG_GENERATION", null);
        assertThat(bugList).hasSize(1);
        assertThat(bugList).allMatch(f -> "BUG_GENERATION".equalsIgnoreCase(f.category()));

        // Update status
        var updateReq = new AdminDtos.UpdateFeedbackStatusRequest("RESOLVED", "Đã xử lý xong");
        var updated = service.updateFeedbackStatus(actor(), created.id(), updateReq);
        assertThat(updated.status()).isEqualTo("RESOLVED");
        assertThat(updated.adminNote()).isEqualTo("Đã xử lý xong");
        assertThat(updated.resolvedAt()).isNotNull();

        var updatedStats = service.getFeedbackOverview();
        assertThat(updatedStats.pending()).isZero();
        assertThat(updatedStats.resolved()).isEqualTo(1L);

        // Delete feedback
        service.deleteFeedback(actor(), created.id());
        assertThat(service.getFeedbacks(null, null, null)).isEmpty();
        assertThat(service.getFeedbackOverview().total()).isZero();
    }

    @Test
    void updateAiModelLifecycle() {
        var req = new com.greytest.dto.admin.AdminDtos.UpdateAiModelRequest("gpt-4o", "mock");
        var res = service.updateAiModel(actor(), req);
        assertThat(res.model()).isEqualTo("gpt-4o");
        assertThat(res.provider()).isEqualTo("mock");

        // Reject mismatch provider
        var invalidReq = new com.greytest.dto.admin.AdminDtos.UpdateAiModelRequest("gemini-1.5-flash", "google");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.updateAiModel(actor(), invalidReq));
    }

    @Test
    void trendWithGranularities() {
        var proj = mock(UserActivityLogRepository.UsageTrendProjection.class);
        when(proj.getBucket()).thenReturn(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(proj.getTotal()).thenReturn(42L);
        when(activities.usageTrend(any(LocalDateTime.class), eq("year"), isNull()))
                .thenReturn(List.of(proj));

        var result = service.trend(1825, "year", null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).total()).isEqualTo(42L);
    }

    @Test
    void trendWithNullAndFallbackGranularity() {
        when(activities.usageTrend(any(LocalDateTime.class), eq("day"), isNull()))
                .thenReturn(List.of());

        var resultNull = service.trend(30, null, null);
        assertThat(resultNull).isNotNull();

        var resultInvalid = service.trend(5000, "invalid_grain", null);
        assertThat(resultInvalid).isNotNull();
        verify(activities, times(2)).usageTrend(any(LocalDateTime.class), eq("day"), isNull());
    }

    @Test
    void trendWithCustomDateRangeBetween() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 8, 23, 59);

        var proj = mock(UserActivityLogRepository.UsageTrendProjection.class);
        when(proj.getBucket()).thenReturn(from);
        when(proj.getTotal()).thenReturn(15L);

        when(activities.usageTrendBetween(eq(from), eq(to), eq("day"), isNull()))
                .thenReturn(List.of(proj));

        var result = service.trend(from, to, 30, "day", null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).total()).isEqualTo(15L);
        verify(activities).usageTrendBetween(eq(from), eq(to), eq("day"), isNull());
    }

    @Test
    void trendWithCustomDateRangeSwappedFromTo() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 8, 23, 59, 59);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0, 0);

        var proj = mock(UserActivityLogRepository.UsageTrendProjection.class);
        when(proj.getBucket()).thenReturn(to);
        when(proj.getTotal()).thenReturn(20L);

        when(activities.usageTrendBetween(any(LocalDateTime.class), any(LocalDateTime.class), eq("day"), isNull()))
                .thenReturn(List.of(proj));

        var result = service.trend(from, to, 30, "day", null);
        assertThat(result).hasSize(1);
        verify(activities).usageTrendBetween(eq(to.toLocalDate().atStartOfDay()), any(LocalDateTime.class), eq("day"), isNull());
    }

    @Test
    void trendWithOnlyToSpecified() {
        LocalDateTime to = LocalDateTime.of(2026, 11, 8, 23, 59, 59);

        var proj = mock(UserActivityLogRepository.UsageTrendProjection.class);
        when(proj.getBucket()).thenReturn(to.minusDays(7));
        when(proj.getTotal()).thenReturn(8L);

        when(activities.usageTrendBetween(any(LocalDateTime.class), eq(to), eq("day"), isNull()))
                .thenReturn(List.of(proj));

        var result = service.trend(null, to, 7, "day", null);
        assertThat(result).hasSize(1);
        verify(activities).usageTrendBetween(eq(to.minusDays(7)), eq(to), eq("day"), isNull());
    }

    @Test
    void trendWithOnlyFromSpecified() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 1, 0, 0, 0);

        var proj = mock(UserActivityLogRepository.UsageTrendProjection.class);
        when(proj.getBucket()).thenReturn(from);
        when(proj.getTotal()).thenReturn(5L);

        when(activities.usageTrend(eq(from), eq("day"), isNull()))
                .thenReturn(List.of(proj));

        var result = service.trend(from, null, 30, "day", null);
        assertThat(result).hasSize(1);
        verify(activities).usageTrend(eq(from), eq("day"), isNull());
    }

    @Test
    void activitiesFilteringAndSearch() {
        AuthUser u1 = target(UserRole.USER, true);
        u1.setId(10L);
        u1.setEmail("dev@greytest.dev");
        u1.setFullName("Dev Lead");

        // 1. Search by userSearch matching email/name
        when(users.findAll(any(Specification.class))).thenReturn(List.of(u1));
        UserActivityLog log1 = new UserActivityLog();
        log1.setId(101L);
        log1.setUserId(10L);
        log1.setActionType(ActivityAction.GENERATE_TEST_PLAN);
        log1.setCreatedAt(LocalDateTime.now());
        when(activities.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log1)));
        when(users.findAllById(anyList())).thenReturn(List.of(u1));

        var page = service.activities(null, "dev", ActivityAction.GENERATE_TEST_PLAN, null, null, 0, 10);
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).userEmail()).isEqualTo("dev@greytest.dev");

        // 2. Search by non-existent userSearch -> immediate empty page
        when(users.findAll(any(Specification.class))).thenReturn(List.of());
        var emptyPage = service.activities(null, "nonexistent_term", null, null, null, 0, 10);
        assertThat(emptyPage.content()).isEmpty();
        assertThat(emptyPage.totalElements()).isEqualTo(0);

        // 3. Search by exact userId
        var userPage = service.activities(10L, null, null, null, null, 0, 10);
        assertThat(userPage.content()).hasSize(1);
    }

    @Test
    void userDetailLoginHistory() {
        AuthUser u = target(UserRole.USER, true);
        u.setId(20L);
        u.setEmail("user20@greytest.dev");
        when(users.findById(20L)).thenReturn(Optional.of(u));
        when(projects.findByOwnerUserIdOrderByCreatedAtDesc(20L)).thenReturn(List.of());

        UserActivityLog loginLog = new UserActivityLog();
        loginLog.setId(501L);
        loginLog.setUserId(20L);
        loginLog.setActionType(ActivityAction.USER_LOGIN);
        loginLog.setCreatedAt(LocalDateTime.now().minusHours(2));
        loginLog.setMetadata(Map.of(
                "ip", "10.0.0.1",
                "userAgent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0",
                "portal", "ADMIN_PORTAL",
                "status", "SUCCESS"
        ));
        when(activities.findByUserIdAndActionTypeOrderByCreatedAtDesc(eq(20L), eq(ActivityAction.USER_LOGIN), any(Pageable.class)))
                .thenReturn(List.of(loginLog));

        var detail = service.userDetail(20L);
        assertThat(detail.lastLoginAt()).isNotNull();
        assertThat(detail.loginHistory()).hasSize(1);
        var item = detail.loginHistory().get(0);
        assertThat(item.ip()).isEqualTo("10.0.0.1");
        assertThat(item.portal()).isEqualTo("ADMIN_PORTAL");
        assertThat(item.status()).isEqualTo("SUCCESS");
        assertThat(item.device()).contains("Windows");
    }

    @Test
    void feedbacksWithRepositoryMock() {
        UserFeedbackRepository fbRepo = mock(UserFeedbackRepository.class);
        service.setFeedbackRepository(fbRepo);

        UserFeedback entity = new UserFeedback();
        entity.setId(99L);
        entity.setUserEmail("user@example.com");
        entity.setUserName("User Example");
        entity.setCategory("BUG_GENERATION");
        entity.setTitle("Bug Title");
        entity.setContent("Bug Content");
        entity.setStatus("NEW");
        entity.setCreatedAt(LocalDateTime.now());

        when(fbRepo.findAll(any(Specification.class))).thenReturn(List.of(entity));
        when(fbRepo.findAll(any(Specification.class), any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(entity));
        when(fbRepo.count()).thenReturn(1L);
        when(fbRepo.countByStatus("NEW")).thenReturn(1L);
        when(fbRepo.countByStatus("RESOLVED")).thenReturn(0L);
        when(fbRepo.save(any(UserFeedback.class))).thenAnswer(inv -> {
            UserFeedback arg = inv.getArgument(0);
            if (arg.getId() == null) arg.setId(100L);
            return arg;
        });
        when(fbRepo.findById(99L)).thenReturn(Optional.of(entity));
        when(fbRepo.existsById(99L)).thenReturn(true);

        var feedbacks = service.getFeedbacks("NEW", "BUG_GENERATION", "Bug");
        assertThat(feedbacks).hasSize(1);
        assertThat(feedbacks.get(0).title()).isEqualTo("Bug Title");

        var overview = service.getFeedbackOverview();
        assertThat(overview.total()).isEqualTo(1L);
        assertThat(overview.pending()).isEqualTo(1L);

        var created = service.createFeedback(actor(), new AdminDtos.SaveFeedbackRequest("BUG_GENERATION", "New Bug", "Desc", 4, "Proj"));
        assertThat(created.id()).isEqualTo(100L);

        var updated = service.updateFeedbackStatus(actor(), 99L, new AdminDtos.UpdateFeedbackStatusRequest("RESOLVED", "Done"));
        assertThat(updated.status()).isEqualTo("RESOLVED");
        assertThat(updated.adminNote()).isEqualTo("Done");

        service.deleteFeedback(actor(), 99L);
        verify(fbRepo).deleteById(99L);
    }

    @Test
    void getUserFeedbacksReturnsUserSpecificList() {
        UserFeedbackRepository fbRepo = mock(UserFeedbackRepository.class);
        service.setFeedbackRepository(fbRepo);

        UserFeedback fb = new UserFeedback();
        fb.setId(1L);
        fb.setUserId(42L);
        fb.setTitle("Bug Report");
        fb.setStatus("NEW");
        fb.setCreatedAt(LocalDateTime.now());
        when(fbRepo.findByUserIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(fb));

        var result = service.getUserFeedbacks(42L);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Bug Report");

        // Verify null userId returns empty list safely
        assertThat(service.getUserFeedbacks(null)).isEmpty();

        // Verify in-memory fallback without repository
        service.setFeedbackRepository(null);
        service.createFeedback(actor(), new AdminDtos.SaveFeedbackRequest("OTHER", "Fallback Item", "Detail", 5, null));
        var inMemoryFeedbacks = service.getUserFeedbacks(actor().getId());
        assertThat(inMemoryFeedbacks).isNotEmpty();
    }
}
