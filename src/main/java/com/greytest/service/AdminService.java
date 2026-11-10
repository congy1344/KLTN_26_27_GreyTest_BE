package com.greytest.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.admin.AdminDtos.ActiveJobDto;
import com.greytest.dto.admin.AdminDtos.ActivityDto;
import com.greytest.dto.admin.AdminDtos.AiRuntimeConfigDto;
import com.greytest.dto.admin.AdminDtos.ApiKeySlotDto;
import com.greytest.dto.admin.AdminDtos.HealthDto;
import com.greytest.dto.admin.AdminDtos.LoginHistoryDto;
import com.greytest.dto.admin.AdminDtos.OverviewDto;
import com.greytest.dto.admin.AdminDtos.PageDto;
import com.greytest.dto.admin.AdminDtos.ProjectSummaryDto;
import com.greytest.dto.admin.AdminDtos.QuotaDto;
import com.greytest.dto.admin.AdminDtos.TopUserDto;
import com.greytest.dto.admin.AdminDtos.TrendPointDto;
import com.greytest.dto.admin.AdminDtos.UpdateAiKeysRequest;
import com.greytest.dto.admin.AdminDtos.UpdateAiModelRequest;
import com.greytest.dto.admin.AdminDtos.UpdateStageLimitsRequest;
import com.greytest.dto.admin.AdminDtos.TierPackageDto;
import com.greytest.dto.admin.AdminDtos.SaveTierPackageRequest;
import com.greytest.dto.admin.AdminDtos.FeedbackOverviewDto;
import com.greytest.dto.admin.AdminDtos.SaveFeedbackRequest;
import com.greytest.dto.admin.AdminDtos.UpdateFeedbackStatusRequest;
import com.greytest.dto.admin.AdminDtos.UserFeedbackDto;
import com.greytest.dto.admin.AdminDtos.UserDetailDto;
import com.greytest.dto.admin.AdminDtos.UserSummaryDto;
import com.greytest.dto.admin.CreateUserRequest;
import com.greytest.dto.admin.UpdateUserRequest;
import com.greytest.entity.AuthUser;
import com.greytest.entity.CoverageReport;
import com.greytest.entity.UsageQuota;
import com.greytest.entity.UserActivityLog;
import com.greytest.entity.UserFeedback;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.dto.admin.AdminDtos.JobHistoryDto;
import com.greytest.dto.admin.AdminDtos.SystemSettingDto;
import com.greytest.dto.admin.AdminDtos.SystemSettingsDto;
import com.greytest.dto.admin.AdminDtos.UpdateSystemSettingsRequest;
import com.greytest.entity.Project;
import com.greytest.entity.SystemSetting;
import com.greytest.entity.TierPackage;
import com.greytest.entity.enums.UserRole;
import com.greytest.entity.enums.UserTier;
import com.greytest.exception.AuthException;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.CoverageReportRepository;
import com.greytest.repository.PaymentTransactionRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.SystemSettingRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.TierPackageRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.repository.UsageQuotaRepository;
import com.greytest.repository.UserActivityLogRepository;
import com.greytest.repository.UserFeedbackRepository;
import jakarta.persistence.criteria.Predicate;

/**
 * Cung cấp toàn bộ use case quản trị user, quota, activity và số liệu
 * dashboard.
 */
@Service
public class AdminService {

    private static final Set<String> DATABASE_USER_SORTS = Set.of("id", "fullName", "email", "createdAt", "role", "enabled", "tier");
    private static final Set<String> COMPUTED_USER_SORTS = Set.of("totalGenerationRequests", "lastActivityAt", "quota",
            "quotaUsed");
    private static final Set<String> SUPPORTED_GRANULARITIES = Set.of("day", "week", "month", "year");
    private static final List<ActivityAction> GENERATION_ACTIONS = List.of(
            ActivityAction.GENERATE_BUSINESS_RULE,
            ActivityAction.GENERATE_TEST_PLAN,
            ActivityAction.GENERATE_TEST_CASE,
            ActivityAction.GENERATE_UNIT_TEST,
            ActivityAction.COVERAGE_REFINEMENT);

    private final AuthUserRepository users;
    private final ProjectRepository projects;
    private final UnitTestRepository unitTests;
    private final UserActivityLogRepository activities;
    private final UsageQuotaRepository quotas;
    private final UsageQuotaService quotaService;
    private final UserActivityService activityService;
    private final BusinessRuleRepository businessRules;
    private final TestPlanRepository testPlans;
    private final TestCaseRepository testCases;
    private final CoverageReportRepository coverageReports;
    private final GenerationProgressService progressService;
    private final PaymentTransactionRepository paymentTransactions;
    private final PasswordEncoder passwordEncoder;
    @Autowired(required = false)
    private UserFeedbackRepository feedbackRepository;

    @org.springframework.beans.factory.annotation.Value("${llm.provider:mock}")
    private String llmProvider = "mock";
    @org.springframework.beans.factory.annotation.Value("${llm.model:gpt-4o-mini}")
    private String llmModel = "gpt-4o-mini";
    @org.springframework.beans.factory.annotation.Value("${llm.openai-url:https://api.openai.com/v1/chat/completions}")
    private String llmEndpoint = "https://api.openai.com/v1/chat/completions";
    @org.springframework.beans.factory.annotation.Value("${greytest.llm-batch.concurrency:4}")
    private int batchConcurrency = 4;
    @Autowired(required = false)
    private com.greytest.service.agent.LlmStageTokenLimits stageTokenLimits;
    @Autowired(required = false)
    private com.greytest.service.agent.OpenAiLlmClient openAiLlmClient;
    @Autowired(required = false)
    private com.greytest.service.agent.GoogleLlmClient googleLlmClient;
    @Autowired(required = false)
    private org.springframework.core.env.Environment environment;
    @Autowired(required = false)
    private TierPackageRepository tierPackageRepository;
    @Autowired(required = false)
    private SystemSettingRepository systemSettingRepository;
    @Autowired(required = false)
    private javax.sql.DataSource dataSource;
    @Autowired(required = false)
    private ServerTelemetryService telemetryService;
    @Autowired(required = false)
    private DdosProtectionService ddosProtectionService;

    private final List<String> currentKeys = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, TierPackageInternal> tierPackages = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String dynamicModel = null;
    private volatile String dynamicProvider = null;

    private static class TierPackageInternal {
        String code;
        String name;
        long price;
        String cycle;
        int quotaLimit;
        int batchConcurrency;
        List<String> features;
        String description;
        boolean active;

        TierPackageInternal(String code, String name, long price, String cycle, int quotaLimit, int batchConcurrency,
                List<String> features, String description, boolean active) {
            this.code = code;
            this.name = name;
            this.price = price;
            this.cycle = cycle;
            this.quotaLimit = quotaLimit;
            this.batchConcurrency = batchConcurrency;
            this.features = new ArrayList<>(features != null ? features : List.of());
            this.description = description != null ? description : "";
            this.active = active;
        }
    }

    @Autowired
    public AdminService(
            AuthUserRepository users,
            ProjectRepository projects,
            UnitTestRepository unitTests,
            UserActivityLogRepository activities,
            UsageQuotaRepository quotas,
            UsageQuotaService quotaService,
            UserActivityService activityService,
            @Autowired(required = false) BusinessRuleRepository businessRules,
            @Autowired(required = false) TestPlanRepository testPlans,
            @Autowired(required = false) TestCaseRepository testCases,
            @Autowired(required = false) CoverageReportRepository coverageReports,
            @Autowired(required = false) GenerationProgressService progressService,
            @Autowired(required = false) PaymentTransactionRepository paymentTransactions) {
        this.users = users;
        this.projects = projects;
        this.unitTests = unitTests;
        this.activities = activities;
        this.quotas = quotas;
        this.quotaService = quotaService;
        this.activityService = activityService;
        this.businessRules = businessRules;
        this.testPlans = testPlans;
        this.testCases = testCases;
        this.coverageReports = coverageReports;
        this.progressService = progressService;
        this.paymentTransactions = paymentTransactions;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    public AdminService(
            AuthUserRepository users,
            ProjectRepository projects,
            UnitTestRepository unitTests,
            UserActivityLogRepository activities,
            UsageQuotaRepository quotas,
            UsageQuotaService quotaService,
            UserActivityService activityService,
            BusinessRuleRepository businessRules,
            TestPlanRepository testPlans,
            TestCaseRepository testCases,
            CoverageReportRepository coverageReports,
            GenerationProgressService progressService) {
        this(users, projects, unitTests, activities, quotas, quotaService, activityService, businessRules, testPlans,
                testCases, coverageReports, progressService, null);
    }

    public AdminService(
            AuthUserRepository users,
            ProjectRepository projects,
            UnitTestRepository unitTests,
            UserActivityLogRepository activities,
            UsageQuotaRepository quotas,
            UsageQuotaService quotaService,
            UserActivityService activityService,
            BusinessRuleRepository businessRules,
            TestPlanRepository testPlans,
            TestCaseRepository testCases,
            CoverageReportRepository coverageReports) {
        this(users, projects, unitTests, activities, quotas, quotaService, activityService, businessRules, testPlans,
                testCases, coverageReports, null, null);
    }

    public AdminService(
            AuthUserRepository users,
            ProjectRepository projects,
            UnitTestRepository unitTests,
            UserActivityLogRepository activities,
            UsageQuotaRepository quotas,
            UsageQuotaService quotaService,
            UserActivityService activityService) {
        this(users, projects, unitTests, activities, quotas, quotaService, activityService, null, null, null, null,
                null, null);
    }

    @Transactional
    public PageDto<UserSummaryDto> listUsers(
            String search, UserRole role, Boolean enabled, int page, int size, String sort, String direction) {
        return listUsers(search, role, enabled, null, page, size, sort, direction);
    }

    @Transactional
    public PageDto<UserSummaryDto> listUsers(
            String search, UserRole role, Boolean enabled, UserTier tier, int page, int size, String sort,
            String direction) {
        boolean isDesc = "desc".equalsIgnoreCase(direction);

        if (COMPUTED_USER_SORTS.contains(sort)) {
            List<AuthUser> allFiltered = users.findAll(userFilter(search, role, enabled, tier));
            List<UserSummaryDto> content = new ArrayList<>(allFiltered.stream().map(this::summary).toList());

            Comparator<UserSummaryDto> comparator;
            if ("totalGenerationRequests".equalsIgnoreCase(sort)) {
                comparator = Comparator.comparingLong(UserSummaryDto::totalGenerationRequests);
                if (isDesc)
                    comparator = comparator.reversed();
            } else if ("lastActivityAt".equalsIgnoreCase(sort)) {
                Comparator<Instant> instantComparator = isDesc
                        ? Comparator.nullsLast(Comparator.reverseOrder())
                        : Comparator.nullsLast(Comparator.naturalOrder());
                comparator = Comparator.comparing(UserSummaryDto::lastActivityAt, instantComparator);
            } else { // "quota" hoặc "quotaUsed"
                comparator = Comparator.comparingInt(u -> u.quota() != null ? u.quota().used() : 0);
                if (isDesc)
                    comparator = comparator.reversed();
            }
            content.sort(comparator);

            int totalElements = content.size();
            int safePage = Math.max(page, 0);
            int safeSize = Math.min(Math.max(size, 1), 100);
            int totalPages = totalElements == 0 ? 1 : (int) Math.ceil((double) totalElements / safeSize);
            int fromIndex = Math.min(safePage * safeSize, totalElements);
            int toIndex = Math.min(fromIndex + safeSize, totalElements);
            List<UserSummaryDto> pageContent = content.subList(fromIndex, toIndex);
            return new PageDto<>(pageContent, safePage, safeSize, totalElements, totalPages);
        }

        String sortField = DATABASE_USER_SORTS.contains(sort) ? sort : "createdAt";
        Sort.Direction sortDirection = isDesc ? Sort.Direction.DESC : Sort.Direction.ASC;
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(sortDirection, sortField));
        Page<AuthUser> result = users.findAll(userFilter(search, role, enabled, tier), pageable);
        List<UserSummaryDto> content = result.getContent().stream().map(this::summary).toList();
        return new PageDto<>(content, result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional
    public UserDetailDto userDetail(Long userId) {
        AuthUser user = requireUser(userId);
        List<ProjectSummaryDto> userProjects = projects.findByOwnerUserIdOrderByCreatedAtDesc(userId).stream()
                .map(project -> new ProjectSummaryDto(
                        project.getId(), project.getName(), project.getStatus(), toInstant(project.getCreatedAt())))
                .toList();
        List<ActivityDto> recent = activities.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20)).stream()
                .map(activity -> toActivity(activity, user.getEmail()))
                .toList();

        List<UserActivityLog> loginLogs = activities.findByUserIdAndActionTypeOrderByCreatedAtDesc(
                userId, ActivityAction.USER_LOGIN, PageRequest.of(0, 20));
        Instant lastLoginAt = loginLogs.isEmpty() ? null : toInstant(loginLogs.get(0).getCreatedAt());
        List<LoginHistoryDto> loginHistory = loginLogs.stream()
                .map(this::toLoginHistory)
                .toList();

        return new UserDetailDto(summary(user), userProjects, unitTests.countByOwnerUserId(userId), recent, lastLoginAt, loginHistory);
    }

