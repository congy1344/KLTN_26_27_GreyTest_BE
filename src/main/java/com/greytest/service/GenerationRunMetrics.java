package com.greytest.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.greytest.dto.GenerationRunMetricsDto;

/** Đo thời gian thực từ lúc nhận lượt sinh ở 0% đến khi công bố kết quả hoàn tất. */
final class GenerationRunMetrics {
    private final String runId = UUID.randomUUID().toString();
    private final Clock clock;
    private final Instant startedAt;
    private final long startedNanos = System.nanoTime();
    private Supplier<Map<String, Object>> metrics;
    private Instant finishedAt;
    private long elapsedMillis;

    GenerationRunMetrics(Clock clock, Supplier<Map<String, Object>> metrics) {
        this.clock = clock;
        this.startedAt = clock.instant();
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
        return new GenerationRunMetricsDto(runId, startedAt, finishedAt,
                finishedAt == null ? elapsedMillis() : elapsedMillis,
                value(values, "llmRecordedCalls"), value(values, "llmInputTokens"),
                value(values, "llmOutputTokens"), value(values, "llmTotalTokens"),
                value(values, "llmEstimatedCalls"));
    }

    private long elapsedMillis() { return (System.nanoTime() - startedNanos) / 1_000_000; }

    private static long value(Map<String, Object> values, String key) {
        return ((Number) values.getOrDefault(key, 0L)).longValue();
    }
}
