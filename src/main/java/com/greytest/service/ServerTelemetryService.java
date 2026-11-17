package com.greytest.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.greytest.dto.admin.AdminDtos.EndpointAnalyticsDto;
import com.greytest.dto.admin.AdminDtos.EndpointMetricDto;
import com.greytest.dto.admin.AdminDtos.PageDto;
import com.greytest.dto.admin.AdminDtos.ServerRequestLogDto;
import com.greytest.dto.admin.AdminDtos.ServerTelemetryDto;
import com.greytest.dto.admin.AdminDtos.TrafficAlertDto;
import com.greytest.dto.admin.AdminDtos.TrafficOverviewDto;
import com.greytest.dto.admin.AdminDtos.TrafficStreamSnapshotDto;
import com.greytest.dto.admin.AdminDtos.TrafficTimeseriesDto;
import com.greytest.dto.admin.AdminDtos.TrafficTimeseriesPointDto;
import com.greytest.dto.admin.AdminDtos.WafStatusDto;

/**
 * Service thu thập và phân tích lưu lượng HTTP thời gian thực (Observability & Traffic Telemetry).
 * Hỗ trợ:
 * 1. Ghi nhận request tập trung, chuẩn hóa route template và phân loại lỗi 2xx/4xx/5xx/429.
 * 2. Tính toán KPI theo các cửa sổ trượt (1m, 5m, 15m, 1h, 24h): Current/Average/Peak RPS,
 *    Latency (Average, P50, P95, P99, Max), Error Rate và so sánh với kỳ liền trước.
 * 3. Phân tích Endpoint Analytics (Top Volume, Top Latency, Top Error Rate, phân phối Method/Status).
 * 4. Cảnh báo lưu lượng tự động (Traffic Alerts) theo ngưỡng INFO / WARNING / CRITICAL.
 * 5. Luồng SSE thời gian thực có giới hạn kết nối, heartbeat và cơ chế chống nghẽn (backpressure).
 */
@Service
public class ServerTelemetryService implements DisposableBean {

    private static final int MAX_LOG_BUFFER_SIZE = 500;
    private static final int MAX_SAMPLE_BUFFER_SIZE = 5000;
    private static final int MAX_SSE_SUBSCRIBERS = 10;
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L; // 30 phút
    public static final int MAX_SANITIZED_URI_LENGTH = 512;
    public static final String URI_TRUNCATED_SUFFIX = "...[TRUNCATED]";

    private static final Set<String> SENSITIVE_PARAM_TOKENS = Set.of(
            "token", "accesstoken", "refreshtoken", "idtoken", "csrftoken", "resettoken",
            "password", "passwd", "pwd",
            "secret", "clientsecret", "appsecret", "hashsecret", "jwtsecret",
            "apikey", "privatekey", "secretkey", "accesskey", "clientkey",
            "authorization", "auth", "credential", "credentials", "jwt", "bearer"
    );

    private static final double DEFAULT_WARNING_RPS = 10.0;
    private static final double DEFAULT_CRITICAL_RPS = 25.0;
    private static final double WARNING_ERROR_RATE_PERCENT = 5.0;
    private static final double CRITICAL_ERROR_RATE_PERCENT = 15.0;
    private static final double WARNING_P95_LATENCY_MS = 1000.0;
    private static final double CRITICAL_P95_LATENCY_MS = 2500.0;

    private static final Pattern UUID_SEGMENT_PATTERN = Pattern.compile(
            "(?<=/)[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?=/|$)");
    private static final Pattern NUMERIC_SEGMENT_PATTERN = Pattern.compile("(?<=/)\\d+(?=/|$)");

    public record RequestTraceContext(
            String requestId,
            String routeTemplate,
            String requestType,
            String userAgent) {}

    private record RequestSample(
            long timestampMs,
            String method,
            String uri,
            String routeTemplate,
            int status,
            long durationMs,
            String requestType) {}

    private static final class TimeBucket {
        final long bucketEpochKey;
        final AtomicLong total = new AtomicLong(0);
        final AtomicLong success2xx = new AtomicLong(0);
        final AtomicLong clientError4xx = new AtomicLong(0);
        final AtomicLong serverError5xx = new AtomicLong(0);
        final AtomicLong rateLimited429 = new AtomicLong(0);
        final AtomicLong totalLatencyMs = new AtomicLong(0);
        final AtomicLong maxLatencyMs = new AtomicLong(0);
        final ConcurrentLinkedDeque<Long> latencySamples = new ConcurrentLinkedDeque<>();

        TimeBucket(long bucketEpochKey) {
            this.bucketEpochKey = bucketEpochKey;
        }

        void record(int status, long durationMs) {
            total.incrementAndGet();
            if (status >= 500) {
                serverError5xx.incrementAndGet();
            } else if (status >= 400) {
                clientError4xx.incrementAndGet();
                if (status == 429) {
                    rateLimited429.incrementAndGet();
                }
            } else {
                success2xx.incrementAndGet();
            }
            long safeDuration = Math.max(0L, durationMs);
            totalLatencyMs.addAndGet(safeDuration);
            maxLatencyMs.accumulateAndGet(safeDuration, Math::max);
            if (latencySamples.size() < 250) {
                latencySamples.addLast(safeDuration);
            }
        }
    }

    private final Deque<ServerRequestLogDto> logBuffer = new ConcurrentLinkedDeque<>();
    private final Deque<RequestSample> sampleBuffer = new ConcurrentLinkedDeque<>();
    private final Deque<Long> slidingTimestamps = new ConcurrentLinkedDeque<>();

    // Lưu trữ time-series: 1s bucket (tối đa 3600s = 1h) và 60s bucket (tối đa 1440m = 24h)
    private final ConcurrentHashMap<Long, TimeBucket> secondBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TimeBucket> minuteBuckets = new ConcurrentHashMap<>();

    private final AtomicLong totalRequestsCounter = new AtomicLong(0);
    private final AtomicInteger activeRequestsCounter = new AtomicInteger(0);
    private final AtomicInteger rateLimitedCounter = new AtomicInteger(0);

    private final ThreadLocal<RequestTraceContext> traceContextHolder = new ThreadLocal<>();
    private final List<SseEmitter> sseSubscribers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService sseScheduler;
    private final AtomicLong lastBroadcastTotalRequests = new AtomicLong(-1);
    private final AtomicInteger ticksSinceLastPush = new AtomicInteger(0);
    private volatile Supplier<WafStatusDto> wafStatusSupplier;

