package com.greytest.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.greytest.dto.GenerationRunMetricsDto;

/** Đo thời gian thực từ lúc nhận lượt sinh ở 0% đến khi công bố kết quả hoàn tất. */
final class GenerationRunMetrics {
    private final String runId = UUID.randomUUID().toString();
    private final Clock clock;
    private final Instant startedAt;
    private final long startedNanos = System.nanoTime();
    private final GenerationRunMetricsDto previous;
    private Supplier<Map<String, Object>> metrics;
    private Instant finishedAt;
    private long elapsedMillis;

    GenerationRunMetrics(Clock clock, Supplier<Map<String, Object>> metrics) {
        this(clock, metrics, null);
    }

    /** previous là tổng của các lần chạy trước chưa hoàn tất; thời gian dừng giữa hai lần không được tính. */
    GenerationRunMetrics(Clock clock, Supplier<Map<String, Object>> metrics, GenerationRunMetricsDto previous) {
        this.clock = clock;
        this.previous = previous;
        this.startedAt = previous == null ? clock.instant() : previous.startedAt();
        this.metrics = metrics;
    }

    synchronized void finish() {
        if (finishedAt != null) return;
        elapsedMillis = elapsedMillis();
        finishedAt = clock.instant();
    }

    synchronized boolean isFinished() { return finishedAt != null; }

    synchronized void setMetrics(Supplier<Map<String, Object>> metrics) { this.metrics = metrics; }

    synchronized GenerationRunMetricsDto snapshot() {
        Map<String, Object> values = metrics.get();
        boolean carried = previous != null;
        return new GenerationRunMetricsDto(runId, startedAt, finishedAt,
                (finishedAt == null ? elapsedMillis() : elapsedMillis) + (carried ? previous.elapsedMillis() : 0),
                TimeUnit.NANOSECONDS.toMillis(value(values, "llmHttpWaitNanos"))
                        + (carried ? previous.llmWaitMillis() : 0),
                value(values, "llmRecordedCalls") + (carried ? previous.recordedCalls() : 0),
                value(values, "llmInputTokens") + (carried ? previous.inputTokens() : 0),
                value(values, "llmOutputTokens") + (carried ? previous.outputTokens() : 0),
                value(values, "llmTotalTokens") + (carried ? previous.totalTokens() : 0),
                value(values, "llmEstimatedCalls") + (carried ? previous.estimatedCalls() : 0));
    }

    private long elapsedMillis() { return (System.nanoTime() - startedNanos) / 1_000_000; }

    private static long value(Map<String, Object> values, String key) {
        return ((Number) values.getOrDefault(key, 0L)).longValue();
    }
}
