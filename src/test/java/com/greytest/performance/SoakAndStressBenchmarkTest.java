package com.greytest.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
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

/**
 * Phase 6.4 Workstream I & J: WAF RateLimit In-Memory Store Stress Testing & Soak Testing Microbenchmark.
 * Đo lường chính xác các chỉ số:
 * Workstream I (WAF RateLimit Store Stress Testing):
 * - Ramp-up các mức tải concurrency cao (1, 5, 10, 25, 50 worker threads).
 * - Xác định điểm suy giảm latency p50/p95/p99 và thông lượng tối đa của store.
 * Workstream J (WAF RateLimit Store Soak Testing):
 * - Chạy tải duy trì 10,000 vòng lặp liên tục trên ConcurrentHashMap sliding-window.
 * - So sánh Heap Memory, Thread Count, GC Collections và Pause time đầu/cuối kỳ để xác minh không có Memory/Thread/Resource Leak.
 */
@SpringBootTest
public class SoakAndStressBenchmarkTest {

    @Test
    @DisplayName("Workstream I: WAF Store Stress Testing với các mức concurrency tăng dần (1, 5, 10, 25, 50 luồng)")
    void benchmarkStressRampUpThroughput() throws Exception {
        int[] concurrencyLevels = { 1, 5, 10, 25, 50 };
        int operationsPerThread = 100;
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();

        System.out.println("=== BẮT ĐẦU STRESS TESTING RAMP-UP (WORKSTREAM I) ===");

        for (int concurrency : concurrencyLevels) {
            ExecutorService executor = Executors.newFixedThreadPool(concurrency);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger successOps = new AtomicInteger(0);

            long startTime = System.currentTimeMillis();
            List<Future<List<Long>>> futures = new ArrayList<>();

            for (int t = 0; t < concurrency; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    List<Long> latencies = new ArrayList<>();
                    String ip = "10.200." + threadId + ".1";
                    for (int i = 0; i < operationsPerThread; i++) {
                        long t0 = System.nanoTime();
                        store.recordAndCheck(ip, 60, 5000);
                        long t1 = System.nanoTime();
                        latencies.add((t1 - t0) / 1_000); // us
                        successOps.incrementAndGet();
                    }
                    return latencies;
                }));
            }

            startLatch.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));

            long totalDurationMs = Math.max(1, System.currentTimeMillis() - startTime);
            int totalOps = concurrency * operationsPerThread;
            double throughput = (totalOps * 1000.0) / totalDurationMs;

            List<Long> allLatencies = new ArrayList<>();
            for (var f : futures) {
                allLatencies.addAll(f.get());
            }
            Collections.sort(allLatencies);

            long p50 = allLatencies.get(allLatencies.size() / 2);
            long p95 = allLatencies.get((int) (allLatencies.size() * 0.95));
            long p99 = allLatencies.get((int) (allLatencies.size() * 0.99));

            System.out.printf("[Stress Level Concurrency=%2d] %5d ops in %4d ms -> Throughput: %8.0f ops/s | p50=%6.2f us | p95=%6.2f us | p99=%6.2f us%n",
                    concurrency, totalOps, totalDurationMs, throughput, (double) p50, (double) p95, (double) p99);

            assertThat(successOps.get()).isEqualTo(totalOps);
        }
    }

    @Test
    @DisplayName("Workstream J: Soak Testing tải liên tục - Đo lường Heap Memory, Thread Count, GC Pause Time")
    void benchmarkSoakTestingResourceStability() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        // 1. Snapshot ban đầu
        System.gc();
        long initialHeapUsedMb = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
        int initialThreadCount = threadBean.getThreadCount();
        long initialGcCount = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        long initialGcTimeMs = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();

        System.out.printf("[Soak Test Start] Initial Heap: %d MB | Active Threads: %d | GC Collections: %d | GC Time: %d ms%n",
                initialHeapUsedMb, initialThreadCount, initialGcCount, initialGcTimeMs);

        // 2. Chạy tải duy trì 10,000 operations
        int iterations = 10_000;
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();

        long startTime = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            store.recordAndCheck("ip.soak." + (i % 500), 60, 100);
            if (i % 2500 == 0) {
                // Kiểm tra nhẹ tiến độ
                long currentUsed = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
                assertThat(currentUsed).isLessThan(2048); // Không được tràn vượt 2GB
            }
        }
        long duration = System.currentTimeMillis() - startTime;

        // 3. Snapshot kết thúc
        System.gc();
        long finalHeapUsedMb = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
        int finalThreadCount = threadBean.getThreadCount();
        long finalGcCount = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        long finalGcTimeMs = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();

        long heapDeltaMb = finalHeapUsedMb - initialHeapUsedMb;
        int threadDelta = finalThreadCount - initialThreadCount;
        long gcDeltaCount = finalGcCount - initialGcCount;
        long gcDeltaTimeMs = finalGcTimeMs - initialGcTimeMs;

        System.out.printf("[Soak Test Finish] 10,000 ops in %d ms%n" +
                        "  ├─ Final Heap: %d MB (Delta: %+d MB)%n" +
                        "  ├─ Final Threads: %d (Delta: %+d threads)%n" +
                        "  └─ GC Delta: %d collections, %d ms pause time%n",
                duration, finalHeapUsedMb, heapDeltaMb, finalThreadCount, threadDelta, gcDeltaCount, gcDeltaTimeMs);

        // Xác minh không có Memory Leak nghiêm trọng: Delta Heap < 100MB sau GC
        assertThat(heapDeltaMb).isLessThan(100);
        // Xác minh không có Thread Leak
        assertThat(threadDelta).isLessThanOrEqualTo(3);
    }
}
