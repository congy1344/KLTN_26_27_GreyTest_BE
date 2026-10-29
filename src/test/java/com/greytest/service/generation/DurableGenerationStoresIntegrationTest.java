package com.greytest.service.generation;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.exception.GenerationInProgressException;

class DurableGenerationStoresIntegrationTest {

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private GenerationCheckpointStore checkpoints;
    private PersistentLlmResponseStore cache;
    private Long projectId;
    private Long updateId;

    @BeforeEach
    void setUp() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        assumeTrue("jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024".equals(url),
                "Dedicated restored PostgreSQL database is required");
        var dataSource = new DriverManagerDataSource(url, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        long projectsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM project", Long.class);
        long updatesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM source_update", Long.class);
        long itemsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM source_update_item", Long.class);
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertEquals(projectsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM project", Long.class));
        assertEquals(updatesBefore, jdbc.queryForObject("SELECT COUNT(*) FROM source_update", Long.class));
        assertEquals(itemsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM source_update_item", Long.class));
        transactionManager = new DataSourceTransactionManager(dataSource);
        checkpoints = new GenerationCheckpointStore(jdbc, transactionManager);
        cache = new PersistentLlmResponseStore(jdbc, transactionManager, new ObjectMapper());
        projectId = jdbc.queryForObject("""
                INSERT INTO project(name, source_type, status) VALUES ('durable-store-fixture', 'ZIP', 'UPLOADED')
                RETURNING id
                """, Long.class);
        Long revisionId = jdbc.queryForObject("""
                INSERT INTO source_revision(project_id, source_type, storage_path) VALUES (?, 'ZIP', '/test/fixture')
                RETURNING id
                """, Long.class, projectId);
        updateId = jdbc.queryForObject("""
                INSERT INTO source_update(project_id, candidate_revision_id, status) VALUES (?, ?, 'DRAFT')
                RETURNING id
                """, Long.class, projectId, revisionId);
    }

    @AfterEach
    void cleanUpOwnFixtures() {
        if (jdbc != null && projectId != null) {
            jdbc.update("DELETE FROM project WHERE id = ?", projectId);
        }
    }

    @Test
    void concurrentClaimsHaveExactlyOneOwner() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<Boolean> claim = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    checkpoints.claim(projectId, updateId, Duration.ofMinutes(1));
                    return true;
                } catch (GenerationInProgressException expected) {
                    return false;
                }
            };
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM generation_checkpoint WHERE source_update_id = ?",
                    Integer.class, updateId));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredOwnerCannotRenewCompleteMarkOrReleaseAndReplacementFenceIncreases() {
        var old = checkpoints.claim(projectId, updateId, Duration.ofMinutes(1));
        expireLease();
        assertThrows(IllegalStateException.class, () -> checkpoints.heartbeat(old, Duration.ofMinutes(1)));
        assertThrows(IllegalStateException.class, () -> checkpoints.complete(old, "BR", "method", "fp", "{}", "[]", "digest"));
        assertThrows(IllegalStateException.class, () -> checkpoints.mark(old, "BR", "method", "FAILED", "fp", "{}", null, "failed"));
        assertThrows(IllegalStateException.class, () -> checkpoints.release(old, "FAILED", "{}"));
        var replacement = checkpoints.claim(projectId, updateId, Duration.ofMinutes(1));
        assertTrue(replacement.fence() > old.fence());
        assertNotEquals(old.owner(), replacement.owner());
        assertThrows(IllegalStateException.class, () -> checkpoints.complete(old, "BR", "method", "fp", "{}", "[]", "digest"));
        checkpoints.heartbeat(replacement, Duration.ofMinutes(1));
        checkpoints.release(replacement, "SUCCEEDED", "{\"generated\":1}");
        assertThrows(IllegalStateException.class, () -> checkpoints.heartbeat(replacement, Duration.ofMinutes(1)));
    }

    @Test
    void concurrentUpdatesInSameProjectCannotBothClaimGeneration() throws Exception {
        Long revisionId = jdbc.queryForObject("SELECT candidate_revision_id FROM source_update WHERE id=?", Long.class, updateId);
        Long secondUpdate = jdbc.queryForObject("""
                INSERT INTO source_update(project_id,candidate_revision_id,status) VALUES (?,?,'DRAFT') RETURNING id
                """, Long.class, projectId, revisionId);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var attempts = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (Long target : List.of(updateId, secondUpdate)) {
                attempts.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try { checkpoints.claim(projectId, target, Duration.ofMinutes(1)); return true; }
                    catch (GenerationInProgressException active) { return false; }
                }));
            }
            start.countDown();
            assertNotEquals(attempts.get(0).get(10, TimeUnit.SECONDS), attempts.get(1).get(10, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void artifactAndSuccessCheckpointRollbackTogether() {
        var lease = checkpoints.claim(projectId, updateId, Duration.ofMinutes(1));
        assertThrows(IllegalArgumentException.class, () -> new TransactionTemplate(transactionManager).execute(status -> {
            checkpoints.assertLease(lease);
            jdbc.update("""
                    INSERT INTO source_update_item(source_update_id,target_type,target_key,action,after_data)
                    VALUES (?, 'BUSINESS_RULE', 'method', 'CREATE', '{}')
                    """, updateId);
            checkpoints.complete(lease, "BR", "method", "fp", "{}", "[1]", "digest");
            throw new IllegalArgumentException("simulate artifact validation/persistence failure");
        }));
        assertTrue(checkpoints.find(updateId, "BR", "method").isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM source_update_item WHERE source_update_id = ?",
                Integer.class, updateId));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            checkpoints.assertLease(lease);
            checkpoints.complete(lease, "BR", "method", "fp", "{}", "[1]", "digest");
        });
        assertEquals("SUCCEEDED", checkpoints.find(updateId, "BR", "method").orElseThrow().state());
        assertEquals(1, checkpoints.list(updateId).size());
        assertThrows(IllegalArgumentException.class, () -> checkpoints.mark(lease, "TC", "plan", "SUCCEEDED", "fp", "{}", "[]", null));
    }

    @Test
    void expiredLeaseAfterCallerLockIsRejectedBeforeCompletion() {
        var lease = checkpoints.claim(projectId, updateId, Duration.ofMinutes(1));
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).execute(status -> {
            checkpoints.assertLease(lease);
            expireLease();
            checkpoints.complete(lease, "BR", "method", "fp", "{}", "[]", "digest");
            return null;
        }));
        assertTrue(checkpoints.find(updateId, "BR", "method").isEmpty());
    }

    @Test
    void validatedResponseSurvivesRollbackAndNewStoreInstance() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionTemplate(transactionManager).execute(status -> {
            cache.putValidated(projectId, "BR", "fingerprint", "{\"businessRules\":[{\"description\":\"rule\"}]}");
            throw new IllegalArgumentException("outer rollback");
        }));
        var restarted = new PersistentLlmResponseStore(jdbc, transactionManager, new ObjectMapper());
        assertEquals("{\"businessRules\":[{\"description\":\"rule\"}]}", restarted.get(projectId, "BR", "fingerprint").orElseThrow());
        cache.putValidated(projectId, "TC", "without-outer-tx", "[{\"description\":\"case\"}]");
        assertTrue(restarted.get(projectId, "TC", "without-outer-tx").isPresent());
        restarted.evict(projectId, "TC", "without-outer-tx");
        assertTrue(cache.get(projectId, "TC", "without-outer-tx").isEmpty());
        cache.putValidated(projectId, "BR", "with-provenance", "[{\"rule\":1}]", "{\"outputFingerprint\":\"versioned-output\"}");
        assertEquals("[{\"rule\":1}]", restarted.getEntry(projectId, "BR", "with-provenance").orElseThrow().response());
        assertTrue(restarted.getEntry(projectId, "BR", "with-provenance").orElseThrow().provenance().contains("versioned-output"));
    }

    @Test
    void corruptExpiredEmptyAndInvalidResponsesAreNeverReturned() {
        for (String invalid : List.of("", "null", "[]", "{}", "123", "invalid-json", "{\"rules\":[]}")) {
            assertThrows(IllegalArgumentException.class, () -> cache.putValidated(projectId, "BR", "bad", invalid));
        }
        assertTrue(cache.get(projectId, "BR", "bad").isEmpty());
        cache.putValidated(projectId, "BR", "corrupt", "[{\"rule\":1}]");
        jdbc.update("UPDATE llm_response_cache SET response = '[{\"rule\":2}]' WHERE project_id = ?", projectId);
        assertTrue(cache.get(projectId, "BR", "corrupt").isEmpty());
        cache.putValidated(projectId, "BR", "expired", "[{\"rule\":1}]");
        jdbc.update("UPDATE llm_response_cache SET expires_at = clock_timestamp() - interval '1 second' WHERE project_id = ?", projectId);
        assertTrue(cache.get(projectId, "BR", "expired").isEmpty());
    }

    @Test
    void migrationKeepsExistingRowsAndDefaultsRevisionsAndCascadesOnlyOwnedData() {
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM source_update WHERE id = ?", Long.class, updateId));
        Long itemId = jdbc.queryForObject("""
                INSERT INTO source_update_item(source_update_id,target_type,action) VALUES (?, 'BUSINESS_RULE','CREATE') RETURNING id
                """, Long.class, updateId);
        assertEquals(0L, jdbc.queryForObject("SELECT user_revision FROM source_update_item WHERE id = ?", Long.class, itemId));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM source_update_item WHERE id = ?", Long.class, itemId));
        var lease = checkpoints.claim(projectId, updateId, Duration.ofMinutes(1));
        checkpoints.mark(lease, "BR", "method", "BLOCKED", "fp", "{}", null, "missing dependency");
        assertEquals("BLOCKED", checkpoints.list(updateId).get(0).state());
        cache.putValidated(projectId, "BR", "fp", "[{\"rule\":1}]");
        jdbc.update("DELETE FROM project WHERE id = ?", projectId);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM generation_checkpoint WHERE source_update_id = ?", Integer.class, updateId));
        assertTrue(cache.get(projectId, "BR", "fp").isEmpty());
    }

    private void expireLease() {
        jdbc.update("UPDATE generation_checkpoint SET lease_until = clock_timestamp() - interval '1 second' WHERE source_update_id = ? AND stage = 'PIPELINE'",
                updateId);
    }
}