    public ServerTelemetryService() {
        this.sseScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "greytest-traffic-sse-broadcaster");
            t.setDaemon(true);
            return t;
        });
        this.sseScheduler.scheduleAtFixedRate(this::broadcastStreamTick, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        sseScheduler.shutdownNow();
        for (SseEmitter emitter : sseSubscribers) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
        sseSubscribers.clear();
    }

    public void setWafStatusSupplier(Supplier<WafStatusDto> wafStatusSupplier) {
        this.wafStatusSupplier = wafStatusSupplier;
    }

    public void setTraceContext(String requestId, String routeTemplate, String requestType, String userAgent) {
        traceContextHolder.set(new RequestTraceContext(requestId, routeTemplate, requestType, userAgent));
    }

    public void clearTraceContext() {
        traceContextHolder.remove();
    }

    public void incrementActive() {
        activeRequestsCounter.incrementAndGet();
    }

    public void decrementActive() {
        activeRequestsCounter.updateAndGet(curr -> Math.max(0, curr - 1));
    }

    public int getActiveRequests() {
        return activeRequestsCounter.get();
    }

    /**
     * Ghi nhận 1 HTTP request hoàn tất. Giữ nguyên chữ ký 6 tham số để tương thích hoàn toàn với các test hiện có.
     */
    public void recordRequest(String method, String uri, int status, long durationMs, String clientIp, String userEmail) {
        RequestTraceContext ctx = traceContextHolder.get();
        String requestId = (ctx != null && ctx.requestId() != null && !ctx.requestId().isBlank()) ? ctx.requestId() : null;
        String routeTemplate = (ctx != null && ctx.routeTemplate() != null && !ctx.routeTemplate().isBlank())
                ? ctx.routeTemplate()
                : normalizeEndpoint(uri);
        String requestType = (ctx != null && ctx.requestType() != null && !ctx.requestType().isBlank())
                ? ctx.requestType()
                : classifyRequestType(method, uri);
        String userAgent = ctx != null ? ctx.userAgent() : null;

        recordRequest(method, uri, routeTemplate, status, durationMs, clientIp, userEmail, requestId, requestType, userAgent);
    }

    /**
     * Ghi nhận chi tiết 1 HTTP request vào bộ đệm log, bộ đệm mẫu phân tích và các time-bucket.
     */
    public void recordRequest(
            String method,
            String uri,
            String routeTemplate,
            int status,
            long durationMs,
            String clientIp,
            String userEmail,
            String requestId,
            String requestType,
            String userAgent) {
        recordRequestAt(
                method,
                uri,
                routeTemplate,
                status,
                durationMs,
                clientIp,
                userEmail,
                requestId,
                requestType,
                userAgent,
                System.currentTimeMillis()
        );
    }

    void recordRequestAt(
            String method,
            String uri,
            String routeTemplate,
            int status,
            long durationMs,
            String clientIp,
            String userEmail,
            String requestId,
            String requestType,
            String userAgent,
            long now) {
        long seq = totalRequestsCounter.incrementAndGet();
        long safeDuration = Math.max(0L, durationMs);

        slidingTimestamps.addLast(now);
        long oneMinuteAgo = now - 60_000L;
        while (!slidingTimestamps.isEmpty() && slidingTimestamps.peekFirst() < oneMinuteAgo) {
            slidingTimestamps.pollFirst();
        }

        if (status == 429) {
            rateLimitedCounter.incrementAndGet();
        }

        String rawUri = uri != null ? uri : "/";
        String safeMethod = (method != null && !method.isBlank()) ? method.toUpperCase() : "GET";
        String safeUri = sanitizeUri(rawUri);
        String safeRoute = (routeTemplate != null && !routeTemplate.isBlank())
                ? normalizeEndpoint(routeTemplate)
                : normalizeEndpoint(rawUri);
        String safeIp = DdosProtectionService.normalizeClientIp(clientIp);
        String safeEmail = (userEmail != null && !userEmail.isBlank()) ? userEmail : "anonymous";
        String safeRequestId = (requestId != null && !requestId.isBlank()) ? requestId : ("req-" + seq);
        String safeType = (requestType != null && !requestType.isBlank())
                ? requestType
                : classifyRequestType(safeMethod, rawUri);
        String safeAgent = sanitizeUserAgent(userAgent);

        ServerRequestLogDto entry = new ServerRequestLogDto(
                safeRequestId,
                Instant.ofEpochMilli(now),
                safeMethod,
                safeUri,
                safeRoute,
                status,
                safeDuration,
                safeIp,
                safeEmail,
                safeType,
                safeAgent
        );

        logBuffer.addFirst(entry);
        while (logBuffer.size() > MAX_LOG_BUFFER_SIZE) {
            logBuffer.pollLast();
        }

        sampleBuffer.addFirst(new RequestSample(now, safeMethod, safeUri, safeRoute, status, safeDuration, safeType));
        while (sampleBuffer.size() > MAX_SAMPLE_BUFFER_SIZE) {
            sampleBuffer.pollLast();
        }

        long epochSecond = now / 1000L;
        secondBuckets.computeIfAbsent(epochSecond, TimeBucket::new).record(status, safeDuration);

        long epochMinute = now / 60_000L;
        minuteBuckets.computeIfAbsent(epochMinute, TimeBucket::new).record(status, safeDuration);

        // Dọn dẹp định kỳ mỗi 64 request để tránh rò rỉ bộ nhớ
        if ((seq & 63L) == 0L) {
            cleanupOldBuckets(now);
        }
    }

    /**
     * API tương thích ngược cho `/api/admin/system/request-logs`.
     */
    public ServerTelemetryDto getTelemetry(int limit, String search, Integer statusFilter) {
        long now = System.currentTimeMillis();
        long oneMinuteAgo = now - 60_000L;
        while (!slidingTimestamps.isEmpty() && slidingTimestamps.peekFirst() < oneMinuteAgo) {
            slidingTimestamps.pollFirst();
        }

        int rpm = slidingTimestamps.size();
        double currentRps = calculateCurrentRps(now);

        List<ServerRequestLogDto> filtered = new ArrayList<>();
        String searchLower = (search != null && !search.isBlank()) ? search.trim().toLowerCase() : null;
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LOG_BUFFER_SIZE);

        for (ServerRequestLogDto item : logBuffer) {
            if (statusFilter != null && item.status() != statusFilter) {
                continue;
            }
            if (searchLower != null && !matchesSearch(item, searchLower)) {
                continue;
            }
            filtered.add(item);
            if (filtered.size() >= safeLimit) {
                break;
            }
        }

        return new ServerTelemetryDto(
                totalRequestsCounter.get(),
                rpm,
                currentRps,
                activeRequestsCounter.get(),
                rateLimitedCounter.get(),
                filtered
        );
    }

    /**
     * Tính toán tổng quan KPI lưu lượng (Traffic Overview) cho cửa sổ thời gian được chọn.
     * - Tổng số request, nhóm mã trạng thái (2xx, 4xx, 5xx, 429), tổng/max latency được tính chính xác
     *   từ secondBuckets (cho cửa sổ <= 1h) hoặc minuteBuckets (cho cửa sổ 24h), không bị giới hạn bởi sampleBuffer.
     * - Các phân vị độ trễ (P50, P95, P99) được ước lượng từ sampleBuffer giới hạn 5.000 mẫu gần nhất.
     * - Phân biệt rõ trạng thái AVAILABLE và NO_DATA (không trả về latency 0 giả tạo khi không có request).
     */
    public TrafficOverviewDto getTrafficOverview(String windowParam, Long totalBlockedFromWaf) {
        return getTrafficOverviewAt(windowParam, totalBlockedFromWaf, System.currentTimeMillis());
    }

    TrafficOverviewDto getTrafficOverviewAt(String windowParam, Long totalBlockedFromWaf, long now) {
        cleanupOldBuckets(now);

        long windowSeconds = parseWindowSeconds(windowParam);
        String normalizedWindow = normalizeWindowLabel(windowParam);
        long windowMs = windowSeconds * 1000L;
        long windowStartMs = now - windowMs;

        long windowTotal = 0;
        long successCount = 0;
        long clientErrorCount = 0;
        long serverErrorCount = 0;
        long windowRejected = 0;
        long prevWindowTotal = 0;
        long prevLatencySum = 0;

        long latencySum = 0;
        long maxLatency = 0;
        List<Long> bucketFallbackLatencies = new ArrayList<>();

        // F-01: Tổng hợp số đếm chính xác theo cửa sổ từ một nguồn bucket duy nhất (không cộng trùng lặp)
        if (windowSeconds <= 3600L) {
            long currentSec = now / 1000L;
            long windowStartSec = currentSec - windowSeconds + 1;
            long prevWindowStartSec = windowStartSec - windowSeconds;

            for (Map.Entry<Long, TimeBucket> entry : secondBuckets.entrySet()) {
                long sec = entry.getKey();
                TimeBucket bucket = entry.getValue();
                if (sec >= windowStartSec && sec <= currentSec) {
                    windowTotal += bucket.total.get();
                    successCount += bucket.success2xx.get();
                    clientErrorCount += bucket.clientError4xx.get();
                    serverErrorCount += bucket.serverError5xx.get();
                    windowRejected += bucket.rateLimited429.get();
                    latencySum += bucket.totalLatencyMs.get();
                    maxLatency = Math.max(maxLatency, bucket.maxLatencyMs.get());
                    bucketFallbackLatencies.addAll(bucket.latencySamples);
                } else if (sec >= prevWindowStartSec && sec < windowStartSec) {
                    prevWindowTotal += bucket.total.get();
                    prevLatencySum += bucket.totalLatencyMs.get();
                }
            }
        } else {
            long currentMin = now / 60_000L;
            long windowMinutes = Math.max(1L, windowSeconds / 60L);
            long windowStartMin = currentMin - windowMinutes + 1;
            long prevWindowStartMin = windowStartMin - windowMinutes;

            for (Map.Entry<Long, TimeBucket> entry : minuteBuckets.entrySet()) {
                long min = entry.getKey();
                TimeBucket bucket = entry.getValue();
                if (min >= windowStartMin && min <= currentMin) {
                    windowTotal += bucket.total.get();
                    successCount += bucket.success2xx.get();
                    clientErrorCount += bucket.clientError4xx.get();
                    serverErrorCount += bucket.serverError5xx.get();
                    windowRejected += bucket.rateLimited429.get();
                    latencySum += bucket.totalLatencyMs.get();
                    maxLatency = Math.max(maxLatency, bucket.maxLatencyMs.get());
                    bucketFallbackLatencies.addAll(bucket.latencySamples);
                } else if (min >= prevWindowStartMin && min < windowStartMin) {
                    prevWindowTotal += bucket.total.get();
                    prevLatencySum += bucket.totalLatencyMs.get();
                }
            }
        }

        // Thu thập mẫu độ trễ từ sampleBuffer (tối đa 5.000 mẫu) để ước lượng phân vị P50/P95/P99
        List<Long> latencies = new ArrayList<>();
        for (RequestSample sample : sampleBuffer) {
            long ts = sample.timestampMs();
            if (ts > now) {
                continue;
            }
            if (ts >= windowStartMs) {
                latencies.add(sample.durationMs());
            } else {
                break;
            }
        }
        if (latencies.isEmpty() && !bucketFallbackLatencies.isEmpty()) {
            latencies = bucketFallbackLatencies;
        }

        long prevLatencyCount = prevWindowTotal;

        double currentRps = calculateCurrentRps(now);
        double averageRps = round2((double) windowTotal / (double) Math.max(1L, windowSeconds));
        double peakRps = calculatePeakRps(now, windowSeconds);

        Double avgLatency = null;
        Double p50Latency = null;
        Double p95Latency = null;
        Double p99Latency = null;
        Long maxLatencyBoxed = null;
        Double errorRatePercent = null;
        Double clientErrorRatePercent = null;
        Double serverErrorRatePercent = null;

        if (windowTotal > 0) {
            Collections.sort(latencies);
            avgLatency = round2((double) latencySum / windowTotal);
            p50Latency = round2(percentileSorted(latencies, 50));
            p95Latency = round2(percentileSorted(latencies, 95));
            p99Latency = round2(percentileSorted(latencies, 99));
            maxLatencyBoxed = maxLatency;

            long totalErrors = clientErrorCount + serverErrorCount;
            errorRatePercent = round2((totalErrors * 100.0) / windowTotal);
            clientErrorRatePercent = round2((clientErrorCount * 100.0) / windowTotal);
            serverErrorRatePercent = round2((serverErrorCount * 100.0) / windowTotal);
        }

        Double requestsChangePercent = prevWindowTotal > 0
                ? round2(((windowTotal - prevWindowTotal) * 100.0) / prevWindowTotal)
                : null;
        Double previousAverageRps = prevWindowTotal > 0
                ? round2((double) prevWindowTotal / (double) Math.max(1L, windowSeconds))
                : null;
        Double rpsChangePercent = (previousAverageRps != null && previousAverageRps > 0)
                ? round2(((averageRps - previousAverageRps) * 100.0) / previousAverageRps)
                : null;
        Double previousAvgLatencyMs = prevLatencyCount > 0
                ? round2((double) prevLatencySum / prevLatencyCount)
                : null;
        Double latencyChangePercent = (avgLatency != null && previousAvgLatencyMs != null && previousAvgLatencyMs > 0)
                ? round2(((avgLatency - previousAvgLatencyMs) * 100.0) / previousAvgLatencyMs)
                : null;

        long totalRejected = totalBlockedFromWaf != null
                ? Math.max(totalBlockedFromWaf, rateLimitedCounter.get())
                : rateLimitedCounter.get();

        String dataState = windowTotal > 0 ? "AVAILABLE" : "NO_DATA";

        return new TrafficOverviewDto(
                normalizedWindow,
                windowSeconds,
                Instant.ofEpochMilli(now),
                totalRequestsCounter.get(),
                windowTotal,
                currentRps,
                averageRps,
                peakRps,
                avgLatency,
                p50Latency,
                p95Latency,
                p99Latency,
                maxLatencyBoxed,
                errorRatePercent,
                clientErrorRatePercent,
                serverErrorRatePercent,
                successCount,
                clientErrorCount,
                serverErrorCount,
                activeRequestsCounter.get(),
                totalRejected,
                windowRejected,
                prevWindowTotal,
                requestsChangePercent,
                previousAverageRps,
                rpsChangePercent,
                previousAvgLatencyMs,
                latencyChangePercent,
                dataState
        );
    }

    /**
     * Xây dựng dữ liệu chuỗi thời gian (Time-series) cho biểu đồ lưu lượng thực tế.
     * Không tạo sóng giả. Các bucket không có request trả về rps = 0 và latency = null.
     */
    public TrafficTimeseriesDto getTrafficTimeseries(String windowParam) {
        long now = System.currentTimeMillis();
        cleanupOldBuckets(now);

        String normalizedWindow = normalizeWindowLabel(windowParam);
        long windowSeconds = parseWindowSeconds(windowParam);
        int bucketSeconds = resolveBucketSeconds(windowSeconds);
        int bucketCount = (int) Math.max(12, windowSeconds / bucketSeconds);

        long currentEpochSec = now / 1000L;
        long alignedEndSec = (currentEpochSec / bucketSeconds) * bucketSeconds;
        long startSec = alignedEndSec - (long) (bucketCount - 1) * bucketSeconds;

        List<TrafficTimeseriesPointDto> points = new ArrayList<>(bucketCount);
        boolean useMinuteStore = windowSeconds > 3600L;

        for (int i = 0; i < bucketCount; i++) {
            long bucketStartSec = startSec + (long) i * bucketSeconds;
            points.add(aggregateBucketRange(bucketStartSec, bucketSeconds, useMinuteStore));
        }

        return new TrafficTimeseriesDto(
                normalizedWindow,
                bucketSeconds,
                DEFAULT_WARNING_RPS,
                DEFAULT_CRITICAL_RPS,
                points
        );
    }

    /**
     * Truy vấn danh sách request gần nhất có hỗ trợ tìm kiếm, lọc theo method, status, statusGroup,
     * độ trễ tối thiểu, sắp xếp và phân trang.
     */
    public PageDto<ServerRequestLogDto> getRecentRequests(
            int page,
            int size,
            String search,
            String method,
            Integer status,
            String statusGroup,
            Long minLatencyMs,
            String sort,
            String direction) {
        String searchLower = (search != null && !search.isBlank()) ? search.trim().toLowerCase() : null;
        String methodUpper = (method != null && !method.isBlank() && !"ALL".equalsIgnoreCase(method))
                ? method.trim().toUpperCase()
                : null;
        String groupUpper = (statusGroup != null && !statusGroup.isBlank() && !"ALL".equalsIgnoreCase(statusGroup))
                ? statusGroup.trim().toUpperCase()
                : null;

        List<ServerRequestLogDto> matched = new ArrayList<>();
        for (ServerRequestLogDto item : logBuffer) {
            if (methodUpper != null && !methodUpper.equalsIgnoreCase(item.method())) {
                continue;
            }
            if (status != null && item.status() != status) {
                continue;
            }
            if (groupUpper != null && !matchesStatusGroup(item.status(), groupUpper)) {
                continue;
            }
            if (minLatencyMs != null && item.durationMs() < minLatencyMs) {
                continue;
            }
            if (searchLower != null && !matchesSearch(item, searchLower)) {
                continue;
            }
            matched.add(item);
        }

        Comparator<ServerRequestLogDto> comparator = buildLogComparator(sort, direction);
        matched.sort(comparator);

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        int totalElements = matched.size();
        int totalPages = totalElements == 0 ? 1 : (int) Math.ceil((double) totalElements / safeSize);
        int fromIndex = Math.min(safePage * safeSize, totalElements);
        int toIndex = Math.min(fromIndex + safeSize, totalElements);

        return new PageDto<>(matched.subList(fromIndex, toIndex), safePage, safeSize, totalElements, totalPages);
    }

    /**
     * Phân tích thống kê theo từng Endpoint (Top Volume, Top Latency, Top Error Rate)
     * và phân phối theo HTTP Method / HTTP Status Code.
     */
    public EndpointAnalyticsDto getEndpointAnalytics(String windowParam, int limit) {
        long now = System.currentTimeMillis();
        long windowSeconds = parseWindowSeconds(windowParam);
        String normalizedWindow = normalizeWindowLabel(windowParam);
        long windowStartMs = now - (windowSeconds * 1000L);
        int safeLimit = Math.min(Math.max(1, limit), 50);

        Map<String, List<RequestSample>> grouped = new LinkedHashMap<>();
        Map<String, Long> methodDist = new LinkedHashMap<>();
        Map<String, Long> statusDist = new LinkedHashMap<>();
        statusDist.put("2xx", 0L);
        statusDist.put("3xx", 0L);
        statusDist.put("4xx", 0L);
        statusDist.put("5xx", 0L);

        for (RequestSample sample : sampleBuffer) {
            if (sample.timestampMs() < windowStartMs) {
                break;
            }
            String key = sample.method() + " " + sample.routeTemplate();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(sample);

            methodDist.merge(sample.method(), 1L, Long::sum);
            String statusKey = sample.status() >= 500 ? "5xx"
                    : sample.status() >= 400 ? "4xx"
                    : sample.status() >= 300 ? "3xx" : "2xx";
            statusDist.merge(statusKey, 1L, Long::sum);
        }

        List<EndpointMetricDto> allMetrics = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<RequestSample>> entry : grouped.entrySet()) {
            List<RequestSample> samples = entry.getValue();
            if (samples.isEmpty()) continue;

            RequestSample first = samples.get(0);
            long count = samples.size();
            long sumLatency = 0;
            long maxLatency = 0;
            long err4xx = 0;
            long err5xx = 0;
            List<Long> latencies = new ArrayList<>((int) count);

            for (RequestSample s : samples) {
                sumLatency += s.durationMs();
                if (s.durationMs() > maxLatency) {
                    maxLatency = s.durationMs();
                }
                latencies.add(s.durationMs());
                if (s.status() >= 500) {
                    err5xx++;
                } else if (s.status() >= 400) {
                    err4xx++;
                }
            }

            Collections.sort(latencies);
            double avgLat = round2((double) sumLatency / count);
            double p95Lat = round2(percentileSorted(latencies, 95));
            long totalErr = err4xx + err5xx;
            double errRate = round2((totalErr * 100.0) / count);

            allMetrics.add(new EndpointMetricDto(
                    first.method(),
                    first.routeTemplate(),
                    count,
                    avgLat,
                    p95Lat,
                    maxLatency,
                    totalErr,
                    err4xx,
                    err5xx,
                    errRate
            ));
        }

        List<EndpointMetricDto> topByVolume = allMetrics.stream()
                .sorted(Comparator.comparingLong(EndpointMetricDto::requestCount).reversed()
                        .thenComparing(Comparator.comparingDouble(EndpointMetricDto::avgLatencyMs).reversed()))
                .limit(safeLimit)
                .toList();

        List<EndpointMetricDto> topByLatency = allMetrics.stream()
                .sorted(Comparator.comparingDouble(EndpointMetricDto::p95LatencyMs).reversed()
                        .thenComparing(Comparator.comparingDouble(EndpointMetricDto::avgLatencyMs).reversed()))
                .limit(safeLimit)
                .toList();

        List<EndpointMetricDto> topByErrorRate = allMetrics.stream()
                .filter(m -> m.errorCount() > 0)
                .sorted(Comparator.comparingDouble(EndpointMetricDto::errorRatePercent).reversed()
                        .thenComparing(Comparator.comparingLong(EndpointMetricDto::errorCount).reversed()))
                .limit(safeLimit)
                .toList();

        return new EndpointAnalyticsDto(
                normalizedWindow,
                topByVolume,
                topByLatency,
                topByErrorRate,
                methodDist,
                statusDist
        );
    }

    /**
     * Sinh danh sách cảnh báo khách quan dựa trên số liệu thực tế (RPS, Error Rate, P95 Latency, Rate Limit).
     * Không tự suy diễn hệ thống bị DDoS chỉ vì RPS tăng.
     */
    public List<TrafficAlertDto> getTrafficAlerts(String windowParam, WafStatusDto wafStatus) {
        Long wafBlocked = wafStatus != null ? wafStatus.totalBlockedRequests() : null;
        TrafficOverviewDto overview = getTrafficOverview(windowParam, wafBlocked);
        List<TrafficAlertDto> alerts = new ArrayList<>();
        Instant now = Instant.now();

        // 1. Cảnh báo RPS vượt ngưỡng
        if (overview.currentRps() >= DEFAULT_CRITICAL_RPS || overview.peakRps() >= DEFAULT_CRITICAL_RPS) {
            double measured = Math.max(overview.currentRps(), overview.peakRps());
            alerts.add(new TrafficAlertDto(
                    "alert-rps-critical",
                    "RPS_THRESHOLD_EXCEEDED",
                    "CRITICAL",
                    "Lưu lượng RPS vượt ngưỡng nghiêm trọng",
                    "Tần suất request đạt " + measured + " req/s, vượt ngưỡng cảnh báo cao (" + DEFAULT_CRITICAL_RPS + " req/s). Cần theo dõi tải máy chủ.",
                    measured + " req/s",
                    DEFAULT_CRITICAL_RPS + " req/s",
                    now
            ));
        } else if (overview.currentRps() >= DEFAULT_WARNING_RPS || overview.peakRps() >= DEFAULT_WARNING_RPS) {
            double measured = Math.max(overview.currentRps(), overview.peakRps());
            alerts.add(new TrafficAlertDto(
                    "alert-rps-warning",
                    "RPS_THRESHOLD_EXCEEDED",
                    "WARNING",
                    "Lưu lượng RPS tăng cao vượt ngưỡng cảnh báo",
                    "Tần suất request đạt " + measured + " req/s (ngưỡng cảnh báo: " + DEFAULT_WARNING_RPS + " req/s).",
                    measured + " req/s",
                    DEFAULT_WARNING_RPS + " req/s",
                    now
            ));
        }

        // 2. Cảnh báo Error Rate (ưu tiên 5xx riêng và tổng lỗi)
        if (overview.serverErrorRatePercent() != null && overview.serverErrorRatePercent() >= WARNING_ERROR_RATE_PERCENT) {
            String severity = overview.serverErrorRatePercent() >= CRITICAL_ERROR_RATE_PERCENT ? "CRITICAL" : "WARNING";
            alerts.add(new TrafficAlertDto(
                    "alert-5xx-rate",
                    "SERVER_ERROR_RATE_HIGH",
                    severity,
                    "Tỷ lệ lỗi máy chủ (HTTP 5xx) tăng cao",
                    "Ghi nhận " + overview.serverErrorCount() + " phản hồi 5xx (" + overview.serverErrorRatePercent() + "%) trong cửa sổ " + overview.window() + ".",
                    overview.serverErrorRatePercent() + "%",
                    WARNING_ERROR_RATE_PERCENT + "%",
                    now
            ));
        } else if (overview.errorRatePercent() != null && overview.errorRatePercent() >= CRITICAL_ERROR_RATE_PERCENT) {
            alerts.add(new TrafficAlertDto(
                    "alert-error-rate",
                    "ERROR_RATE_SPIKE",
                    "WARNING",
                    "Tỷ lệ phản hồi lỗi (4xx/5xx) vượt ngưỡng",
                    "Tổng tỷ lệ lỗi HTTP 4xx/5xx đạt " + overview.errorRatePercent() + "% (" + (overview.clientErrorCount() + overview.serverErrorCount()) + " requests).",
                    overview.errorRatePercent() + "%",
                    CRITICAL_ERROR_RATE_PERCENT + "%",
                    now
            ));
        }

        // 3. Cảnh báo độ trễ P95/P99
        if (overview.p95LatencyMs() != null && overview.p95LatencyMs() >= CRITICAL_P95_LATENCY_MS) {
            alerts.add(new TrafficAlertDto(
                    "alert-latency-critical",
                    "LATENCY_P95_HIGH",
                    "CRITICAL",
                    "Độ trễ phản hồi P95 vượt ngưỡng nghiêm trọng",
                    "Độ trễ P95 hiện tại là " + overview.p95LatencyMs() + " ms (P99: " + overview.p99LatencyMs() + " ms), vượt ngưỡng " + CRITICAL_P95_LATENCY_MS + " ms.",
                    overview.p95LatencyMs() + " ms",
                    CRITICAL_P95_LATENCY_MS + " ms",
                    now
            ));
        } else if (overview.p95LatencyMs() != null && overview.p95LatencyMs() >= WARNING_P95_LATENCY_MS) {
            alerts.add(new TrafficAlertDto(
                    "alert-latency-warning",
                    "LATENCY_P95_HIGH",
                    "WARNING",
                    "Độ trễ phản hồi P95 cao",
                    "Độ trễ P95 hiện tại đạt " + overview.p95LatencyMs() + " ms, vượt ngưỡng khuyến nghị " + WARNING_P95_LATENCY_MS + " ms.",
                    overview.p95LatencyMs() + " ms",
                    WARNING_P95_LATENCY_MS + " ms",
                    now
            ));
        }

        // 4. Cảnh báo khi có request bị chặn bởi Rate Limit / WAF
        if (overview.windowRejectedRequests() > 0 || (wafStatus != null && wafStatus.activeBlockedIpCount() > 0)) {
            int blockedIps = wafStatus != null ? wafStatus.activeBlockedIpCount() : 0;
            String severity = overview.windowRejectedRequests() >= 20 || blockedIps > 0 ? "WARNING" : "INFO";
            alerts.add(new TrafficAlertDto(
                    "alert-rate-limited",
                    "RATE_LIMIT_TRIGGERED",
                    severity,
                    "Có request bị giới hạn tần suất (HTTP 429)",
                    "Bộ giới hạn tần suất đã từ chối " + overview.windowRejectedRequests() + " request trong cửa sổ "
                            + overview.window() + " (Đang cách ly " + blockedIps + " IP).",
                    overview.windowRejectedRequests() + " req",
                    (wafStatus != null ? wafStatus.rateLimitPerMinute() : 100) + " req/min/IP",
                    now
            ));
        }

        // 5. Cảnh báo trạng thái chế độ Under Attack hoặc tắt Rate Limit
        if (wafStatus != null && !wafStatus.active()) {
            alerts.add(new TrafficAlertDto(
                    "alert-waf-disabled",
                    "RATE_LIMIT_DISABLED",
                    "WARNING",
                    "Bộ giới hạn tần suất (Rate Limiting) đang tắt",
                    "Hệ thống hiện không giới hạn tần suất request theo IP. Khuyến nghị bật lại khi vận hành thực tế.",
                    "DISABLED",
                    "ENABLED",
                    now
            ));
        } else if (wafStatus != null && wafStatus.underAttackMode()) {
            alerts.add(new TrafficAlertDto(
                    "alert-under-attack-mode",
                    "UNDER_ATTACK_MODE_ACTIVE",
                    "INFO",
                    "Chế độ giới hạn nghiêm ngặt (Under Attack Mode) đang bật",
                    "Ngưỡng giới hạn đang siết chặt ở mức " + wafStatus.rateLimitPerMinute() + " req/phút/IP.",
                    wafStatus.rateLimitPerMinute() + " req/min",
                    wafStatus.standardRateLimitPerMinute() + " req/min",
                    now
            ));
        }

        return alerts;
    }

    /**
     * Đăng ký nhận luồng sự kiện SSE thời gian thực cho trang Monitoring.
     * Giới hạn tối đa {@link #MAX_SSE_SUBSCRIBERS} kết nối đồng thời để bảo vệ tài nguyên server.
     */
    public SseEmitter subscribeStream(WafStatusDto initialWafStatus) {
        if (sseSubscribers.size() >= MAX_SSE_SUBSCRIBERS) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Đã đạt giới hạn số kết nối SSE giám sát đồng thời (" + MAX_SSE_SUBSCRIBERS + ").");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sseSubscribers.add(emitter);

        Runnable cleanup = () -> sseSubscribers.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(ex -> cleanup.run());

        try {
            TrafficStreamSnapshotDto snapshot = buildStreamSnapshot(initialWafStatus);
            emitter.send(SseEmitter.event()
                    .name("snapshot")
                    .id(String.valueOf(System.currentTimeMillis()))
                    .data(snapshot));
        } catch (Exception ex) {
            cleanup.run();
        }

        return emitter;
    }

    public int getActiveSseSubscriberCount() {
        return sseSubscribers.size();
    }

    /**
     * Chuẩn hóa URI thành route template để tránh phân mảnh thống kê theo ID hoặc UUID động.
     * Tách query string trước khi chuẩn hóa để không làm hỏng endpoint path của analytics.
     */
    public static String normalizeEndpoint(String rawUri) {
        if (rawUri == null || rawUri.isBlank()) {
            return "/";
        }
        String trimmed = rawUri.trim();
        int qIdx = trimmed.indexOf('?');
        String cleanPath = qIdx >= 0 ? trimmed.substring(0, qIdx) : trimmed;
        if (cleanPath.isBlank()) {
            return "/";
        }
        String normalized = UUID_SEGMENT_PATTERN.matcher(cleanPath).replaceAll("{uuid}");
        normalized = NUMERIC_SEGMENT_PATTERN.matcher(normalized).replaceAll("{id}");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return truncateUri(normalized);
    }

    /**
     * Phân loại request để phục vụ lọc và kiểm toán phạm vi đo lường.
     */
    public static String classifyRequestType(String method, String uri) {
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return "PREFLIGHT";
        }
        if (uri == null) {
            return "USER_API";
        }
        if (uri.equals("/api/admin/health") || uri.startsWith("/actuator/health")) {
            return "HEALTH_CHECK";
        }
        if (uri.startsWith("/api/admin")) {
            return "ADMIN_API";
        }
        return "USER_API";
    }

    /**
     * Loại bỏ các query parameter nhạy cảm (token, key, password, secret, authorization, bao gồm cả
     * nested parameter như nested[token], user[password] và tên parameter URL-encoded) khỏi URI trước khi lưu log.
     * Giới hạn độ dài URI tối đa {@link #MAX_SANITIZED_URI_LENGTH} (512 ký tự) kèm hậu tố nhận biết {@link #URI_TRUNCATED_SUFFIX}.
     */
    public static String sanitizeUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return "/";
        }
        String trimmed = uri.trim();
        int qIdx = trimmed.indexOf('?');
        if (qIdx < 0) {
            return truncateUri(trimmed);
        }
        String path = trimmed.substring(0, qIdx);
        String query = trimmed.substring(qIdx + 1);
        if (query.isBlank()) {
            return truncateUri(path);
        }
        String[] parts = query.split("&");
        StringBuilder safeQuery = new StringBuilder();
        for (String part : parts) {
            int eqIdx = part.indexOf('=');
            String rawParamName = (eqIdx >= 0 ? part.substring(0, eqIdx) : part).trim();
            if (isSensitiveParamName(rawParamName)) {
                if (safeQuery.length() > 0) safeQuery.append('&');
                safeQuery.append(rawParamName).append("=[REDACTED]");
            } else {
                if (safeQuery.length() > 0) safeQuery.append('&');
                safeQuery.append(part);
            }
        }
        return truncateUri(path + "?" + safeQuery);
    }

    private static boolean isSensitiveParamName(String rawParamName) {
        if (rawParamName == null || rawParamName.isBlank()) {
            return false;
        }
        String decoded = decodePercentEncodedSafe(rawParamName);
        String normalized = decoded
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT)
                .trim();

        // Kiểm tra theo từng cấp ngoặc vuông hoặc dấu chấm: ví dụ nested[token], user[api_key], auth.secret
        String[] segments = normalized.split("[\\[\\].]+");
        for (String seg : segments) {
            if (seg.isEmpty()) {
                continue;
            }
            if (seg.equals("key") || seg.endsWith("_key") || seg.endsWith("-key")) {
                return true;
            }
            String collapsed = seg.replace("_", "").replace("-", "");
            if (SENSITIVE_PARAM_TOKENS.contains(collapsed)) {
                return true;
            }
            String[] subTokens = seg.split("[^a-z0-9]+");
            for (String token : subTokens) {
                if (!token.isEmpty() && SENSITIVE_PARAM_TOKENS.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String decodePercentEncodedSafe(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value != null ? value : "";
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private static String truncateUri(String sanitized) {
        if (sanitized == null || sanitized.length() <= MAX_SANITIZED_URI_LENGTH) {
            return sanitized;
        }
        int keepLength = MAX_SANITIZED_URI_LENGTH - URI_TRUNCATED_SUFFIX.length();
        return sanitized.substring(0, keepLength) + URI_TRUNCATED_SUFFIX;
    }

    private static String sanitizeUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String trimmed = userAgent.trim();
        return trimmed.length() > 180 ? trimmed.substring(0, 180) : trimmed;
    }

    private void broadcastStreamTick() {
        if (sseSubscribers.isEmpty()) {
            return;
        }
        try {
            long currentTotal = totalRequestsCounter.get();
            long prevTotal = lastBroadcastTotalRequests.get();
            int ticks = ticksSinceLastPush.incrementAndGet();

            // Backpressure & tiết kiệm băng thông:
            // Nếu có request mới hoặc có kết nối đang active hoặc mỗi 2 giây thì gửi snapshot;
            // Ngược lại gửi heartbeat nhẹ để duy trì kết nối.
            boolean hasActivity = currentTotal != prevTotal || activeRequestsCounter.get() > 0 || (ticks % 2 == 0);
            if (!hasActivity) {
                sendHeartbeatToSubscribers();
                return;
            }

            lastBroadcastTotalRequests.set(currentTotal);
            ticksSinceLastPush.set(0);

            WafStatusDto waf = wafStatusSupplier != null ? wafStatusSupplier.get() : null;
            TrafficStreamSnapshotDto snapshot = buildStreamSnapshot(waf);

            for (SseEmitter emitter : sseSubscribers) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("snapshot")
                            .id(String.valueOf(snapshot.timestamp().toEpochMilli()))
                            .data(snapshot));
                } catch (Exception ex) {
                    sseSubscribers.remove(emitter);
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
            // Đảm bảo lỗi luồng SSE không bao giờ làm dừng scheduler
        }
    }

    private void sendHeartbeatToSubscribers() {
        long now = System.currentTimeMillis();
        for (SseEmitter emitter : sseSubscribers) {
            try {
                emitter.send(SseEmitter.event()
                        .name("heartbeat")
                        .id(String.valueOf(now))
                        .data(Map.of("timestamp", Instant.ofEpochMilli(now).toString(), "status", "ALIVE")));
            } catch (Exception ex) {
                sseSubscribers.remove(emitter);
            }
        }
    }

    private TrafficStreamSnapshotDto buildStreamSnapshot(WafStatusDto wafStatus) {
        WafStatusDto effectiveWaf = wafStatus != null
                ? wafStatus
                : (wafStatusSupplier != null ? wafStatusSupplier.get() : null);
        Long wafBlocked = effectiveWaf != null ? effectiveWaf.totalBlockedRequests() : null;

        TrafficOverviewDto overview = getTrafficOverview("1m", wafBlocked);
        long nowEpochSec = System.currentTimeMillis() / 1000L;
        TrafficTimeseriesPointDto latestPoint = aggregateBucketRange(nowEpochSec, 1, false);
        List<TrafficAlertDto> alerts = getTrafficAlerts("1m", effectiveWaf);

        List<ServerRequestLogDto> recent = new ArrayList<>(15);
        int count = 0;
        for (ServerRequestLogDto item : logBuffer) {
            recent.add(item);
            if (++count >= 15) break;
        }

        return new TrafficStreamSnapshotDto(
                Instant.now(),
                overview,
                latestPoint,
                alerts,
                effectiveWaf,
                recent
        );
    }

    /**
     * Tính Current RPS thực tế dựa trên cửa sổ 1 giây gần nhất (kết hợp trung bình 3 giây hoàn chỉnh gần nhất).
     * Tuyệt đối KHÔNG lấy tổng request chia cho 60 để giả làm Current RPS.
     */
    private double calculateCurrentRps(long nowMs) {
        long oneSecondAgo = nowMs - 1000L;
        long last1sCount = 0;
        long threeSecondsAgo = nowMs - 3000L;
        long last3sCount = 0;

        for (Long ts : slidingTimestamps) {
            if (ts >= oneSecondAgo) {
                last1sCount++;
            }
            if (ts >= threeSecondsAgo) {
                last3sCount++;
            }
        }
        if (last1sCount > 0) {
            return (double) last1sCount;
        }
        return round2(last3sCount / 3.0);
    }

    private double calculatePeakRps(long nowMs, long windowSeconds) {
        long currentSec = nowMs / 1000L;
        long lookbackSeconds = Math.min(windowSeconds, 3600L);
        long startSec = currentSec - lookbackSeconds + 1;
        long maxInOneSecond = 0;

        for (Map.Entry<Long, TimeBucket> entry : secondBuckets.entrySet()) {
            long sec = entry.getKey();
            if (sec >= startSec && sec <= currentSec) {
                long total = entry.getValue().total.get();
                if (total > maxInOneSecond) {
                    maxInOneSecond = total;
                }
            }
        }
        return (double) maxInOneSecond;
    }

    private TrafficTimeseriesPointDto aggregateBucketRange(long bucketStartSec, int bucketSeconds, boolean useMinuteStore) {
        long total = 0;
        long s2xx = 0;
        long e4xx = 0;
        long e5xx = 0;
        long r429 = 0;
        long latencySum = 0;
        List<Long> latencies = new ArrayList<>();

        if (!useMinuteStore) {
            for (int offset = 0; offset < bucketSeconds; offset++) {
                TimeBucket b = secondBuckets.get(bucketStartSec + offset);
                if (b != null) {
                    total += b.total.get();
                    s2xx += b.success2xx.get();
                    e4xx += b.clientError4xx.get();
                    e5xx += b.serverError5xx.get();
                    r429 += b.rateLimited429.get();
                    latencySum += b.totalLatencyMs.get();
                    latencies.addAll(b.latencySamples);
                }
            }
        } else {
            long startMin = bucketStartSec / 60L;
            long endMin = (bucketStartSec + bucketSeconds - 1) / 60L;
            for (long m = startMin; m <= endMin; m++) {
                TimeBucket b = minuteBuckets.get(m);
                if (b != null) {
                    total += b.total.get();
                    s2xx += b.success2xx.get();
                    e4xx += b.clientError4xx.get();
                    e5xx += b.serverError5xx.get();
                    r429 += b.rateLimited429.get();
                    latencySum += b.totalLatencyMs.get();
                    latencies.addAll(b.latencySamples);
                }
            }
        }

        double rps = round2((double) total / (double) Math.max(1, bucketSeconds));
        Double avgLatency = total > 0 ? round2((double) latencySum / total) : null;
        Double p95Latency = null;
        if (!latencies.isEmpty()) {
            Collections.sort(latencies);
            p95Latency = round2(percentileSorted(latencies, 95));
        }
        boolean isSpike = rps >= DEFAULT_WARNING_RPS;

        return new TrafficTimeseriesPointDto(
                Instant.ofEpochSecond(bucketStartSec),
                rps,
                total,
                s2xx,
                e4xx,
                e5xx,
                r429,
                avgLatency,
                p95Latency,
                isSpike
        );
    }

    private void cleanupOldBuckets(long nowMs) {
        long minSecond = (nowMs / 1000L) - 3660L; // Giữ 1 giờ + 1 phút đệm
        secondBuckets.keySet().removeIf(sec -> sec < minSecond);

        long minMinute = (nowMs / 60_000L) - 1500L; // Giữ 25 giờ
        minuteBuckets.keySet().removeIf(min -> min < minMinute);
    }

    public static long parseWindowSeconds(String window) {
        if (window == null || window.isBlank()) {
            return 60L;
        }
        return switch (window.trim().toLowerCase()) {
            case "1m", "60s" -> 60L;
            case "5m", "300s" -> 300L;
            case "15m", "900s" -> 900L;
            case "1h", "60m", "3600s" -> 3600L;
            case "24h", "1d" -> 86400L;
            default -> 60L;
        };
    }

    public static String normalizeWindowLabel(String window) {
        if (window == null || window.isBlank()) {
            return "1m";
        }
        return switch (window.trim().toLowerCase()) {
            case "5m", "300s" -> "5m";
            case "15m", "900s" -> "15m";
            case "1h", "60m", "3600s" -> "1h";
            case "24h", "1d" -> "24h";
            default -> "1m";
        };
    }

    private static int resolveBucketSeconds(long windowSeconds) {
        if (windowSeconds <= 60L) return 1;        // 60 điểm (mỗi 1s)
        if (windowSeconds <= 300L) return 5;       // 60 điểm (mỗi 5s)
        if (windowSeconds <= 900L) return 15;      // 60 điểm (mỗi 15s)
        if (windowSeconds <= 3600L) return 60;     // 60 điểm (mỗi 1m)
        return 900;                                // 96 điểm cho 24h (mỗi 15m)
    }

    private static boolean matchesStatusGroup(int status, String groupUpper) {
        return switch (groupUpper) {
            case "2XX", "SUCCESS" -> status >= 200 && status < 300;
            case "3XX", "REDIRECT" -> status >= 300 && status < 400;
            case "4XX", "CLIENT_ERROR" -> status >= 400 && status < 500;
            case "5XX", "SERVER_ERROR" -> status >= 500;
            case "ERROR", "ERRORS" -> status >= 400;
            case "429", "RATE_LIMITED" -> status == 429;
            default -> true;
        };
    }

    private static boolean matchesSearch(ServerRequestLogDto item, String searchLower) {
        return (item.uri() != null && item.uri().toLowerCase().contains(searchLower))
                || (item.routeTemplate() != null && item.routeTemplate().toLowerCase().contains(searchLower))
                || (item.method() != null && item.method().toLowerCase().contains(searchLower))
                || (item.clientIp() != null && item.clientIp().toLowerCase().contains(searchLower))
                || (item.userEmail() != null && item.userEmail().toLowerCase().contains(searchLower))
                || (item.id() != null && item.id().toLowerCase().contains(searchLower));
    }

    private static Comparator<ServerRequestLogDto> buildLogComparator(String sort, String direction) {
        boolean asc = "asc".equalsIgnoreCase(direction);
        String sortField = sort != null ? sort.trim().toLowerCase() : "timestamp";
        Comparator<ServerRequestLogDto> comp = switch (sortField) {
            case "duration", "durationms", "latency" -> Comparator.comparingLong(ServerRequestLogDto::durationMs);
            case "status" -> Comparator.comparingInt(ServerRequestLogDto::status);
            case "method" -> Comparator.comparing(ServerRequestLogDto::method, Comparator.nullsLast(String::compareToIgnoreCase));
            case "uri", "endpoint" -> Comparator.comparing(ServerRequestLogDto::uri, Comparator.nullsLast(String::compareToIgnoreCase));
            default -> Comparator.comparing(ServerRequestLogDto::timestamp, Comparator.nullsLast(Comparator.naturalOrder()));
        };
        return asc ? comp : comp.reversed();
    }

    private static double percentileSorted(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) {
            return 0.0;
        }
        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }
        double rank = (percentile / 100.0) * (sortedValues.size() - 1);
        int lowerIndex = (int) Math.floor(rank);
        int upperIndex = (int) Math.ceil(rank);
        if (lowerIndex == upperIndex) {
            return sortedValues.get(lowerIndex);
        }
        double weight = rank - lowerIndex;
        return sortedValues.get(lowerIndex) * (1.0 - weight) + sortedValues.get(upperIndex) * weight;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
