package com.greytest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.greytest.config.AdminAuthorizationInterceptor;
import com.greytest.dto.admin.AdminDtos;
import com.greytest.dto.admin.AdminDtos.OverviewDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.GlobalExceptionHandler;
import com.greytest.service.AdminService;
import com.greytest.service.AuthService;

class AdminControllerTest {

    private AdminService adminService;
    private AuthService authService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        adminService = mock(AdminService.class);
        authService = mock(AuthService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminController(adminService))
                .addInterceptors(new AdminAuthorizationInterceptor(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void rejectsNonAdminOnEveryAdminRoute() throws Exception {
        when(authService.currentUser("Bearer user-token")).thenReturn(user(UserRole.USER));

        mvc.perform(get("/api/admin/stats/overview").header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
    }

    @Test
    void returnsOverviewForAdmin() throws Exception {
        when(authService.currentUser("Bearer admin-token")).thenReturn(user(UserRole.ADMIN));
        when(adminService.overview()).thenReturn(new OverviewDto(10, 2, 5, 30, 45, 1));

        mvc.perform(get("/api/admin/stats/overview").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(10))
                .andExpect(jsonPath("$.totalLlmCalls").value(45))
                .andExpect(jsonPath("$.quotaAlerts").value(1));
    }

    @Test
    void returnsTrendForAdminWithDateRange() throws Exception {
        when(authService.currentUser("Bearer admin-token")).thenReturn(user(UserRole.ADMIN));
        when(adminService.trend(any(LocalDateTime.class), any(LocalDateTime.class), eq(30), eq("day"), isNull()))
                .thenReturn(List.of(new AdminDtos.TrendPointDto(LocalDateTime.of(2026, 11, 1, 0, 0), 12L)));

        mvc.perform(get("/api/admin/stats/trend")
                        .header("Authorization", "Bearer admin-token")
                        .param("from", "2026-11-01T00:00:00")
                        .param("to", "2026-11-08T23:59:59")
                        .param("granularity", "day"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].total").value(12));
    }

    private AuthUser user(UserRole role) {
        AuthUser user = new AuthUser();
        user.setId(1L);
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }

    @Test
    void forwardsLockReasonToService() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        mvc.perform(patch("/api/admin/users/2/status").header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"reason\":\"Test account\"}"))
                .andExpect(status().isOk());

        verify(adminService).updateStatus(admin, 2L, false, "Test account");
    }

    @Test
    void rejectsLockReasonLongerThan500Characters() throws Exception {
        when(authService.currentUser("Bearer admin-token")).thenReturn(user(UserRole.ADMIN));

        mvc.perform(patch("/api/admin/users/2/status").header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminService);
    }

    @Test
    void forwardsUnlimitedQuotaAsNull() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        mvc.perform(patch("/api/admin/users/2/quota").header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quotaLimit\":null}"))
                .andExpect(status().isOk());

        verify(adminService).updateQuota(admin, 2L, null);
    }

