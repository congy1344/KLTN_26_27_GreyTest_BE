package com.greytest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.greytest.security.waf.InMemoryRateLimitStore;
import com.greytest.service.DdosProtectionService;
import com.greytest.service.DdosProtectionService.CheckResult;
import com.greytest.service.DdosProtectionService.CheckStatus;
import com.greytest.service.ServerTelemetryService;

/**
 * Kiểm thử tích hợp chuyên sâu cho Workstream 4: Resource Exhaustion Protection & Emergency Safe Mode.
 * Xác minh:
 * 1. HTTP active request tracking và giới hạn concurrency tại tầng telemetry.
 * 2. Bảo vệ endpoint upload (/api/projects/upload) với Upload Tier riêng (10 req/phút).
 * 3. Bảo vệ endpoint sinh mã (/api/generation/**, /generate*) với Gen Tier riêng (15 req/phút).
 * 4. Emergency Safe Mode: nới lỏng hạn mức chung nhưng giữ chặt Blacklist và Burst detection.
 * 5. Khôi phục sau khi giải phóng tài nguyên.
 */
@DisplayName("Phase 5 - Workstream 4 & 6: Resource Exhaustion & Emergency Safe Mode Integration Tests")
class Phase5ResourceExhaustionIntegrationTest {

    @Test
    @DisplayName("1. ServerTelemetryService theo dõi và cô lập số lượng request đồng thời (Active Requests)")
    void testActiveRequestTrackingAndIsolation() {
        ServerTelemetryService telemetry = new ServerTelemetryService();
        try {
            assertEquals(0, telemetry.getActiveRequests());

            telemetry.incrementActive();
            telemetry.incrementActive();
            assertEquals(2, telemetry.getActiveRequests());

            telemetry.decrementActive();
            assertEquals(1, telemetry.getActiveRequests());

            telemetry.decrementActive();
            assertEquals(0, telemetry.getActiveRequests());
        } finally {
            telemetry.destroy();
        }
    }

    @Test
    @DisplayName("2. Upload Tier Protection: Giới hạn nghiêm ngặt 10 req/phút cho /api/projects/upload")
    void testUploadEndpointRateLimitingProtection() {
        DdosProtectionService ddos = new DdosProtectionService();
        String uploaderIp = "198.51.100.33";

        // Upload 10 lần liên tiếp thành công
        for (int i = 0; i < 10; i++) {
            CheckResult res = ddos.checkRequest(uploaderIp, "/api/projects/upload");
            assertEquals(CheckStatus.ALLOWED, res.status());
        }

        // Lần thứ 11 bị chặn Rate Limit (429) để bảo vệ tài nguyên ổ đĩa và RAM
        CheckResult blockedRes = ddos.checkRequest(uploaderIp, "/api/projects/upload");
        assertEquals(CheckStatus.RATE_LIMITED, blockedRes.status());
        assertTrue(blockedRes.reason().contains("10 req/phút"));
    }

    @Test
    @DisplayName("3. Generation Tier Protection: Giới hạn 15 req/phút cho các API sinh mã AI")
    void testGenerationEndpointRateLimitingProtection() {
        DdosProtectionService ddos = new DdosProtectionService();
        String genUserIp = "198.51.100.44";

        // 15 requests sinh mã được phép
        for (int i = 0; i < 15; i++) {
            assertEquals(CheckStatus.ALLOWED, ddos.checkRequest(genUserIp, "/api/projects/1/generate-plan").status());
        }

        // Request thứ 16 bị chặn để bảo vệ quota LLM token và worker slot
        assertEquals(CheckStatus.RATE_LIMITED, ddos.checkRequest(genUserIp, "/api/projects/1/generate-plan").status());
    }

    @Test
    @DisplayName("4. Emergency Safe Mode: Giữ chặt Blacklist và Burst detection, nới lỏng trần chung")
    void testEmergencySafeModePreservesCoreProtections() {
        DdosProtectionService ddos = new DdosProtectionService();
        ddos.setEmergencySafeMode(true);
        assertTrue(ddos.isEmergencySafeMode());

        // 1. Blacklist VẪN ĐƯỢC THI HÀNH trong Safe Mode
        String bannedIp = "203.0.113.15";
        ddos.blockIp(bannedIp, "Malicious actor", 10);
        assertTrue(ddos.checkRequest(bannedIp, "/api/projects").isBlocked());

        // 2. Burst Attack (> 200 req/s) VẪN BỊ BẮT trong Safe Mode
        ddos.setBurstPerSecondLimit(5);
        String burstIp = "198.51.100.99";
        for (int i = 0; i < 5; i++) {
            assertEquals(CheckStatus.ALLOWED, ddos.checkRequest(burstIp, "/api/projects").status());
        }
        CheckResult burstResult = ddos.checkRequest(burstIp, "/api/projects");
        assertEquals(CheckStatus.IP_BLOCKED, burstResult.status()); // Bị bắt burst ngay!

        // 3. User bình thường gửi lượng request lớn được nới lỏng hạn mức an toàn (500 req/m)
        ddos.setBurstPerSecondLimit(200); // Reset burst limit về chuẩn để test hạn mức nới lỏng theo phút
        String normalIp = "198.51.100.55";
        for (int i = 0; i < 50; i++) {
            assertEquals(CheckStatus.ALLOWED, ddos.checkRequest(normalIp, "/api/projects").status());
        }
    }

    @Test
    @DisplayName("5. HTTP Endpoint: Bật/tắt Emergency Safe Mode qua AdminController và MockMvc")
    void testEmergencySafeModeHttpEndpoint() throws Exception {
        DdosProtectionService ddos = new DdosProtectionService();
        com.greytest.service.AdminService adminService = org.mockito.Mockito.mock(com.greytest.service.AdminService.class);
        org.mockito.Mockito.when(adminService.getWafStatus()).thenAnswer(inv -> ddos.getWafStatus());
        org.mockito.Mockito.doAnswer(inv -> {
            ddos.setEmergencySafeMode(inv.getArgument(1));
            return null;
        }).when(adminService).setEmergencySafeMode(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());

        com.greytest.controller.AdminController controller = new com.greytest.controller.AdminController(adminService);
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .build();

        // 1. Kích hoạt Emergency Safe Mode qua API
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/system/security/emergency-safe-mode")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.emergencySafeMode").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.rateLimitPerMinute").value(500));

        assertTrue(ddos.isEmergencySafeMode());

        // 2. Tắt Emergency Safe Mode quay về hạn mức bình thường
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/system/security/emergency-safe-mode")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.emergencySafeMode").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.rateLimitPerMinute").value(100));

        assertFalse(ddos.isEmergencySafeMode());
    }
}
