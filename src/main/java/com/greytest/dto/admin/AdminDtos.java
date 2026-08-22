package com.greytest.dto.admin;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.UserRole;

public final class AdminDtos {
    private AdminDtos() {}

    public record PageDto<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}

    public record QuotaDto(int limit, int used, int remaining, LocalDate periodStart, boolean exceeded) {}

    public record UserSummaryDto(
            Long id,
            String email,
            String fullName,
            UserRole role,
            boolean enabled,
            Instant createdAt,
            long totalActivities,
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

    public record UserDetailDto(
            UserSummaryDto user,
            List<ProjectSummaryDto> projects,
            long generatedUnitTests,
            List<ActivityDto> recentActivities) {}

    public record OverviewDto(
            long totalUsers,
            long newUsers7Days,
            long newUsers30Days,
            long totalGenerationRequests,
            long totalLlmCalls,
            long quotaAlerts) {}

    public record TrendPointDto(LocalDateTime bucket, long total) {}

    public record TopUserDto(Long userId, String email, long totalLlmCalls) {}

    public record HealthDto(String application, String database, String llmGateway, String javaParser) {}
}
