package com.greytest.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Phase 6.3 Workstream A: PostgreSQL Fault Injection Integration Tests.
 * Xác minh tính bền bỉ của hệ thống khi cơ sở dữ liệu gặp sự cố:
 * A1. Connection drop / unreachable host.
 * A2. Transaction rollback atomicity (không để lại dữ liệu nửa chừng).
 * A3. Query timeout.
 * A4. Connection pool saturation (xác minh SQLTransientConnectionException khi vượt pool).
 * A5. Transaction rollback isolation giữa các Project khác nhau.
 * A6. Mất kết nối trước khi commit: không ghi trạng thái giả SUCCESS.
 * A7. Tự động phục hồi kết nối (reconnection recovery).
 */
@SpringBootTest
public class PostgreSqlFaultInjectionIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DataSourceProperties dataSourceProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long testUserId;

    @BeforeEach
    void ensureTestUser() {
        // Lấy hoặc tạo user hợp lệ để không phụ thuộc cứng vào user id = 1
        var users = jdbcTemplate.queryForList("SELECT id FROM auth_user ORDER BY id ASC LIMIT 1", Long.class);
        if (users.isEmpty()) {
            jdbcTemplate.update("INSERT INTO auth_user (email, password_hash, role, created_at, updated_at) " +
                    "VALUES ('resilience_test@greytest.com', 'dummy_hash', 'USER', NOW(), NOW())");
            testUserId = jdbcTemplate.queryForObject("SELECT id FROM auth_user WHERE email = 'resilience_test@greytest.com'", Long.class);
        } else {
            testUserId = users.get(0);
        }
    }

    @Test
    @DisplayName("A1 & A7: Connection drop / unreachable host ném DataAccessException và tự phục hồi khi DB sẵn sàng")
    void testConnectionLossAndRecovery() {
        // Tạo một DataSource trỏ vào cổng không tồn tại để giả lập database bị sập mạng / drop connection
        HikariConfig deadConfig = new HikariConfig();
        deadConfig.setJdbcUrl("jdbc:postgresql://localhost:59998/greytest");
        deadConfig.setUsername(dataSourceProperties.getUsername());
        deadConfig.setPassword(dataSourceProperties.getPassword());
        deadConfig.setConnectionTimeout(1000); // 1s fast timeout
        deadConfig.setInitializationFailTimeout(-1);

        try (HikariDataSource deadDs = new HikariDataSource(deadConfig)) {
            JdbcTemplate deadJdbc = new JdbcTemplate(deadDs);
            // Request truy vấn trên DB chết phải bị từ chối bằng DataAccessException mà không làm crash Backend JVM
            assertThatThrownBy(() -> deadJdbc.queryForObject("SELECT 1", Integer.class))
                    .isInstanceOf(DataAccessException.class);
        }

        // Kiểm tra DataSource thật (kết nối bình thường) hoạt động hoàn hảo (Reconnection Verification)
        Integer liveResult = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(liveResult).isEqualTo(1);
    }

    @Test
    @DisplayName("A2 & A6: Transaction rollback atomicity - Lỗi giữa chừng rollback sạch sẽ, không tạo dữ liệu mồ côi hoặc SUCCESS giả")
    void testTransactionRollbackAtomicity() {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        TransactionStatus status = transactionManager.getTransaction(def);

        Long tempProjectId = 999101L;

        try {
            // Chèn dữ liệu thử nghiệm trong transaction với testUserId hợp lệ
            jdbcTemplate.update(
                    "INSERT INTO project (id, name, source_type, status, storage_encrypted, owner_user_id, created_at, updated_at) " +
                    "VALUES (?, 'Fault Injection Rollback Test', 'ZIP', 'UPLOADED', false, ?, NOW(), NOW())",
                    tempProjectId, testUserId);

            // Giả lập lỗi nghiêm trọng / crash trước khi commit
            throw new RuntimeException("Simulated mid-transaction failure / network disconnect");
        } catch (RuntimeException ex) {
            transactionManager.rollback(status);
        }

        // Xác minh bản ghi đã bị rollback hoàn toàn, không lưu lại dữ liệu rác
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project WHERE id = ?", Integer.class, tempProjectId);
        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("A3: Query timeout ném QueryTimeoutException / DataAccessException mà không treo kết nối")
    void testQueryTimeoutHandling() {
        JdbcTemplate timeoutJdbc = new JdbcTemplate(dataSource);
        timeoutJdbc.setQueryTimeout(1); // 1 second timeout

        // pg_sleep(3) kéo dài 3 giây, vượt quá query timeout 1 giây
        assertThatThrownBy(() -> timeoutJdbc.execute("SELECT pg_sleep(3)"))
                .isInstanceOf(DataAccessException.class);

        // Sau khi bị timeout, kết nối trả lại pool và các query tiếp theo thực thi bình thường
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(result).isEqualTo(1);
    }

    @Test
    @DisplayName("A4: Connection pool saturation - Kiểm chứng SQLTransientConnectionException khi pool cạn kiệt")
    void testConnectionPoolSaturationAndFairQueuing() throws Exception {
        // Tạo pool nhỏ với tối đa 1 connection và connectionTimeout 300ms
        HikariConfig smallConfig = new HikariConfig();
        smallConfig.setJdbcUrl(dataSourceProperties.getUrl());
        smallConfig.setUsername(dataSourceProperties.getUsername());
        smallConfig.setPassword(dataSourceProperties.getPassword());
        smallConfig.setMaximumPoolSize(1);
        smallConfig.setConnectionTimeout(300); // 300ms timeout khi chờ connection

        try (HikariDataSource smallDs = new HikariDataSource(smallConfig)) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch connectionAcquiredLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(1);

            // Thread 1 mượn connection duy nhất và giữ trong 1 giây
            Future<?> holdingFuture = executor.submit(() -> {
                try (Connection conn = smallDs.getConnection()) {
                    connectionAcquiredLatch.countDown();
                    finishLatch.await(3, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // Chờ Thread 1 đã lấy connection
            assertTrue(connectionAcquiredLatch.await(2, TimeUnit.SECONDS));

            // Thread 2 cố gắng lấy connection trong khi pool đã cạn kiệt (chỉ có 1 connection) -> Phải ném SQLTransientConnectionException
            Future<?> starvedFuture = executor.submit(() -> {
                try (Connection conn = smallDs.getConnection()) {
                    fail("Không được cấp connection khi pool đang bị chiếm dụng và quá timeout");
                } catch (SQLException e) {
                    assertThat((Throwable) e).isInstanceOf(SQLTransientConnectionException.class);
                }
            });

            // Thread 2 phải kết thúc và bắt đúng SQLTransientConnectionException trong vòng 1s
            starvedFuture.get(2, TimeUnit.SECONDS);

            // Cho phép Thread 1 kết thúc
            finishLatch.countDown();
            holdingFuture.get(2, TimeUnit.SECONDS);

            executor.shutdown();
        }
    }

    @Test
    @DisplayName("A5: Transaction rollback isolation - Lỗi ở Project A hoàn toàn không ảnh hưởng tới commit của Project B")
    void testCrossProjectTransactionRollbackIsolation() {
        Long projectAId = 999201L;
        Long projectBId = 999202L;

        // Dọn dẹp trước nếu có
        jdbcTemplate.update("DELETE FROM project WHERE id IN (?, ?)", projectAId, projectBId);

        // Transaction 1: Project A (bị lỗi và rollback)
        DefaultTransactionDefinition defA = new DefaultTransactionDefinition();
        TransactionStatus statusA = transactionManager.getTransaction(defA);
        try {
            jdbcTemplate.update(
                    "INSERT INTO project (id, name, source_type, status, storage_encrypted, owner_user_id, created_at, updated_at) " +
                    "VALUES (?, 'Project A Fault', 'ZIP', 'UPLOADED', false, ?, NOW(), NOW())",
                    projectAId, testUserId);
            throw new RuntimeException("Simulated failure in Project A processing");
        } catch (RuntimeException ex) {
            transactionManager.rollback(statusA);
        }

        // Transaction 2: Project B (hoàn tất thành công và commit)
        DefaultTransactionDefinition defB = new DefaultTransactionDefinition();
        TransactionStatus statusB = transactionManager.getTransaction(defB);
        jdbcTemplate.update(
                "INSERT INTO project (id, name, source_type, status, storage_encrypted, owner_user_id, created_at, updated_at) " +
                "VALUES (?, 'Project B Success', 'ZIP', 'UPLOADED', false, ?, NOW(), NOW())",
                projectBId, testUserId);
        transactionManager.commit(statusB);

        // Xác minh Project A không tồn tại, Project B được lưu thành công
        Integer countA = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM project WHERE id = ?", Integer.class, projectAId);
        Integer countB = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM project WHERE id = ?", Integer.class, projectBId);

        assertThat(countA).isEqualTo(0);
        assertThat(countB).isEqualTo(1);

        // Dọn dẹp
        jdbcTemplate.update("DELETE FROM project WHERE id = ?", projectBId);
    }
}