    @Test
    void forwardsTierUpdateToService() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        mvc.perform(patch("/api/admin/users/2/tier").header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tier\":\"PRO\"}"))
                .andExpect(status().isOk());

        verify(adminService).updateTier(admin, 2L, com.greytest.entity.enums.UserTier.PRO);
    }

    @Test
    void returnsActiveJobsForAdmin() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);
        when(adminService.activeJobs(com.greytest.dto.GenerationProgressStage.UNIT_TEST))
                .thenReturn(java.util.List.of(new com.greytest.dto.admin.AdminDtos.ActiveJobDto(
                        10L, "Sample App", "admin@greytest.dev",
                        com.greytest.dto.GenerationProgressStage.UNIT_TEST,
                        com.greytest.dto.GenerationProgressStatus.RUNNING,
                        75, 3, 4, "Batch 3/4", java.time.Instant.now())));

        mvc.perform(get("/api/admin/jobs/active?stage=UNIT_TEST").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectId").value(10))
                .andExpect(jsonPath("$[0].projectName").value("Sample App"))
                .andExpect(jsonPath("$[0].percent").value(75));
    }

    @Test
    void createsUserForAdmin() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        mvc.perform(post("/api/admin/users").header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"john@greytest.dev\",\"password\":\"password123\",\"fullName\":\"John Doe\",\"role\":\"USER\",\"tier\":\"FREE\"}"))
                .andExpect(status().isCreated());

        verify(adminService).createUser(org.mockito.ArgumentMatchers.eq(admin), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updatesUserForAdmin() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        mvc.perform(put("/api/admin/users/5").header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Updated Name\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        verify(adminService).updateUser(org.mockito.ArgumentMatchers.eq(admin), org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deletesUserForAdmin() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        mvc.perform(delete("/api/admin/users/5").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());

        verify(adminService).deleteUser(admin, 5L);
    }

    @Test
    void resetsQuotaForAdmin() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        mvc.perform(post("/api/admin/quotas/5/reset").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());

        verify(adminService).resetQuota(admin, 5L);
    }

    @Test
    void cancelsActiveJobForAdmin() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        mvc.perform(delete("/api/admin/jobs/active/10/UNIT_TEST").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());

        verify(adminService).cancelActiveJob(admin, 10L, com.greytest.dto.GenerationProgressStage.UNIT_TEST);
    }

    @Test
    void getsAiConfigForAdmin() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);
        when(adminService.getAiRuntimeConfig()).thenReturn(new AdminDtos.AiRuntimeConfigDto(
                "openai", "gpt-6-luna", "https://api.openai.com/v1/chat/completions", 4, List.of(), Map.of()));

        mvc.perform(get("/api/admin/ai-config").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("gpt-6-luna"));
    }

    @Test
    void updatesAiKeysForAdmin() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);
        when(adminService.updateAiKeys(eq(admin), any())).thenReturn(new AdminDtos.AiRuntimeConfigDto(
                "openai", "gpt-6-luna", "https://api.openai.com/v1/chat/completions", 4, List.of(), Map.of()));

        mvc.perform(put("/api/admin/ai-config/keys")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slots\":[{\"slot\":0,\"key\":\"sk-new-key\"}]}"))
                .andExpect(status().isOk());

        verify(adminService).updateAiKeys(eq(admin), any());
    }

    @Test
    void updatesStageLimitsForAdmin() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);
        when(adminService.updateStageLimits(eq(admin), any())).thenReturn(new AdminDtos.AiRuntimeConfigDto(
                "openai", "gpt-6-luna", "https://api.openai.com/v1/chat/completions", 4, List.of(), Map.of("unit-test", 12288)));

        mvc.perform(put("/api/admin/ai-config/limits")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stageLimits\":{\"unit-test\":12288}}"))
                .andExpect(status().isOk());

        verify(adminService).updateStageLimits(eq(admin), any());
    }

    @Test
    void managesTierPackagesForAdmin() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);
        var pkg = new AdminDtos.TierPackageDto("PRO", "Gói PRO", 20000L, "MONTHLY", 300, 4, List.of("AST"), "Mô tả", true, 5L);
        when(adminService.getTierPackages()).thenReturn(List.of(pkg));
        when(adminService.createTierPackage(eq(admin), any())).thenReturn(pkg);
        when(adminService.updateTierPackage(eq(admin), eq("PRO"), any())).thenReturn(pkg);

        mvc.perform(get("/api/admin/tier-packages").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("PRO"));

        mvc.perform(post("/api/admin/tier-packages")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CUSTOM\",\"name\":\"Custom\",\"price\":50000}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/admin/tier-packages/PRO")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pro Updated\",\"price\":25000}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/admin/tier-packages/CUSTOM").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());

        verify(adminService).createTierPackage(eq(admin), any());
        verify(adminService).updateTierPackage(eq(admin), eq("PRO"), any());
        verify(adminService).deleteTierPackage(eq(admin), eq("CUSTOM"));
    }

    @Test
    void managesFeedbacksForAdmin() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);
        var fb = new AdminDtos.UserFeedbackDto(
                1L, 1L, "user@test.dev", "User", "BUG_GENERATION", "Tiêu đề", "Nội dung",
                5, "NEW", "", LocalDateTime.now(), null, "Project #1");
        var stats = new AdminDtos.FeedbackOverviewDto(10L, 3L, 7L, 4.8);

        when(adminService.getFeedbacks(any(), any(), any())).thenReturn(List.of(fb));
        when(adminService.getFeedbackOverview()).thenReturn(stats);
        when(adminService.createFeedback(eq(admin), any())).thenReturn(fb);
        when(adminService.updateFeedbackStatus(eq(admin), eq(1L), any())).thenReturn(fb);

        mvc.perform(get("/api/admin/feedbacks").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));

        mvc.perform(get("/api/admin/feedbacks/stats").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10));

        mvc.perform(post("/api/admin/feedbacks")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Feedback mới\",\"content\":\"Chi tiết\"}"))
                .andExpect(status().isOk());

        mvc.perform(patch("/api/admin/feedbacks/1/status")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"adminNote\":\"Đã xử lý\"}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/admin/feedbacks/1").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());

        verify(adminService).createFeedback(eq(admin), any());
        verify(adminService).updateFeedbackStatus(eq(admin), eq(1L), any());
        verify(adminService).deleteFeedback(eq(admin), eq(1L));
    }

    @Test
    void updatesAiModelSuccessfully() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);
        var expectedConfig = new AdminDtos.AiRuntimeConfigDto("openai", "gpt-4o",
                "https://api.openai.com/v1/chat/completions", 4, List.of(), Map.of("business-rule", 4096));
        when(adminService.updateAiModel(eq(admin), any())).thenReturn(expectedConfig);

        mvc.perform(put("/api/admin/ai-config/model")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"provider\":\"openai\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("gpt-4o"))
                .andExpect(jsonPath("$.provider").value("openai"));

        verify(adminService).updateAiModel(eq(admin), any());
    }

    @Test
    void managesSystemSettingsForAdmin() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        var settingsDto = new AdminDtos.SystemSettingsDto(
                Map.of("ai.model", "gpt-4o", "ai.batch_concurrency", "4"),
                List.of(new AdminDtos.SystemSettingDto("ai.model", "gpt-4o", "AI Model", java.time.Instant.now())));
        when(adminService.getSystemSettings()).thenReturn(settingsDto);
        when(adminService.updateSystemSettings(eq(admin), any())).thenReturn(settingsDto);

        mvc.perform(get("/api/admin/settings").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings['ai.model']").value("gpt-4o"));

        mvc.perform(put("/api/admin/settings")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"ai.model\":\"gpt-4o\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings['ai.model']").value("gpt-4o"));

        verify(adminService).getSystemSettings();
        verify(adminService).updateSystemSettings(eq(admin), any());
    }

    @Test
    void managesJobsHistoryAndRetry() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        var historyPage = new AdminDtos.PageDto<>(
                List.of(new AdminDtos.JobHistoryDto(1L, 10L, "DemoProject", 2L, "dev@test.com",
                        com.greytest.entity.enums.ActivityAction.GENERATE_TEST_PLAN, "COMPLETED",
                        java.time.Instant.now(), Map.of())),
                0, 20, 1L, 1);
        when(adminService.getJobHistory(0, 20, null)).thenReturn(historyPage);

        mvc.perform(get("/api/admin/jobs/history").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].projectName").value("DemoProject"));

        mvc.perform(post("/api/admin/jobs/10/TEST_PLAN/retry")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());

        verify(adminService).getJobHistory(0, 20, null);
        verify(adminService).retryJob(eq(admin), eq(10L), eq(com.greytest.dto.GenerationProgressStage.TEST_PLAN));
    }

    @Test
    void managesWafAndTelemetryEndpoints() throws Exception {
        AuthUser admin = user(UserRole.ADMIN);
        when(authService.currentUser("Bearer admin-token")).thenReturn(admin);

        var telemetryDto = new AdminDtos.ServerTelemetryDto(
                50L, 20, 0.33, 2, 0, List.of()
        );
        when(adminService.getTelemetry(eq(50), isNull(), isNull())).thenReturn(telemetryDto);

        mvc.perform(get("/api/admin/system/request-logs")
                        .header("Authorization", "Bearer admin-token")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(50))
                .andExpect(jsonPath("$.requestsPerMinute").value(20));

        var wafStatusDto = new AdminDtos.WafStatusDto(
                true, false, 100, 15, 0, 0, List.of()
        );
        when(adminService.getWafStatus()).thenReturn(wafStatusDto);

        mvc.perform(get("/api/admin/system/security/waf-status")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.rateLimitPerMinute").value(100));

        var underAttackDto = new AdminDtos.WafStatusDto(
                true, true, 30, 15, 0, 0, List.of()
        );
        when(adminService.getWafStatus()).thenReturn(underAttackDto);

        mvc.perform(post("/api/admin/system/security/under-attack-mode")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underAttackMode").value(true))
                .andExpect(jsonPath("$.rateLimitPerMinute").value(30));

        verify(adminService).setUnderAttackMode(true);

        mvc.perform(post("/api/admin/system/security/block-ip")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ip\":\"192.168.1.100\",\"reason\":\"DDoS script\",\"durationMinutes\":15}"))
                .andExpect(status().isOk());

        verify(adminService).blockIp("192.168.1.100", "DDoS script", 15);

        mvc.perform(delete("/api/admin/system/security/block-ip/192.168.1.100")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());

        verify(adminService).unblockIp("192.168.1.100");

        mvc.perform(put("/api/admin/system/security/rate-limit")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rateLimitPerMinute\":60}"))
                .andExpect(status().isOk());

        verify(adminService).setRateLimit(60);

        // Test new /api/admin/monitoring/** endpoints
        var overviewDto = new AdminDtos.TrafficOverviewDto(
                "1m", 60L, java.time.Instant.now(), 120L, 45L, 2.5, 0.75, 8.0,
                32.5, 25.0, 88.0, 140.0, 210L, 4.44, 4.44, 0.0,
                43L, 2L, 0L, 2, 3L, 1L, 30L, 50.0, 0.5, 50.0, 30.0, 8.33, "AVAILABLE"
        );
        when(adminService.getTrafficOverview("1m")).thenReturn(overviewDto);

        mvc.perform(get("/api/admin/monitoring/traffic/overview")
                        .header("Authorization", "Bearer admin-token")
                        .param("window", "1m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window").value("1m"))
                .andExpect(jsonPath("$.currentRps").value(2.5))
                .andExpect(jsonPath("$.p95LatencyMs").value(88.0))
                .andExpect(jsonPath("$.dataState").value("AVAILABLE"));

        var timeseriesDto = new AdminDtos.TrafficTimeseriesDto("5m", 5, 10.0, 25.0, List.of());
        when(adminService.getTrafficTimeseries("5m")).thenReturn(timeseriesDto);

        mvc.perform(get("/api/admin/monitoring/traffic/timeseries")
                        .header("Authorization", "Bearer admin-token")
                        .param("window", "5m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window").value("5m"))
                .andExpect(jsonPath("$.bucketSeconds").value(5));

        when(adminService.updateRateLimitConfig(any(), any())).thenReturn(underAttackDto);
        mvc.perform(put("/api/admin/monitoring/rate-limit")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true,\"underAttackMode\":true,\"rateLimitPerMinute\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underAttackMode").value(true));
    }

    @Test
    void rejectsInvalidEnumQueryParameterWithBadRequest() throws Exception {
        when(authService.currentUser("Bearer admin-token")).thenReturn(user(UserRole.ADMIN));

        mvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer admin-token")
                        .param("tier", "INVALID_TIER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(adminService);
    }
}
