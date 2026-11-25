package com.greytest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.greytest.dto.LoginRequest;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.repository.AuthUserRepository;
import com.greytest.security.waf.InMemoryRateLimitStore;
import com.greytest.security.waf.RedisRateLimitStore;
import com.greytest.security.waf.TrustedProxyResolver;
import com.greytest.service.AuthService;
import com.greytest.service.DdosProtectionService;
import com.greytest.service.DdosProtectionService.CheckResult;
import com.greytest.service.DdosProtectionService.CheckStatus;

/**
 * Bộ kiểm thử an ninh chuyên sâu xác thực toàn bộ 11 Workstreams của Phase 5 (WAF & Rate Limiting Hardening).
 */
@DisplayName("Phase 5 - Enterprise WAF & Advanced Rate Limiting Security Hardening Tests")
class Phase5WafSecurityTest {

    private DdosProtectionService ddosService;
    private TrustedProxyResolver proxyResolver;
    private InMemoryRateLimitStore rateLimitStore;

    @BeforeEach
    void setUp() {
        rateLimitStore = new InMemoryRateLimitStore();
        proxyResolver = new TrustedProxyResolver("127.0.0.1/32,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16");
        ddosService = new DdosProtectionService(rateLimitStore, proxyResolver);
    }

    @Test
    @DisplayName("Workstream A: Chống IP Spoofing - Bỏ qua XFF/X-Real-IP nếu kết nối trực tiếp không thuộc Trusted Proxy")
    void testAntiIpSpoofingWhenUntrustedDirectConnection() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.50"); // Untrusted public IP
        request.addHeader("X-Forwarded-For", "1.1.1.1, 8.8.8.8");
        request.addHeader("X-Real-IP", "9.9.9.9");

