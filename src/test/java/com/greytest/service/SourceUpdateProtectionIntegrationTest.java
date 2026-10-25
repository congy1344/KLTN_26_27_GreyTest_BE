package com.greytest.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.greytest.dto.SourceUpdateItemPatchRequest;
import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.GenerationInProgressException;
import com.greytest.service.generation.GenerationCheckpointStore;

@SpringBootTest(properties = "greytest.auth.token-secret=test-only-token-secret-at-least-32-bytes")
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = "jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024")
class SourceUpdateProtectionIntegrationTest {
    @Autowired private SourceUpdateService service;
    @Autowired private GenerationCheckpointStore checkpoints;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private com.greytest.service.generation.IncrementalCheckpointCoordinator coordinator;
    @TempDir Path snapshot;
    private Long projectId;
    private Long updateId;
    private Long itemId;
    private AuthUser admin;

    @BeforeEach
    void fixture() {
        projectId = jdbc.queryForObject("""
                INSERT INTO project(name,source_type,status) VALUES ('source-protection-fixture','ZIP','ANALYZED') RETURNING id
                """, Long.class);
        Long revision = jdbc.queryForObject("""
                INSERT INTO source_revision(project_id,source_type,storage_path) VALUES (?,'ZIP',?) RETURNING id
                """, Long.class, projectId, snapshot.toString());
        updateId = jdbc.queryForObject("""
                INSERT INTO source_update(project_id,candidate_revision_id,status,impact_summary)
                VALUES (?,?,'ANALYZED','{"changedMethods":[],"affectedServiceMethods":[],"affectedBusinessRuleIds":[],
                    "affectedTestPlanIds":[],"affectedTestCaseIds":[],"affectedUnitTestIds":[]}') RETURNING id
                """, Long.class, projectId, revision);
        itemId = jdbc.queryForObject("""
                INSERT INTO source_update_item(source_update_id,target_type,action,after_data,review_status,user_revision)
                VALUES (?,'BUSINESS_RULE','UPDATE','{"description":"user decision"}','MODIFIED',1) RETURNING id
                """, Long.class, updateId);
        admin = new AuthUser(); admin.setId(1L); admin.setRole(UserRole.ADMIN);
    }

    @AfterEach
    void cleanupOwnFixture() {
        if (projectId != null) jdbc.update("DELETE FROM project WHERE id=?", projectId);
    }

    @Test
    void activeLeaseBlocksAnalyzeReviewApplyAndCancelWithoutChangingDraft() {
        checkpoints.claim(projectId, updateId, Duration.ofMinutes(1));
        assertThatThrownBy(() -> service.analyzeUpdate(projectId, updateId, admin)).isInstanceOf(GenerationInProgressException.class);
        assertThatThrownBy(() -> service.patchItem(projectId, updateId, itemId,
                new SourceUpdateItemPatchRequest(null, null, "late edit", null), admin)).isInstanceOf(GenerationInProgressException.class);
        assertThatThrownBy(() -> service.applyUpdate(projectId, updateId, admin)).isInstanceOf(GenerationInProgressException.class);
        assertThatThrownBy(() -> service.cancelUpdate(projectId, updateId, admin)).isInstanceOf(GenerationInProgressException.class);
        assertThat(jdbc.queryForObject("SELECT after_data FROM source_update_item WHERE id=?", String.class, itemId))
                .isEqualTo("{\"description\":\"user decision\"}");
        assertThat(jdbc.queryForObject("SELECT status FROM source_update WHERE id=?", String.class, updateId)).isEqualTo("ANALYZED");
    }

