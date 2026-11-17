package com.greytest.dto.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.UserRole;
import com.greytest.entity.enums.UserTier;

public final class AdminDtos {
    private AdminDtos() {}

    public record PageDto<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}

    public record QuotaDto(
            Integer limit, int used, Integer remaining, LocalDate periodStart, LocalDate resetDate, boolean exceeded) {}

    public record UserSummaryDto(
            Long id,
            String email,
            String fullName,
            UserRole role,
            UserTier tier,
            boolean enabled,
            Instant createdAt,
            long totalGenerationRequests,
            Instant lastActivityAt,
            QuotaDto quota) {}

    public record ProjectSummaryDto(Long id, String name, ProjectStatus status, Instant createdAt) {}

    public record ActivityDto(
            Long id,
            Long userId,
            String userEmail,
            ActivityAction actionType,
            Long projectId,
            Instant createdAt,
            Map<String, Object> metadata) {}

    public record LoginHistoryDto(
            Long id,
            Instant timestamp,
            String ip,
            String userAgent,
            String portal,
            String status,
            String device) {}

    public record UserDetailDto(
            UserSummaryDto user,
            List<ProjectSummaryDto> projects,
            long generatedUnitTests,
            List<ActivityDto> recentActivities,
            Instant lastLoginAt,
            List<LoginHistoryDto> loginHistory) {

        public UserDetailDto(
                UserSummaryDto user,
                List<ProjectSummaryDto> projects,
                long generatedUnitTests,
                List<ActivityDto> recentActivities) {
            this(user, projects, generatedUnitTests, recentActivities, null, List.of());
        }
    }

    public record OverviewDto(
            long totalUsers,
            long newUsers7Days,
            long newUsers30Days,
            long totalGenerationRequests,
            long totalLlmCalls,
            long quotaAlerts,
            long totalProjects,
            long totalBusinessRules,
            long totalTestPlans,
            long totalTestCases,
            long totalUnitTests,
            Double avgLineCoverage,
            Double avgBranchCoverage,
            long totalCoverageReports,
            long generationRequests7Days,
            long llmCalls7Days) {
        public OverviewDto(
                long totalUsers,
                long newUsers7Days,
                long newUsers30Days,
                long totalGenerationRequests,
                long totalLlmCalls,
                long quotaAlerts) {
            this(totalUsers, newUsers7Days, newUsers30Days, totalGenerationRequests, totalLlmCalls, quotaAlerts,
                    0L, 0L, 0L, 0L, 0L, null, null, 0L, 0L, 0L);
        }

        public OverviewDto(
                long totalUsers,
                long newUsers7Days,
                long newUsers30Days,
                long totalGenerationRequests,
                long totalLlmCalls,
                long quotaAlerts,
                long totalProjects,
                long totalBusinessRules,
                long totalTestPlans,
                long totalTestCases,
                long totalUnitTests,
                Double avgLineCoverage,
                Double avgBranchCoverage,
                long totalCoverageReports) {
            this(totalUsers, newUsers7Days, newUsers30Days, totalGenerationRequests, totalLlmCalls, quotaAlerts,
                    totalProjects, totalBusinessRules, totalTestPlans, totalTestCases, totalUnitTests,
                    avgLineCoverage, avgBranchCoverage, totalCoverageReports, 0L, 0L);
        }
    }

    public record TrendPointDto(LocalDateTime bucket, long total) {}

    public record TopUserDto(Long userId, String email, long totalLlmCalls) {}

    public record ActiveJobDto(
            Long projectId,
            String projectName,
            String userEmail,
            com.greytest.dto.GenerationProgressStage stage,
            com.greytest.dto.GenerationProgressStatus status,
            int percent,
            int completedSteps,
            int totalSteps,
            String message,
            Instant updatedAt) {}

    public record JobHistoryDto(
            Long id,
            Long projectId,
            String projectName,
            Long userId,
            String userEmail,
            ActivityAction action,
            String status,
            Instant createdAt,
            Map<String, Object> metadata) {}

    public record HealthDto(
            String application,
            String database,
            String llmGateway,
            String javaParser,
            long jvmHeapUsedMb,
            long jvmHeapMaxMb,
            long jvmNonHeapUsedMb,
            int activeConnections,
            int idleConnections,
            int maxConnections,
            int threadCount,
            long uptimeSeconds) {

        public HealthDto(String application, String database, String llmGateway, String javaParser) {
            this(application, database, llmGateway, javaParser, 0L, 0L, 0L, 0, 0, 0, 0, 0L);
        }
    }

    public record ApiKeySlotDto(
            int slot,
            String envVar,
            String role,
            String maskedKey,
            String rawKey,
            boolean configured) {}

    public record AiRuntimeConfigDto(
            String provider,
            String model,
            String endpoint,
            int batchConcurrency,
            List<ApiKeySlotDto> keys,
            Map<String, Integer> stageLimits) {}

    public record UpdateApiKeySlotRequest(
            int slot,
            String key) {}

    public record UpdateAiKeysRequest(
            List<UpdateApiKeySlotRequest> slots) {}

    public record UpdateAiModelRequest(
            String model,
            String provider) {}

    public record UpdateStageLimitsRequest(
            Map<String, Integer> stageLimits) {}

    public record TierPackageDto(
            String code,
            String name,
            long price,
            String cycle,
            int quotaLimit,
            int batchConcurrency,
            List<String> features,
            String description,
            boolean active,
            long activeUsersCount) {}

    public record SaveTierPackageRequest(
            String code,
            String name,
            Long price,
            String cycle,
            Integer quotaLimit,
            Integer batchConcurrency,
            List<String> features,
            String description,
            Boolean active) {}

    public record UserFeedbackDto(
            Long id,
            Long userId,
            String userEmail,
            String userName,
            String category,
            String title,
            String content,
            String status,
            String adminNote,
            LocalDateTime createdAt,
            LocalDateTime resolvedAt,
            String projectName) {

        public UserFeedbackDto(
                Long id, Long userId, String userEmail, String userName, String category,
                String title, String content, Integer rating, String status, String adminNote,
                LocalDateTime createdAt, LocalDateTime resolvedAt, String projectName) {
            this(id, userId, userEmail, userName, category, title, content, status, adminNote, createdAt, resolvedAt, projectName);
        }
    }

    public record SaveFeedbackRequest(
            String category,
            String title,
            String content,
            String projectName) {

        public SaveFeedbackRequest(String category, String title, String content, Integer rating, String projectName) {
            this(category, title, content, projectName);
        }
    }

    public record UpdateFeedbackStatusRequest(
            String status,
            String adminNote) {}

    public record FeedbackOverviewDto(
            long total,
            long pending,
            long resolved) {

        public FeedbackOverviewDto(long total, long pending, long resolved, double averageRating) {
            this(total, pending, resolved);
        }
    }

    public record SystemSettingDto(
            String key,
            String value,
            String description,
            Instant updatedAt) {}

    public record SystemSettingsDto(
            Map<String, String> settings,
            List<SystemSettingDto> details) {}

    public record UpdateSystemSettingsRequest(
            Map<String, String> settings) {}

    public record ServerRequestLogDto(
            String id,
            Instant timestamp,
            String method,
            String uri,
            String routeTemplate,
            int status,
            long durationMs,
            String clientIp,
            String userEmail,
            String requestType,
            String userAgent) {

        public ServerRequestLogDto(
                String id,
                Instant timestamp,
                String method,
                String uri,
                int status,
                long durationMs,
                String clientIp,
                String userEmail) {
            this(id, timestamp, method, uri, uri, status, durationMs, clientIp, userEmail, "USER_API", null);
        }
    }

    public record ServerTelemetryDto(
            long totalRequests,
            int requestsPerMinute,
            double requestsPerSecond,
            int activeRequests,
            int rateLimitedCount,
            List<ServerRequestLogDto> logs) {}

    public record BlockedIpDto(
            String ip,
            String reason,
            Instant blockedAt,
            Instant expiresAt,
            int violationCount) {}

    public record WafStatusDto(
            boolean active,
            boolean underAttackMode,
            int rateLimitPerMinute,
            int standardRateLimitPerMinute,
            int underAttackRateLimitPerMinute,
            int burstLimitPerSecond,
            long totalBlockedRequests,
            int activeBlockedIpCount,
            List<BlockedIpDto> blockedIps) {

        public WafStatusDto(
                boolean active,
                boolean underAttackMode,
                int rateLimitPerMinute,
                int burstLimitPerSecond,
                long totalBlockedRequests,
                int activeBlockedIpCount,
                List<BlockedIpDto> blockedIps) {
            this(active, underAttackMode, rateLimitPerMinute, rateLimitPerMinute, 30,
                    burstLimitPerSecond, totalBlockedRequests, activeBlockedIpCount, blockedIps);
        }
    }

    public record BlockIpRequest(
            String ip,
            String reason,
            int durationMinutes) {}

    public record ToggleUnderAttackRequest(
            boolean enabled) {}

    public record UpdateRateLimitRequest(
            int rateLimitPerMinute) {}

    public record RateLimitConfigRequest(
            Boolean active,
            Boolean underAttackMode,
            Integer rateLimitPerMinute,
            Integer underAttackRateLimitPerMinute,
            Integer burstLimitPerSecond) {}

    public record TrafficOverviewDto(
            String window,
            long windowSeconds,
            Instant generatedAt,
            long lifetimeTotalRequests,
            long windowTotalRequests,
            double currentRps,
            double averageRps,
            double peakRps,
            Double avgLatencyMs,
            Double p50LatencyMs,
            Double p95LatencyMs,
            Double p99LatencyMs,
            Long maxLatencyMs,
            Double errorRatePercent,
            Double clientErrorRatePercent,
            Double serverErrorRatePercent,
            long successCount,
            long clientErrorCount,
            long serverErrorCount,
            int activeConnections,
            long rejectedRequests,
            long windowRejectedRequests,
            long previousWindowRequests,
            Double requestsChangePercent,
            Double previousAverageRps,
            Double rpsChangePercent,
            Double previousAvgLatencyMs,
            Double latencyChangePercent,
            String dataState) {}

    public record TrafficTimeseriesPointDto(
            Instant timestamp,
            double rps,
            long totalRequests,
            long success2xx,
            long clientError4xx,
            long serverError5xx,
            long rateLimited429,
            Double avgLatencyMs,
            Double p95LatencyMs,
            boolean spike) {}

    public record TrafficTimeseriesDto(
            String window,
            int bucketSeconds,
            double warningRpsThreshold,
            double criticalRpsThreshold,
            List<TrafficTimeseriesPointDto> points) {}

    public record EndpointMetricDto(
            String method,
            String endpoint,
            long requestCount,
            double avgLatencyMs,
            double p95LatencyMs,
            long maxLatencyMs,
            long errorCount,
            long clientError4xx,
            long serverError5xx,
            double errorRatePercent) {}

    public record EndpointAnalyticsDto(
            String window,
            List<EndpointMetricDto> topByVolume,
            List<EndpointMetricDto> topByLatency,
            List<EndpointMetricDto> topByErrorRate,
            Map<String, Long> methodDistribution,
            Map<String, Long> statusDistribution) {}

    public record TrafficAlertDto(
            String id,
            String code,
            String severity,
            String title,
            String message,
            String metricValue,
            String thresholdValue,
            Instant triggeredAt) {}

    public record TrafficStreamSnapshotDto(
            Instant timestamp,
            TrafficOverviewDto overview,
            TrafficTimeseriesPointDto latestPoint,
            List<TrafficAlertDto> alerts,
            WafStatusDto rateLimit,
            List<ServerRequestLogDto> recentRequests) {}
}
