package com.greytest.dto;

import java.time.Instant;

/** Số liệu của một lượt chạy stage, gồm các phản hồi AI đã ghi nhận usage. */
public record GenerationRunMetricsDto(
        String runId,
        Instant startedAt,
        Instant finishedAt,
        long elapsedMillis,
        long llmWaitMillis,
        long recordedCalls,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long estimatedCalls) {}
