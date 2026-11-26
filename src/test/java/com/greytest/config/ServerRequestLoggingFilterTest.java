package com.greytest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.greytest.service.AuthService;
import com.greytest.service.DdosProtectionService;
import com.greytest.service.DdosProtectionService.CheckResult;
import com.greytest.service.DdosProtectionService.CheckStatus;
import com.greytest.service.ServerTelemetryService;

import jakarta.servlet.FilterChain;

class ServerRequestLoggingFilterTest {

    private ServerTelemetryService telemetryService;
    private AuthService authService;
    private DdosProtectionService ddosProtectionService;
    private ServerRequestLoggingFilter filter;

    @BeforeEach
    void setUp() {
        telemetryService = mock(ServerTelemetryService.class);
        authService = mock(AuthService.class);
        ddosProtectionService = mock(DdosProtectionService.class);
        filter = new ServerRequestLoggingFilter(telemetryService, authService, ddosProtectionService);
    }

    @Test
    void shouldNotFilterStaticOrPollingEndpoints() {
        MockHttpServletRequest request1 = new MockHttpServletRequest("GET", "/index.html");
        assertTrue(filter.shouldNotFilter(request1));

        MockHttpServletRequest request2 = new MockHttpServletRequest("GET", "/api/admin/system/request-logs");
        assertTrue(filter.shouldNotFilter(request2));

        MockHttpServletRequest request3 = new MockHttpServletRequest("GET", "/api/admin/system/security/waf-status");
        assertTrue(filter.shouldNotFilter(request3));

        MockHttpServletRequest request4 = new MockHttpServletRequest("GET", "/api/projects");
        org.junit.jupiter.api.Assertions.assertFalse(filter.shouldNotFilter(request4));
    }

