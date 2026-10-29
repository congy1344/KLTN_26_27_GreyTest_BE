package com.greytest.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doNothing;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.greytest.dto.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;
import com.greytest.entity.AuthUser;
import com.greytest.entity.SourceUpdateItem;
import com.greytest.entity.enums.*;
import com.greytest.repository.SourceUpdateItemRepository;
import com.greytest.service.generation.GenerationCheckpointStore;
import com.greytest.service.generation.IncrementalCheckpointCoordinator;

@SpringBootTest(properties = "greytest.auth.token-secret=test-only-token-secret-at-least-32-bytes")
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = "jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024")
class TemporaryArtifactPreservationIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IncrementalGenerationService generation;
    @Autowired private SourceUpdateService updates;
    @Autowired private BusinessRuleService rules;
    @Autowired private TestPlanService plans;
    @Autowired private TestCaseService cases;
    @Autowired private SourceUpdateItemRepository items;
    @Autowired private GenerationCheckpointStore checkpoints;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired private com.greytest.repository.BusinessRuleRepository ruleRows;
    @SpyBean private IncrementalCheckpointCoordinator coordinator;
    @TempDir Path snapshot;
    private Long projectId;
    private Long updateId;
    private Long methodId;
    private AuthUser admin;

    @BeforeEach
    void fixture() {
        projectId = jdbc.queryForObject("INSERT INTO project(name,source_type,status) VALUES ('temporary-preservation-fixture','ZIP','ANALYZED') RETURNING id", Long.class);
        Long owner = jdbc.queryForObject("""
                INSERT INTO java_class(project_id,class_name,qualified_name,class_type,file_path)
                VALUES (?,'Service','demo.Service','SERVICE','src/main/java/demo/Service.java') RETURNING id
                """, Long.class, projectId);
        methodId = jdbc.queryForObject("""
                INSERT INTO java_method(class_id,method_name,source_code,return_type,parameters,visibility)
                VALUES (?,'value','public int value() { return 1; }','int','[]','PUBLIC') RETURNING id
                """, Long.class, owner);
        Long revision = jdbc.queryForObject("INSERT INTO source_revision(project_id,source_type,storage_path) VALUES (?,'ZIP',?) RETURNING id",
                Long.class, projectId, snapshot.toString());
        updateId = jdbc.queryForObject("""
                INSERT INTO source_update(project_id,candidate_revision_id,status,impact_summary)
                VALUES (?,?,'ANALYZED','{"changedMethods":[],"affectedServiceMethods":[],"affectedBusinessRuleIds":[],
                    "affectedTestPlanIds":[],"affectedTestCaseIds":[],"affectedUnitTestIds":[]}') RETURNING id
                """, Long.class, projectId, revision);
        admin = new AuthUser(); admin.setId(1L); admin.setRole(UserRole.ADMIN);
    }

    @AfterEach
    void cleanupOwnFixture() {
        if (projectId != null) jdbc.update("DELETE FROM project WHERE id=?", projectId);
    }

    @ParameterizedTest
    @EnumSource(value = SourceUpdateTargetType.class, names = {"BUSINESS_RULE", "TEST_PLAN", "TEST_CASE"})
    void applyingCannotOverwriteTemporaryArtifactsEditedThroughTheirNormalApi(SourceUpdateTargetType type) {
        SourceUpdateItem item = createTemporary(type);
        editThroughNormalApi(item);
        jdbc.update("UPDATE source_update_item SET review_status='ACCEPTED',user_revision=1 WHERE source_update_id=?", updateId);
        doNothing().when(coordinator).assertReadyForApply(updateId);

        assertThatThrownBy(() -> updates.applyUpdate(projectId, updateId, admin)).isInstanceOf(IllegalArgumentException.class);

        assertUserContent(item);
        assertThat(jdbc.queryForObject("SELECT status FROM source_update WHERE id=?", String.class, updateId)).isEqualTo("ANALYZED");
    }

    @Test
    void cancelCannotDeleteAnEditedThenApprovedTemporaryRuleWithClearedModifiedFlag() {
        SourceUpdateItem item = createTemporary(SourceUpdateTargetType.BUSINESS_RULE);
        editThroughNormalApi(item);
        assertThat(jdbc.queryForObject("SELECT is_modified FROM business_rule WHERE id=?", Boolean.class, item.getTargetId())).isFalse();
        var lease = checkpoints.claim(projectId, updateId, Duration.ofMinutes(1));
        checkpoints.complete(lease, "BUSINESS_RULE", methodId.toString(), "fp", "{}", "{\"itemIds\":[" + item.getId() + "]}", "digest");
        checkpoints.release(lease, "SUCCEEDED", "{}");

        assertThatThrownBy(() -> updates.cancelUpdate(projectId, updateId, admin)).isInstanceOf(IllegalArgumentException.class);

        assertUserContent(item);
    }

    @Test
    void newTemporaryRowsHaveAnInitialBaselineAndRetryDoesNotAdoptAnExternalEdit() {
        SourceUpdateItem item = createTemporary(SourceUpdateTargetType.BUSINESS_RULE);
        assertThat(item.getBeforeData()).isNotBlank();
        String initial = item.getBeforeData();
        editThroughNormalApi(item);

        assertThatThrownBy(() -> persist("persistBusinessRules", projectId, updateId,
                new BusinessRuleResponseDto(List.of(new GeneratedBusinessRuleDto(methodId,"AI regenerated","BUSINESS_LOGIC","STMT-1"))), new AtomicInteger(2)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(items.findById(item.getId()).orElseThrow().getBeforeData()).isEqualTo(initial);
        assertUserContent(item);
    }

    @Test
    void keepingTheUserVersionAdoptsThePhysicalRowAndCancelPreservesIt() throws Exception {
        SourceUpdateItem item = createTemporary(SourceUpdateTargetType.BUSINESS_RULE);
        String initial = item.getBeforeData();
        editThroughNormalApi(item);
        recordExternalConflict(item);

        var result = resolve(item, "KEEP_USER");

        assertThat(result.action()).isEqualTo(SourceUpdateAction.KEEP);
        assertThat(result.beforeData()).isEqualTo(initial);
        updates.cancelUpdate(projectId,updateId,admin);
        assertUserContent(item);
    }

    @Test
    void acceptingProposalRefreshesBaselineOnlyThroughExplicitResolution() throws Exception {
        SourceUpdateItem item = createTemporary(SourceUpdateTargetType.BUSINESS_RULE);
        editThroughNormalApi(item);
        String acceptedSnapshot = recordExternalConflict(item);

        var result = resolve(item, "USE_PROPOSAL");

        assertThat(result.action()).isEqualTo(SourceUpdateAction.CREATE);
        assertThat(result.beforeData()).isEqualTo(acceptedSnapshot);
        assertThat(result.beforeData()).isNotEqualTo(item.getBeforeData());
        assertThatCode(() -> coordinator.assertActiveArtifactUnchanged(items.findById(item.getId()).orElseThrow())).doesNotThrowAnyException();
    }

    @Test
    void anotherMainApiEditMakesTheRecordedConflictResolutionStale() throws Exception {
        SourceUpdateItem item = createTemporary(SourceUpdateTargetType.BUSINESS_RULE);
        editThroughNormalApi(item);
        recordExternalConflict(item);
        rules.update(item.getTargetId(),new UpdateBusinessRuleRequest(methodId,"second user content"));
        rules.approve(projectId);

        assertThatThrownBy(() -> resolve(item,"USE_PROPOSAL")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        assertThat(items.findById(item.getId()).orElseThrow().getBeforeData()).isEqualTo(item.getBeforeData());
        assertThat(jdbc.queryForObject("SELECT description FROM business_rule WHERE id=?",String.class,item.getTargetId())).isEqualTo("second user content");
    }

    @Test
    void ordinaryReviewCannotAdoptAChangedLegacyTemporaryRowAsItsMissingBaseline() {
        SourceUpdateItem item = createTemporary(SourceUpdateTargetType.BUSINESS_RULE);
        jdbc.update("UPDATE source_update_item SET before_data=NULL WHERE id=?",item.getId());
        editThroughNormalApi(item);
        updates.patchItem(projectId,updateId,item.getId(),new SourceUpdateItemPatchRequest(null,SourceUpdateReviewStatus.ACCEPTED,
                null,null,item.getVersion(),item.getUserRevision(),null,null,null),admin);
        SourceUpdateItem reviewed = items.findById(item.getId()).orElseThrow();

        assertThat(reviewed.getBeforeData()).isNull();
        assertThatThrownBy(() -> coordinator.assertActiveArtifactUnchanged(reviewed)).isInstanceOf(IllegalArgumentException.class);
        assertUserContent(item);
    }

    private SourceUpdateItemDto resolve(SourceUpdateItem item,String resolution) {
        SourceUpdateItem current=items.findById(item.getId()).orElseThrow();
        return updates.patchItem(projectId,updateId,item.getId(),new SourceUpdateItemPatchRequest(null,null,null,null,
                current.getVersion(),current.getUserRevision(),"BUSINESS_RULE",methodId.toString(),resolution),admin);
    }

    private String recordExternalConflict(SourceUpdateItem item) throws Exception {
        String current=mapper.writeValueAsString(ruleRows.findById(item.getTargetId()).orElseThrow());
        var envelope=mapper.createObjectNode(); envelope.put("itemId",item.getId());
        envelope.put("proposedAction",item.getAction().name()); envelope.put("proposedAfterData",item.getAfterData());
        envelope.put("activeSnapshot",current);
        var lease=checkpoints.claim(projectId,updateId,Duration.ofMinutes(1));
        checkpoints.mark(lease,"BUSINESS_RULE",methodId.toString(),"CONFLICT","fp","{}",envelope.toString(),"main API edit");
        checkpoints.release(lease,"CONFLICT","{}");
        return current;
    }

    private SourceUpdateItem createTemporary(SourceUpdateTargetType type) {
        persist("persistBusinessRules", projectId, updateId,
                new BusinessRuleResponseDto(List.of(new GeneratedBusinessRuleDto(methodId,"AI original","BUSINESS_LOGIC","STMT-1"))), new AtomicInteger(1));
        SourceUpdateItem rule = find(SourceUpdateTargetType.BUSINESS_RULE);
        if (type == SourceUpdateTargetType.BUSINESS_RULE) return rule;
        persist("persistTestPlans", projectId, updateId,
                new TestPlanResponseDto(List.of(new GeneratedTestPlanDto(methodId,rule.getTargetId(),List.of(rule.getTargetId()),"AI plan","AI plan description","HAPPY_PATH"))), new AtomicInteger(1));
        SourceUpdateItem plan = find(SourceUpdateTargetType.TEST_PLAN);
        if (type == SourceUpdateTargetType.TEST_PLAN) return plan;
        persist("persistTestCases", projectId, updateId, Set.of(plan.getTargetId()),
                new TestCaseResponseDto(List.of(new GeneratedTestCaseDto(plan.getTargetId(),"HAPPY_PATH","AI case","ready",Map.of(),"one","HIGH","BR -> TP"))), new AtomicInteger(1));
        return find(SourceUpdateTargetType.TEST_CASE);
    }

    private void editThroughNormalApi(SourceUpdateItem item) {
        if (item.getTargetType() == SourceUpdateTargetType.BUSINESS_RULE) {
            rules.update(item.getTargetId(), new UpdateBusinessRuleRequest(methodId,"user content"));
            rules.approve(projectId);
        } else if (item.getTargetType() == SourceUpdateTargetType.TEST_PLAN) {
            jdbc.update("UPDATE project SET status='BR_APPROVED' WHERE id=?", projectId);
            plans.update(item.getTargetId(), new UpdateTestPlanRequest(find(SourceUpdateTargetType.BUSINESS_RULE).getTargetId(),"user content","user description",TestType.HAPPY_PATH));
        } else {
            jdbc.update("UPDATE project SET status='PLAN_APPROVED' WHERE id=?", projectId);
            cases.update(item.getTargetId(), new UpdateTestCaseRequest(TestType.HAPPY_PATH,"user content","ready",Map.of(),"user result",Priority.HIGH,"BR -> TP"));
        }
    }

    private SourceUpdateItem find(SourceUpdateTargetType type) {
        return items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream().filter(i -> i.getTargetType() == type).findFirst().orElseThrow();
    }

    private void assertUserContent(SourceUpdateItem item) {
        String sql = switch (item.getTargetType()) {
            case BUSINESS_RULE -> "SELECT description FROM business_rule WHERE id=?";
            case TEST_PLAN -> "SELECT title FROM test_plan WHERE id=?";
            case TEST_CASE -> "SELECT description FROM test_case WHERE id=?";
            default -> throw new AssertionError("unsupported fixture");
        };
        assertThat(jdbc.queryForObject(sql,String.class,item.getTargetId())).isEqualTo("user content");
    }

    private void persist(String method, Object... arguments) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> ReflectionTestUtils.invokeMethod(generation,method,arguments));
    }
}
