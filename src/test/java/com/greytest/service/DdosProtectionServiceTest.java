package com.greytest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.greytest.dto.admin.AdminDtos.WafStatusDto;

class DdosProtectionServiceTest {

    private DdosProtectionService service;

    @BeforeEach
    void setUp() {
        service = new DdosProtectionService();
    }

    @Test
    void allowsNormalTrafficUnderLimit() {
        DdosProtectionService.CheckResult result = service.checkRequest("192.168.1.1", "/api/projects");
        assertFalse(result.isBlocked());
        assertEquals(DdosProtectionService.CheckStatus.ALLOWED, result.status());
    }

    @Test
    void progressPollingHasItsOwnTierAndIsNeverAutoBanned() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "burstPerSecondLimit",
                new java.util.concurrent.atomic.AtomicInteger(10_000));
        String ip = "10.0.0.42";
        String progress = "/api/projects/7/generation-progress/UNIT_TEST";
        assertEquals("polling", service.resolveTierName(progress));

        DdosProtectionService.CheckResult last = null;
        for (int i = 0; i < 500; i++) last = service.checkRequest(ip, progress);

        // Vượt hạn mức polling chỉ trả 429, không khóa cả IP (khóa IP chặn luôn người dùng chung mạng).
        assertEquals(DdosProtectionService.CheckStatus.RATE_LIMITED, last.status());
        assertFalse(service.isIpBlocked(ip));
        assertFalse(service.checkRequest(ip, "/api/projects").isBlocked());
    }

    @Test
    void sandboxAndSourceUpdateGetPollingDoesNotAutoBanIpWhilePostStaysProtected() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "burstPerSecondLimit",
                new java.util.concurrent.atomic.AtomicInteger(10_000));
        String ip = "10.0.0.43";
        assertEquals("polling", service.resolveTierName("GET", "/api/projects/7/coverage/run"));
        assertEquals("polling", service.resolveTierName("GET", "/api/projects/7/source-updates"));
        assertEquals("standard", service.resolveTierName("POST", "/api/projects/7/coverage/run"));

        for (int i = 0; i < 500; i++) {
            service.checkRequest(ip, "GET", "/api/projects/7/coverage/run");
        }
        assertFalse(service.isIpBlocked(ip));
        assertFalse(service.checkRequest(ip, "GET", "/api/projects").isBlocked());
    }

    @Test
    void blocksTrafficWhenRateLimitExceeded() {
        service.setStandardRateLimit(5);
        String ip = "10.0.0.99";

        for (int i = 0; i < 5; i++) {
            assertFalse(service.checkRequest(ip, "/api/projects").isBlocked());
        }

        // 6th request exceeds limit
        DdosProtectionService.CheckResult result = service.checkRequest(ip, "/api/projects");
        assertTrue(result.isBlocked());
        assertEquals(DdosProtectionService.CheckStatus.RATE_LIMITED, result.status());
    }

    @Test
    void supportsManualAndAutoIpBlocking() {
        String ip = "1.2.3.4";
        service.blockIp(ip, "Spamming auth endpoint", 10);

        DdosProtectionService.CheckResult result = service.checkRequest(ip, "/api/auth/login");
        assertTrue(result.isBlocked());
        assertEquals(DdosProtectionService.CheckStatus.IP_BLOCKED, result.status());

        // Admin unblocks IP
        assertTrue(service.unblockIp(ip));
        assertFalse(service.checkRequest(ip, "/api/auth/login").isBlocked());
    }

    @Test
    void togglesUnderAttackModeAndProvidesWafStatus() {
        service.setUnderAttackMode(true);
        WafStatusDto status = service.getWafStatus();
        assertTrue(status.underAttackMode());
        assertEquals(30, status.rateLimitPerMinute());

        service.setUnderAttackMode(false);
        status = service.getWafStatus();
        assertFalse(status.underAttackMode());
        assertEquals(100, status.rateLimitPerMinute());
    }

    @Test
    void handlesNullOrBlankIpGracefully() {
        service.blockIp(null, "Null IP", 10);
        service.blockIp("   ", "Blank IP", 10);
        assertFalse(service.unblockIp(null));
        assertFalse(service.unblockIp("   "));
        assertEquals(0, service.getWafStatus().activeBlockedIpCount());
    }

    @Test
    void autoBlocksWhenExceeding150PercentThreshold() {
        service.setStandardRateLimit(4);
        String ip = "192.168.1.50";
        for (int i = 0; i < 4; i++) {
            assertEquals(DdosProtectionService.CheckStatus.ALLOWED, service.checkRequest(ip, "/api/test").status());
        }
        // 5th and 6th request: Rate limited
        assertEquals(DdosProtectionService.CheckStatus.RATE_LIMITED, service.checkRequest(ip, "/api/test").status());
        assertEquals(DdosProtectionService.CheckStatus.RATE_LIMITED, service.checkRequest(ip, "/api/test").status());

        // 7th request: > 4 * 1.5 (=6) -> Auto blocked
        DdosProtectionService.CheckResult result = service.checkRequest(ip, "/api/test");
        assertEquals(DdosProtectionService.CheckStatus.IP_BLOCKED, result.status());
        assertEquals(600, result.retryAfterSeconds());
    }

    @Test
    void normalizesMultipleIpsPortsIpv6AndRejectsInvalidHeadersForRateLimiting() {
        assertEquals("198.51.100.5", DdosProtectionService.normalizeClientIp("198.51.100.5, 10.0.0.1"));
        assertEquals("198.51.100.5", DdosProtectionService.normalizeClientIp("198.51.100.5:54321"));
        assertEquals("2001:db8::1", DdosProtectionService.normalizeClientIp("[2001:db8::1]:8443"));
        assertEquals("127.0.0.1", DdosProtectionService.normalizeClientIp("unknown"));
        assertEquals("127.0.0.1", DdosProtectionService.normalizeClientIp("malicious' OR 1=1--"));

        // Kiểm tra tính nhất quán khi chặn/mở khóa IP có kèm danh sách proxy hoặc port
        service.setStandardRateLimit(2);
        assertFalse(service.checkRequest("198.51.100.99, 10.0.0.1", "/api/projects").isBlocked());
        assertFalse(service.checkRequest("198.51.100.99:44321", "/api/projects").isBlocked());
        assertTrue(service.checkRequest("198.51.100.99", "/api/projects").isBlocked());
    }

    @Test
    void recognizesLoopbackAndDockerSubnetsAndExemptsFromWafBlocking() {
        assertTrue(DdosProtectionService.isLoopbackOrDockerIp("127.0.0.1"));
        assertTrue(DdosProtectionService.isLoopbackOrDockerIp("::1"));
        assertTrue(DdosProtectionService.isLoopbackOrDockerIp("::ffff:127.0.0.1"));
        assertTrue(DdosProtectionService.isLoopbackOrDockerIp("172.20.0.1"));
        assertTrue(DdosProtectionService.isLoopbackOrDockerIp("172.16.0.5"));
        assertTrue(DdosProtectionService.isLoopbackOrDockerIp("172.31.255.250"));
        assertFalse(DdosProtectionService.isLoopbackOrDockerIp("172.32.0.1"));
        assertFalse(DdosProtectionService.isLoopbackOrDockerIp("203.0.113.1"));

        // Loopback and Docker IPs are never blocked by burst or rate limiting when configured
        service.setExemptDockerSubnets(true);
        service.setStandardRateLimit(1);
        for (int i = 0; i < 20; i++) {
            assertFalse(service.checkRequest("172.20.0.1", "/api/projects").isBlocked());
            assertFalse(service.checkRequest("127.0.0.1", "/api/projects").isBlocked());
        }
    }

    @Test
    void testEmergencySafeModeDoesNotDisableSensitiveTiers() {
        service.setEmergencySafeMode(true);
        assertTrue(service.isEmergencySafeMode());

        // Standard browsing endpoint is relaxed to 500 req/min
        assertEquals(500, service.resolveTierLimit("/api/projects"));

        // Sensitive tiers REMAIN strictly protected at dedicated limits
        assertEquals(20, service.resolveTierLimit("/api/auth/login"));
        assertEquals(10, service.resolveTierLimit("/api/projects/upload"));
        assertEquals(15, service.resolveTierLimit("/api/projects/1/generate"));
        assertEquals(30, service.resolveTierLimit("/api/projects/1/export"));
        assertEquals(60, service.resolveTierLimit("/api/admin/system/metrics"));

        // Blacklist and Burst detection still active
        service.blockIp("198.51.100.88", "Attacker", 10);
        assertTrue(service.checkRequest("198.51.100.88", "/api/projects").isBlocked());
    }

    @Test
    void testEmergencySafeModeAutoExpires() {
        // Safe mode with negative duration -> already expired
        service.setEmergencySafeMode(true, -1);
        assertFalse(service.isEmergencySafeMode());
        assertEquals(100, service.resolveTierLimit("/api/projects"));
    }

    @Test
    void unblockAllClearsBlacklistAndTimestamps() {
        service.blockIp("198.51.100.11", "Manual test", 10);
        service.blockIp("198.51.100.12", "Manual test", 10);
        assertEquals(2, service.getWafStatus().activeBlockedIpCount());

        service.unblockAll();
        assertEquals(0, service.getWafStatus().activeBlockedIpCount());
        assertFalse(service.checkRequest("198.51.100.11", "/api/projects").isBlocked());
    }

    @Test
    void neverBansTheReverseProxyThatAllUsersShare() {
        DdosProtectionService behindProxy = new DdosProtectionService(
                new com.greytest.security.waf.InMemoryRateLimitStore(),
                new com.greytest.security.waf.TrustedProxyResolver("127.0.0.1/32,172.16.0.0/12"));
        for (int i = 0; i < 400; i++) {
            assertFalse(behindProxy.checkRequest("172.18.0.5", "/api/projects").isBlocked());
        }
        // IP proxy không phải lối vòng: vượt hạn mức chung thì bị giới hạn nhịp, nhưng không bị khóa IP.
        DdosProtectionService.CheckResult flooded = null;
        for (int i = 0; i < 250; i++) {
            flooded = behindProxy.checkRequest("172.18.0.5", "/api/auth/login");
        }
        assertEquals(DdosProtectionService.CheckStatus.RATE_LIMITED, flooded.status());
        assertFalse(behindProxy.isIpBlocked("172.18.0.5"));
        assertFalse(behindProxy.checkRequest("172.18.0.5", "/api/projects").isBlocked());
        // Client thật phía sau proxy vẫn bị giới hạn.
        boolean blocked = false;
        for (int i = 0; i < 400; i++) {
            blocked |= behindProxy.checkRequest("203.0.113.9", "/api/projects").isBlocked();
        }
        assertTrue(blocked);
    }
}
