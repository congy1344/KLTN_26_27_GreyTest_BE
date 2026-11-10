package com.greytest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.greytest.dto.admin.AdminDtos.EndpointAnalyticsDto;
import com.greytest.dto.admin.AdminDtos.PageDto;
import com.greytest.dto.admin.AdminDtos.ServerRequestLogDto;
import com.greytest.dto.admin.AdminDtos.ServerTelemetryDto;
import com.greytest.dto.admin.AdminDtos.TrafficAlertDto;
import com.greytest.dto.admin.AdminDtos.TrafficOverviewDto;
import com.greytest.dto.admin.AdminDtos.TrafficTimeseriesDto;
import com.greytest.dto.admin.AdminDtos.WafStatusDto;

class ServerTelemetryServiceTest {

    private ServerTelemetryService telemetryService;

    @BeforeEach
    void setUp() {
        telemetryService = new ServerTelemetryService();
    }

    @AfterEach
    void tearDown() {
        telemetryService.destroy();
    }

    @Test
    void testRecordAndGetTelemetry() {
        telemetryService.incrementActive();
        telemetryService.recordRequest("GET", "/api/admin/overview", 200, 25L, "192.168.1.10", "admin@gmail.com");
        telemetryService.recordRequest("POST", "/api/auth/login", 200, 45L, "192.168.1.11", "user@gmail.com");
        telemetryService.recordRequest("POST", "/api/generation/test-cases", 429, 2L, "192.168.1.12", "attacker@gmail.com");
        telemetryService.decrementActive();

        ServerTelemetryDto dto = telemetryService.getTelemetry(50, null, null);

        assertEquals(3, dto.totalRequests());
        assertEquals(3, dto.requestsPerMinute());
        assertEquals(1, dto.rateLimitedCount());
        assertEquals(0, dto.activeRequests());
        assertEquals(3, dto.logs().size());

        // Test filtering by status
        ServerTelemetryDto errorDto = telemetryService.getTelemetry(50, null, 429);
        assertEquals(1, errorDto.logs().size());
        assertEquals("POST", errorDto.logs().get(0).method());
        assertEquals("/api/generation/test-cases", errorDto.logs().get(0).uri());

        // Test search filter
        ServerTelemetryDto searchDto = telemetryService.getTelemetry(50, "admin@gmail.com", null);
        assertEquals(1, searchDto.logs().size());
        assertEquals("GET", searchDto.logs().get(0).method());
        assertEquals("/api/admin/overview", searchDto.logs().get(0).uri());
    }

    @Test
    void returnsNoDataStateWithNullLatenciesWhenNoRequestsRecorded() {
        TrafficOverviewDto overview = telemetryService.getTrafficOverview("1m", 0L);

        assertEquals("NO_DATA", overview.dataState());
        assertEquals(0L, overview.windowTotalRequests());
        assertEquals(0.0, overview.currentRps());
        assertNull(overview.avgLatencyMs());
        assertNull(overview.p50LatencyMs());
        assertNull(overview.p95LatencyMs());
        assertNull(overview.p99LatencyMs());
        assertNull(overview.errorRatePercent());
    }

    @Test
    void calculatesAccurateRpsPercentilesAndErrorRatesAcrossWindows() {
        // Ghi nhận 10 request: 7x 200 OK, 2x 400 Client Error, 1x 500 Server Error
        long[] durations = {10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 200L};
        int[] statuses = {200, 200, 200, 200, 200, 200, 200, 400, 429, 500};

        for (int i = 0; i < 10; i++) {
            telemetryService.recordRequest("GET", "/api/projects/" + (i + 1), statuses[i], durations[i], "10.0.0.1", "u@test.com");
        }

        TrafficOverviewDto overview = telemetryService.getTrafficOverview("1m", 1L);

        assertEquals("AVAILABLE", overview.dataState());
        assertEquals(10L, overview.windowTotalRequests());
        assertEquals(7L, overview.successCount());
        assertEquals(2L, overview.clientErrorCount());
        assertEquals(1L, overview.serverErrorCount());
        assertEquals(65.0, overview.avgLatencyMs());
        assertNotNull(overview.p50LatencyMs());
        assertNotNull(overview.p95LatencyMs());
        assertNotNull(overview.p99LatencyMs());
        assertTrue(overview.p95LatencyMs() >= 90.0);
        assertEquals(200L, overview.maxLatencyMs());
        assertEquals(30.0, overview.errorRatePercent());
        assertEquals(20.0, overview.clientErrorRatePercent());
        assertEquals(10.0, overview.serverErrorRatePercent());
        // Current RPS phản ánh đúng số request thực trong 1 giây, không phải 10 / 60
        assertEquals(10.0, overview.currentRps());
    }

