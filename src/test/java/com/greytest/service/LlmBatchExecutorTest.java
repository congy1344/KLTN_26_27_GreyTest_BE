package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import com.greytest.service.agent.LlmResponseException;

class LlmBatchExecutorTest {

    private LlmBatchExecutor executor;

    @AfterEach
    void cleanUp() {
        GenerationJobContext.clear();
        LocaleContextHolder.resetLocaleContext();
        if (executor != null) executor.shutdown();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {2, 4})
    void limitsConcurrencyPreservesOrderAndPropagatesContext(int concurrency) {
        executor = new LlmBatchExecutor(concurrency);
        var barrier = new java.util.concurrent.CyclicBarrier(concurrency);
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
                barrier.await(2, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(30);
                return "item-" + input;
            } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException exception) {
                throw new IllegalStateException(exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                active.decrementAndGet();
            }
        }, (batchNumber, output) -> completedBatches.add(batchNumber));

        assertThat(outputs).containsExactly("item-3", "item-1", "item-2", "item-4");
        assertThat(maximumActive.get()).isEqualTo(concurrency);
        assertThat(completedBatches).containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    @Test
    void reportsFailedBatchAndWaitsForOtherStartedBatches() {
        executor = new LlmBatchExecutor(2);
        AtomicInteger finished = new AtomicInteger();
        var started = new java.util.concurrent.CountDownLatch(2);
        IllegalStateException original = new IllegalStateException("provider failed");

        RuntimeException failure = catchThrowableOfType(() ->
                executor.map(List.of(1, 2), input -> {
                    try {
                        started.countDown();
                        if (!started.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Workers did not start");
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
        assertThat(finished.get()).isEqualTo(1);
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

    @Test
    void retriesRetryableLlmFailureWithinTheSameBatch() {
        executor = new LlmBatchExecutor(1, 2, 0);
        AtomicInteger calls = new AtomicInteger();

        List<Integer> outputs = executor.map(List.of(1), input -> {
            if (calls.getAndIncrement() == 0) {
                throw new LlmResponseException("temporary provider failure", true);
            }
            return input;
        });

        assertThat(outputs).containsExactly(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    void doesNotRetryNonRetryableLlmFailure() {
        executor = new LlmBatchExecutor(1, 2, 0);
        AtomicInteger calls = new AtomicInteger();

        RuntimeException failure = catchThrowableOfType(() -> executor.map(List.of(1), input -> {
            calls.incrementAndGet();
            throw new LlmResponseException("source cannot be resolved");
        }), RuntimeException.class);

        assertThat(failure).isInstanceOf(LlmBatchExecutor.BatchExecutionException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotReinvokeLlmWhenBatchPersistenceCallbackFails() {
        executor = new LlmBatchExecutor(1, 2, 0);
        AtomicInteger calls = new AtomicInteger();

        RuntimeException failure = catchThrowableOfType(() -> executor.map(
                List.of(1),
                input -> {
                    calls.incrementAndGet();
                    return input;
                },
                (batch, output) -> {
                    throw new LlmResponseException("database temporarily unavailable", true);
                }), RuntimeException.class);

        assertThat(failure).isInstanceOf(LlmBatchExecutor.BatchExecutionException.class);
        assertThat(calls).hasValue(1);
    }
    @Test
    void serializesPersistenceCallbacksWhileAiWorkersRunInParallel() {
        executor = new LlmBatchExecutor(3);
        AtomicInteger activeCallbacks = new AtomicInteger();
        AtomicInteger peakCallbacks = new AtomicInteger();
        CountDownLatch workersStarted = new CountDownLatch(3);
        List<Integer> saved = new ArrayList<>();

        executor.map(List.of(1, 2, 3), input -> {
            workersStarted.countDown();
            try {
                assertThat(workersStarted.await(3, TimeUnit.SECONDS)).isTrue();
                return input;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }, (batchNumber, output) -> {
            peakCallbacks.accumulateAndGet(activeCallbacks.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
                saved.add(output);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                activeCallbacks.decrementAndGet();
            }
        });

        assertThat(peakCallbacks.get()).isEqualTo(1);
        assertThat(saved).containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void closedLoopAutoRequeuesFailedBatchAndContinuesWithoutDelayingOtherBatches() {
        executor = new LlmBatchExecutor(2, 2, 0);
        AtomicInteger item2Attempts = new AtomicInteger();
        List<Integer> executionOrder = java.util.Collections.synchronizedList(new ArrayList<>());

        List<String> outputs = executor.map(List.of(1, 2, 3), input -> {
            executionOrder.add(input);
            if (input == 2 && item2Attempts.getAndIncrement() == 0) {
                throw new LlmResponseException("temporary failure", true);
            }
            return "res-" + input;
        });

        // Batch 2 gặp lỗi lần 1 được đưa về đuôi hàng đợi; Batch 3 vẫn chạy và Batch 2 thử lại thành công
        assertThat(outputs).containsExactly("res-1", "res-2", "res-3");
        assertThat(executionOrder).contains(1, 2, 3, 2);
    }
}