    @Test
    void explicitKeepPreservesUserContentAndRequiresRevalidation() {
        conflict();
        var result = service.patchItem(projectId, updateId, itemId,
                new SourceUpdateItemPatchRequest(null, null, null, null, 0L, 1L, "BUSINESS_RULE", "10", "KEEP_USER"), admin);
        assertThat(result.afterData()).isEqualTo("{\"description\":\"user decision\"}");
        assertThat(result.userRevision()).isEqualTo(2L);
        assertThat(result.version()).isEqualTo(1L);
        assertThat(checkpoints.find(updateId, "BUSINESS_RULE", "10").orElseThrow().state()).isEqualTo("BLOCKED");
        assertThat(checkpoints.find(updateId, "BUSINESS_RULE", "10").orElseThrow().outputJson()).contains("KEEP_USER");
    }

    @Test
    void explicitProposalChoiceChangesOnlyMatchingItemAndRejectsStaleResolution() {
        conflict();
        var result = service.patchItem(projectId, updateId, itemId,
                new SourceUpdateItemPatchRequest(null, null, null, null, 0L, 1L, "BUSINESS_RULE", "10", "USE_PROPOSAL"), admin);
        assertThat(result.afterData()).isEqualTo("{\"description\":\"proposal\"}");
        assertThat(result.userRevision()).isEqualTo(2L);
        assertThatThrownBy(() -> service.patchItem(projectId, updateId, itemId,
                new SourceUpdateItemPatchRequest(null, null, null, null, 0L, 1L, "BUSINESS_RULE", "10", "KEEP_USER"), admin))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT after_data FROM source_update_item WHERE id=?", String.class, itemId))
                .isEqualTo("{\"description\":\"proposal\"}");
    }

    @Test
    void applyCannotPromoteSourceWithoutAllStageReceipts() {
        assertThatThrownBy(() -> service.applyUpdate(projectId, updateId, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stage");
        assertThat(jdbc.queryForObject("SELECT active_source_revision_id FROM project WHERE id=?", Long.class, projectId)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM source_update WHERE id=?", String.class, updateId)).isEqualTo("ANALYZED");
    }

    @Test
    void externalArtifactEditWaitsForApplyValidationTransactionToFinish() throws Exception {
        Long ruleId = jdbc.queryForObject("""
                INSERT INTO business_rule(project_id,rule_code,description,source,status,is_modified)
                VALUES (?,'BR-LOCK','original','AI_GENERATED','APPROVED',false) RETURNING id
                """, Long.class, projectId);
        jdbc.update("UPDATE source_update_item SET target_id=? WHERE id=?", ruleId, itemId);
        var enteredValidation = new java.util.concurrent.CountDownLatch(1);
        var releaseValidation = new java.util.concurrent.CountDownLatch(1);
        var editStarted = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            enteredValidation.countDown();
            if (!releaseValidation.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            throw new IllegalArgumentException("stop after the artifact lock was acquired");
        }).when(coordinator).assertReadyForApply(updateId);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var applying = executor.submit(() -> {
                assertThatThrownBy(() -> service.applyUpdate(projectId, updateId, admin)).isInstanceOf(IllegalArgumentException.class);
            });
            assertThat(enteredValidation.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var editing = executor.submit(() -> {
                editStarted.countDown();
                return jdbc.update("UPDATE business_rule SET description='external edit' WHERE id=?", ruleId);
            });
            assertThat(editStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> editing.get(500, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            releaseValidation.countDown();
            applying.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(editing.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT description FROM business_rule WHERE id=?", String.class, ruleId))
                    .isEqualTo("external edit");
        } finally {
            releaseValidation.countDown();
            executor.shutdownNow();
        }
    }

    private void conflict() {
        var lease = checkpoints.claim(projectId, updateId, Duration.ofMinutes(1));
        checkpoints.mark(lease, "BUSINESS_RULE", "10", "CONFLICT", "same-input", "{}",
                "{\"itemId\":" + itemId + ",\"proposedAfterData\":\"{\\\"description\\\":\\\"proposal\\\"}\",\"proposedAction\":\"UPDATE\"}", "reviewed draft");
        checkpoints.release(lease, "CONFLICT", "{}");
    }
}