    @Test
    void normalizesDynamicIdsAndRedactsSensitiveQueryParams() {
        assertEquals("/api/projects/{id}/test-cases",
                ServerTelemetryService.normalizeEndpoint("/api/projects/42/test-cases?page=0"));
        assertEquals("/api/users/{uuid}/profile",
                ServerTelemetryService.normalizeEndpoint("/api/users/550e8400-e29b-41d4-a716-446655440000/profile"));

        String sanitized = ServerTelemetryService.sanitizeUri("/api/auth/callback?token=secret-jwt-123&mode=ok&api_key=abc");
        assertTrue(sanitized.contains("token=[REDACTED]"));
        assertTrue(sanitized.contains("api_key=[REDACTED]"));
        assertTrue(sanitized.contains("mode=ok"));
        assertFalse(sanitized.contains("secret-jwt-123"));
    }

    @Test
    void aggregatesEndpointAnalyticsAndDistributions() {
        telemetryService.recordRequest("GET", "/api/projects/1", 200, 20L, "127.0.0.1", "a@b.com");
        telemetryService.recordRequest("GET", "/api/projects/2", 200, 40L, "127.0.0.1", "a@b.com");
        telemetryService.recordRequest("POST", "/api/projects/2/generate", 500, 800L, "127.0.0.1", "a@b.com");

        EndpointAnalyticsDto analytics = telemetryService.getEndpointAnalytics("15m", 10);
        assertEquals(2, analytics.topByVolume().size());
        assertEquals("/api/projects/{id}", analytics.topByVolume().get(0).endpoint());
        assertEquals(2L, analytics.topByVolume().get(0).requestCount());

        assertEquals("/api/projects/{id}/generate", analytics.topByLatency().get(0).endpoint());
        assertEquals(1, analytics.topByErrorRate().size());
        assertEquals(100.0, analytics.topByErrorRate().get(0).errorRatePercent());
        assertEquals(2L, analytics.methodDistribution().get("GET"));
        assertEquals(1L, analytics.methodDistribution().get("POST"));
    }

    @Test
    void supportsRecentRequestsPaginationSortingAndFiltering() {
        telemetryService.recordRequest("GET", "/api/projects/1", 200, 15L, "10.0.0.1", "u1@test.com");
        telemetryService.recordRequest("POST", "/api/projects/2", 500, 450L, "10.0.0.2", "u2@test.com");
        telemetryService.recordRequest("DELETE", "/api/projects/3", 404, 120L, "10.0.0.3", "u3@test.com");

        PageDto<ServerRequestLogDto> byLatencyDesc = telemetryService.getRecentRequests(
                0, 10, null, null, null, null, 100L, "durationMs", "desc");
        assertEquals(2L, byLatencyDesc.totalElements());
        assertEquals(450L, byLatencyDesc.content().get(0).durationMs());
        assertEquals(120L, byLatencyDesc.content().get(1).durationMs());

        PageDto<ServerRequestLogDto> only5xx = telemetryService.getRecentRequests(
                0, 10, null, "POST", null, "5XX", null, "timestamp", "desc");
        assertEquals(1L, only5xx.totalElements());
        assertEquals(500, only5xx.content().get(0).status());
    }

    @Test
    void buildsTimeseriesAndTrafficAlerts() {
        for (int i = 0; i < 12; i++) {
            telemetryService.recordRequest("POST", "/api/test", 500, 1200L, "10.0.0.1", "u@test.com");
        }
        TrafficTimeseriesDto ts = telemetryService.getTrafficTimeseries("1m");
        assertEquals("1m", ts.window());
        assertEquals(60, ts.points().size());

        WafStatusDto waf = new WafStatusDto(true, true, 30, 100, 30, 15, 5L, 1, List.of());
        List<TrafficAlertDto> alerts = telemetryService.getTrafficAlerts("1m", waf);
        assertFalse(alerts.isEmpty());
        assertTrue(alerts.stream().anyMatch(a -> "RPS_THRESHOLD_EXCEEDED".equals(a.code())));
        assertTrue(alerts.stream().anyMatch(a -> "SERVER_ERROR_RATE_HIGH".equals(a.code())));
        assertTrue(alerts.stream().anyMatch(a -> "LATENCY_P95_HIGH".equals(a.code())));
    }