    @Test
    void allowsAllowedRequestsThroughFilterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(ddosProtectionService.checkRequest(anyString(), eq("GET"), eq("/api/projects")))
                .thenReturn(new CheckResult(CheckStatus.ALLOWED, "OK", 0));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(telemetryService).incrementActive();
        verify(telemetryService).decrementActive();
        verify(telemetryService).recordRequest(eq("GET"), eq("/api/projects"), eq(200), anyLong(), anyString(), anyString());
    }

    @Test
    void blocksAndReturns429WhenWafTriggers() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(ddosProtectionService.checkRequest(anyString(), eq("POST"), eq("/api/auth/login")))
                .thenReturn(new CheckResult(CheckStatus.RATE_LIMITED, "Rate limit exceeded \"test\"", 60));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
        assertEquals("Active-Blocked", response.getHeader("X-WAF-Defense"));

        String body = response.getContentAsString();
        assertTrue(body.contains("\"status\":429"));
        assertTrue(body.contains("Rate limit exceeded 'test'"));
        verify(telemetryService).recordRequest(eq("POST"), eq("/api/auth/login"), eq(429), anyLong(), anyString(), eq("rate-limited"));
    }

    @Test
    void doesNotFilterMonitoringStreamOrOverviewEndpointsAndPreventsDoubleCounting() throws Exception {
        MockHttpServletRequest streamReq = new MockHttpServletRequest("GET", "/api/admin/monitoring/stream");
        assertTrue(filter.shouldNotFilter(streamReq));

        MockHttpServletRequest overviewReq = new MockHttpServletRequest("GET", "/api/admin/monitoring/traffic/overview");
        assertTrue(filter.shouldNotFilter(overviewReq));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects/123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(ddosProtectionService.checkRequest(anyString(), eq("GET"), eq("/api/projects/123")))
                .thenReturn(new CheckResult(CheckStatus.ALLOWED, "OK", 0));

        // Simulate nested/forwarded filter invocation on the same request
        FilterChain nestedChain = (req, res) -> filter.doFilterInternal(
                (jakarta.servlet.http.HttpServletRequest) req,
                (jakarta.servlet.http.HttpServletResponse) res,
                mock(FilterChain.class));

        filter.doFilter(request, response, nestedChain);

        // Must only record once despite re-entering doFilterInternal
        verify(telemetryService, org.mockito.Mockito.times(1))
                .recordRequest(eq("GET"), eq("/api/projects/123"), eq(200), anyLong(), anyString(), anyString());
        org.junit.jupiter.api.Assertions.assertNotNull(response.getHeader(ServerRequestLoggingFilter.REQUEST_ID_HEADER));
    }

    @Test
    void isolatesMonitoringFailureWithoutBreakingBusinessRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(ddosProtectionService.checkRequest(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Simulated WAF failure"));
        org.mockito.Mockito.doThrow(new RuntimeException("Simulated telemetry failure"))
                .when(telemetryService).recordRequest(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyLong(), anyString(), anyString());

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void sanitizesSensitiveQueryParametersBeforeRecordingAndPreservesAnalyticsRoute() throws Exception {
        ServerTelemetryService realTelemetry = new ServerTelemetryService();
        try {
            DdosProtectionService realDdos = new DdosProtectionService();
            ServerRequestLoggingFilter realFilter = new ServerRequestLoggingFilter(realTelemetry, authService, realDdos);

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects/99/test-cases");
            request.setQueryString("token=raw-secret-jwt&nested[token]=nested-secret&user[password]=pw123&page=2");
            request.setRemoteAddr("203.0.113.15");
            MockHttpServletResponse response = new MockHttpServletResponse();

            realFilter.doFilter(request, response, mock(FilterChain.class));

            var logs = realTelemetry.getRecentRequests(0, 10, null, null, null, null, null, "timestamp", "desc").content();
            assertEquals(1, logs.size());
            String recordedUri = logs.get(0).uri();
            assertTrue(recordedUri.contains("token=[REDACTED]"));
            assertTrue(recordedUri.contains("nested[token]=[REDACTED]"));
            assertTrue(recordedUri.contains("user[password]=[REDACTED]"));
            assertTrue(recordedUri.contains("page=2"));
            org.junit.jupiter.api.Assertions.assertFalse(recordedUri.contains("raw-secret-jwt"));
            org.junit.jupiter.api.Assertions.assertFalse(recordedUri.contains("nested-secret"));
            org.junit.jupiter.api.Assertions.assertFalse(recordedUri.contains("pw123"));
            assertEquals("/api/projects/{id}/test-cases", logs.get(0).routeTemplate());
        } finally {
            realTelemetry.destroy();
        }
    }

    @Test
    void handlesDirectSpoofedTrustedProxyMultipleAndInvalidForwardedHeadersConsistently() throws Exception {
        ServerTelemetryService realTelemetry = new ServerTelemetryService();
        try {
            DdosProtectionService realDdos = new DdosProtectionService();
            realDdos.setStandardRateLimit(2);
            com.greytest.security.waf.TrustedProxyResolver testProxyResolver =
                    new com.greytest.security.waf.TrustedProxyResolver("127.0.0.1/32,::1/128,172.20.0.0/16", true);
            ServerRequestLoggingFilter realFilter = new ServerRequestLoggingFilter(realTelemetry, authService, realDdos, testProxyResolver);

            // 1. Request trực tiếp không có forwarded headers
            MockHttpServletRequest directReq = new MockHttpServletRequest("GET", "/api/projects");
            directReq.setRemoteAddr("198.51.100.10");
            assertEquals("198.51.100.10", realFilter.resolveClientIp(directReq));

            // 2. Request trực tiếp có X-Forwarded-For giả mạo (khi strategy=none mặc định):
            // Không tin X-Forwarded-For do client tự gửi, phải dùng remoteAddr thật và áp dụng Rate Limit đúng IP đó
            for (int i = 1; i <= 3; i++) {
                MockHttpServletRequest spoofReq = new MockHttpServletRequest("GET", "/api/projects");
                spoofReq.setRemoteAddr("198.51.100.20");
                spoofReq.addHeader("X-Forwarded-For", "1.1.1." + i);
                MockHttpServletResponse spoofRes = new MockHttpServletResponse();
                realFilter.doFilter(spoofReq, spoofRes, mock(FilterChain.class));
                if (i <= 2) {
                    assertEquals(200, spoofRes.getStatus());
                } else {
                    // Request thứ 3 bị chặn 429 dù client đổi X-Forwarded-For giả mạo mỗi lần
                    assertEquals(429, spoofRes.getStatus());
                }
            }

            // 3. Request qua trusted reverse proxy (sử dụng ForwardedHeaderFilter của Spring khi bật strategy=framework)
            org.springframework.web.filter.ForwardedHeaderFilter trustedProxyFilter =
                    new org.springframework.web.filter.ForwardedHeaderFilter();
            MockHttpServletRequest proxyReq = new MockHttpServletRequest("GET", "/api/projects");
            proxyReq.setRemoteAddr("10.0.0.1"); // Trusted Reverse Proxy IP
            proxyReq.addHeader("X-Forwarded-For", "203.0.113.77, 10.0.0.2");
            MockHttpServletResponse proxyRes = new MockHttpServletResponse();
            trustedProxyFilter.doFilter(proxyReq, proxyRes, (wrappedReq, wrappedRes) -> {
                String resolved = realFilter.resolveClientIp(( jakarta.servlet.http.HttpServletRequest ) wrappedReq);
                assertEquals("203.0.113.77", resolved);
                realFilter.doFilter(wrappedReq, wrappedRes, mock(FilterChain.class));
            });
            assertEquals(200, proxyRes.getStatus());

            // 4. Multiple forwarded IPs & 5. Header không hợp lệ
            assertEquals("203.0.113.88", DdosProtectionService.normalizeClientIp("203.0.113.88, 10.0.0.1, 172.16.0.1"));
            assertEquals("127.0.0.1", DdosProtectionService.normalizeClientIp("invalid-ip;<script>alert(1)</script>"));
            assertEquals("127.0.0.1", DdosProtectionService.normalizeClientIp("[::1"));

            // 6. Cho phép X-Test-Client-IP khi kiểm thử từ localhost/Docker, từ chối khi từ external IP
            MockHttpServletRequest testIpReq = new MockHttpServletRequest("GET", "/api/projects");
            testIpReq.setRemoteAddr("172.20.0.1");
            testIpReq.addHeader("X-Test-Client-IP", "203.0.113.195");
            assertEquals("203.0.113.195", realFilter.resolveClientIp(testIpReq));

            MockHttpServletRequest untrustedReq = new MockHttpServletRequest("GET", "/api/projects");
            untrustedReq.setRemoteAddr("198.51.100.20");
            untrustedReq.addHeader("X-Test-Client-IP", "203.0.113.195");
            assertEquals("198.51.100.20", realFilter.resolveClientIp(untrustedReq));
        } finally {
            realTelemetry.destroy();
        }
    }
}