        // Resolver phải trả về chính remoteAddr, tuyệt đối không tin header giả mạo
        String resolvedIp = proxyResolver.resolveClientIp(request);
        assertEquals("203.0.113.50", resolvedIp);
    }

    @Test
    @DisplayName("Workstream A: Right-to-Left (RTL) Proxy Chain Parsing khi qua Trusted Proxy")
    void testTrustedProxyRightToLeftIpResolution() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1"); // Trusted Reverse Proxy IP
        // Giả lập chuỗi proxy: Client -> Proxy biên trung gian -> Load Balancer
        request.addHeader("X-Forwarded-For", "203.0.113.99, 10.0.0.2");

        String resolvedIp = proxyResolver.resolveClientIp(request);
        // Dò ngược từ phải sang trái: 10.0.0.2 là trusted -> bỏ qua; 203.0.113.99 là untrusted -> đây là Client IP thật
        assertEquals("203.0.113.99", resolvedIp);
    }

    @Test
    @DisplayName("Workstream A: Chuẩn hóa IPv4-mapped IPv6, IPv6 brackets và loại bỏ Port")
    void testIpv4MappedIpv6NormalizationAndPortStripping() {
        assertEquals("192.0.2.1", TrustedProxyResolver.normalizeIp("::ffff:192.0.2.1"));
        assertEquals("192.0.2.1", TrustedProxyResolver.normalizeIp("::FFFF:192.0.2.1"));
        assertEquals("198.51.100.2", TrustedProxyResolver.normalizeIp("198.51.100.2:8443"));
        assertEquals("2001:db8::1", TrustedProxyResolver.normalizeIp("[2001:db8::1]:443"));
        assertEquals("127.0.0.1", TrustedProxyResolver.normalizeIp("malicious<script>"));
    }

    @Test
    @DisplayName("Workstream B: Độc lập kiểm tra Burst Attack trước khi xét giới hạn phút -> Auto-ban 15 phút")
    void testBurstAttackDetectionBeforeMinuteLimit() {
        ddosService.setStandardRateLimit(100);
        ddosService.setBurstPerSecondLimit(5); // Ngưỡng burst: tối đa 5 req/giây

        String attackerIp = "198.51.100.77";

        // Gửi 5 requests bình thường
        for (int i = 0; i < 5; i++) {
            CheckResult res = ddosService.checkRequest(attackerIp, "/api/projects");
            assertEquals(CheckStatus.ALLOWED, res.status());
        }

        // Request thứ 6 vượt burst limit (5 req/s) -> Bị phát hiện tấn công dồn dập và Auto-ban 15 phút
        CheckResult burstResult = ddosService.checkRequest(attackerIp, "/api/projects");
        assertEquals(CheckStatus.IP_BLOCKED, burstResult.status());
        assertEquals(900, burstResult.retryAfterSeconds());
        assertTrue(burstResult.reason().contains("tần suất đột biến"));
        assertTrue(ddosService.getTotalBurstAttacks() > 0);
    }

    @Test
    @DisplayName("Workstream B: Auto-ban lũy tiến khi vượt 150% ngưỡng Rate Limit theo phút")
    void testProgressiveAutoBanOn150PercentExceeded() {
        ddosService.setStandardRateLimit(4); // Ngưỡng: 4 req/phút, 150% = 6 req/phút
        String clientIp = "198.51.100.88";

        // 4 requests đầu được phép
        for (int i = 0; i < 4; i++) {
            assertEquals(CheckStatus.ALLOWED, ddosService.checkRequest(clientIp, "/api/test").status());
        }

        // Requests thứ 5 và 6 bị RATE_LIMITED (429, 60s)
        assertEquals(CheckStatus.RATE_LIMITED, ddosService.checkRequest(clientIp, "/api/test").status());
        assertEquals(CheckStatus.RATE_LIMITED, ddosService.checkRequest(clientIp, "/api/test").status());

        // Request thứ 7 (> 4 * 1.5) -> Tự động đưa vào Blacklist (Auto-ban 10 phút = 600s)
        CheckResult autoBanResult = ddosService.checkRequest(clientIp, "/api/test");
        assertEquals(CheckStatus.IP_BLOCKED, autoBanResult.status());
        assertEquals(600, autoBanResult.retryAfterSeconds());
        assertTrue(ddosService.getTotalAutoBans() > 0);
    }

    @Test
    @DisplayName("Workstream C: Admin Login Brute-Force Protection - Khóa tạm thời không vô hiệu hóa DB")
    void testAdminLoginBruteForceLockoutWithoutAccountDisabling() {
        AuthUserRepository userRepository = mock(AuthUserRepository.class);
        AuthUser adminUser = new AuthUser();
        adminUser.setId(1L);
        adminUser.setEmail("admin@greytest.dev");
        adminUser.setPasswordHash(new BCryptPasswordEncoder().encode("SecretAdminPassword123!"));
        adminUser.setRole(UserRole.ADMIN);
        adminUser.setEnabled(true);

        when(userRepository.findByEmailIgnoreCase("admin@greytest.dev")).thenReturn(Optional.of(adminUser));

        AuthService authService = new AuthService(userRepository, "12345678901234567890123456789012");

        // 4 lần nhập sai mật khẩu đầu tiên trả về 401 UNAUTHORIZED
        for (int i = 1; i <= 4; i++) {
            final String wrongPass = "WrongPass" + i;
            assertThatThrownBy(() -> authService.adminLogin(new LoginRequest("admin@greytest.dev", wrongPass)))
                    .isInstanceOf(AuthException.class)
                    .matches(e -> ((AuthException) e).getStatus() == HttpStatus.UNAUTHORIZED);
        }

        // Lần thứ 5 sai kích hoạt cơ chế khóa tạm thời Brute-Force (429 TOO_MANY_REQUESTS)
        assertThatThrownBy(() -> authService.adminLogin(new LoginRequest("admin@greytest.dev", "WrongPass5")))
                .isInstanceOf(AuthException.class)
                .matches(e -> ((AuthException) e).getStatus() == HttpStatus.TOO_MANY_REQUESTS
                        && "TOO_MANY_LOGIN_ATTEMPTS".equals(((AuthException) e).getCode()));

        // Tài khoản Admin trong Database vẫn ở trạng thái ENABLED (chống tấn công DoS vô hiệu hóa vĩnh viễn admin)
        assertTrue(adminUser.getEnabled());
    }

    @Test
    @DisplayName("Workstream D: Phân loại hạn mức Endpoint theo Multi-Dimensional Tiers")
    void testMultiDimensionalEndpointRateLimits() {
        assertEquals(20, ddosService.resolveTierLimit("/api/auth/login"));
        assertEquals(20, ddosService.resolveTierLimit("/api/auth/register"));
        assertEquals(10, ddosService.resolveTierLimit("/api/projects/upload"));
        assertEquals(15, ddosService.resolveTierLimit("/api/projects/1/generate-plan"));
        assertEquals(30, ddosService.resolveTierLimit("/api/projects/1/export"));
        assertEquals(60, ddosService.resolveTierLimit("/api/admin/users"));
        assertEquals(100, ddosService.resolveTierLimit("/api/projects"));

        // Khi bật chế độ Under Attack Mode -> Áp dụng hạn mức nghiêm ngặt 30 req/phút cho toàn bộ
        ddosService.setUnderAttackMode(true);
        assertEquals(30, ddosService.resolveTierLimit("/api/projects"));
        assertEquals(30, ddosService.resolveTierLimit("/api/admin/users"));
    }

    @Test
    @DisplayName("Workstream G: Admin WAF Security - Chống tự khóa chính mình (Anti-Self-Lockout)")
    void testAdminAntiSelfLockout() {
        String adminIp = "192.168.1.100";

        // Admin cố gắng khóa chính IP của mình -> Hệ thống từ chối
        assertThatThrownBy(() -> ddosService.blockIp("192.168.1.100", "Testing self ban", 10, adminIp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Anti-self-lockout");

        // Admin khóa IP khác -> Thành công
        ddosService.blockIp("192.168.1.200", "Testing other ban", 10, adminIp);
        assertTrue(ddosService.checkRequest("192.168.1.200", "/api/projects").isBlocked());
    }

    @Test
    @DisplayName("Workstream F: Distributed RateLimitStore - Fallback an toàn giữa Redis và Memory")
    void testDistributedRateLimitStoreFallback() {
        InMemoryRateLimitStore memStore = new InMemoryRateLimitStore();
        RedisRateLimitStore redisStore = new RedisRateLimitStore(memStore);

        assertFalse(redisStore.isRedisAvailable()); // Single node fallback
        assertTrue(redisStore.recordAndCheck("test-key", 60, 5));
        assertEquals(1, redisStore.getRequestCount("test-key", 60));

        redisStore.block("bad-ip", "Spam", 300);
        assertTrue(redisStore.isBlocked("bad-ip"));
        assertEquals("Spam", redisStore.getBlockReason("bad-ip"));

        redisStore.unblock("bad-ip");
        assertFalse(redisStore.isBlocked("bad-ip"));
    }

    @Test
    @DisplayName("Workstream B & E: Giới hạn bộ nhớ Bounded Memory & Eviction chống cạn kiệt tài nguyên")
    void testMemoryBoundingAndEvictionUnderHighCardinalityIps() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();

        // Ghi nhận nhiều IP
        for (int i = 0; i < 50; i++) {
            store.recordAndCheck("ip-" + i, 60, 10);
        }
        assertThat(store.getActiveTrackedKeyCount()).isGreaterThan(0);

        // Evict các key nhàn rỗi
        store.evictIdleKeys(System.currentTimeMillis() + 1000L);
        assertEquals(0, store.getActiveTrackedKeyCount());
    }

    @Test
    @DisplayName("Finding SEC-P5-01: Admin login fail sau reverse proxy không gây DoS tới người dùng khác")
    void testAdminLoginFailureBehindProxyDoesNotBlockOtherUsers() {
        AuthUserRepository userRepository = mock(AuthUserRepository.class);
        AuthUser admin = new AuthUser();
        admin.setId(1L);
        admin.setEmail("admin@greytest.dev");
        admin.setPasswordHash(new BCryptPasswordEncoder().encode("Secret123!"));
        admin.setRole(UserRole.ADMIN);
        admin.setEnabled(true);

        AuthUser user2 = new AuthUser();
        user2.setId(2L);
        user2.setEmail("user2@greytest.dev");
        user2.setPasswordHash(new BCryptPasswordEncoder().encode("User2Secret123!"));
        user2.setRole(UserRole.USER);
        user2.setEnabled(true);

        when(userRepository.findByEmailIgnoreCase("admin@greytest.dev")).thenReturn(Optional.of(admin));
        when(userRepository.findByEmailIgnoreCase("user2@greytest.dev")).thenReturn(Optional.of(user2));

        MockHttpServletRequest request1 = new MockHttpServletRequest();
        request1.setRemoteAddr("10.0.0.1"); // Trusted Reverse Proxy IP
        request1.addHeader("X-Forwarded-For", "203.0.113.10"); // Attacker IP

        AuthService authService = new AuthService(
                userRepository,
                "12345678901234567890123456789012",
                null,
                request1,
                null,
                null,
                proxyResolver
        );

        // Kẻ tấn công thử sai 5 lần từ IP 203.0.113.10
        for (int i = 1; i <= 5; i++) {
            final String wrongPass = "WrongPass" + i;
            try {
                authService.adminLogin(new LoginRequest("admin@greytest.dev", wrongPass));
            } catch (AuthException ignored) {
            }
        }

        // Tài khoản admin từ IP kẻ tấn công bị khóa
        assertThatThrownBy(() -> authService.adminLogin(new LoginRequest("admin@greytest.dev", "Secret123!")))
                .isInstanceOf(AuthException.class)
                .matches(e -> ((AuthException) e).getStatus() == HttpStatus.TOO_MANY_REQUESTS);

        // Người dùng hợp lệ khác đăng nhập qua cùng Reverse Proxy (remoteAddr=10.0.0.1) từ IP khác (203.0.113.20)
        MockHttpServletRequest request2 = new MockHttpServletRequest();
        request2.setRemoteAddr("10.0.0.1"); // Cùng Reverse Proxy IP
        request2.addHeader("X-Forwarded-For", "203.0.113.20"); // Real User IP

        AuthService authServiceUser2 = new AuthService(
                userRepository,
                "12345678901234567890123456789012",
                null,
                request2,
                null,
                null,
                proxyResolver
        );

        // Người dùng hợp lệ KHÔNG bị khóa DoS nhầm do proxy IP!
        var loginResponse = authServiceUser2.login(new LoginRequest("user2@greytest.dev", "User2Secret123!"));
        assertThat(loginResponse).isNotNull();
        assertThat(loginResponse.token()).isNotBlank();
    }

    @Test
    @DisplayName("Finding SEC-P5-02: Anti-self-lockout hoạt động chính xác khi Admin kết nối qua Reverse Proxy")
    void testAdminAntiSelfLockoutBehindReverseProxy() {
        com.greytest.service.AdminService adminService = mock(com.greytest.service.AdminService.class);
        com.greytest.repository.ProjectRepository projectRepo = mock(com.greytest.repository.ProjectRepository.class);
        com.greytest.controller.AdminController controller = new com.greytest.controller.AdminController(
                adminService,
                null,
                null
        );
        controller.setTrustedProxyResolver(proxyResolver);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1"); // Trusted Reverse Proxy IP
        request.addHeader("X-Forwarded-For", "203.0.113.88"); // Real Admin IP

        // Admin cố gắng khóa chính IP thật của mình (203.0.113.88)
        var blockRequest = new com.greytest.dto.admin.AdminDtos.BlockIpRequest("203.0.113.88", "Self ban test", 10);

        assertThatThrownBy(() -> controller.blockIp(null, blockRequest, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Anti-self-lockout");
    }
}