    @Test
    void enforcesMaxSseSubscriberLimit() {
        WafStatusDto waf = new WafStatusDto(true, false, 100, 15, 0L, 0, List.of());
        List<SseEmitter> emitters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            emitters.add(telemetryService.subscribeStream(waf));
        }
        assertEquals(10, telemetryService.getActiveSseSubscriberCount());
        assertThrows(ResponseStatusException.class, () -> telemetryService.subscribeStream(waf));
    }

    @Test
    void countsAll6400ConcurrentBurstRequestsInWindowWithoutSampleBufferTruncation() throws Exception {
        int threads = 32;
        int perThread = 200;
        int totalExpected = threads * perThread; // 6.400 requests (> 5.000 sampleBuffer cap)

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        // Phân bổ trạng thái thực tế trên mỗi luồng (200 req):
        // - 150 request 200 OK
        // - 20 request 400 Bad Request
        // - 15 request 429 Too Many Requests
        // - 15 request 500 Internal Server Error
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < perThread; i++) {
                        int status;
                        if (i < 150) {
                            status = 200;
                        } else if (i < 170) {
                            status = 400;
                        } else if (i < 185) {
                            status = 429;
                        } else {
                            status = 500;
                        }
                        telemetryService.recordRequest("GET", "/api/projects/" + i, status, 10L + (i % 50), "10.0.0.1", "u@test.com");
                    }
                } catch (Throwable ex) {
                    errors.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(doneGate.await(15, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(0, errors.get());

        TrafficOverviewDto overview1m = telemetryService.getTrafficOverview("1m", null);
        assertEquals(6400L, overview1m.lifetimeTotalRequests());
        assertEquals(6400L, overview1m.windowTotalRequests());
        assertEquals(32L * 150L, overview1m.successCount());       // 4.800 (2xx)
        assertEquals(32L * 35L, overview1m.clientErrorCount());    // 1.120 (4xx bao gồm 400 + 429)
        assertEquals(32L * 15L, overview1m.serverErrorCount());    // 480 (5xx)
        assertEquals(32L * 15L, overview1m.windowRejectedRequests()); // 480 (429)
        assertEquals(6400L, overview1m.successCount() + overview1m.clientErrorCount() + overview1m.serverErrorCount());
        assertNotNull(overview1m.p95LatencyMs());

        // Kiểm tra cửa sổ 24h (minuteBuckets) cũng tính chính xác 6.400 và không cộng trùng với secondBuckets
        TrafficOverviewDto overview24h = telemetryService.getTrafficOverview("24h", null);
        assertEquals(6400L, overview24h.windowTotalRequests());
        assertEquals(4800L, overview24h.successCount());
        assertEquals(1120L, overview24h.clientErrorCount());
        assertEquals(480L, overview24h.serverErrorCount());
        assertEquals(480L, overview24h.windowRejectedRequests());
    }

    @Test
    void respectsRollingWindowBoundariesAndEvictsExpiredBuckets() {
        long baseNow = 1_700_000_000_000L; // Mốc thời gian cố định chia hết cho phút/giây

        // 1. Bucket quá hạn (> 26 giờ trước): phải bị dọn dẹp khi cleanupOldBuckets chạy
        telemetryService.recordRequestAt("GET", "/api/old", "/api/old", 200, 10L, "10.0.0.1", "u@test.com", "req-old", "USER_API", null,
                baseNow - (26 * 3600_000L));

        // 2. Request cách đây 2 giờ (nằm trong cửa sổ 24h nhưng ngoài cửa sổ 1h)
        telemetryService.recordRequestAt("GET", "/api/2h", "/api/2h", 500, 100L, "10.0.0.1", "u@test.com", "req-2h", "USER_API", null,
                baseNow - (2 * 3600_000L));

        // 3. Request cách đây 8 phút (nằm trong cửa sổ 15m, 1h, 24h và nằm trong cửa sổ 5m liền trước [5m..10m], nhưng ngoài 1m, 5m hiện tại)
        telemetryService.recordRequestAt("GET", "/api/8m", "/api/8m", 429, 50L, "10.0.0.1", "u@test.com", "req-8m", "USER_API", null,
                baseNow - (8 * 60_000L));

        // 4. Request cách đây 3 phút (nằm trong cửa sổ 5m, 15m, 1h, 24h nhưng ngoài 1m)
        telemetryService.recordRequestAt("GET", "/api/3m", "/api/3m", 400, 30L, "10.0.0.1", "u@test.com", "req-3m", "USER_API", null,
                baseNow - (3 * 60_000L));

        // 5. Request cách đây 10 giây (nằm trong mọi cửa sổ 1m, 5m, 15m, 1h, 24h)
        telemetryService.recordRequestAt("GET", "/api/10s", "/api/10s", 200, 20L, "10.0.0.1", "u@test.com", "req-10s", "USER_API", null,
                baseNow - 10_000L);

        TrafficOverviewDto w1m = telemetryService.getTrafficOverviewAt("1m", null, baseNow);
        assertEquals(1L, w1m.windowTotalRequests());
        assertEquals(1L, w1m.successCount());

        TrafficOverviewDto w5m = telemetryService.getTrafficOverviewAt("5m", null, baseNow);
        assertEquals(2L, w5m.windowTotalRequests());
        assertEquals(1L, w5m.successCount());
        assertEquals(1L, w5m.clientErrorCount());
        assertEquals(1L, w5m.previousWindowRequests()); // req-10m nằm ở cửa sổ 5m liền trước (5m..10m)

        TrafficOverviewDto w15m = telemetryService.getTrafficOverviewAt("15m", null, baseNow);
        assertEquals(3L, w15m.windowTotalRequests());
        assertEquals(1L, w15m.windowRejectedRequests());

        TrafficOverviewDto w1h = telemetryService.getTrafficOverviewAt("1h", null, baseNow);
        assertEquals(3L, w1h.windowTotalRequests()); // req-2h và req-old đã nằm ngoài 1h

        TrafficOverviewDto w24h = telemetryService.getTrafficOverviewAt("24h", null, baseNow);
        assertEquals(4L, w24h.windowTotalRequests()); // Bao gồm req-2h, nhưng req-old (>25h) đã bị dọn dẹp
        assertEquals(1L, w24h.serverErrorCount());
    }

    @Test
    void hardensSanitizeUriForNestedEncodedMultipleSecretsAndTruncatesAt512Chars() {
        assertEquals("/api/test?token=[REDACTED]", ServerTelemetryService.sanitizeUri("/api/test?token=abc"));
        assertEquals("/api/test?password=[REDACTED]", ServerTelemetryService.sanitizeUri("/api/test?password=abc"));
        assertEquals("/api/test?api_key=[REDACTED]", ServerTelemetryService.sanitizeUri("/api/test?api_key=abc"));
        assertEquals("/api/test?Authorization=[REDACTED]", ServerTelemetryService.sanitizeUri("/api/test?Authorization=abc"));
        assertEquals("/api/test?nested[token]=[REDACTED]", ServerTelemetryService.sanitizeUri("/api/test?nested[token]=abc"));
        assertEquals("/api/test?user[password]=[REDACTED]", ServerTelemetryService.sanitizeUri("/api/test?user[password]=abc"));

        // Tên tham số và giá trị được URL-encode
        String encodedParams = ServerTelemetryService.sanitizeUri(
                "/api/test?nested%5Btoken%5D=my-secret-1&%70%61%73%73%77%6F%72%64=my-secret-2&api%5Fkey=my-secret-3&token=Bearer%20xyz%26key%3D999");
        assertFalse(encodedParams.contains("my-secret-1"));
        assertFalse(encodedParams.contains("my-secret-2"));
        assertFalse(encodedParams.contains("my-secret-3"));
        assertFalse(encodedParams.contains("xyz"));
        assertTrue(encodedParams.contains("nested%5Btoken%5D=[REDACTED]"));
        assertTrue(encodedParams.contains("%70%61%73%73%77%6F%72%64=[REDACTED]"));
        assertTrue(encodedParams.contains("api%5Fkey=[REDACTED]"));

        // Nhiều tham số nhạy cảm kết hợp tham số nghiệp vụ bình thường (không được over-redact keyword, page, sort)
        String mixed = ServerTelemetryService.sanitizeUri(
                "/api/projects?page=1&keyword=monkey&sort=name&token=secA&user[password]=secB&client_secret=secC&auth=secD");
        assertTrue(mixed.contains("page=1"));
        assertTrue(mixed.contains("keyword=monkey"));
        assertTrue(mixed.contains("sort=name"));
        assertTrue(mixed.contains("token=[REDACTED]"));
        assertTrue(mixed.contains("user[password]=[REDACTED]"));
        assertTrue(mixed.contains("client_secret=[REDACTED]"));
        assertTrue(mixed.contains("auth=[REDACTED]"));
        assertFalse(mixed.contains("secA"));
        assertFalse(mixed.contains("secB"));
        assertFalse(mixed.contains("secC"));
        assertFalse(mixed.contains("secD"));

        // URI dài hơn 4.000 ký tự phải bị cắt còn đúng 512 ký tự kèm hậu tố ...[TRUNCATED] và không lộ secret
        String longUri = "/api/projects/42/" + "a".repeat(4000) + "?token=super-secret-value-999";
        assertTrue(longUri.length() > 4000);
        String sanitizedLong = ServerTelemetryService.sanitizeUri(longUri);
        assertEquals(ServerTelemetryService.MAX_SANITIZED_URI_LENGTH, sanitizedLong.length());
        assertTrue(sanitizedLong.endsWith(ServerTelemetryService.URI_TRUNCATED_SUFFIX));
        assertFalse(sanitizedLong.contains("super-secret-value-999"));

        // Endpoint analytics vẫn giữ đúng route chuẩn hóa khi query dài > 4.000 ký tự
        String longQueryUri = "/api/projects/42/test-cases?filter=" + "x".repeat(4000) + "&token=secret123";
        assertEquals("/api/projects/{id}/test-cases", ServerTelemetryService.normalizeEndpoint(longQueryUri));
    }
}
