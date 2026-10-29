package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GenerationJobContextTest {
    @Test
    void scopedValueRestoresOuterLeaseAfterNestedFailure() {
        try (var scope = GenerationJobContext.openScope(null)) {
            GenerationJobContext.memoize("lease", () -> "outer");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> GenerationJobContext.withValue("lease", "inner", () -> {
                assertThat(GenerationJobContext.<String>scopedValue("lease")).isEqualTo("inner");
                throw new IllegalStateException("failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(GenerationJobContext.<String>scopedValue("lease")).isEqualTo("outer");
        }
        assertThat(GenerationJobContext.<String>scopedValue("lease")).isNull();
    }
    @Test
    void nestedScopeAndWorkersShareMetricsWithoutLeakingState() {
        try (var outer = GenerationJobContext.openScope(42L)) {
            GenerationJobContext.setSourceUpdateId(99L);
            GenerationJobContext.memoize("source", () -> "outer");
            try (var nested = GenerationJobContext.openScope(7L)) {
                assertThat(GenerationJobContext.actorUserId()).isEqualTo(42L);
                assertThat(GenerationJobContext.sourceUpdateId()).isEqualTo(99L);
                assertThat(GenerationJobContext.wrap(() -> {
                    GenerationJobContext.increment("providerCalls");
                    GenerationJobContext.recordDuration("stage", 123L);
                    return GenerationJobContext.memoize("source", () -> "worker");
                }).get()).isEqualTo("outer");
            }
            assertThat(GenerationJobContext.actorUserId()).isEqualTo(42L);
            assertThat(GenerationJobContext.metrics()).containsEntry("providerCalls", 1L).containsEntry("stageNanos", 123L);
        }
        assertThat(GenerationJobContext.actorUserId()).isNull();
        assertThat(GenerationJobContext.sourceUpdateId()).isNull();
        assertThat(GenerationJobContext.metrics()).isEmpty();
    }
    @AfterEach
    void clear() {
        GenerationJobContext.clear();
    }

    @Test
    void sharesJobInputsAcrossWorkersAndDropsThemBetweenJobs() {
        AtomicInteger loads = new AtomicInteger();
        LlmBatchExecutor executor = new LlmBatchExecutor(2);
        GenerationJobContext.bind(42L, ignored -> { });
        try {
            assertThat(executor.map(List.of(1, 2, 3, 4), ignored ->
                    GenerationJobContext.memoize("source", loads::incrementAndGet)))
                    .containsExactly(1, 1, 1, 1);
            assertThat(GenerationJobContext.memoize("source", loads::incrementAndGet)).isEqualTo(1);
            GenerationJobContext.clear();
            GenerationJobContext.bind(42L, ignored -> { });
            assertThat(GenerationJobContext.memoize("source", loads::incrementAndGet)).isEqualTo(2);
            GenerationJobContext.clear();
            assertThat(GenerationJobContext.memoize("source", loads::incrementAndGet)).isEqualTo(3);
            assertThat(GenerationJobContext.memoize("source", loads::incrementAndGet)).isEqualTo(4);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void failedWorkerCannotLeakInputsOrActorIntoNextJob() {
        var task = GenerationJobContext.wrap(() -> {
            GenerationJobContext.memoize("source", () -> "old");
            throw new IllegalStateException("failed");
        });
        org.assertj.core.api.Assertions.assertThatThrownBy(task::get).isInstanceOf(IllegalStateException.class);
        assertThat(GenerationJobContext.actorUserId()).isNull();
        assertThat(GenerationJobContext.memoize("source", () -> "fresh")).isEqualTo("fresh");
    }

    @Test
    void parallelBatchesHaveIndependentBudgetsAndCannotLeakThemToNextWork() {
        var executor = new LlmBatchExecutor(2, 0, 0);
        try {
            assertThat(executor.map(List.of(1, 2, 3, 4), input -> {
                for (int call = 0; call < 3; call++) GenerationJobContext.reserveLlmCall();
                org.assertj.core.api.Assertions.assertThatThrownBy(GenerationJobContext::reserveLlmCall)
                        .isInstanceOf(com.greytest.service.agent.LlmResponseException.class);
                return input;
            })).containsExactly(1, 2, 3, 4);
            assertThat(executor.map(List.of(5), input -> {
                GenerationJobContext.reserveLlmCall();
                return input;
            })).containsExactly(5);
            assertThat(GenerationJobContext.llmCallBudgetExhausted()).isFalse();
        } finally {
            executor.shutdown();
        }
    }
}
