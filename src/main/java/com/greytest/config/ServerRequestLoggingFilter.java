package com.greytest.config;

import java.io.IOException;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import com.greytest.entity.AuthUser;
import com.greytest.security.waf.TrustedProxyResolver;
import com.greytest.service.AuthService;
import com.greytest.service.DdosProtectionService;
import com.greytest.service.ServerTelemetryService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Filter ghi nhận tập trung toàn bộ lưu lượng HTTP request gửi tới server Spring Boot theo thời gian thực
 * và tích hợp tầng bảo vệ tường lửa WAF / chống tấn công DDoS & Spam tự động.
 * Đảm bảo:
 * 1. Mỗi request chỉ được ghi nhận đúng 1 lần (không double-count).
 * 2. Sinh và gắn X-Request-Id cho mọi request.
 * 3. Chuẩn hóa route template và phân loại request (USER_API, ADMIN_API, HEALTH_CHECK, PREFLIGHT).
 * 4. Cách ly lỗi tuyệt đối: lỗi trong tầng telemetry không bao giờ làm hỏng request nghiệp vụ.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class ServerRequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = "greytest.telemetry.requestId";
    private static final String RECORDED_ATTRIBUTE = "greytest.telemetry.recorded";

    private final ServerTelemetryService telemetryService;
    private final AuthService authService;
    private final DdosProtectionService ddosProtectionService;
    private final TrustedProxyResolver trustedProxyResolver;

    public ServerRequestLoggingFilter(
            ServerTelemetryService telemetryService,
            AuthService authService,
            DdosProtectionService ddosProtectionService) {
        this(telemetryService, authService, ddosProtectionService, new TrustedProxyResolver());
    }

    @Autowired
    public ServerRequestLoggingFilter(
            ServerTelemetryService telemetryService,
            @Autowired(required = false) AuthService authService,
            @Autowired(required = false) DdosProtectionService ddosProtectionService,
            @Autowired(required = false) TrustedProxyResolver trustedProxyResolver) {
        this.telemetryService = telemetryService;
        this.authService = authService;
        this.ddosProtectionService = ddosProtectionService;
        this.trustedProxyResolver = trustedProxyResolver != null ? trustedProxyResolver : new TrustedProxyResolver();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Không log tài nguyên tĩnh hoặc chính các endpoint polling/streaming của hệ thống giám sát
        // để tránh vòng lặp tự sinh log (self-observation feedback loop)
        return path == null
                || !path.startsWith("/api")
                || path.equals("/api/admin/system/request-logs")
                || path.equals("/api/admin/system/security/waf-status")
                || path.startsWith("/api/admin/monitoring/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Chống ghi nhận trùng lặp khi request đi qua forward/async dispatch
        if (Boolean.TRUE.equals(request.getAttribute(RECORDED_ATTRIBUTE))) {
            filterChain.doFilter(request, response);
            return;
        }
        request.setAttribute(RECORDED_ATTRIBUTE, Boolean.TRUE);

        long startTime = System.currentTimeMillis();
        String clientIp = resolveClientIp(request);
        String rawPath = request.getRequestURI();
        String path = rawPath;
        if (path != null) {
            int semi = path.indexOf(';');
            if (semi >= 0) {
                path = path.substring(0, semi);
            }
        }
        String sanitizedUri = ServerTelemetryService.sanitizeUri(buildRawUriWithQuery(request));
        String requestId = resolveOrGenerateRequestId(request, response);

        // 1. Kiểm tra phòng vệ tầng WAF / Anti-DDoS (vẫn chặn IP nếu đã trong danh sách đen)
        if (ddosProtectionService != null && path != null) {
            boolean isSecurityMgmt = path.equals("/api/admin/system/security/waf-status")
                    || path.startsWith("/api/admin/monitoring/rate-limit");
            if (!isSecurityMgmt || ddosProtectionService.isIpBlocked(clientIp)) {
                DdosProtectionService.CheckResult checkResult = null;
                try {
                    checkResult = ddosProtectionService.checkRequest(clientIp, request.getMethod(), path);
                    if (checkResult == null) {
                        checkResult = ddosProtectionService.checkRequest(clientIp, path);
                    }
                } catch (Exception ignored) {
                    // Không để lỗi bộ kiểm tra WAF làm gián đoạn request
                }

                if (checkResult != null && checkResult.isBlocked()) {
                    response.setStatus(429);
                    response.setContentType("application/json;charset=UTF-8");
                    response.setHeader("Retry-After", String.valueOf(checkResult.retryAfterSeconds()));
                    response.setHeader("X-WAF-Defense", "Active-Blocked");
                    String safeReason = checkResult.reason() != null
                            ? checkResult.reason().replace("\\", "\\\\").replace("\"", "'").replace("\n", " ")
                            : "";
                    response.getWriter().write(
                            "{\"status\":429,\"error\":\"TOO_MANY_REQUESTS\",\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"" +
                            safeReason + "\",\"retryAfter\":" + checkResult.retryAfterSeconds() + "}"
                    );

                    long duration = Math.max(0L, System.currentTimeMillis() - startTime);
                    recordSafely(request, path, sanitizedUri, 429, duration, clientIp, "rate-limited", requestId, null);
                    return;
                }
            }
        }

        try {
            if (telemetryService != null) {
                telemetryService.incrementActive();
            }
        } catch (Exception ignored) {
        }

        Throwable unhandledError = null;
        try {
            response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("X-Content-Type-Options", "nosniff");
            filterChain.doFilter(request, response);
        } catch (Throwable ex) {
            unhandledError = ex;
            throw ex;
        } finally {
            long duration = Math.max(0L, System.currentTimeMillis() - startTime);
            try {
                if (telemetryService != null) {
                    telemetryService.decrementActive();
                }
            } catch (Exception ignored) {
            }

            int status = response.getStatus();
            if (unhandledError != null && status < 400) {
                status = 500;
            }
            String userEmail = resolveUserEmail(request);
            recordSafely(request, path, sanitizedUri, status, duration, clientIp, userEmail, requestId, unhandledError);
        }
    }

    private void recordSafely(
            HttpServletRequest request,
            String rawPath,
            String sanitizedUri,
            int status,
            long durationMs,
            String clientIp,
            String userEmail,
            String requestId,
            Throwable error) {
        if (telemetryService == null) {
            return;
        }
        try {
            String routeTemplate = resolveRouteTemplate(request, rawPath);
            String requestType = ServerTelemetryService.classifyRequestType(request.getMethod(), rawPath);
            String userAgent = request.getHeader("User-Agent");

            telemetryService.setTraceContext(requestId, routeTemplate, requestType, userAgent);
            try {
                telemetryService.recordRequest(
                        request.getMethod(),
                        sanitizedUri,
                        status,
                        durationMs,
                        clientIp,
                        userEmail
                );
            } finally {
                telemetryService.clearTraceContext();
            }
        } catch (Exception ignored) {
            // Cách ly lỗi tuyệt đối: không bao giờ ném ngoại lệ từ tầng giám sát
        }
    }

    private String buildRawUriWithQuery(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            uri = "/";
        }
        String query = request.getQueryString();
        if (query != null && !query.isBlank() && uri.indexOf('?') < 0) {
            return uri + "?" + query;
        }
        return uri;
    }

    private String resolveOrGenerateRequestId(HttpServletRequest request, HttpServletResponse response) {
        try {
            String incoming = request.getHeader(REQUEST_ID_HEADER);
            String requestId = (incoming != null && !incoming.isBlank() && incoming.length() <= 64)
                    ? incoming.trim()
                    : "req-" + UUID.randomUUID().toString().substring(0, 8);
            request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
            if (response != null) {
                response.setHeader(REQUEST_ID_HEADER, requestId);
            }
            return requestId;
        } catch (Exception ignored) {
            return "req-unknown";
        }
    }

    private String resolveRouteTemplate(HttpServletRequest request, String rawUri) {
        try {
            Object patternAttr = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (patternAttr != null) {
                String pattern = patternAttr.toString();
                if (!pattern.isBlank()) {
                    return pattern;
                }
            }
        } catch (Exception ignored) {
        }
        return ServerTelemetryService.normalizeEndpoint(rawUri);
    }

    String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }
        if (trustedProxyResolver != null) {
            return trustedProxyResolver.resolveClientIp(request);
        }
        String remoteAddr = request.getRemoteAddr();
        if (DdosProtectionService.isLoopbackOrDockerIp(remoteAddr)) {
            String testIp = request.getHeader("X-Test-Client-IP");
            if (testIp != null && !testIp.isBlank()) {
                return DdosProtectionService.normalizeClientIp(testIp);
            }
        }
        return DdosProtectionService.normalizeClientIp(remoteAddr);
    }

    private String resolveUserEmail(HttpServletRequest request) {
        try {
            Object userAttr = request.getAttribute(AdminAuthorizationInterceptor.ADMIN_USER_ATTRIBUTE);
            if (userAttr instanceof AuthUser user) {
                return user.getEmail();
            }

            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ") && authService != null) {
                AuthUser user = authService.currentUser(authHeader);
                if (user != null) {
                    return user.getEmail();
                }
            }
        } catch (Exception ignored) {
            // Token không hợp lệ hoặc hết hạn
        }
        return "anonymous";
    }
}
