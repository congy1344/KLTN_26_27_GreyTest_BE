package com.greytest.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.config.AdminAuthorizationInterceptor;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.admin.AdminDtos.ActiveJobDto;
import com.greytest.dto.admin.AdminDtos.ActivityDto;
import com.greytest.dto.admin.AdminDtos.AiRuntimeConfigDto;
import com.greytest.dto.admin.AdminDtos.BlockIpRequest;
import com.greytest.dto.admin.AdminDtos.EndpointAnalyticsDto;
import com.greytest.dto.admin.AdminDtos.HealthDto;
import com.greytest.dto.admin.AdminDtos.RateLimitConfigRequest;
import com.greytest.dto.admin.AdminDtos.ServerRequestLogDto;
import com.greytest.dto.admin.AdminDtos.ServerTelemetryDto;
import com.greytest.dto.admin.AdminDtos.ToggleUnderAttackRequest;
import com.greytest.dto.admin.AdminDtos.TrafficAlertDto;
import com.greytest.dto.admin.AdminDtos.TrafficOverviewDto;
import com.greytest.dto.admin.AdminDtos.TrafficTimeseriesDto;
import com.greytest.dto.admin.AdminDtos.UpdateRateLimitRequest;
import com.greytest.dto.admin.AdminDtos.WafStatusDto;
import com.greytest.dto.admin.BreakGlassGrantDto;
import com.greytest.dto.admin.BreakGlassRequestDto;
import com.greytest.entity.Project;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.DdosProtectionService;
import com.greytest.service.BreakGlassAccessService;
import com.greytest.service.UserActivityService;
import com.greytest.service.storage.migration.StorageMigrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.greytest.dto.admin.AdminDtos.JobHistoryDto;
import com.greytest.dto.admin.AdminDtos.OverviewDto;
import com.greytest.dto.admin.AdminDtos.PageDto;
import com.greytest.dto.admin.AdminDtos.QuotaDto;
import com.greytest.dto.admin.AdminDtos.SystemSettingsDto;
import com.greytest.dto.admin.AdminDtos.TopUserDto;
import com.greytest.dto.admin.AdminDtos.TrendPointDto;
import com.greytest.dto.admin.AdminDtos.UpdateAiKeysRequest;
import com.greytest.dto.admin.AdminDtos.UpdateAiModelRequest;
import com.greytest.dto.admin.AdminDtos.UpdateStageLimitsRequest;
import com.greytest.dto.admin.AdminDtos.UpdateSystemSettingsRequest;
import com.greytest.dto.admin.AdminDtos.TierPackageDto;
import com.greytest.dto.admin.AdminDtos.SaveTierPackageRequest;
import com.greytest.dto.admin.AdminDtos.FeedbackOverviewDto;
import com.greytest.dto.admin.AdminDtos.SaveFeedbackRequest;
import com.greytest.dto.admin.AdminDtos.UpdateFeedbackStatusRequest;
import com.greytest.dto.admin.AdminDtos.UserFeedbackDto;
import com.greytest.dto.admin.AdminDtos.UserDetailDto;
import com.greytest.dto.admin.AdminDtos.UserSummaryDto;
import com.greytest.dto.admin.CreateUserRequest;
import com.greytest.dto.admin.UpdateQuotaRequest;
import com.greytest.dto.admin.UpdateUserRequest;
import com.greytest.dto.admin.UpdateUserRoleRequest;
import com.greytest.dto.admin.UpdateUserStatusRequest;
import com.greytest.dto.admin.UpdateUserTierRequest;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.UserRole;
import com.greytest.entity.enums.UserTier;
import com.greytest.service.AdminService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService service;
    private final BreakGlassAccessService breakGlassAccessService;
    private StorageMigrationService storageMigrationService;
    @Autowired(required = false)
    private ProjectRepository projectRepository;
    @Autowired(required = false)
    private UserActivityService userActivityService;
    @Autowired(required = false)
    private com.greytest.security.waf.TrustedProxyResolver trustedProxyResolver = new com.greytest.security.waf.TrustedProxyResolver();

    public void setTrustedProxyResolver(com.greytest.security.waf.TrustedProxyResolver trustedProxyResolver) {
        this.trustedProxyResolver = trustedProxyResolver;
    }

    public void setProjectRepository(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public void setUserActivityService(UserActivityService userActivityService) {
        this.userActivityService = userActivityService;
    }

    @Autowired
    public AdminController(
            AdminService service,
            @Autowired(required = false) BreakGlassAccessService breakGlassAccessService,
            @Autowired(required = false) StorageMigrationService storageMigrationService) {
        this.service = service;
        this.breakGlassAccessService = breakGlassAccessService;
        this.storageMigrationService = storageMigrationService;
    }

    public AdminController(AdminService service, BreakGlassAccessService breakGlassAccessService) {
        this(service, breakGlassAccessService, null);
    }

    public AdminController(AdminService service) {
        this(service, null, null);
    }

    public void setStorageMigrationService(StorageMigrationService storageMigrationService) {
        this.storageMigrationService = storageMigrationService;
    }

    @GetMapping("/users")
    public PageDto<UserSummaryDto> users(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) UserTier tier,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.listUsers(search, role, enabled, tier, page, size, sort, direction);
    }

    @GetMapping("/users/{userId}")
    public UserDetailDto user(@PathVariable Long userId) {
        return service.userDetail(userId);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserSummaryDto createUser(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @Valid @RequestBody CreateUserRequest request) {
        return service.createUser(admin, request);
    }

    @PutMapping("/users/{userId}")
    public UserSummaryDto updateUser(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserRequest request) {
        return service.updateUser(admin, userId, request);
    }

    @DeleteMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long userId) {
        service.deleteUser(admin, userId);
    }

    @PatchMapping("/users/{userId}/status")
    public UserSummaryDto status(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return service.updateStatus(admin, userId, request.enabled(), request.reason());
    }

    @PatchMapping("/users/{userId}/role")
    public UserSummaryDto role(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserRoleRequest request) {
        return service.updateRole(admin, userId, request.role());
    }

    @PatchMapping("/users/{userId}/tier")
    public UserSummaryDto tier(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserTierRequest request) {
        return service.updateTier(admin, userId, request.tier());
    }

    @PatchMapping("/users/{userId}/quota")
    public QuotaDto quota(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateQuotaRequest request) {
        return service.updateQuota(admin, userId, request.quotaLimit());
    }

    @PostMapping("/quotas/{userId}/reset")
    public QuotaDto resetQuota(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long userId) {
        return service.resetQuota(admin, userId);
    }

    @GetMapping("/activity")
    public PageDto<ActivityDto> activity(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String userSearch,
            @RequestParam(required = false) ActivityAction action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.activities(userId, userSearch, action, from, to, page, size);
    }

    @GetMapping("/jobs/active")
    public List<ActiveJobDto> activeJobs(
            @RequestParam(required = false) GenerationProgressStage stage) {
        return service.activeJobs(stage);
    }

    @DeleteMapping("/jobs/active/{projectId}/{stage}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelActiveJob(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long projectId,
            @PathVariable GenerationProgressStage stage) {
        service.cancelActiveJob(admin, projectId, stage);
    }

    @GetMapping("/stats/overview")
    public OverviewDto overview() {
        return service.overview();
    }

    @GetMapping("/stats/trend")
    public List<TrendPointDto> trend(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "day") String granularity,
            @RequestParam(required = false) ActivityAction action) {
        return service.trend(from, to, days, granularity, action);
    }

    @GetMapping("/stats/top-users")
    public List<TopUserDto> topUsers(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "10") int limit) {
        return service.topUsers(days, limit);
    }

    @GetMapping("/health")
    public HealthDto health() {
        return service.health();
    }

    @GetMapping("/system/request-logs")
    public ServerTelemetryDto getRequestLogs(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer status) {
        return service.getTelemetry(limit, search, status);
    }

    @GetMapping("/system/security/waf-status")
    public WafStatusDto getWafStatus() {
        return service.getWafStatus();
    }

    @PostMapping("/system/security/under-attack-mode")
    public WafStatusDto toggleUnderAttack(
            @RequestAttribute(value = AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE, required = false) AuthUser admin,
            @RequestBody ToggleUnderAttackRequest request) {
        service.setUnderAttackMode(request.enabled());
        service.auditSecurityChange(admin, java.util.Map.of(
                "action", "TOGGLE_UNDER_ATTACK_MODE",
                "underAttackMode", request.enabled()));
        return service.getWafStatus();
    }

    @PostMapping("/system/security/emergency-safe-mode")
    public WafStatusDto toggleEmergencySafeMode(
            @RequestAttribute(value = AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE, required = false) AuthUser admin,
            @RequestBody com.greytest.dto.admin.AdminDtos.ToggleEmergencySafeModeRequest request) {
        service.setEmergencySafeMode(admin, request.enabled());
        return service.getWafStatus();
    }

    @PostMapping("/system/security/block-ip")
    public WafStatusDto blockIp(
            @RequestAttribute(value = AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE, required = false) AuthUser admin,
            @RequestBody BlockIpRequest request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String requesterIp = httpRequest != null
                ? (trustedProxyResolver != null ? trustedProxyResolver.resolveClientIp(httpRequest)
                        : httpRequest.getRemoteAddr())
                : null;
        if (requesterIp != null && request != null && request.ip() != null) {
            String canonTarget = com.greytest.security.waf.TrustedProxyResolver.canonicalizeIp(
                    com.greytest.security.waf.TrustedProxyResolver.extractIpCandidate(request.ip()));
            String canonReq = com.greytest.security.waf.TrustedProxyResolver.canonicalizeIp(
                    com.greytest.security.waf.TrustedProxyResolver.extractIpCandidate(requesterIp));
            if (canonTarget != null && canonTarget.equals(canonReq)) {
                throw new IllegalArgumentException("Không thể tự chặn địa chỉ IP của chính mình (Anti-self-lockout)");
            }
        }
        service.blockIp(request.ip(), request.reason(), request.durationMinutes());
        service.auditSecurityChange(admin, java.util.Map.of(
                "action", "BLOCK_IP",
                "ip", request.ip() != null ? request.ip() : "",
                "reason", request.reason() != null ? request.reason() : "",
                "durationMinutes", request.durationMinutes()));
        return service.getWafStatus();
    }

    @DeleteMapping("/system/security/block-ip/{ip}")
    public WafStatusDto unblockIp(
            @RequestAttribute(value = AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE, required = false) AuthUser admin,
            @PathVariable String ip) {
        boolean removed = service.unblockIp(ip);
        service.auditSecurityChange(admin, java.util.Map.of(
                "action", "UNBLOCK_IP",
                "ip", ip != null ? ip : "",
                "removed", removed));
        return service.getWafStatus();
    }

    @PostMapping("/system/security/unblock-all")
    public WafStatusDto unblockAll(
            @RequestAttribute(value = AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE, required = false) AuthUser admin) {
        return service.unblockAll(admin);
    }

    @PutMapping("/system/security/rate-limit")
    public WafStatusDto updateRateLimit(
            @RequestAttribute(value = AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE, required = false) AuthUser admin,
            @RequestBody UpdateRateLimitRequest request) {
        service.setRateLimit(request.rateLimitPerMinute());
        service.auditSecurityChange(admin, java.util.Map.of(
                "action", "UPDATE_RATE_LIMIT",
                "rateLimitPerMinute", request.rateLimitPerMinute()));
        return service.getWafStatus();
    }

    @GetMapping("/monitoring/traffic/overview")
    public TrafficOverviewDto getTrafficOverview(
            @RequestParam(defaultValue = "1m") String window) {
        return service.getTrafficOverview(window);
    }

    @GetMapping("/monitoring/traffic/timeseries")
    public TrafficTimeseriesDto getTrafficTimeseries(
            @RequestParam(defaultValue = "1m") String window) {
        return service.getTrafficTimeseries(window);
    }

    @GetMapping("/monitoring/requests/recent")
    public PageDto<ServerRequestLogDto> getRecentRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String statusGroup,
            @RequestParam(required = false) Long minLatencyMs,
            @RequestParam(defaultValue = "timestamp") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.getRecentRequests(page, size, search, method, status, statusGroup, minLatencyMs, sort,
                direction);
    }

    @GetMapping("/monitoring/endpoints")
    public EndpointAnalyticsDto getEndpointAnalytics(
            @RequestParam(defaultValue = "15m") String window,
            @RequestParam(defaultValue = "10") int limit) {
        return service.getEndpointAnalytics(window, limit);
    }

    @GetMapping("/monitoring/alerts")
    public List<TrafficAlertDto> getTrafficAlerts(
            @RequestParam(defaultValue = "1m") String window) {
        return service.getTrafficAlerts(window);
    }

    @GetMapping(path = "/monitoring/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMonitoring() {
        return service.subscribeMonitoringStream();
    }

    @GetMapping("/monitoring/rate-limit")
    public WafStatusDto getMonitoringRateLimit() {
        return service.getWafStatus();
    }

    @PutMapping("/monitoring/rate-limit")
    public WafStatusDto updateMonitoringRateLimit(
            @RequestAttribute(value = AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE, required = false) AuthUser admin,
            @RequestBody RateLimitConfigRequest request) {
        return service.updateRateLimitConfig(admin, request);
    }

    @GetMapping("/ai-config")
    public AiRuntimeConfigDto getAiConfig() {
        return service.getAiRuntimeConfig();
    }

    @PutMapping("/ai-config/keys")
    public AiRuntimeConfigDto updateAiKeys(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @RequestBody UpdateAiKeysRequest request) {
        return service.updateAiKeys(admin, request);
    }

    @PutMapping("/ai-config/limits")
    public AiRuntimeConfigDto updateStageLimits(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @RequestBody UpdateStageLimitsRequest request) {
        return service.updateStageLimits(admin, request);
    }

    @PutMapping("/ai-config/model")
    public AiRuntimeConfigDto updateAiModel(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @RequestBody UpdateAiModelRequest request) {
        return service.updateAiModel(admin, request);
    }

    @GetMapping("/tier-packages")
    public List<TierPackageDto> getTierPackages() {
        return service.getTierPackages();
    }

    @PostMapping("/tier-packages")
    public TierPackageDto createTierPackage(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @RequestBody SaveTierPackageRequest request) {
        return service.createTierPackage(admin, request);
    }

    @PutMapping("/tier-packages/{code}")
    public TierPackageDto updateTierPackage(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable String code,
            @RequestBody SaveTierPackageRequest request) {
        return service.updateTierPackage(admin, code, request);
    }

    @DeleteMapping("/tier-packages/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTierPackage(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable String code) {
        service.deleteTierPackage(admin, code);
    }

    @GetMapping("/feedbacks")
    public List<UserFeedbackDto> getFeedbacks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search) {
        return service.getFeedbacks(status, category, search);
    }

    @GetMapping("/feedbacks/stats")
    public FeedbackOverviewDto getFeedbackStats() {
        return service.getFeedbackOverview();
    }

    @PostMapping("/feedbacks")
    public UserFeedbackDto createFeedback(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @RequestBody SaveFeedbackRequest request) {
        return service.createFeedback(admin, request);
    }

    @PatchMapping("/feedbacks/{id}/status")
    public UserFeedbackDto updateFeedbackStatus(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long id,
            @RequestBody UpdateFeedbackStatusRequest request) {
        return service.updateFeedbackStatus(admin, id, request);
    }

    @DeleteMapping("/feedbacks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFeedback(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long id) {
        service.deleteFeedback(admin, id);
    }

    @GetMapping("/settings")
    public SystemSettingsDto getSettings() {
        return service.getSystemSettings();
    }

    @PutMapping("/settings")
    public SystemSettingsDto updateSettings(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @RequestBody UpdateSystemSettingsRequest request) {
        return service.updateSystemSettings(admin, request);
    }

    @GetMapping("/jobs/history")
    public PageDto<JobHistoryDto> jobHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) GenerationProgressStage stage) {
        return service.getJobHistory(page, size, stage);
    }

    @PostMapping("/jobs/{projectId}/{stage}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retryJob(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long projectId,
            @PathVariable GenerationProgressStage stage) {
        service.retryJob(admin, projectId, stage);
    }

    @PostMapping("/break-glass/request")
    @ResponseStatus(HttpStatus.CREATED)
    public BreakGlassGrantDto requestBreakGlass(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @Valid @RequestBody BreakGlassRequestDto request) {
        if (breakGlassAccessService == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Break-Glass service chưa sẵn sàng");
        }
        return breakGlassAccessService.requestAccess(request, admin);
    }

    @PostMapping("/break-glass/{grantId}/approve")
    public BreakGlassGrantDto approveBreakGlass(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser approver,
            @PathVariable Long grantId) {
        if (breakGlassAccessService == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Break-Glass service chưa sẵn sàng");
        }
        return breakGlassAccessService.approveAccess(grantId, approver);
    }

    @PostMapping("/break-glass/{grantId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeBreakGlass(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin,
            @PathVariable Long grantId) {
        if (breakGlassAccessService == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Break-Glass service chưa sẵn sàng");
        }
        breakGlassAccessService.revokeGrant(grantId, admin);
    }

    @GetMapping("/break-glass/active")
    public List<BreakGlassGrantDto> listActiveBreakGlass(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin) {
        if (breakGlassAccessService == null) {
            return List.of();
        }
        return breakGlassAccessService.listActiveGrants(admin);
    }

    @GetMapping("/break-glass/pending")
    public List<BreakGlassGrantDto> listPendingBreakGlass(
            @RequestAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE) AuthUser admin) {
        if (breakGlassAccessService == null) {
            return List.of();
        }
        return breakGlassAccessService.listPendingRequests(admin);
    }

    @PostMapping("/storage/projects/{id}/migrate")
    public StorageMigrationService.MigrationResult migrateProjectStorage(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestAttribute(value = AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE, required = false) AuthUser admin) {
        if (storageMigrationService == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Migration service chưa sẵn sàng");
        }
        if (!dryRun && admin != null && breakGlassAccessService != null && projectRepository != null) {
            Project project = projectRepository.findById(id).orElseThrow(() -> new ProjectNotFoundException(id));
            if (!Objects.equals(admin.getId(), project.getOwnerUserId())) {
                if (!breakGlassAccessService.hasActiveBreakGlass(id, admin.getId())) {
                    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,
                            "Thao tác yêu cầu quyền Break-Glass đối với dự án này");
                }
            }
        }
        StorageMigrationService.MigrationResult result = storageMigrationService.migrateProject(id, dryRun);
        if (!dryRun && admin != null && userActivityService != null) {
            userActivityService.record(admin.getId(), ActivityAction.STORAGE_ENCRYPTION_MIGRATED, id,
                    Map.of("fileCount", result.fileCount(), "totalBytes", result.totalBytes()));
        }
        return result;
    }

    @PostMapping("/storage/projects/{id}/rollback")
    public StorageMigrationService.RollbackResult rollbackProjectStorage(
            @PathVariable Long id,
            @RequestAttribute(value = AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE, required = false) AuthUser admin) {
        if (storageMigrationService == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Migration service chưa sẵn sàng");
        }
        if (admin != null && breakGlassAccessService != null && projectRepository != null) {
            Project project = projectRepository.findById(id).orElseThrow(() -> new ProjectNotFoundException(id));
            if (!Objects.equals(admin.getId(), project.getOwnerUserId())) {
                if (!breakGlassAccessService.hasActiveBreakGlass(id, admin.getId())) {
                    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,
                            "Thao tác yêu cầu quyền Break-Glass đối với dự án này");
                }
            }
        }
        StorageMigrationService.RollbackResult result = storageMigrationService.rollbackProject(id);
        if (admin != null && userActivityService != null) {
            userActivityService.record(admin.getId(), ActivityAction.STORAGE_ENCRYPTION_ROLLED_BACK, id,
                    Map.of("status", result.status(), "message", result.message()));
        }
        return result;
    }
}
