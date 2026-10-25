package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class GenerationPerformanceTest {
    @Test
    void comparesSequentialAndParallelWaitingWithoutCallingProvider() {
        long sequential = runBatches(1);
        long parallel = runBatches(2);
        long fast = runBatches(4);
        LoggerFactory.getLogger(getClass()).info(
                "Simulated LLM benchmark: 8 batches x 100ms, concurrency1={}ms, concurrency2={}ms, concurrency4={}ms",
                sequential, parallel, fast);
    }

    private long runBatches(int concurrency) {
        var executor = new LlmBatchExecutor(concurrency, 0, 0);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<Integer> inputs = IntStream.range(0, 8).boxed().toList();
        long start = System.nanoTime();
        try {
            var results = executor.map(inputs, input -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(100);
                    return input;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                } finally {
                    active.decrementAndGet();
                }
            });
            assertThat(results).isEqualTo(inputs);
            assertThat(maximum.get()).isBetween(1, concurrency);
            return (System.nanoTime() - start) / 1_000_000;
        } finally {
            executor.shutdown();
        }
    }
}