    @Transactional
    public UserSummaryDto updateStatus(AuthUser admin, Long userId, boolean enabled, String reason) {
        // Khóa chung trước khi đọc target để request đồng thời không dùng trạng thái
        // admin cũ.
        List<AuthUser> enabledAdmins = users.findEnabledAdminsForUpdate();
        AuthUser user = requireUser(userId);
        if (admin.getId().equals(userId) && !enabled) {
            throw new IllegalArgumentException("Admin không thể tự khóa tài khoản đang đăng nhập");
        }
        if (!enabled && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Cần nhập lý do khi khóa tài khoản");
        }
        // Luôn giữ ít nhất một admin hoạt động để quản trị hệ thống.
        if (!enabled && isLastEnabledAdmin(user, enabledAdmins)) {
            throw new IllegalArgumentException("Không thể khóa admin đang hoạt động cuối cùng");
        }
        boolean previousEnabled = Boolean.TRUE.equals(user.getEnabled());
        user.setEnabled(enabled);
        users.save(user);
        activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                Map.of("targetUserId", userId, "previousEnabled", previousEnabled,
                        "newEnabled", enabled, "reason", reason == null ? "" : reason.trim()));
        return summary(user);
    }

    @Transactional
    public UserSummaryDto updateRole(AuthUser admin, Long userId, UserRole role) {
        if (admin.getId().equals(userId) && role != UserRole.ADMIN) {
            throw new IllegalArgumentException("Admin không thể tự hạ quyền tài khoản đang đăng nhập");
        }
        List<AuthUser> enabledAdmins = users.findEnabledAdminsForUpdate();
        AuthUser user = requireUser(userId);
        if (role != UserRole.ADMIN && isLastEnabledAdmin(user, enabledAdmins)) {
            throw new IllegalArgumentException("Không thể hạ quyền admin đang hoạt động cuối cùng");
        }
        UserRole previousRole = user.getRole();
        user.setRole(role);
        users.save(user);
        activityService.record(admin.getId(), ActivityAction.ADMIN_ROLE_CHANGE, null,
                Map.of("targetUserId", userId, "previousRole", previousRole.name(), "newRole", role.name()));
        return summary(user);
    }

    @Transactional
    public UserSummaryDto updateTier(AuthUser admin, Long userId, UserTier tier) {
        AuthUser user = requireUser(userId);
        UserTier previousTier = user.getTier() != null ? user.getTier() : UserTier.FREE;
        user.setTier(tier);
        users.save(user);

        quotaService.upgradeToTier(userId, tier);

        activityService.record(admin.getId(), ActivityAction.ADMIN_TIER_CHANGE, null,
                Map.of("targetUserId", userId, "previousTier", previousTier.name(), "newTier", tier.name()));
        return summary(user);
    }

    @Transactional
    public QuotaDto updateQuota(AuthUser admin, Long userId, Integer limit) {
        requireUser(userId);
        UsageQuota quota = quotaService.updateLimit(userId, limit);
        // Giữ key null trong audit để frontend phân biệt unlimited với dữ liệu cũ thiếu
        // key.
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("targetUserId", userId);
        metadata.put("quotaLimit", limit);
        activityService.record(admin.getId(), ActivityAction.ADMIN_QUOTA_CHANGE, null, metadata);
        return toQuota(quota);
    }

    /**
     * Tạo mới người dùng từ giao diện quản trị.
     */
    @Transactional
    public UserSummaryDto createUser(AuthUser admin, CreateUserRequest request) {
        String email = request.email().trim().toLowerCase();
        if (users.findByEmailIgnoreCase(email).isPresent()) {
            throw new IllegalArgumentException("Email '" + email + "' đã tồn tại trong hệ thống.");
        }
        AuthUser user = new AuthUser();
        user.setEmail(email);
        user.setFullName(
                request.fullName() != null && !request.fullName().isBlank() ? request.fullName().trim() : null);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role() != null ? request.role() : UserRole.USER);
        user.setTier(request.tier() != null ? request.tier() : UserTier.FREE);
        user.setEnabled(true);
        users.save(user);

        // Khởi tạo quota theo tier
        if (user.getTier() == UserTier.PRO) {
            quotaService.upgradeToPro(user.getId());
        } else if (user.getTier() == UserTier.PLUS) {
            quotaService.upgradeToPlus(user.getId());
        } else {
            quotaService.downgradeToFree(user.getId());
        }
        if (request.quotaLimit() != null) {
            quotaService.updateLimit(user.getId(), request.quotaLimit());
        }

        activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                Map.of("targetUserId", user.getId(), "createdEmail", user.getEmail(), "action", "CREATE_USER"));
        return summary(user);
    }

    /**
     * Cập nhật thông tin chi tiết người dùng (họ tên, mật khẩu, role, tier, quota).
     */
    @Transactional
    public UserSummaryDto updateUser(AuthUser admin, Long userId, UpdateUserRequest request) {
        AuthUser user = requireUser(userId);
        if (request.fullName() != null) {
            user.setFullName(request.fullName().trim().isEmpty() ? null : request.fullName().trim());
        }
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().trim().length() < 6) {
                throw new IllegalArgumentException("Mật khẩu phải có ít nhất 6 ký tự");
            }
            user.setPasswordHash(passwordEncoder.encode(request.password().trim()));
        }
        if (request.role() != null) {
            if (admin.getId().equals(userId) && request.role() != UserRole.ADMIN) {
                throw new IllegalArgumentException("Không thể tự giáng quyền Quản trị viên của chính mình.");
            }
            if (request.role() != UserRole.ADMIN && isLastEnabledAdmin(user, users.findEnabledAdminsForUpdate())) {
                throw new IllegalArgumentException("Không thể hạ quyền admin đang hoạt động cuối cùng");
            }
            user.setRole(request.role());
        }
        if (request.tier() != null && request.tier() != user.getTier()) {
            user.setTier(request.tier());
            quotaService.upgradeToTier(userId, request.tier());
        }
        users.save(user);
        if (Boolean.TRUE.equals(request.unlimitedQuota())) {
            quotaService.updateLimit(userId, null);
        } else if (request.quotaLimit() != null) {
            quotaService.updateLimit(userId, request.quotaLimit());
        }

        activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                Map.of("targetUserId", userId, "action", "UPDATE_USER_DETAILS"));
        return summary(user);
    }

    /**
     * Xóa tài khoản người dùng và dọn dẹp các ràng buộc liên quan.
     */
    @Transactional
    public void deleteUser(AuthUser admin, Long userId) {
        if (admin.getId().equals(userId)) {
            throw new IllegalArgumentException("Không thể tự xóa tài khoản của chính mình.");
        }
        AuthUser user = requireUser(userId);
        if (isLastEnabledAdmin(user, users.findEnabledAdminsForUpdate())) {
            throw new IllegalArgumentException("Không thể xóa admin đang hoạt động cuối cùng");
        }
        if (paymentTransactions != null) {
            paymentTransactions.deleteByUserId(userId);
        }
        quotas.deleteByUserId(userId);
        users.delete(user);

        activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                Map.of("targetUserId", userId, "deletedEmail", user.getEmail(), "action", "DELETE_USER"));
    }

    /**
     * Đặt lại số lượt gọi LLM đã dùng của người dùng về 0.
     */
    @Transactional
    public QuotaDto resetQuota(AuthUser admin, Long userId) {
        requireUser(userId);
        UsageQuota quota = quotaService.resetUsage(userId);
        activityService.record(admin.getId(), ActivityAction.ADMIN_QUOTA_CHANGE, null,
                Map.of("targetUserId", userId, "action", "RESET_USAGE"));
        return toQuota(quota);
    }

    /**
     * Hủy/Dừng tác vụ đang chạy hoặc đang trong hàng đợi sinh test.
     */
    public void cancelActiveJob(AuthUser admin, Long projectId, GenerationProgressStage stage) {
        if (progressService != null) {
            progressService.fail(projectId, stage, "Tác vụ đã bị hủy bởi Quản trị viên.");
        }
        activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, projectId,
                Map.of("action", "ADMIN_CANCEL_JOB", "stage", stage != null ? stage.name() : "ALL"));
    }

    @Transactional(readOnly = true)
    public PageDto<ActivityDto> activities(
            Long userId, ActivityAction action, LocalDateTime from, LocalDateTime to, int page, int size) {
        return activities(userId, null, action, from, to, page, size);
    }

    @Transactional(readOnly = true)
    public PageDto<ActivityDto> activities(
            Long userId, String userSearch, ActivityAction action, LocalDateTime from, LocalDateTime to, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);

        List<Long> matchedUserIds = null;
        if (userId != null) {
            matchedUserIds = List.of(userId);
        } else if (userSearch != null && !userSearch.isBlank()) {
            String searchTrimmed = userSearch.trim();
            List<AuthUser> matchedUsers = users.findAll((uRoot, uQuery, cb) -> {
                String pattern = "%" + searchTrimmed.toLowerCase() + "%";
                return cb.or(
                        cb.like(cb.lower(uRoot.get("email")), pattern),
                        cb.like(cb.lower(uRoot.get("fullName")), pattern)
                );
            });
            matchedUserIds = new java.util.ArrayList<>(matchedUsers.stream().map(AuthUser::getId).toList());
            try {
                matchedUserIds.add(Long.parseLong(searchTrimmed));
            } catch (NumberFormatException ignored) {}
            if (matchedUserIds.isEmpty()) {
                return new PageDto<>(List.of(), safePage, safeSize, 0L, 0);
            }
        }

        PageRequest pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UserActivityLog> result = activities.findAll(activityFilter(matchedUserIds, action, from, to), pageable);
        Map<Long, String> emails = users.findAllById(result.getContent().stream()
                .map(UserActivityLog::getUserId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(AuthUser::getId, AuthUser::getEmail));
        List<ActivityDto> content = result.getContent().stream()
                .map(activity -> toActivity(activity, emails.get(activity.getUserId())))
                .toList();
        return new PageDto<>(content, result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public OverviewDto overview() {
        LocalDateTime now = LocalDateTime.now();
        long alerts = quotas.countExceededInPeriod(java.time.LocalDate.now().withDayOfMonth(1));

        long projCount = projects.count();
        long brCount = businessRules != null ? businessRules.count() : 0L;
        long tpCount = testPlans != null ? testPlans.count() : 0L;
        long tcCount = testCases != null ? testCases.count() : 0L;
        long utCount = unitTests.count();

        Double avgLine = null;
        Double avgBranch = null;
        long coverageCount = 0L;

        if (coverageReports != null) {
            List<CoverageReport> allReports = coverageReports.findAll();
            coverageCount = allReports.size();
            if (!allReports.isEmpty()) {
                // Chỉ tổng hợp báo cáo đo lường mới nhất của mỗi dự án và service path để tránh
                // làm loãng dữ liệu lịch sử
                Map<String, CoverageReport> latestByScope = new LinkedHashMap<>();
                for (CoverageReport r : allReports) {
                    String scopeKey = r.getProjectId() + ":" + (r.getServicePath() != null ? r.getServicePath() : ".");
                    CoverageReport existing = latestByScope.get(scopeKey);
                    if (existing == null
                            || (r.getId() != null && existing.getId() != null && r.getId() > existing.getId())) {
                        latestByScope.put(scopeKey, r);
                    }
                }

                double lineSum = 0;
                int lineCount = 0;
                double branchSum = 0;
                int branchCount = 0;

                for (CoverageReport r : latestByScope.values()) {
                    if (r.getLineCoverage() != null) {
                        lineSum += r.getLineCoverage().doubleValue();
                        lineCount++;
                    }
                    if (r.getBranchCoverage() != null) {
                        branchSum += r.getBranchCoverage().doubleValue();
                        branchCount++;
                    }
                }
                if (lineCount > 0)
                    avgLine = Math.round((lineSum / lineCount) * 10.0) / 10.0;
                if (branchCount > 0)
                    avgBranch = Math.round((branchSum / branchCount) * 10.0) / 10.0;
            }
        }

        long gen7Days = activities.countByActionTypeInAndCreatedAtAfter(GENERATION_ACTIONS, now.minusDays(7));
        long llm7Days = activities.countByActionTypeAndCreatedAtAfter(ActivityAction.LLM_CALL, now.minusDays(7));

        return new OverviewDto(
                users.count(),
                users.countByCreatedAtAfter(now.minusDays(7)),
                users.countByCreatedAtAfter(now.minusDays(30)),
                activities.countByActionTypeIn(GENERATION_ACTIONS),
                activities.countByActionType(ActivityAction.LLM_CALL),
                alerts,
                projCount,
                brCount,
                tpCount,
                tcCount,
                utCount,
                avgLine,
                avgBranch,
                coverageCount,
                gen7Days,
                llm7Days);
    }

    @Transactional(readOnly = true)
    public List<TrendPointDto> trend(int days, String granularity, ActivityAction action) {
        return trend(null, null, days, granularity, action);
    }

    @Transactional(readOnly = true)
    public List<TrendPointDto> trend(LocalDateTime from, LocalDateTime to, int days, String granularity, ActivityAction action) {
        String bucket = (granularity != null && SUPPORTED_GRANULARITIES.contains(granularity.trim().toLowerCase()))
                ? granularity.trim().toLowerCase()
                : "day";
        String actionName = action == null ? null : action.name();

        if (from != null && to != null) {
            LocalDateTime start = from.isBefore(to) ? from : to.toLocalDate().atStartOfDay();
            LocalDateTime end = from.isBefore(to) ? to : from.toLocalDate().atTime(23, 59, 59, 999999999);
            return activities.usageTrendBetween(start, end, bucket, actionName).stream()
                    .map(point -> new TrendPointDto(point.getBucket(), point.getTotal()))
                    .toList();
        } else if (from != null) {
            return activities.usageTrend(from, bucket, actionName).stream()
                    .map(point -> new TrendPointDto(point.getBucket(), point.getTotal()))
                    .toList();
        } else if (to != null) {
            int safeDays = Math.min(Math.max(days, 1), 3650);
            LocalDateTime start = to.minusDays(safeDays);
            return activities.usageTrendBetween(start, to, bucket, actionName).stream()
                    .map(point -> new TrendPointDto(point.getBucket(), point.getTotal()))
                    .toList();
        }

        int safeDays = Math.min(Math.max(days, 1), 3650);
        return activities.usageTrend(
                LocalDateTime.now().minusDays(safeDays),
                bucket,
                actionName).stream()
                .map(point -> new TrendPointDto(point.getBucket(), point.getTotal()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TopUserDto> topUsers(int days, int limit) {
        return activities.topLlmUsers(LocalDateTime.now().minusDays(Math.min(Math.max(days, 1), 365)),
                Math.min(Math.max(limit, 1), 20)).stream()
                .map(user -> new TopUserDto(user.getUserId(), user.getEmail(), user.getTotal()))
                .toList();
    }

    public HealthDto health() {
        String appStatus = "UP";
        String dbStatus = "UP";
        int activeConn = 0;
        int idleConn = 0;
        int maxConn = 20;

        if (dataSource != null) {
            try (java.sql.Connection conn = dataSource.getConnection()) {
                if (!conn.isValid(2)) {
                    dbStatus = "DOWN";
                }
                if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
                    var pool = hikari.getHikariPoolMXBean();
                    if (pool != null) {
                        activeConn = pool.getActiveConnections();
                        idleConn = pool.getIdleConnections();
                        maxConn = hikari.getMaximumPoolSize();
                    }
                }
            } catch (Exception e) {
                dbStatus = "DOWN";
            }
        }

        if (currentKeys.isEmpty()) {
            initKeys();
        }
        String primaryKey = currentKeys.isEmpty() ? null : currentKeys.get(0);
        String llmStatus = (primaryKey != null && !primaryKey.isBlank()) ? "ONLINE" : "KEY_MISSING";

        Runtime runtime = Runtime.getRuntime();
        long heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long heapMaxMb = runtime.maxMemory() / (1024 * 1024);
        long nonHeapMb = java.lang.management.ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed() / (1024 * 1024);
        int threadCount = java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount();
        long uptimeSeconds = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        return new HealthDto(
                appStatus,
                dbStatus,
                llmStatus,
                "READY",
                heapUsedMb,
                heapMaxMb,
                nonHeapMb,
                activeConn,
                idleConn,
                maxConn,
                threadCount,
                uptimeSeconds);
    }

    public void setTierPackageRepository(TierPackageRepository tierPackageRepository) {
        this.tierPackageRepository = tierPackageRepository;
    }

    public void setSystemSettingRepository(SystemSettingRepository systemSettingRepository) {
        this.systemSettingRepository = systemSettingRepository;
    }

    public void setDataSource(javax.sql.DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void setStageTokenLimits(com.greytest.service.agent.LlmStageTokenLimits limits) {
        this.stageTokenLimits = limits;
    }

    public void setFeedbackRepository(UserFeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    public void setTelemetryService(ServerTelemetryService telemetryService) {
        this.telemetryService = telemetryService;
        if (this.telemetryService != null) {
            this.telemetryService.setWafStatusSupplier(this::getWafStatus);
        }
    }

    public void setDdosProtectionService(DdosProtectionService ddosProtectionService) {
        this.ddosProtectionService = ddosProtectionService;
        if (this.telemetryService != null) {
            this.telemetryService.setWafStatusSupplier(this::getWafStatus);
        }
    }

    public com.greytest.dto.admin.AdminDtos.ServerTelemetryDto getTelemetry(int limit, String search, Integer status) {
        if (telemetryService != null) {
            return telemetryService.getTelemetry(limit, search, status);
        }
        return new com.greytest.dto.admin.AdminDtos.ServerTelemetryDto(0, 0, 0.0, 0, 0, java.util.List.of());
    }

    public com.greytest.dto.admin.AdminDtos.TrafficOverviewDto getTrafficOverview(String window) {
        Long wafBlocked = ddosProtectionService != null ? ddosProtectionService.getWafStatus().totalBlockedRequests() : null;
        if (telemetryService != null) {
            return telemetryService.getTrafficOverview(window, wafBlocked);
        }
        return new com.greytest.dto.admin.AdminDtos.TrafficOverviewDto(
                "1m", 60L, Instant.now(), 0L, 0L, 0.0, 0.0, 0.0,
                null, null, null, null, null, null, null, null,
                0L, 0L, 0L, 0, 0L, 0L, 0L, null, null, null, null, null, "NO_DATA");
    }

    public com.greytest.dto.admin.AdminDtos.TrafficTimeseriesDto getTrafficTimeseries(String window) {
        if (telemetryService != null) {
            return telemetryService.getTrafficTimeseries(window);
        }
        return new com.greytest.dto.admin.AdminDtos.TrafficTimeseriesDto("1m", 1, 10.0, 25.0, java.util.List.of());
    }

    public PageDto<com.greytest.dto.admin.AdminDtos.ServerRequestLogDto> getRecentRequests(
            int page,
            int size,
            String search,
            String method,
            Integer status,
            String statusGroup,
            Long minLatencyMs,
            String sort,
            String direction) {
        if (telemetryService != null) {
            return telemetryService.getRecentRequests(page, size, search, method, status, statusGroup, minLatencyMs, sort, direction);
        }
        return new PageDto<>(java.util.List.of(), 0, size, 0L, 1);
    }

    public com.greytest.dto.admin.AdminDtos.EndpointAnalyticsDto getEndpointAnalytics(String window, int limit) {
        if (telemetryService != null) {
            return telemetryService.getEndpointAnalytics(window, limit);
        }
        return new com.greytest.dto.admin.AdminDtos.EndpointAnalyticsDto(
                "1m", java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of(), java.util.Map.of());
    }

    public java.util.List<com.greytest.dto.admin.AdminDtos.TrafficAlertDto> getTrafficAlerts(String window) {
        if (telemetryService != null) {
            return telemetryService.getTrafficAlerts(window, getWafStatus());
        }
        return java.util.List.of();
    }

    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribeMonitoringStream() {
        if (telemetryService == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Telemetry service chưa sẵn sàng");
        }
        telemetryService.setWafStatusSupplier(this::getWafStatus);
        return telemetryService.subscribeStream(getWafStatus());
    }

    public com.greytest.dto.admin.AdminDtos.WafStatusDto getWafStatus() {
        if (ddosProtectionService != null) {
            return ddosProtectionService.getWafStatus();
        }
        return new com.greytest.dto.admin.AdminDtos.WafStatusDto(true, false, 100, 100, 30, 15, 0, 0, java.util.List.of());
    }

    public void setUnderAttackMode(boolean enabled) {
        setUnderAttackMode(null, enabled);
    }

    public void setUnderAttackMode(AuthUser admin, boolean enabled) {
        if (ddosProtectionService != null) {
            ddosProtectionService.setUnderAttackMode(enabled);
        }
        recordSecurityAudit(admin, Map.of(
                "action", "TOGGLE_UNDER_ATTACK_MODE",
                "underAttackMode", enabled));
    }

    public void blockIp(String ip, String reason, int durationMinutes) {
        blockIp(null, ip, reason, durationMinutes);
    }

    public void blockIp(AuthUser admin, String ip, String reason, int durationMinutes) {
        if (ddosProtectionService != null) {
            ddosProtectionService.blockIp(ip, reason, durationMinutes);
        }
        recordSecurityAudit(admin, Map.of(
                "action", "BLOCK_IP",
                "ip", ip != null ? ip : "",
                "reason", reason != null ? reason : "",
                "durationMinutes", durationMinutes));
    }

    public boolean unblockIp(String ip) {
        return unblockIp(null, ip);
    }

    public boolean unblockIp(AuthUser admin, String ip) {
        boolean removed = false;
        if (ddosProtectionService != null) {
            removed = ddosProtectionService.unblockIp(ip);
        }
        recordSecurityAudit(admin, Map.of(
                "action", "UNBLOCK_IP",
                "ip", ip != null ? ip : "",
                "removed", removed));
        return removed;
    }

    public com.greytest.dto.admin.AdminDtos.WafStatusDto unblockAll(AuthUser admin) {
        if (ddosProtectionService != null) {
            ddosProtectionService.unblockAll();
        }
        recordSecurityAudit(admin, Map.of("action", "UNBLOCK_ALL_IPS"));
        return getWafStatus();
    }

    public void setRateLimit(int limit) {
        setRateLimit(null, limit);
    }

    public void setRateLimit(AuthUser admin, int limit) {
        if (ddosProtectionService != null) {
            ddosProtectionService.setStandardRateLimit(limit);
        }
        recordSecurityAudit(admin, Map.of(
                "action", "UPDATE_RATE_LIMIT",
                "rateLimitPerMinute", limit));
    }

    public com.greytest.dto.admin.AdminDtos.WafStatusDto updateRateLimitConfig(
            AuthUser admin,
            com.greytest.dto.admin.AdminDtos.RateLimitConfigRequest request) {
        if (ddosProtectionService != null && request != null) {
            if (request.active() != null) {
                ddosProtectionService.setWafActive(request.active());
            }
            if (request.underAttackMode() != null) {
                ddosProtectionService.setUnderAttackMode(request.underAttackMode());
            }
            if (request.rateLimitPerMinute() != null && request.rateLimitPerMinute() > 0) {
                ddosProtectionService.setStandardRateLimit(request.rateLimitPerMinute());
            }
            if (request.underAttackRateLimitPerMinute() != null && request.underAttackRateLimitPerMinute() > 0) {
                ddosProtectionService.setUnderAttackRateLimit(request.underAttackRateLimitPerMinute());
            }
            if (request.burstLimitPerSecond() != null && request.burstLimitPerSecond() > 0) {
                ddosProtectionService.setBurstPerSecondLimit(request.burstLimitPerSecond());
            }
        }
        Map<String, Object> auditMeta = new HashMap<>();
        auditMeta.put("action", "UPDATE_RATE_LIMIT_CONFIG");
        if (request != null) {
            if (request.active() != null) auditMeta.put("active", request.active());
            if (request.underAttackMode() != null) auditMeta.put("underAttackMode", request.underAttackMode());
            if (request.rateLimitPerMinute() != null) auditMeta.put("rateLimitPerMinute", request.rateLimitPerMinute());
            if (request.underAttackRateLimitPerMinute() != null) auditMeta.put("underAttackRateLimitPerMinute", request.underAttackRateLimitPerMinute());
            if (request.burstLimitPerSecond() != null) auditMeta.put("burstLimitPerSecond", request.burstLimitPerSecond());
        }
        recordSecurityAudit(admin, auditMeta);
        return getWafStatus();
    }

    public void auditSecurityChange(AuthUser admin, Map<String, Object> metadata) {
        recordSecurityAudit(admin, metadata);
    }

    private void recordSecurityAudit(AuthUser admin, Map<String, Object> metadata) {
        if (admin != null && admin.getId() != null && activityService != null) {
            try {
                activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null, metadata);
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void initKeys() {
        if (!currentKeys.isEmpty())
            return;
        String[] envKeys = { "LLM_API_KEY", "LLM_API_KEY1", "LLM_API_KEY2", "LLM_API_KEY3", "LLM_API_KEY4",
                "LLM_API_KEY5", "LLM_API_KEY6", "LLM_API_KEY7" };
        String[] propKeys = { "llm.api-key", "llm.api-key-fallback", "llm.api-key-2", "llm.api-key-3", "llm.api-key-4",
                "llm.api-key-5", "llm.api-key-6", "llm.api-key-7" };
        for (int i = 0; i < 8; i++) {
            String val = null;
            if (environment != null) {
                val = environment.getProperty(propKeys[i]);
                if (val == null || val.isBlank()) {
                    val = environment.getProperty(envKeys[i]);
                }
            }
            if (val == null || val.isBlank()) {
                val = System.getenv(envKeys[i]);
            }
            currentKeys.add(val != null ? val : "");
        }
    }

    public AiRuntimeConfigDto getAiRuntimeConfig() {
        if (currentKeys.isEmpty()) {
            initKeys();
        }
        List<ApiKeySlotDto> slots = new ArrayList<>();
        String[] envVars = { "LLM_API_KEY", "LLM_API_KEY1", "LLM_API_KEY2", "LLM_API_KEY3", "LLM_API_KEY4",
                "LLM_API_KEY5", "LLM_API_KEY6", "LLM_API_KEY7" };
        String[] roles = {
                "Khóa mặc định chính (Primary API Key)",
                "Khóa phụ xoay vòng 1 (Rotation Pool Slot 1)",
                "Khóa phụ xoay vòng 2 (Rotation Pool Slot 2)",
                "Khóa phụ xoay vòng 3 (Rotation Pool Slot 3)",
                "Khóa phụ xoay vòng 4 (Rotation Pool Slot 4)",
                "Khóa phụ xoay vòng 5 (Rotation Pool Slot 5)",
                "Khóa phụ xoay vòng 6 (Rotation Pool Slot 6)",
                "Khóa phụ xoay vòng 7 (Rotation Pool Slot 7)"
        };
        for (int i = 0; i < 8; i++) {
            String val = (i < currentKeys.size()) ? currentKeys.get(i) : "";
            boolean configured = val != null && !val.isBlank();
            String masked = maskKey(val);
            slots.add(new ApiKeySlotDto(i, envVars[i], roles[i], masked, "", configured));
        }
        Map<String, Integer> limits = stageTokenLimits != null ? stageTokenLimits.getLimits() : Map.of();
        String activeModel = this.dynamicModel != null ? this.dynamicModel
                : (environment != null ? environment.getProperty("llm.model", llmModel) : llmModel);
        String activeProvider = this.dynamicProvider != null ? this.dynamicProvider
                : (environment != null ? environment.getProperty("llm.provider", llmProvider) : llmProvider);
        String activeEndpoint = environment != null ? environment.getProperty("llm.openai-url", llmEndpoint)
                : llmEndpoint;
        return new AiRuntimeConfigDto(activeProvider, activeModel, activeEndpoint, batchConcurrency, slots, limits);
    }

    private String maskKey(String key) {
        if (key == null || key.isBlank())
            return "Chưa cấu hình";
        if (key.length() < 16)
            return "••••••••";
        return key.substring(0, 4) + "••••••••" + key.substring(key.length() - 4);
    }

    public synchronized AiRuntimeConfigDto updateAiKeys(AuthUser admin, UpdateAiKeysRequest request) {
        if (currentKeys.isEmpty()) {
            initKeys();
        }
        if (request != null && request.slots() != null) {
            for (var slotReq : request.slots()) {
                int slot = slotReq.slot();
                if (slot >= 0 && slot < 8) {
                    while (currentKeys.size() <= slot) {
                        currentKeys.add("");
                    }
                    currentKeys.set(slot, slotReq.key() != null ? slotReq.key().trim() : "");
                }
            }
        }
        List<String> activeKeys = currentKeys.stream().filter(k -> k != null && !k.isBlank()).toList();
        if (openAiLlmClient != null) {
            openAiLlmClient.setApiKeys(activeKeys);
        }
        if (googleLlmClient != null) {
            googleLlmClient.setApiKeys(activeKeys);
        }
        activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                Map.of("action", "UPDATE_AI_KEYS", "configuredCount", activeKeys.size()));
        return getAiRuntimeConfig();
    }

    public synchronized AiRuntimeConfigDto updateStageLimits(AuthUser admin, UpdateStageLimitsRequest request) {
        if (stageTokenLimits != null && request != null && request.stageLimits() != null) {
            stageTokenLimits.updateAll(request.stageLimits());
            activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                    Map.of("action", "UPDATE_STAGE_LIMITS", "limits", request.stageLimits()));
        }
        return getAiRuntimeConfig();
    }

    public synchronized AiRuntimeConfigDto updateAiModel(AuthUser admin, UpdateAiModelRequest request) {
        if (request != null) {
            String activeProvider = openAiLlmClient != null ? "openai" : (googleLlmClient != null ? "google" : (llmProvider != null ? llmProvider : "openai"));
            if (request.provider() != null && !request.provider().isBlank()) {
                String requestedProvider = request.provider().trim().toLowerCase();
                if (!requestedProvider.equalsIgnoreCase(activeProvider)) {
                    throw new IllegalArgumentException("Hệ thống hiện đang chạy provider '" + activeProvider + "'. Đổi sang provider khác yêu cầu cấu hình llm.provider và khởi động lại server.");
                }
                this.dynamicProvider = requestedProvider;
            }
            if (request.model() != null && !request.model().isBlank()) {
                this.dynamicModel = request.model().trim();
                if (openAiLlmClient != null)
                    openAiLlmClient.setModel(this.dynamicModel);
                if (googleLlmClient != null)
                    googleLlmClient.setModel(this.dynamicModel);
            }
        }
        if (systemSettingRepository != null && this.dynamicModel != null) {
            SystemSetting modelSetting = systemSettingRepository.findById("ai.model")
                    .orElse(new SystemSetting("ai.model", this.dynamicModel, "Mô hình suy luận AI mặc định"));
            modelSetting.setSettingValue(this.dynamicModel);
            systemSettingRepository.save(modelSetting);
        }
        activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                Map.of("action", "UPDATE_AI_MODEL",
                        "model", this.dynamicModel != null ? this.dynamicModel : (llmModel != null ? llmModel : ""),
                        "provider", this.dynamicProvider != null ? this.dynamicProvider
                                : (llmProvider != null ? llmProvider : "")));
        return getAiRuntimeConfig();
    }

    private void initTierPackagesInDb() {
        if (tierPackageRepository == null) return;
        List<TierPackage> defaults = List.of(
                new TierPackage("FREE", "Gói Miễn Phí (FREE)", 0L, "MONTHLY", 100, 2,
                        "Trích xuất Quy tắc Nghiệp vụ (AST)\nSinh Test Plan & Test Case\nSinh Unit Test JUnit 5 cơ bản",
                        "Dành cho sinh viên và dự án thử nghiệm cơ bản", true),
                new TierPackage("PLUS", "Gói Tiêu Chuẩn (PLUS)", 10000L, "WEEKLY", 200, 3,
                        "Toàn bộ tính năng gói FREE\n200 lượt gọi AI hồi lại hàng tuần\nSinh tối đa 3 batch song song\nƯu tiên xử lý hàng đợi tác vụ",
                        "Dành cho nhóm phát triển mở rộng / dự án sprint nước rút", true),
                new TierPackage("PRO", "Gói Chuyên Nghiệp (PRO)", 20000L, "MONTHLY", 300, 4,
                        "Toàn bộ tính năng gói PLUS\nPhân tích cú pháp AST & Call Graph sâu\nSinh Unit Test JUnit 5 song song 4 luồng\nVòng lặp JaCoCo Refinement tự cải thiện độ bao phủ",
                        "Toàn bộ tính năng phân tích sâu & JaCoCo Refinement", true)
        );
        for (TierPackage p : defaults) {
            if (!tierPackageRepository.existsById(p.getCode())) {
                tierPackageRepository.save(p);
            }
        }
    }

    private void initTierPackages() {
        tierPackages.put("FREE", new TierPackageInternal(
                "FREE",
                "Gói Miễn Phí (FREE)",
                0L,
                "MONTHLY",
                100,
                2,
                List.of("Trích xuất Quy tắc Nghiệp vụ (AST)", "Sinh Test Plan & Test Case",
                        "Sinh Unit Test JUnit 5 cơ bản"),
                "Dành cho sinh viên và dự án thử nghiệm cơ bản",
                true));
        tierPackages.put("PLUS", new TierPackageInternal(
                "PLUS",
                "Gói Tiêu Chuẩn (PLUS)",
                10_000L,
                "WEEKLY",
                200,
                3,
                List.of("Toàn bộ tính năng gói FREE", "200 lượt gọi AI hồi lại hàng tuần",
                        "Sinh tối đa 3 batch song song", "Ưu tiên xử lý hàng đợi tác vụ"),
                "Dành cho nhóm phát triển mở rộng / dự án sprint nước rút",
                true));
        tierPackages.put("PRO", new TierPackageInternal(
                "PRO",
                "Gói Chuyên Nghiệp (PRO)",
                20_000L,
                "MONTHLY",
                300,
                4,
                List.of("Toàn bộ tính năng gói PLUS", "Phân tích cú pháp AST & Call Graph sâu",
                        "Sinh Unit Test JUnit 5 song song 4 luồng",
                        "Vòng lặp JaCoCo Refinement tự cải thiện độ bao phủ"),
                "Toàn bộ tính năng phân tích sâu & JaCoCo Refinement",
                true));
    }

    public List<TierPackageDto> getTierPackages() {
        if (tierPackageRepository != null) {
            List<TierPackage> list = tierPackageRepository.findAll();
            if (list.isEmpty()) {
                initTierPackagesInDb();
                list = tierPackageRepository.findAll();
            }
            List<String> order = List.of("FREE", "PLUS", "PRO");
            List<TierPackageDto> result = new ArrayList<>();
            for (String code : order) {
                list.stream().filter(p -> code.equalsIgnoreCase(p.getCode())).findFirst()
                        .ifPresent(p -> result.add(toTierPackageDto(p, countUsersInTier(code))));
            }
            for (TierPackage p : list) {
                if (!order.contains(p.getCode().toUpperCase())) {
                    result.add(toTierPackageDto(p, countUsersInTier(p.getCode())));
                }
            }
            return result;
        }

        if (tierPackages.isEmpty()) {
            initTierPackages();
        }
        List<TierPackageDto> result = new ArrayList<>();
        List<String> order = List.of("FREE", "PLUS", "PRO");
        for (String code : order) {
            TierPackageInternal pkg = tierPackages.get(code);
            if (pkg != null) {
                long count = countUsersInTier(code);
                result.add(toTierPackageDto(pkg, count));
            }
        }
        for (var entry : tierPackages.entrySet()) {
            if (!order.contains(entry.getKey())) {
                long count = countUsersInTier(entry.getKey());
                result.add(toTierPackageDto(entry.getValue(), count));
            }
        }
        return result;
    }

    public TierPackageDto getTierPackage(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        if (tierPackageRepository != null) {
            String key = code.trim().toUpperCase();
            return tierPackageRepository.findById(key)
                    .map(p -> toTierPackageDto(p, countUsersInTier(key)))
                    .orElse(null);
        }

        if (tierPackages.isEmpty()) {
            initTierPackages();
        }
        String key = code.trim().toUpperCase();
        TierPackageInternal pkg = tierPackages.get(key);
        if (pkg == null) {
            return null;
        }
        return toTierPackageDto(pkg, countUsersInTier(key));
    }

    private long countUsersInTier(String code) {
        try {
            UserTier tier = UserTier.valueOf(code.toUpperCase());
            return users != null ? users.countByTier(tier) : 0L;
        } catch (IllegalArgumentException e) {
            return 0L;
        }
    }

    private TierPackageDto toTierPackageDto(TierPackage pkg, long count) {
        List<String> featureList = (pkg.getFeatures() != null && !pkg.getFeatures().isBlank())
                ? List.of(pkg.getFeatures().split("\\r?\\n"))
                : List.of();
        return new TierPackageDto(
                pkg.getCode(),
                pkg.getName(),
                pkg.getPrice() != null ? pkg.getPrice() : 0L,
                pkg.getCycle() != null ? pkg.getCycle() : "MONTHLY",
                pkg.getQuotaLimit() != null ? pkg.getQuotaLimit() : 100,
                pkg.getBatchConcurrency() != null ? pkg.getBatchConcurrency() : 2,
                featureList,
                pkg.getDescription(),
                Boolean.TRUE.equals(pkg.getActive()),
                count);
    }

    private TierPackageDto toTierPackageDto(TierPackageInternal pkg, long count) {
        return new TierPackageDto(
                pkg.code,
                pkg.name,
                pkg.price,
                pkg.cycle,
                pkg.quotaLimit,
                pkg.batchConcurrency,
                List.copyOf(pkg.features),
                pkg.description,
                pkg.active,
                count);
    }

    @Transactional
    public synchronized TierPackageDto createTierPackage(AuthUser admin, SaveTierPackageRequest req) {
        if (req == null || req.code() == null || req.code().isBlank()) {
            throw new IllegalArgumentException("Mã gói không được để trống.");
        }
        String code = req.code().trim().toUpperCase();

        if (tierPackageRepository != null) {
            if (tierPackageRepository.existsById(code)) {
                throw new IllegalArgumentException("Mã gói " + code + " đã tồn tại.");
            }
            List<String> sanitizedFeatures = req.features() != null
                    ? req.features().stream().filter(f -> f != null && !f.isBlank()).map(String::trim).toList()
                    : List.of();
            TierPackage pkg = new TierPackage(
                    code,
                    req.name() != null && !req.name().isBlank() ? req.name().trim() : ("Gói " + code),
                    req.price() != null && req.price() >= 0 ? req.price() : 0L,
                    req.cycle() != null && !req.cycle().isBlank() ? req.cycle().trim().toUpperCase() : "MONTHLY",
                    req.quotaLimit() != null && req.quotaLimit() > 0 ? req.quotaLimit() : 100,
                    req.batchConcurrency() != null && req.batchConcurrency() > 0 ? req.batchConcurrency() : 2,
                    String.join("\n", sanitizedFeatures),
                    req.description() != null ? req.description() : "",
                    req.active() != null ? req.active() : true);
            pkg = tierPackageRepository.save(pkg);
            activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                    Map.of("action", "CREATE_TIER_PACKAGE", "code", code));
            return toTierPackageDto(pkg, countUsersInTier(code));
        }

        if (tierPackages.isEmpty()) {
            initTierPackages();
        }
        if (tierPackages.containsKey(code)) {
            throw new IllegalArgumentException("Mã gói " + code + " đã tồn tại.");
        }
        List<String> sanitizedFeatures = req.features() != null
                ? req.features().stream().filter(f -> f != null && !f.isBlank()).map(String::trim).toList()
                : List.of();
        TierPackageInternal pkg = new TierPackageInternal(
                code,
                req.name() != null && !req.name().isBlank() ? req.name().trim() : ("Gói " + code),
                req.price() != null && req.price() >= 0 ? req.price() : 0L,
                req.cycle() != null && !req.cycle().isBlank() ? req.cycle().trim().toUpperCase() : "MONTHLY",
                req.quotaLimit() != null && req.quotaLimit() > 0 ? req.quotaLimit() : 100,
                req.batchConcurrency() != null && req.batchConcurrency() > 0 ? req.batchConcurrency() : 2,
                sanitizedFeatures,
                req.description() != null ? req.description() : "",
                req.active() != null ? req.active() : true);
        tierPackages.put(code, pkg);
        activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                Map.of("action", "CREATE_TIER_PACKAGE", "code", code));
        return toTierPackageDto(pkg, countUsersInTier(code));
    }

    @Transactional
    public synchronized TierPackageDto updateTierPackage(AuthUser admin, String code, SaveTierPackageRequest req) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Mã gói không hợp lệ.");
        }
        if (req == null) {
            throw new IllegalArgumentException("Dữ liệu cập nhật không được để trống.");
        }
        String key = code.trim().toUpperCase();

        if (tierPackageRepository != null) {
            TierPackage pkg = tierPackageRepository.findById(key)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy gói: " + key));
            if (req.name() != null && !req.name().isBlank()) pkg.setName(req.name().trim());
            if (req.price() != null && req.price() >= 0) pkg.setPrice(req.price());
            if (req.cycle() != null && !req.cycle().isBlank()) pkg.setCycle(req.cycle().trim().toUpperCase());
            if (req.quotaLimit() != null && req.quotaLimit() > 0) {
                final int newLimit = req.quotaLimit();
                pkg.setQuotaLimit(newLimit);
                try {
                    UserTier userTier = UserTier.valueOf(key);
                    if (quotaService != null) {
                        quotaService.setTierLimit(userTier, newLimit);
                    }
                    if (users != null && quotas != null) {
                        List<AuthUser> tierUsers = users.findAll((root, q, cb) ->
                                userTier == UserTier.FREE
                                        ? cb.or(cb.equal(root.get("tier"), UserTier.FREE), cb.isNull(root.get("tier")))
                                        : cb.equal(root.get("tier"), userTier));
                        for (AuthUser u : tierUsers) {
                            quotas.findByUserId(u.getId()).ifPresent(quota -> {
                                quota.setQuotaLimit(newLimit);
                                quotas.save(quota);
                            });
                        }
                    }
                } catch (IllegalArgumentException ignored) {}
            }
            if (req.batchConcurrency() != null && req.batchConcurrency() > 0) pkg.setBatchConcurrency(req.batchConcurrency());
            if (req.features() != null) {
                List<String> feats = req.features().stream().filter(f -> f != null && !f.isBlank()).map(String::trim).toList();
                pkg.setFeatures(String.join("\n", feats));
            }
            if (req.description() != null) pkg.setDescription(req.description());
            if (req.active() != null) pkg.setActive(req.active());
            TierPackage savedPkg = tierPackageRepository.save(pkg);
            activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                    Map.of("action", "UPDATE_TIER_PACKAGE", "code", key));
            return toTierPackageDto(savedPkg, countUsersInTier(key));
        }

        if (tierPackages.isEmpty()) {
            initTierPackages();
        }
        TierPackageInternal pkg = tierPackages.get(key);
        if (pkg == null) {
            throw new IllegalArgumentException("Không tìm thấy gói: " + key);
        }
        if (req.name() != null && !req.name().isBlank())
            pkg.name = req.name().trim();
        if (req.price() != null && req.price() >= 0)
            pkg.price = req.price();
        if (req.cycle() != null && !req.cycle().isBlank())
            pkg.cycle = req.cycle().trim().toUpperCase();
        if (req.quotaLimit() != null && req.quotaLimit() > 0) {
            pkg.quotaLimit = req.quotaLimit();
            try {
                UserTier userTier = UserTier.valueOf(key);
                if (quotaService != null) {
                    quotaService.setTierLimit(userTier, pkg.quotaLimit);
                }
                if (users != null && quotas != null) {
                    List<AuthUser> tierUsers = users.findAll((root, q, cb) ->
                            userTier == UserTier.FREE
                                    ? cb.or(cb.equal(root.get("tier"), UserTier.FREE), cb.isNull(root.get("tier")))
                                    : cb.equal(root.get("tier"), userTier));
                    for (AuthUser u : tierUsers) {
                        quotas.findByUserId(u.getId()).ifPresent(quota -> {
                            quota.setQuotaLimit(pkg.quotaLimit);
                            quotas.save(quota);
                        });
                    }
                }
            } catch (IllegalArgumentException ignored) {}
        }
        if (req.batchConcurrency() != null && req.batchConcurrency() > 0)
            pkg.batchConcurrency = req.batchConcurrency();
        if (req.features() != null) {
            pkg.features = req.features().stream().filter(f -> f != null && !f.isBlank()).map(String::trim).toList();
        }
        if (req.description() != null)
            pkg.description = req.description();
        if (req.active() != null)
            pkg.active = req.active();

        activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                Map.of("action", "UPDATE_TIER_PACKAGE", "code", key));
        return toTierPackageDto(pkg, countUsersInTier(key));
    }

    @Transactional
    public synchronized void deleteTierPackage(AuthUser admin, String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Mã gói không hợp lệ.");
        }
        String key = code.trim().toUpperCase();

        if (tierPackageRepository != null) {
            TierPackage pkg = tierPackageRepository.findById(key)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy gói: " + key));
            if (List.of("FREE", "PLUS", "PRO").contains(key)) {
                pkg.setActive(false);
                tierPackageRepository.save(pkg);
                activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                        Map.of("action", "DISABLE_TIER_PACKAGE", "code", key));
                return;
            }
            long activeCount = countUsersInTier(key);
            if (activeCount > 0) {
                throw new IllegalArgumentException("Không thể xóa gói " + key + " vì đang có " + activeCount + " người dùng sử dụng. Hãy chuyển gói sang TẠM DỪNG.");
            }
            tierPackageRepository.delete(pkg);
            activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                    Map.of("action", "DELETE_TIER_PACKAGE", "code", key));
            return;
        }

        if (tierPackages.isEmpty()) {
            initTierPackages();
        }
        if (!tierPackages.containsKey(key)) {
            throw new IllegalArgumentException("Không tìm thấy gói: " + key);
        }
        if (List.of("FREE", "PLUS", "PRO").contains(key)) {
            TierPackageInternal pkg = tierPackages.get(key);
            if (pkg != null) {
                pkg.active = false;
                activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                        Map.of("action", "DISABLE_TIER_PACKAGE", "code", key));
            }
            return;
        }
        tierPackages.remove(key);
        activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                Map.of("action", "DELETE_TIER_PACKAGE", "code", key));
    }

    public SystemSettingsDto getSystemSettings() {
        Map<String, String> map = new LinkedHashMap<>();
        List<SystemSettingDto> details = new ArrayList<>();
        if (systemSettingRepository != null) {
            List<SystemSetting> list = systemSettingRepository.findAll();
            for (SystemSetting s : list) {
                map.put(s.getSettingKey(), s.getSettingValue());
                details.add(new SystemSettingDto(s.getSettingKey(), s.getSettingValue(), s.getDescription(), toInstant(s.getUpdatedAt())));
            }
        }
        map.putIfAbsent("ai.provider", dynamicProvider != null ? dynamicProvider : llmProvider);
        map.putIfAbsent("ai.model", dynamicModel != null ? dynamicModel : llmModel);
        map.putIfAbsent("ai.batch_concurrency", String.valueOf(batchConcurrency));
        map.putIfAbsent("vnpay.tmn_code", "CGXZLS0Z");
        map.putIfAbsent("vnpay.url", "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        map.putIfAbsent("log.output_dir", "../log/llm-usage");
        map.putIfAbsent("alert.quota_threshold_percent", "80");
        return new SystemSettingsDto(map, details);
    }

    @Transactional
    public SystemSettingsDto updateSystemSettings(AuthUser admin, UpdateSystemSettingsRequest req) {
        if (req != null && req.settings() != null && systemSettingRepository != null) {
            for (var entry : req.settings().entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue() != null ? entry.getValue().trim() : "";
                SystemSetting s = systemSettingRepository.findById(key)
                        .orElse(new SystemSetting(key, val, "Cấu hình hệ thống"));
                s.setSettingValue(val);
                s.setUpdatedBy(admin.getId());
                systemSettingRepository.save(s);

                // Apply live
                if ("ai.model".equalsIgnoreCase(key) && !val.isBlank()) {
                    this.dynamicModel = val;
                    if (openAiLlmClient != null) openAiLlmClient.setModel(val);
                    if (googleLlmClient != null) googleLlmClient.setModel(val);
                } else if ("ai.batch_concurrency".equalsIgnoreCase(key)) {
                    try {
                        int c = Integer.parseInt(val);
                        if (c > 0) this.batchConcurrency = c;
                    } catch (NumberFormatException ignored) {}
                }
            }
            activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                    Map.of("action", "UPDATE_SYSTEM_SETTINGS", "keys", req.settings().keySet()));
        }
        return getSystemSettings();
    }

    public PageDto<JobHistoryDto> getJobHistory(int page, int size, GenerationProgressStage stage) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        List<ActivityAction> targetActions = stage != null ? List.of(stageToAction(stage)) : GENERATION_ACTIONS;
        Page<UserActivityLog> logPage = activities.findAll(
                (root, q, cb) -> root.get("actionType").in(targetActions),
                pageable);
        List<JobHistoryDto> dtos = logPage.getContent().stream().map(log -> {
            String projName = "—";
            if (log.getRelatedProjectId() != null) {
                projName = projects.findById(log.getRelatedProjectId()).map(Project::getName).orElse("Project #" + log.getRelatedProjectId());
            }
            String email = "—";
            if (log.getUserId() != null) {
                email = users.findById(log.getUserId()).map(AuthUser::getEmail).orElse("User #" + log.getUserId());
            }
            String status = "COMPLETED";
            if (log.getMetadata() != null && log.getMetadata().containsKey("status")) {
                status = String.valueOf(log.getMetadata().get("status"));
            }
            return new JobHistoryDto(log.getId(), log.getRelatedProjectId(), projName, log.getUserId(), email, log.getActionType(), status, toInstant(log.getCreatedAt()), log.getMetadata());
        }).toList();
        return new PageDto<>(dtos, logPage.getNumber(), logPage.getSize(), logPage.getTotalElements(), logPage.getTotalPages());
    }

    public void retryJob(AuthUser admin, Long projectId, GenerationProgressStage stage) {
        if (projectId == null || stage == null) {
            throw new IllegalArgumentException("ProjectId và Stage không được để trống khi retry job.");
        }
        var project = projects.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy dự án ID: " + projectId));
        if (progressService != null) {
            progressService.reset(projectId, stage);
            progressService.start(projectId, stage, List.of("Khởi tạo tác vụ", "Sinh kiểm thử AI", "Hoàn tất"), "Quản trị viên đã kích hoạt Retry tác vụ.");
        }
        activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, projectId,
                Map.of("action", "ADMIN_RETRY_JOB", "stage", stage.name(), "projectName", project.getName()));
    }

    private ActivityAction stageToAction(GenerationProgressStage stage) {
        return switch (stage) {
            case BUSINESS_RULE -> ActivityAction.GENERATE_BUSINESS_RULE;
            case TEST_PLAN -> ActivityAction.GENERATE_TEST_PLAN;
            case TEST_CASE -> ActivityAction.GENERATE_TEST_CASE;
            case UNIT_TEST -> ActivityAction.GENERATE_UNIT_TEST;
        };
    }

    public static class UserFeedbackInternal {
        public Long id;
        public Long userId;
        public String userEmail;
        public String userName;
        public String category;
        public String title;
        public String content;
        public Integer rating;
        public String status;
        public String adminNote;
        public LocalDateTime createdAt;
        public LocalDateTime resolvedAt;
        public String projectName;

        public UserFeedbackInternal(Long id, Long userId, String userEmail, String userName, String category,
                String title, String content, Integer rating, String status, String adminNote,
                LocalDateTime createdAt, LocalDateTime resolvedAt, String projectName) {
            this.id = id;
            this.userId = userId;
            this.userEmail = userEmail;
            this.userName = userName;
            this.category = category;
            this.title = title;
            this.content = content;
            this.rating = rating;
            this.status = status;
            this.adminNote = adminNote;
            this.createdAt = createdAt;
            this.resolvedAt = resolvedAt;
            this.projectName = projectName;
        }
    }

    private final Map<Long, UserFeedbackInternal> feedbacks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong feedbackIdGenerator = new java.util.concurrent.atomic.AtomicLong(
            100);
    private volatile boolean feedbacksInitialized = false;

    private synchronized void ensureFeedbacksInitialized() {
        if (!feedbacksInitialized) {
            initFeedbacks();
            feedbacksInitialized = true;
        }
    }

    private void initFeedbacks() {
        // Để trống, không khởi tạo dữ liệu mẫu mock để đảm bảo hệ thống phản ánh dữ liệu thực tế từ người dùng
    }

    public List<UserFeedbackDto> getUserFeedbacks(Long userId) {
        if (userId == null) {
            return List.of();
        }
        if (feedbackRepository != null) {
            return feedbackRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                    .map(this::toUserFeedbackDto)
                    .toList();
        }
        ensureFeedbacksInitialized();
        return feedbacks.values().stream()
                .filter(f -> userId.equals(f.userId))
                .sorted(Comparator.comparing((UserFeedbackInternal f) -> f.createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toUserFeedbackDto)
                .toList();
    }

    public List<UserFeedbackDto> getFeedbacks(String status, String category, String search) {
        if (feedbackRepository != null) {
            Specification<UserFeedback> spec = (root, query, cb) -> {
                List<Predicate> predicates = new ArrayList<>();
                if (status != null && !status.isBlank()) {
                    predicates.add(cb.equal(cb.upper(root.get("status")), status.trim().toUpperCase()));
                }
                if (category != null && !category.isBlank()) {
                    predicates.add(cb.equal(cb.upper(root.get("category")), category.trim().toUpperCase()));
                }
                if (search != null && !search.isBlank()) {
                    String pattern = "%" + search.trim().toLowerCase() + "%";
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.get("title")), pattern),
                            cb.like(cb.lower(root.get("content")), pattern),
                            cb.like(cb.lower(root.get("userEmail")), pattern),
                            cb.like(cb.lower(root.get("userName")), pattern)
                    ));
                }
                return cb.and(predicates.toArray(Predicate[]::new));
            };
            return feedbackRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                    .map(this::toUserFeedbackDto)
                    .toList();
        }

        ensureFeedbacksInitialized();
        return feedbacks.values().stream()
                .filter(f -> status == null || status.isBlank() || f.status.equalsIgnoreCase(status.trim()))
                .filter(f -> category == null || category.isBlank() || f.category.equalsIgnoreCase(category.trim()))
                .filter(f -> search == null || search.isBlank()
                        || (f.title != null && f.title.toLowerCase().contains(search.trim().toLowerCase()))
                        || (f.content != null && f.content.toLowerCase().contains(search.trim().toLowerCase()))
                        || (f.userEmail != null && f.userEmail.toLowerCase().contains(search.trim().toLowerCase()))
                        || (f.userName != null && f.userName.toLowerCase().contains(search.trim().toLowerCase())))
                .sorted(Comparator.comparing((UserFeedbackInternal f) -> f.createdAt).reversed())
                .map(this::toUserFeedbackDto)
                .toList();
    }

    public FeedbackOverviewDto getFeedbackOverview() {
        if (feedbackRepository != null) {
            long total = feedbackRepository.count();
            long pending = feedbackRepository.countByStatus("NEW") + feedbackRepository.countByStatus("IN_PROGRESS");
            long resolved = feedbackRepository.countByStatus("RESOLVED") + feedbackRepository.countByStatus("CLOSED");
            return new FeedbackOverviewDto(total, pending, resolved);
        }

        ensureFeedbacksInitialized();
        long total = feedbacks.size();
        long pending = feedbacks.values().stream().filter(f -> "NEW".equals(f.status) || "IN_PROGRESS".equals(f.status))
                .count();
        long resolved = feedbacks.values().stream().filter(f -> "RESOLVED".equals(f.status) || "CLOSED".equals(f.status)).count();
        return new FeedbackOverviewDto(total, pending, resolved);
    }

    public synchronized UserFeedbackDto createFeedback(AuthUser user, SaveFeedbackRequest req) {
        if (req == null || req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("Tiêu đề phản hồi không được để trống.");
        }

        if (feedbackRepository != null) {
            UserFeedback fb = new UserFeedback();
            fb.setUserId(user != null ? user.getId() : null);
            fb.setUserEmail(user != null ? user.getEmail() : "anonymous@greytest.dev");
            fb.setUserName(user != null ? user.getFullName() : "Anonymous User");
            fb.setCategory(req.category() != null && !req.category().isBlank() ? req.category().trim().toUpperCase() : "GENERAL");
            fb.setTitle(req.title().trim());
            fb.setContent(req.content() != null ? req.content().trim() : "");
            fb.setStatus("NEW");
            fb.setAdminNote("");
            fb.setProjectName(req.projectName() != null ? req.projectName().trim() : null);
            fb = feedbackRepository.save(fb);
            return toUserFeedbackDto(fb);
        }

        ensureFeedbacksInitialized();
        long id = feedbackIdGenerator.incrementAndGet();
        Long userId = user != null ? user.getId() : null;
        String email = user != null ? user.getEmail() : "anonymous@greytest.dev";
        String name = user != null ? user.getFullName() : "Anonymous User";
        String cat = req.category() != null && !req.category().isBlank() ? req.category().trim().toUpperCase()
                : "GENERAL";

        UserFeedbackInternal fb = new UserFeedbackInternal(
                id, userId, email, name, cat,
                req.title().trim(), req.content() != null ? req.content().trim() : "",
                5, "NEW", "",
                LocalDateTime.now(), null,
                req.projectName() != null ? req.projectName().trim() : null);

        feedbacks.put(id, fb);
        return toUserFeedbackDto(fb);
    }

    public synchronized UserFeedbackDto updateFeedbackStatus(AuthUser admin, Long id, UpdateFeedbackStatusRequest req) {
        if (id == null) {
            throw new IllegalArgumentException("ID phản hồi không hợp lệ.");
        }
        if (req == null) {
            throw new IllegalArgumentException("Dữ liệu cập nhật không được để trống.");
        }

        if (feedbackRepository != null) {
            UserFeedback fb = feedbackRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phản hồi ID: " + id));
            if (req.status() != null && !req.status().isBlank()) {
                fb.setStatus(req.status().trim().toUpperCase());
            }
            if (req.adminNote() != null) {
                fb.setAdminNote(req.adminNote().trim());
            }
            fb.setUpdatedAt(LocalDateTime.now());
            fb = feedbackRepository.save(fb);
            activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                    Map.of("action", "UPDATE_FEEDBACK_STATUS", "id", id, "status", fb.getStatus()));
            return toUserFeedbackDto(fb);
        }

        ensureFeedbacksInitialized();
        UserFeedbackInternal fb = feedbacks.get(id);
        if (fb == null) {
            throw new IllegalArgumentException("Không tìm thấy phản hồi ID: " + id);
        }
        if (req.status() != null && !req.status().isBlank()) {
            String newStatus = req.status().trim().toUpperCase();
            fb.status = newStatus;
            if ("RESOLVED".equals(newStatus) || "CLOSED".equals(newStatus)) {
                fb.resolvedAt = LocalDateTime.now();
            } else {
                fb.resolvedAt = null;
            }
        }
        if (req.adminNote() != null) {
            fb.adminNote = req.adminNote().trim();
        }
        activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                Map.of("action", "UPDATE_FEEDBACK_STATUS", "id", id, "status", fb.status));
        return toUserFeedbackDto(fb);
    }

    public synchronized void deleteFeedback(AuthUser admin, Long id) {
        if (id == null) {
            throw new IllegalArgumentException("ID phản hồi không hợp lệ.");
        }

        if (feedbackRepository != null) {
            if (!feedbackRepository.existsById(id)) {
                throw new IllegalArgumentException("Không tìm thấy phản hồi ID: " + id);
            }
            feedbackRepository.deleteById(id);
            activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                    Map.of("action", "DELETE_FEEDBACK", "id", id));
            return;
        }

        ensureFeedbacksInitialized();
        if (!feedbacks.containsKey(id)) {
            throw new IllegalArgumentException("Không tìm thấy phản hồi ID: " + id);
        }
        feedbacks.remove(id);
        activityService.record(admin.getId(), ActivityAction.ADMIN_STATUS_CHANGE, null,
                Map.of("action", "DELETE_FEEDBACK", "id", id));
    }

    private UserFeedbackDto toUserFeedbackDto(UserFeedback fb) {
        return new UserFeedbackDto(
                fb.getId(),
                fb.getUserId(),
                fb.getUserEmail(),
                fb.getUserName(),
                fb.getCategory(),
                fb.getTitle(),
                fb.getContent(),
                fb.getStatus(),
                fb.getAdminNote(),
                fb.getCreatedAt(),
                fb.getUpdatedAt(),
                fb.getProjectName());
    }

    private UserFeedbackDto toUserFeedbackDto(UserFeedbackInternal fb) {
        return new UserFeedbackDto(
                fb.id,
                fb.userId,
                fb.userEmail,
                fb.userName,
                fb.category,
                fb.title,
                fb.content,
                fb.status,
                fb.adminNote,
                fb.createdAt,
                fb.resolvedAt,
                fb.projectName);
    }

    private Specification<AuthUser> userFilter(String search, UserRole role, Boolean enabled, UserTier tier) {
        return (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("email")), pattern),
                        builder.like(builder.lower(root.get("fullName")), pattern)));
            }
            if (role != null)
                predicates.add(builder.equal(root.get("role"), role));
            if (enabled != null)
                predicates.add(builder.equal(root.get("enabled"), enabled));
            if (tier != null)
                predicates.add(builder.equal(root.get("tier"), tier));
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private Specification<UserActivityLog> activityFilter(
            List<Long> matchedUserIds, ActivityAction action, LocalDateTime from, LocalDateTime to) {
        return (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (matchedUserIds != null) {
                if (matchedUserIds.size() == 1) {
                    predicates.add(builder.equal(root.get("userId"), matchedUserIds.get(0)));
                } else {
                    predicates.add(root.get("userId").in(matchedUserIds));
                }
            }
            if (action != null)
                predicates.add(builder.equal(root.get("actionType"), action));
            if (from != null)
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null)
                predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), to));
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private UserSummaryDto summary(AuthUser user) {
        return new UserSummaryDto(
                user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.getTier() != null ? user.getTier() : UserTier.FREE,
                Boolean.TRUE.equals(user.getEnabled()), toInstant(user.getCreatedAt()),
                activities.countByUserIdAndActionTypeIn(user.getId(), GENERATION_ACTIONS),
                activities.findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                        .map(activity -> toInstant(activity.getCreatedAt())).orElse(null),
                toQuota(quotaService.current(user.getId())));
    }

    private QuotaDto toQuota(UsageQuota quota) {
        if (quota == null) {
            return new QuotaDto(100, 0, 100, java.time.LocalDate.now(), java.time.LocalDate.now().plusMonths(1), false);
        }
        Integer limit = quota.getQuotaLimit();
        Integer remaining = limit == null ? null : Math.max(limit - quota.getQuotaUsed(), 0);
        return new QuotaDto(limit, quota.getQuotaUsed(), remaining,
                quota.getPeriodStart(), quota.getPeriodStart().plusMonths(1),
                limit != null && quota.getQuotaUsed() >= limit);
    }

    private ActivityDto toActivity(UserActivityLog activity, String email) {
        return new ActivityDto(
                activity.getId(), activity.getUserId(), email, activity.getActionType(),
                activity.getRelatedProjectId(), toInstant(activity.getCreatedAt()), activity.getMetadata());
    }

    private LoginHistoryDto toLoginHistory(UserActivityLog log) {
        Map<String, Object> meta = log.getMetadata() != null ? log.getMetadata() : Map.of();
        String ip = String.valueOf(meta.getOrDefault("ip", "127.0.0.1"));
        String userAgent = String.valueOf(meta.getOrDefault("userAgent", "Web Client"));
        String portal = String.valueOf(meta.getOrDefault("portal", "USER_PORTAL"));
        String status = String.valueOf(meta.getOrDefault("status", "SUCCESS"));
        String device = resolveDevice(userAgent);
        return new LoginHistoryDto(log.getId(), toInstant(log.getCreatedAt()), ip, userAgent, portal, status, device);
    }

    private String resolveDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Web Browser";
        String lower = userAgent.toLowerCase();
        String os = lower.contains("windows") ? "Windows" : lower.contains("mac") ? "macOS" : lower.contains("linux") ? "Linux" : lower.contains("android") ? "Android" : lower.contains("iphone") ? "iOS" : "PC";
        String browser = lower.contains("edg") ? "Edge" : lower.contains("chrome") ? "Chrome" : lower.contains("firefox") ? "Firefox" : lower.contains("safari") ? "Safari" : "Browser";
        return browser + " (" + os + ")";
    }

    private java.time.Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(java.time.ZoneId.systemDefault()).toInstant();
    }

    private AuthUser requireUser(Long userId) {
        return users.findById(userId).orElseThrow(() -> new AuthException(
                "USER_NOT_FOUND", "Không tìm thấy người dùng", org.springframework.http.HttpStatus.NOT_FOUND));
    }

    public List<ActiveJobDto> activeJobs(GenerationProgressStage stage) {
        if (progressService == null) {
            return List.of();
        }
        List<GenerationProgressService.ActiveProgressItem> items = progressService.getActiveProgressItems();
        List<ActiveJobDto> dtos = new ArrayList<>();
        for (var item : items) {
            if (stage != null && item.stage() != stage) {
                continue;
            }
            String projectName = "Project #" + item.projectId();
            String userEmail = "—";
            var projectOpt = projects.findById(item.projectId());
            if (projectOpt.isPresent()) {
                var project = projectOpt.get();
                projectName = project.getName();
                if (project.getOwnerUserId() != null) {
                    userEmail = users.findById(project.getOwnerUserId())
                            .map(AuthUser::getEmail)
                            .orElse("User #" + project.getOwnerUserId());
                }
            }
            String message = null;
            Instant lastUpdate = Instant.now();
            if (item.progress().logs() != null && !item.progress().logs().isEmpty()) {
                var lastLog = item.progress().logs().get(item.progress().logs().size() - 1);
                message = lastLog.message();
                lastUpdate = lastLog.timestamp();
            }
            dtos.add(new ActiveJobDto(
                    item.projectId(),
                    projectName,
                    userEmail,
                    item.stage(),
                    item.progress().status(),
                    item.progress().percent(),
                    item.progress().completedSteps(),
                    item.progress().totalSteps(),
                    message,
                    lastUpdate));
        }
        return dtos;
    }

    private boolean isLastEnabledAdmin(AuthUser user, List<AuthUser> enabledAdmins) {
        return user.getRole() == UserRole.ADMIN && Boolean.TRUE.equals(user.getEnabled())
                && enabledAdmins.size() <= 1;
    }
}
