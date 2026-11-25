package com.greytest.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.greytest.security.waf.InMemoryRateLimitStore;
import com.greytest.security.waf.RedisRateLimitStore;

/**
 * Phase 6.4 Workstream A3 & F: Redis & WAF Layer 7 Performance Benchmark.
 * Đo lường chính xác các chỉ số:
 * F1. Redis Lua Script Execution Latency & Operations Per Second.
 * F2. Memory Growth & Key Eviction Latency (InMemory bounded store at 20,000 keys).
 * F3. Concurrent IP Tracking & Block/Unblock Operations Throughput.
 * F4. Fallback Transition Latency khi Redis gặp sự cố.
 */
@SpringBootTest
public class RedisAndWafPerformanceBenchmarkTest {

    @Test
    @DisplayName("Workstream F1: Benchmark Redis Lua Script Latency & Throughput (Single & Multi-Threaded)")
    void benchmarkRedisRateLimitLuaThroughput() throws Exception {
        InMemoryRateLimitStore memoryStore = new InMemoryRateLimitStore();
        RedisRateLimitStore redisStore = new RedisRateLimitStore(memoryStore, true, "localhost", 6379, 2000);

        try {
            assumeTrue(redisStore.isRedisActive(), "Skip live Redis benchmark when Redis is not active on localhost:6379");

            // 1. Đo đơn luồng: 1000 requests liên tiếp
            int iterations = 1000;
            List<Long> latencies = new ArrayList<>();
            String testIp = "192.168.10.1";
            redisStore.unblock(testIp);

            for (int i = 0; i < iterations; i++) {
                long t0 = System.nanoTime();
                redisStore.recordAndCheck(testIp, 60, 5000);
                long t1 = System.nanoTime();
                latencies.add((t1 - t0) / 1_000); // us
            }

            Collections.sort(latencies);
            long p50 = latencies.get(iterations / 2);
            long p95 = latencies.get((int) (iterations * 0.95));
            long p99 = latencies.get((int) (iterations * 0.99));

            double rpsSingle = (iterations / ((double) latencies.stream().mapToLong(Long::longValue).sum() / 1_000_000.0));

            System.out.printf("[Redis RateLimit Benchmark] Single Thread 1000 ops - p50=%.2f ms, p95=%.2f ms, p99=%.2f ms (Throughput: %.0f ops/sec)%n",
                    p50 / 1000.0, p95 / 1000.0, p99 / 1000.0, rpsSingle);

            // 2. Đo đa luồng đồng thời: 8 threads x 250 requests = 2000 requests
            int threadCount = 8;
            int opsPerThread = 250;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger totalAllowed = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            java.util.concurrent.CopyOnWriteArrayList<Throwable> workerErrors = new java.util.concurrent.CopyOnWriteArrayList<>();

            long startTime = System.currentTimeMillis();

            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                final String ip = "192.168.10." + (t + 10);
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            if (redisStore.recordAndCheck(ip, 60, 1000)) {
                                totalAllowed.incrementAndGet();
                            }
                        }
                    } catch (Throwable ex) {
                        errorCount.incrementAndGet();
                        workerErrors.add(ex);
                    }
                }));
            }

            startLatch.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            long totalDurationMs = Math.max(1, System.currentTimeMillis() - startTime);
            double totalThroughput = (threadCount * opsPerThread * 1000.0) / totalDurationMs;

            System.out.printf("[Redis RateLimit Concurrency] 8 Threads x 250 ops = %d requests in %d ms (Throughput: %.0f ops/sec)%n",
                    threadCount * opsPerThread, totalDurationMs, totalThroughput);

            assertThat(workerErrors).as("Không có ngoại lệ trong các worker threads đo Redis").isEmpty();
            assertThat(errorCount.get()).isEqualTo(0);
            assertThat(totalAllowed.get()).isGreaterThan(0);
            assertThat(p50).isLessThan(5000); // p50 < 5ms
            assertThat(totalThroughput).isGreaterThan(100.0); // > 100 ops/sec
        } finally {
            redisStore.close();
        }
    }

    @Test
    @DisplayName("Workstream F2: Benchmark InMemoryRateLimitStore Bounded Memory & Eviction Latency")
    void benchmarkInMemoryStoreBoundedMemoryEviction() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();

        // Nạp 20,000 keys vào memory store để đẩy store tới ngưỡng trần MAX_TRACKED_KEYS
        int keyCount = 20_000;
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < keyCount; i++) {
            store.recordAndCheck("ip.test." + i, 60, 10);
        }
        long fillDuration = System.currentTimeMillis() - t0;

        System.out.printf("[InMemory Store Benchmark] Filled 20,000 keys in %d ms (Avg: %.2f us/key)%n",
                fillDuration, (fillDuration * 1000.0) / keyCount);

        // Đo độ trễ khi ghi thêm 1,000 keys mới (kích hoạt chính sách loại bỏ key cũ nhất)
        int extraKeys = 1000;
        List<Long> evictionLatencies = new ArrayList<>();

        for (int i = 0; i < extraKeys; i++) {
            long start = System.nanoTime();
            store.recordAndCheck("ip.overflow." + i, 60, 10);
            long end = System.nanoTime();
            evictionLatencies.add((end - start) / 1_000); // us
        }

        Collections.sort(evictionLatencies);
        long p50 = evictionLatencies.get(extraKeys / 2);
        long p95 = evictionLatencies.get((int) (extraKeys * 0.95));

        System.out.printf("[InMemory Eviction Benchmark] 1000 overflow inserts - p50=%.3f ms, p95=%.3f ms (Tracked Keys: %d)%n",
                p50 / 1000.0, p95 / 1000.0, store.getActiveTrackedKeyCount());

        assertThat(p50).isLessThan(1000); // p50 < 1ms
        assertThat(store.getActiveTrackedKeyCount())
                .as("Dung lượng tracked keys không được vượt quá giới hạn an toàn bộ nhớ MAX_TRACKED_KEYS")
                .isLessThanOrEqualTo(InMemoryRateLimitStore.MAX_TRACKED_KEYS);
    }

    @Test
    @DisplayName("Workstream F3: Benchmark tốc độ Block/Unblock IP đồng thời trên Redis")
    void benchmarkRedisBlockUnblockThroughput() {
        InMemoryRateLimitStore memoryStore = new InMemoryRateLimitStore();
        RedisRateLimitStore redisStore = new RedisRateLimitStore(memoryStore, true, "localhost", 6379, 2000);

        try {
            assumeTrue(redisStore.isRedisActive(), "Skip live Redis benchmark when Redis is not active on localhost:6379");
            int count = 500;
            long t0 = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                String ip = "172.16.0." + (i % 250);
                redisStore.block(ip, "Benchmark Block", 60);
                boolean blocked = redisStore.isBlocked(ip);
                assertTrue(blocked);
                redisStore.unblock(ip);
            }
            long duration = System.currentTimeMillis() - t0;
            double opsPerSec = (count * 3 * 1000.0) / Math.max(1, duration);

            System.out.printf("[Redis Block/Unblock Benchmark] 500 cycles (block + check + unblock = 1500 ops) in %d ms (Throughput: %.0f ops/sec)%n",
                    duration, opsPerSec);

            assertThat(opsPerSec).isGreaterThan(50.0);
        } finally {
            redisStore.close();
        }
    }
}
