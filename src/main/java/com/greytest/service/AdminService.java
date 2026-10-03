package com.greytest.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.admin.AdminDtos.ActivityDto;
import com.greytest.dto.admin.AdminDtos.HealthDto;
import com.greytest.dto.admin.AdminDtos.OverviewDto;
import com.greytest.dto.admin.AdminDtos.PageDto;
import com.greytest.dto.admin.AdminDtos.ProjectSummaryDto;
import com.greytest.dto.admin.AdminDtos.QuotaDto;
import com.greytest.dto.admin.AdminDtos.TopUserDto;
import com.greytest.dto.admin.AdminDtos.TrendPointDto;
import com.greytest.dto.admin.AdminDtos.UserDetailDto;
import com.greytest.dto.admin.AdminDtos.UserSummaryDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.UsageQuota;
import com.greytest.entity.UserActivityLog;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.UserRole;
import com.greytest.entity.enums.UserTier;
import com.greytest.exception.AuthException;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.repository.UsageQuotaRepository;
import com.greytest.repository.UserActivityLogRepository;

/** Cung cấp toàn bộ use case quản trị user, quota, activity và số liệu dashboard. */
@Service
public class AdminService {

    private static final Set<String> DATABASE_USER_SORTS = Set.of("email", "createdAt", "role", "enabled", "tier");
    private static final Set<String> COMPUTED_USER_SORTS = Set.of("totalGenerationRequests", "lastActivityAt", "quota", "quotaUsed");
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

    public AdminService(
            AuthUserRepository users,
            ProjectRepository projects,
            UnitTestRepository unitTests,
            UserActivityLogRepository activities,
            UsageQuotaRepository quotas,
            UsageQuotaService quotaService,
            UserActivityService activityService) {
        this.users = users;
        this.projects = projects;
        this.unitTests = unitTests;
        this.activities = activities;
        this.quotas = quotas;
        this.quotaService = quotaService;
        this.activityService = activityService;
    }

    @Transactional
    public PageDto<UserSummaryDto> listUsers(
            String search, UserRole role, Boolean enabled, int page, int size, String sort, String direction) {
        boolean isDesc = "desc".equalsIgnoreCase(direction);

        if (COMPUTED_USER_SORTS.contains(sort)) {
            List<AuthUser> allFiltered = users.findAll(userFilter(search, role, enabled));
            List<UserSummaryDto> content = new ArrayList<>(allFiltered.stream().map(this::summary).toList());

            Comparator<UserSummaryDto> comparator;
            if ("totalGenerationRequests".equalsIgnoreCase(sort)) {
                comparator = Comparator.comparingLong(UserSummaryDto::totalGenerationRequests);
                if (isDesc) comparator = comparator.reversed();
            } else if ("lastActivityAt".equalsIgnoreCase(sort)) {
                Comparator<Instant> instantComparator = isDesc
                        ? Comparator.nullsLast(Comparator.reverseOrder())
                        : Comparator.nullsLast(Comparator.naturalOrder());
                comparator = Comparator.comparing(UserSummaryDto::lastActivityAt, instantComparator);
            } else { // "quota" hoặc "quotaUsed"
                comparator = Comparator.comparingInt(u -> u.quota() != null ? u.quota().used() : 0);
                if (isDesc) comparator = comparator.reversed();
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
        Page<AuthUser> result = users.findAll(userFilter(search, role, enabled), pageable);
        List<UserSummaryDto> content = result.getContent().stream().map(this::summary).toList();
        return new PageDto<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
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
        return new UserDetailDto(summary(user), userProjects, unitTests.countByOwnerUserId(userId), recent);
    }

    @Transactional
    public UserSummaryDto updateStatus(AuthUser admin, Long userId, boolean enabled, String reason) {
        // Khóa chung trước khi đọc target để request đồng thời không dùng trạng thái admin cũ.
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

        if (tier == UserTier.PRO) {
            quotaService.upgradeToPro(userId);
        } else {
            quotaService.downgradeToFree(userId);
        }

        activityService.record(admin.getId(), ActivityAction.ADMIN_TIER_CHANGE, null,
                Map.of("targetUserId", userId, "previousTier", previousTier.name(), "newTier", tier.name()));
        return summary(user);
    }

    @Transactional
    public QuotaDto updateQuota(AuthUser admin, Long userId, Integer limit) {
        requireUser(userId);
        UsageQuota quota = quotaService.updateLimit(userId, limit);
        // Giữ key null trong audit để frontend phân biệt unlimited với dữ liệu cũ thiếu key.
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("targetUserId", userId);
        metadata.put("quotaLimit", limit);
        activityService.record(admin.getId(), ActivityAction.ADMIN_QUOTA_CHANGE, null, metadata);
        return toQuota(quota);
    }

    @Transactional(readOnly = true)
    public PageDto<ActivityDto> activities(
            Long userId, ActivityAction action, LocalDateTime from, LocalDateTime to, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UserActivityLog> result = activities.findAll(activityFilter(userId, action, from, to), pageable);
        Map<Long, String> emails = users.findAllById(result.getContent().stream()
                        .map(UserActivityLog::getUserId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(AuthUser::getId, AuthUser::getEmail));
        List<ActivityDto> content = result.getContent().stream()
                .map(activity -> toActivity(activity, emails.get(activity.getUserId())))
                .toList();
        return new PageDto<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public OverviewDto overview() {
        LocalDateTime now = LocalDateTime.now();
        long alerts = quotas.countExceededInPeriod(java.time.LocalDate.now().withDayOfMonth(1));
        return new OverviewDto(
                users.count(),
                users.countByCreatedAtAfter(now.minusDays(7)),
                users.countByCreatedAtAfter(now.minusDays(30)),
                activities.countByActionTypeIn(GENERATION_ACTIONS),
                activities.countByActionType(ActivityAction.LLM_CALL),
                alerts);
    }

    @Transactional(readOnly = true)
    public List<TrendPointDto> trend(int days, String granularity, ActivityAction action) {
        String bucket = Set.of("day", "week", "month").contains(granularity) ? granularity : "day";
        return activities.usageTrend(
                        LocalDateTime.now().minusDays(Math.min(Math.max(days, 1), 365)),
                        bucket,
                        action == null ? null : action.name()).stream()
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
        return new HealthDto("UP", "UNKNOWN", "UNKNOWN", "IN_PROCESS");
    }

    private Specification<AuthUser> userFilter(String search, UserRole role, Boolean enabled) {
        return (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("email")), pattern),
                        builder.like(builder.lower(root.get("fullName")), pattern)));
            }
            if (role != null) predicates.add(builder.equal(root.get("role"), role));
            if (enabled != null) predicates.add(builder.equal(root.get("enabled"), enabled));
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private Specification<UserActivityLog> activityFilter(
            Long userId, ActivityAction action, LocalDateTime from, LocalDateTime to) {
        return (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (userId != null) predicates.add(builder.equal(root.get("userId"), userId));
            if (action != null) predicates.add(builder.equal(root.get("actionType"), action));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), to));
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

    private java.time.Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(java.time.ZoneId.systemDefault()).toInstant();
    }

    private AuthUser requireUser(Long userId) {
        return users.findById(userId).orElseThrow(() -> new AuthException(
                "USER_NOT_FOUND", "Không tìm thấy người dùng", org.springframework.http.HttpStatus.NOT_FOUND));
    }

    private boolean isLastEnabledAdmin(AuthUser user, List<AuthUser> enabledAdmins) {
        return user.getRole() == UserRole.ADMIN && Boolean.TRUE.equals(user.getEnabled())
                && enabledAdmins.size() <= 1;
    }
}
