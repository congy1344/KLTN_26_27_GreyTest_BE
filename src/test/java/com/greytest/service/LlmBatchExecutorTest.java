package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

class LlmBatchExecutorTest {

    private LlmBatchExecutor executor;

    @AfterEach
    void cleanUp() {
        GenerationJobContext.clear();
        LocaleContextHolder.resetLocaleContext();
        if (executor != null) executor.shutdown();
    }

    @Test
    void limitsConcurrencyPreservesOrderAndPropagatesContext() {
        executor = new LlmBatchExecutor(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        List<Integer> completedBatches = Collections.synchronizedList(new ArrayList<>());
        GenerationJobContext.bind(42L, ignored -> { });
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        List<String> outputs = executor.map(List.of(3, 1, 2, 4), input -> {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            try {
                assertThat(GenerationJobContext.actorUserId()).isEqualTo(42L);
                assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.ENGLISH);
                Thread.sleep(30);
                return "item-" + input;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                active.decrementAndGet();
            }
        }, (batchNumber, output) -> completedBatches.add(batchNumber));

        assertThat(outputs).containsExactly("item-3", "item-1", "item-2", "item-4");
        assertThat(maximumActive.get()).isEqualTo(2);
        assertThat(completedBatches).containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    @Test
    void reportsFailedBatchAndWaitsForOtherStartedBatches() {
        executor = new LlmBatchExecutor(2);
        AtomicInteger finished = new AtomicInteger();
        IllegalStateException original = new IllegalStateException("provider failed");

        RuntimeException failure = catchThrowableOfType(() ->
                executor.map(List.of(1, 2, 3), input -> {
                    try {
                        if (input == 2) throw original;
                        Thread.sleep(20);
                        finished.incrementAndGet();
                        return input;
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }), RuntimeException.class);

        assertThat(LlmBatchExecutor.failedBatch(failure, 0)).isEqualTo(2);
        assertThat(LlmBatchExecutor.originalFailure(failure)).isSameAs(original);
        assertThat(finished.get()).isEqualTo(2);
    }

    @Test
    void reportsFailedBatchWhenConcurrencyIsOne() {
        executor = new LlmBatchExecutor(1);
        IllegalArgumentException original = new IllegalArgumentException("invalid response");

        RuntimeException failure = catchThrowableOfType(() ->
                executor.map(List.of(1, 2, 3), input -> {
                    if (input == 2) throw original;
                    return input;
                }), RuntimeException.class);

        assertThat(LlmBatchExecutor.failedBatch(failure, 0)).isEqualTo(2);
        assertThat(LlmBatchExecutor.originalFailure(failure)).isSameAs(original);
    }
}
