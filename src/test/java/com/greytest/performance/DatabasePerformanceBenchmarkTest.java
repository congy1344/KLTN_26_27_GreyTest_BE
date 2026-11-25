package com.greytest.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Phase 6.4 Workstream A2 & E: PostgreSQL Performance & Query Profiling Benchmark.
 * Đo lường chính xác các chỉ số:
 * E1. Connection Pool Utilization (Active, Idle, Max Connections).
 * E2. Query Latency & EXPLAIN ANALYZE trên các bảng cốt lõi (project, business_rule, test_case).
 * E3. Transaction Duration & Concurrency Contention.
 * E4. Đánh giá phát hiện N+1 Query và hiệu năng phân trang.
 */
@SpringBootTest
public class DatabasePerformanceBenchmarkTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long testUserId;
    private Long testProjectId = 888101L;

    @BeforeEach
    void setupBenchmarkFixtures() {
        var users = jdbcTemplate.queryForList("SELECT id FROM auth_user ORDER BY id ASC LIMIT 1", Long.class);
        if (users.isEmpty()) {
            jdbcTemplate.update("INSERT INTO auth_user (email, password_hash, role, created_at, updated_at) " +
                    "VALUES ('perf_user@greytest.com', 'dummy_hash', 'USER', NOW(), NOW())");
            testUserId = jdbcTemplate.queryForObject("SELECT id FROM auth_user WHERE email = 'perf_user@greytest.com'", Long.class);
        } else {
            testUserId = users.get(0);
        }

        // Tạo fixture Project nếu chưa có
        jdbcTemplate.update("DELETE FROM test_case WHERE test_plan_id IN (SELECT id FROM test_plan WHERE project_id = ?)", testProjectId);
        jdbcTemplate.update("DELETE FROM test_plan WHERE project_id = ?", testProjectId);
        jdbcTemplate.update("DELETE FROM business_rule WHERE project_id = ?", testProjectId);
        jdbcTemplate.update("DELETE FROM project WHERE id = ?", testProjectId);

        jdbcTemplate.update("INSERT INTO project (id, name, source_type, status, storage_encrypted, owner_user_id, created_at, updated_at) " +
                "VALUES (?, 'DB Benchmark Project', 'ZIP', 'UPLOADED', false, ?, NOW(), NOW())", testProjectId, testUserId);

        // Tạo 50 Business Rules và 100 Test Cases
        for (int i = 1; i <= 50; i++) {
            jdbcTemplate.update("INSERT INTO business_rule (project_id, rule_code, description, source, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, 'AI_GENERATED', 'APPROVED', NOW(), NOW())", testProjectId, "BR-" + i, "Mô tả quy tắc nghiệp vụ " + i);
        }

        Long firstBrId = jdbcTemplate.queryForObject(
                "SELECT id FROM business_rule WHERE project_id = ? LIMIT 1", Long.class, testProjectId);

        Long testPlanId = 888201L;
        jdbcTemplate.update("INSERT INTO test_plan (id, project_id, business_rule_id, plan_code, title, description, test_type, status, created_at) " +
                "VALUES (?, ?, ?, 'TP-1', 'Main Test Plan', 'Kế hoạch kiểm thử chính', 'UNIT', 'APPROVED', NOW())", testPlanId, testProjectId, firstBrId);

        for (int i = 1; i <= 100; i++) {
            jdbcTemplate.update("INSERT INTO test_case (test_plan_id, case_code, test_type, description, expected_result, priority, status, created_at) " +
                    "VALUES (?, ?, 'UNIT', ?, 'Kết quả mong đợi', 'MEDIUM', 'APPROVED', NOW())", testPlanId, "TC-" + i, "Test case kiểm thử " + i);
        }
    }

    @AfterEach
    void cleanupBenchmarkFixtures() {
        jdbcTemplate.update("DELETE FROM test_case WHERE test_plan_id IN (SELECT id FROM test_plan WHERE project_id = ?)", testProjectId);
        jdbcTemplate.update("DELETE FROM test_plan WHERE project_id = ?", testProjectId);
        jdbcTemplate.update("DELETE FROM business_rule WHERE project_id = ?", testProjectId);
        jdbcTemplate.update("DELETE FROM project WHERE id = ?", testProjectId);
    }

    @Test
    @DisplayName("Workstream E1: Đo lường HikariCP Connection Pool Utilization & Latency")
    void benchmarkConnectionPoolUtilization() throws Exception {
        if (dataSource instanceof HikariDataSource hikariDs) {
            int active = hikariDs.getHikariPoolMXBean().getActiveConnections();
            int idle = hikariDs.getHikariPoolMXBean().getIdleConnections();
            int total = hikariDs.getHikariPoolMXBean().getTotalConnections();
            int max = hikariDs.getMaximumPoolSize();

            System.out.printf("[DB Pool Benchmark] HikariPool: Active=%d, Idle=%d, Total=%d, Max=%d%n",
                    active, idle, total, max);

            assertThat(total).isLessThanOrEqualTo(max);
        }

        // Đo độ trễ mượn kết nối từ pool (1000 lượt)
        int iterations = 1000;
        List<Long> acquireTimes = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            try (Connection conn = dataSource.getConnection()) {
                long t1 = System.nanoTime();
                acquireTimes.add((t1 - t0) / 1_000); // us
            }
        }
        Collections.sort(acquireTimes);
        long p50 = acquireTimes.get(iterations / 2);
        long p95 = acquireTimes.get((int) (iterations * 0.95));

        System.out.printf("[DB Connection Acquire] 1000 ops - p50=%.3f ms, p95=%.3f ms%n",
                p50 / 1000.0, p95 / 1000.0);

        assertThat(p50).isLessThan(2000); // p50 < 2ms
    }

    @Test
    @DisplayName("Workstream E2: Đo EXPLAIN ANALYZE và độ trễ truy vấn các bảng cốt lõi")
    void benchmarkQueryLatencyAndExplainAnalyze() {
        // 1. EXPLAIN ANALYZE truy vấn Project theo User
        List<String> explainProject = jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE SELECT * FROM project WHERE owner_user_id = ?", String.class, testUserId);
        System.out.println("--- EXPLAIN ANALYZE Project Query ---");
        explainProject.forEach(System.out::println);

        // 2. EXPLAIN ANALYZE truy vấn Business Rules theo Project
        List<String> explainBr = jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE SELECT * FROM business_rule WHERE project_id = ?", String.class, testProjectId);
        System.out.println("--- EXPLAIN ANALYZE BusinessRule Query ---");
        explainBr.forEach(System.out::println);

        // 3. EXPLAIN ANALYZE truy vấn Test Case theo Test Plan
        List<String> explainTc = jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE SELECT * FROM test_case WHERE test_plan_id = ?", String.class, 888201L);
        System.out.println("--- EXPLAIN ANALYZE TestCase Query ---");
        explainTc.forEach(System.out::println);

        // Đo độ trễ thực thi 200 lượt truy vấn
        int iterations = 200;
        List<Long> queryTimes = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            var list = jdbcTemplate.queryForList("SELECT id, name, status FROM project WHERE owner_user_id = ?", testUserId);
            long t1 = System.nanoTime();
            queryTimes.add((t1 - t0) / 1_000); // us
            assertThat(list).isNotEmpty();
        }
        Collections.sort(queryTimes);
        long p50 = queryTimes.get(iterations / 2);
        long p95 = queryTimes.get((int) (iterations * 0.95));

        System.out.printf("[DB Query Latency] Project Select 200 ops - p50=%.2f ms, p95=%.2f ms%n",
                p50 / 1000.0, p95 / 1000.0);

        assertThat(p50).isLessThan(10000); // p50 < 10ms
    }

    @Test
    @DisplayName("Workstream E3: Đo thời gian giao dịch (Transaction Duration) & Concurrent Contention")
    void benchmarkConcurrentTransactionThroughput() throws Exception {
        int threadCount = 10;
        int operationsPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger totalCommitted = new AtomicInteger(0);
        AtomicInteger rollbackCount = new AtomicInteger(0);
        java.util.concurrent.CopyOnWriteArrayList<Throwable> unexpectedErrors = new java.util.concurrent.CopyOnWriteArrayList<>();

        List<Future<List<Long>>> futures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                startLatch.await();
                List<Long> durations = new ArrayList<>();
                for (int i = 0; i < operationsPerThread; i++) {
                    DefaultTransactionDefinition def = new DefaultTransactionDefinition();
                    TransactionStatus status = transactionManager.getTransaction(def);
                    long t0 = System.nanoTime();
                    try {
                        jdbcTemplate.update("UPDATE project SET updated_at = NOW() WHERE id = ?", testProjectId);
                        transactionManager.commit(status);
                        totalCommitted.incrementAndGet();
                    } catch (Exception ex) {
                        transactionManager.rollback(status);
                        rollbackCount.incrementAndGet();
                        unexpectedErrors.add(ex);
                    }
                    long t1 = System.nanoTime();
                    durations.add((t1 - t0) / 1_000); // us
                }
                return durations;
            }));
        }

        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        List<Long> allDurations = new ArrayList<>();
        for (var f : futures) {
            allDurations.addAll(f.get());
        }
        Collections.sort(allDurations);

        long p50 = allDurations.get(allDurations.size() / 2);
        long p95 = allDurations.get((int) (allDurations.size() * 0.95));
        long p99 = allDurations.get((int) (allDurations.size() * 0.99));

        System.out.printf("[DB Concurrency Benchmark] %d commits across %d threads - p50=%.2f ms, p95=%.2f ms, p99=%.2f ms (Total Committed: %d, Rollbacks: %d)%n",
                threadCount * operationsPerThread, threadCount, p50 / 1000.0, p95 / 1000.0, p99 / 1000.0, totalCommitted.get(), rollbackCount.get());

        assertThat(unexpectedErrors).as("Không phát sinh lỗi SQL/deadlock ngoài ý muốn").isEmpty();
        assertThat(totalCommitted.get()).isEqualTo(threadCount * operationsPerThread);
    }
}
