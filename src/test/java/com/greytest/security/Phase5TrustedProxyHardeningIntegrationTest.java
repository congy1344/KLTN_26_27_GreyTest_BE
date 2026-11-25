package com.greytest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.greytest.security.waf.TrustedProxyResolver;

/**
 * Kiểm thử tích hợp chuyên sâu cho Workstream 1: Trusted Proxy Hardening.
 * Xác minh:
 * 1. Mặc định chỉ có Loopback (127.0.0.1/32, ::1/128) là Trusted Proxy.
 * 2. Không mặc định tin cậy toàn bộ dải mạng private (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16).
 * 3. Kết nối trực tiếp untrusted không thể giả mạo IP bằng X-Forwarded-For hoặc X-Real-IP.
 * 4. Production profile (allowTestHeader = false) tuyệt đối từ chối X-Test-Client-IP.
 * 5. Test profile (allowTestHeader = true) chỉ chấp nhận X-Test-Client-IP khi kết nối từ Trusted Proxy.
 */
@DisplayName("Phase 5 - Workstream 1: Trusted Proxy Hardening Integration Tests")
class Phase5TrustedProxyHardeningIntegrationTest {

    @Test
    @DisplayName("1. Mặc định chỉ tin cậy Loopback; không tin cậy private IP ngoài danh mục")
    void testDefaultTrustedProxiesStrictLoopbackOnly() {
        TrustedProxyResolver resolver = new TrustedProxyResolver();

        // Loopback được tin cậy
        assertTrue(resolver.isTrustedProxy("127.0.0.1"));
        assertTrue(resolver.isTrustedProxy("::1"));

        // Các dải mạng private KHÔNG được tin cậy mặc định (chống container nội bộ lạm dụng)
        assertFalse(resolver.isTrustedProxy("10.0.0.1"));
        assertFalse(resolver.isTrustedProxy("172.16.0.1"));
        assertFalse(resolver.isTrustedProxy("172.20.0.5"));
        assertFalse(resolver.isTrustedProxy("192.168.1.1"));
        assertFalse(resolver.isTrustedProxy("169.254.169.254")); // Link-local bị loại bỏ
    }

    @Test
    @DisplayName("2. Kết nối trực tiếp untrusted: Bỏ qua toàn bộ header giả mạo (Anti-IP Spoofing)")
    void testDirectUntrustedConnectionIgnoresSpoofedHeaders() {
        TrustedProxyResolver resolver = new TrustedProxyResolver();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.195"); // Public Client IP
        request.addHeader("X-Forwarded-For", "1.1.1.1, 8.8.8.8");
        request.addHeader("X-Real-IP", "9.9.9.9");
        request.addHeader("X-Test-Client-IP", "10.0.0.99");

        String resolved = resolver.resolveClientIp(request);
        assertEquals("203.0.113.195", resolved);
    }

    @Test
    @DisplayName("3. Kết nối từ untrusted container trong mạng private không thể spoof IP")
    void testUntrustedPrivateContainerCannotSpoofClientIp() {
        // Cấu hình chỉ tin cậy đúng 1 reverse proxy cụ thể tại 10.0.1.5/32
        TrustedProxyResolver resolver = new TrustedProxyResolver("127.0.0.1/32,::1/128,10.0.1.5/32", false);

        // Request từ một container khác trong subnet 10.0.1.99 (không phải proxy 10.0.1.5)
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.1.99");
        request.addHeader("X-Forwarded-For", "203.0.113.50");

        // Vì 10.0.1.99 không nằm trong dải tin cậy, XFF bị bỏ qua
        String resolved = resolver.resolveClientIp(request);
        assertEquals("10.0.1.99", resolved);
    }

    @Test
    @DisplayName("4. Kết nối từ đúng Reverse Proxy tin cậy: Phân giải chuẩn xác qua RTL parsing")
    void testConfiguredTrustedProxyResolvesViaRightToLeftParsing() {
        TrustedProxyResolver resolver = new TrustedProxyResolver("127.0.0.1/32,::1/128,10.0.1.5/32", false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.1.5"); // Đúng Trusted Reverse Proxy
        request.addHeader("X-Forwarded-For", "198.51.100.22, 10.0.1.5");

        String resolved = resolver.resolveClientIp(request);
        assertEquals("198.51.100.22", resolved);
    }

    @Test
    @DisplayName("5. Chế độ Production (allowTestHeader = false): Tuyệt đối từ chối X-Test-Client-IP")
    void testProductionRejectsTestHeaderEvenFromTrustedProxy() {
        // Cấu hình production: allowTestHeader = false
        TrustedProxyResolver prodResolver = new TrustedProxyResolver("127.0.0.1/32", false);
        assertFalse(prodResolver.isAllowTestHeader());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Test-Client-IP", "203.0.113.88");

        String resolved = prodResolver.resolveClientIp(request);
        // Không được ăn theo header test, phải trả về remoteAddr
        assertEquals("127.0.0.1", resolved);
    }

    @Test
    @DisplayName("6. Chế độ Test (allowTestHeader = true): Chỉ chấp nhận khi từ Trusted Proxy")
    void testTestProfileAcceptsTestHeaderOnlyFromTrustedProxy() {
        TrustedProxyResolver testResolver = new TrustedProxyResolver("127.0.0.1/32", true);
        assertTrue(testResolver.isAllowTestHeader());

        // Từ trusted proxy -> chấp nhận
        MockHttpServletRequest trustedReq = new MockHttpServletRequest();
        trustedReq.setRemoteAddr("127.0.0.1");
        trustedReq.addHeader("X-Test-Client-IP", "203.0.113.88");
        assertEquals("203.0.113.88", testResolver.resolveClientIp(trustedReq));

        // Từ untrusted IP ngoài -> từ chối
        MockHttpServletRequest untrustedReq = new MockHttpServletRequest();
        untrustedReq.setRemoteAddr("198.51.100.20");
        untrustedReq.addHeader("X-Test-Client-IP", "203.0.113.88");
        assertEquals("198.51.100.20", testResolver.resolveClientIp(untrustedReq));
    }

    @Test
    @DisplayName("7. Header giả mạo không hợp lệ (unknown, rác) không bị bypass về 127.0.0.1")
    void testMalformedHeadersNeverResolveToLocalhost() {
        TrustedProxyResolver resolver = new TrustedProxyResolver("10.0.1.5/32", false);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.1.5");
        req.addHeader("X-Real-IP", "unknown");

        String resolved = resolver.resolveClientIp(req);
        // Không được biến 'unknown' thành 127.0.0.1 để bypass WAF, phải an toàn trả về remoteAddr
        assertEquals("10.0.1.5", resolved);

        // Chuỗi X-Forwarded-For có hop rác ở giữa
        MockHttpServletRequest xffReq = new MockHttpServletRequest();
        xffReq.setRemoteAddr("10.0.1.5");
        xffReq.addHeader("X-Forwarded-For", "spoofed.ip, unknown, 10.0.1.5");
        assertEquals("10.0.1.5", resolver.resolveClientIp(xffReq));
    }

    @Test
    @DisplayName("8. Chuẩn hóa IPv6 Canonical: 0:0:0:0:0:0:0:1 và ::1 khớp chính xác với dải ::1/128")
    void testIpv6CanonicalizationMatching() {
        TrustedProxyResolver resolver = new TrustedProxyResolver("::1/128", false);

        assertTrue(resolver.isTrustedProxy("::1"));
        assertTrue(resolver.isTrustedProxy("0:0:0:0:0:0:0:1"));
        assertFalse(resolver.isTrustedProxy("2001:db8::1"));
    }
}
