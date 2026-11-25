package com.greytest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.greytest.security.waf.InMemoryRateLimitStore;
import com.greytest.service.DdosProtectionService;

/**
 * Benchmark thuc nghiem dung luong bo nho (Workstream 5: Memory Bound Verification).
 */
@DisplayName("Phase 5 - Workstream 5: Memory Bound Verification & Empirical Benchmarks")
class Phase5MemoryBenchmarkTest {

    @Test
    @DisplayName("Thuc nghiem do luong bo nho tai cac moc 1000, 5000, 10000 va 20000 IP")
    void testMemoryFootprintUnderHighCardinalityIps() {
        String jvmVersion = System.getProperty("java.version");
        String jvmVendor = System.getProperty("java.vendor");
        String osName = System.getProperty("os.name");
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);

        // Giai doan Warm-up JVM JIT Compiler
        InMemoryRateLimitStore warmupStore = new InMemoryRateLimitStore();
        for (int w = 0; w < 1000; w++) {
            warmupStore.recordAndCheck("warmup-" + w, 60, 100);
        }
        warmupStore.clear();
        System.gc();

        int[] scaleCheckpoints = {1000, 5000, 10000, 20000};

        System.out.println("==========================================================================================");
        System.out.println("  PHASE 5 WAF MEMORY & HIGH-CARDINALITY IP BENCHMARK REPORT");
        System.out.println("==========================================================================================");
        System.out.printf("  JVM: %s (%s) | OS: %s | Max Heap: %d MB%n", jvmVersion, jvmVendor, osName, maxHeapMb);
        System.out.println("------------------------------------------------------------------------------------------");
        System.out.printf("%-12s | %-12s | %-14s | %-14s | %-12s | %-12s%n",
                "Scale (IPs)", "Req/IP", "Heap Before", "Heap After", "Delta (MB)", "Evict Time");
        System.out.println("------------------------------------------------------------------------------------------");

        for (int numIps : scaleCheckpoints) {
            InMemoryRateLimitStore store = new InMemoryRateLimitStore();

            System.gc();
            long heapBefore = getUsedHeapMb();
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < numIps; i++) {
                String ip = "10." + (i / 65536) + "." + ((i / 256) % 256) + "." + (i % 256);
                store.recordAndCheck(ip, 60, 100);
                store.recordAndCheck(ip, 60, 100);
                store.recordAndCheck(ip, 60, 100);
            }

            long heapAfter = getUsedHeapMb();
            long deltaMb = Math.max(0, heapAfter - heapBefore);

            long evictStart = System.nanoTime();
            store.evictIdleKeys(System.currentTimeMillis() + 1000L);
            long evictDurationUs = (System.nanoTime() - evictStart) / 1000L;

            System.out.printf("%-12d | %-12d | %-11d MB | %-11d MB | %-9d MB | %-10d us%n",
                    numIps, 3, heapBefore, heapAfter, deltaMb, evictDurationUs);

            assertEquals(0, store.getActiveTrackedKeyCount());
            assertThat(deltaMb).isLessThan(45L);
        }
        System.out.println("==========================================================================================");
    }

    @Test
    @DisplayName("Kiem tra Bounded Memory trong DdosProtectionService khi vuot nguong 20,000 IP")
    void testDdosProtectionServiceBoundedMemoryCapacity() {
        DdosProtectionService ddos = new DdosProtectionService();

        for (int i = 0; i < 21000; i++) {
            String ip = "198.51." + (i / 256) + "." + (i % 256);
            ddos.checkRequest(ip, "/api/projects");
        }

        ddos.cleanupIdleIpTracking();
        assertEquals(DdosProtectionService.CheckStatus.ALLOWED, ddos.checkRequest("127.0.0.1", "/api/projects").status());
    }

    @Test
    @DisplayName("Kiem tra kha nang cleanup an toan khi co nhieu request dong thoi (Thread-Safety & Concurrent Cleanup)")
    void testConcurrentCleanupAndRequests() throws Exception {
        DdosProtectionService ddos = new DdosProtectionService();
        int totalWorkers = 6;
        int requestsPerWorker = 800;
        ExecutorService executor = Executors.newFixedThreadPool(totalWorkers + 1);
        AtomicBoolean running = new AtomicBoolean(true);

        Future<?> cleanupTask = executor.submit(() -> {
            while (running.get()) {
                ddos.cleanupIdleIpTracking();
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int w = 0; w < totalWorkers; w++) {
            final int workerId = w;
            tasks.add(() -> {
                for (int i = 0; i < requestsPerWorker; i++) {
                    String ip = "192.168." + workerId + "." + (i % 256);
                    DdosProtectionService.CheckResult res = ddos.checkRequest(ip, "/api/projects");
                    if (res == null) return false;
                }
                return true;
            });
        }

        assertDoesNotThrow(() -> {
            List<Future<Boolean>> results = executor.invokeAll(tasks);
            for (Future<Boolean> f : results) {
                assertThat(f.get()).isTrue();
            }
        });

        running.set(false);
        cleanupTask.get();
        executor.shutdown();

        assertEquals(DdosProtectionService.CheckStatus.ALLOWED, ddos.checkRequest("127.0.0.1", "/api/projects").status());
    }

    private long getUsedHeapMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
    }
}
