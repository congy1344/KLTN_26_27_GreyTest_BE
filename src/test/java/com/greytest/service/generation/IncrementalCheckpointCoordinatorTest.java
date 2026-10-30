package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.entity.SourceUpdateItem;
import com.greytest.entity.enums.SourceUpdateReviewStatus;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.Map;
import java.time.Duration;
import java.nio.file.Path;
import java.util.function.Supplier;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.greytest.service.GenerationJobService;
import com.greytest.service.agent.AIAgentService;
import com.greytest.repository.SourceUpdateItemRepository;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedBusinessRuleDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class IncrementalCheckpointCoordinatorTest {
    @Test void deletedCaseChildConflictIsRecordedEvenWithoutACaseTargetKey() {
        var fixture = deletedUnitFixture(3L);
        assertThatThrownBy(() -> fixture.run(() -> fixture.coordinator.executeBatch(1L, 2L, GenerationProgressStage.TEST_CASE,
                Set.of(3L), Path.of("candidate"), com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto.class,
                ids -> { throw new GenerationConflictException(6L, "{\"action\":\"REMOVE\",\"afterData\":null}"); }, (ids, output) -> { })))
                .isInstanceOf(GenerationConflictException.class);
        verify(fixture.store).mark(any(), eq("TEST_CASE"), eq("3"), eq("CONFLICT"), anyString(), anyString(), contains("itemId"), anyString());
    }

    @Test void deletedUnitConflictIsNotAssignedToAnotherPlan() {
        var fixture = deletedUnitFixture(4L);
        assertThatThrownBy(() -> fixture.run(() -> fixture.coordinator.executeBatch(1L, 2L, GenerationProgressStage.TEST_CASE,
                Set.of(3L), Path.of("candidate"), com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto.class,
                ids -> { throw new GenerationConflictException(6L, "{\"action\":\"REMOVE\",\"afterData\":null}"); }, (ids, output) -> { })))
                .isInstanceOf(GenerationConflictException.class);
        verify(fixture.store).mark(any(), eq("TEST_CASE"), eq("3"), eq("FAILED"), anyString(), anyString(), isNull(), anyString());
    }

    private Fixture deletedUnitFixture(Long planId) {
        var fixture = new Fixture();
        var units = mock(com.greytest.repository.UnitTestRepository.class);
        var cases = mock(com.greytest.repository.TestCaseRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.coordinator, "unitTests", units);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.coordinator, "cases", cases);
        var unit = new com.greytest.entity.UnitTest(); unit.setId(801L); unit.setTestCaseId(701L);
        var parent = new com.greytest.entity.TestCase(); parent.setId(701L); parent.setTestPlanId(planId);
        when(units.findById(801L)).thenReturn(Optional.of(unit)); when(cases.findById(701L)).thenReturn(Optional.of(parent));
        var child = new SourceUpdateItem(); child.setId(6L); child.setTargetId(801L);
        child.setTargetType(com.greytest.entity.enums.SourceUpdateTargetType.UNIT_TEST);
        when(fixture.items.findById(6L)).thenReturn(Optional.of(child));
        return fixture;
    }
    @Test void baselineComparisonIgnoresJsonFieldOrderButDetectsActualEdits() throws Exception {
        var fixture = new Fixture();
        var repository = mock(com.greytest.repository.TestPlanRepository.class);
        var coverage = mock(com.greytest.repository.TestPlanCoveredRuleRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.coordinator, "plans", repository);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.coordinator, "coveredRules", coverage);
        var plan = new com.greytest.entity.TestPlan(); plan.setId(9L); plan.setTitle("Approved plan");
        when(repository.findById(9L)).thenReturn(Optional.of(plan));
        when(coverage.findByTestPlanId(9L)).thenReturn(List.of());
        var item = new SourceUpdateItem(); item.setId(6L); item.setTargetId(9L);
        item.setTargetType(com.greytest.entity.enums.SourceUpdateTargetType.TEST_PLAN);
        item.setAction(com.greytest.entity.enums.SourceUpdateAction.UPDATE);
        var original = fixture.mapper.readTree(fixture.coordinator.activeSnapshot(item));
        var fields = new java.util.ArrayList<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>>();
        original.fields().forEachRemaining(fields::add); java.util.Collections.reverse(fields);
        var reordered = fixture.mapper.createObjectNode(); fields.forEach(entry -> reordered.set(entry.getKey(), entry.getValue()));
        item.setBeforeData(reordered.toPrettyString());
        fixture.coordinator.assertActiveArtifactUnchanged(item);
        plan.setTitle("User changed the plan");
        assertThatThrownBy(() -> fixture.coordinator.assertActiveArtifactUnchanged(item)).isInstanceOf(IllegalArgumentException.class);
        plan.setTitle("Approved plan");
        ((com.fasterxml.jackson.databind.node.ObjectNode) reordered.path("plan")).put("updatedAt", "2026-10-29T00:00:00");
        item.setBeforeData(reordered.toString());
        assertThatThrownBy(() -> fixture.coordinator.assertActiveArtifactUnchanged(item)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void planFingerprintAndFreshValidationIncludeSiblingRules() throws Exception {
        var fixture = planFixture();
        when(fixture.fingerprints.fingerprint(eq("test-plan"), any(), any())).thenAnswer(call -> {
            var context = (com.greytest.dto.agent.GenerationContextDtos.TestPlanContextDto) call.getArgument(1);
            assertThat(context.approvedBusinessRules()).hasSize(2);
            return new GenerationFingerprintService.Fingerprint("same", "{}", true);
        });
        fixture.run(() -> fixture.coordinator.executeBatch(1L, 2L, GenerationProgressStage.TEST_PLAN, Set.of(1L, 2L), Path.of("candidate"),
                com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto.class, ids -> planResponse(), (ids, output) -> { }));
    }

    @Test void missingSiblingPlanCheckpointRegeneratesTheWholeMethodGroup() throws Exception {
        var fixture = planFixture();
        var current = fixture.items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(2L);
        String envelope = fixture.mapper.writeValueAsString(Map.of("itemIds", List.of(4L), "revisions", Map.of("4", 0L), "outputFingerprint", "out"));
        when(fixture.store.find(2L, "TEST_PLAN", "1")).thenReturn(Optional.of(new GenerationCheckpointStore.Checkpoint(
                "TEST_PLAN", "1", "same", "{}", envelope, IncrementalCheckpointCoordinator.outputDigest(fixture.mapper, current), "SUCCEEDED")));
        fixture.run(() -> fixture.coordinator.executeBatch(1L, 2L, GenerationProgressStage.TEST_PLAN, Set.of(1L, 2L), Path.of("candidate"),
                com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto.class, ids -> {
                    assertThat(ids).containsExactlyInAnyOrder(1L, 2L); return planResponse();
                }, (ids, output) -> { }));
    }

    private static com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto planResponse() {
        return new com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto(List.of(
                new com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestPlanDto(3L, 1L, List.of(1L, 2L), "Plan", "Shared coverage", "HAPPY_PATH")));
    }

    private Fixture planFixture() throws Exception {
        var fixture = new Fixture();
        var repository = mock(com.greytest.repository.BusinessRuleRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.coordinator, "rules", repository);
        var rules = new java.util.ArrayList<com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto>();
        for (long id : new long[] {1, 2}) {
            var row = new com.greytest.entity.BusinessRule(); row.setId(id); row.setMethodId(3L);
            when(repository.findById(id)).thenReturn(Optional.of(row));
            rules.add(new com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto(id, 3L, "BR-" + id, "Rule", null, null, "APPROVED", false, "IF-" + id));
        }
        when(fixture.agent.snapshotContext(eq(GenerationProgressStage.TEST_PLAN), eq(1L), anySet(), any())).thenAnswer(call -> {
            Set<Long> selected = call.getArgument(2);
            return new com.greytest.dto.agent.GenerationContextDtos.TestPlanContextDto(null, null, List.of(),
                    rules.stream().filter(rule -> selected.contains(rule.id())).toList(), List.of());
        });
        var item = new SourceUpdateItem(); item.setId(4L); item.setSourceUpdateId(2L); item.setUserRevision(0L);
        item.setTargetType(com.greytest.entity.enums.SourceUpdateTargetType.TEST_PLAN);
        item.setAction(com.greytest.entity.enums.SourceUpdateAction.UPDATE);
        item.setAfterData(fixture.mapper.writeValueAsString(planResponse().plans().get(0)));
        when(fixture.items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(2L)).thenReturn(List.of(item));
        return fixture;
    }
    @Test void changedPhysicalCreateCannotReuseAnOtherwiseValidCheckpoint() throws Exception {
        var fixture = new Fixture();
        var repository = mock(com.greytest.repository.BusinessRuleRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.coordinator,"rules",repository);
        var active = new com.greytest.entity.BusinessRule(); active.setId(10L); active.setProjectId(1L); active.setMethodId(3L);
        active.setDescription("AI original"); active.setStatus(com.greytest.entity.enums.ReviewStatus.APPROVED); active.setIsModified(false);
        var item = new SourceUpdateItem(); item.setId(4L); item.setSourceUpdateId(2L); item.setTargetId(10L);
        item.setTargetType(com.greytest.entity.enums.SourceUpdateTargetType.BUSINESS_RULE);
        item.setAction(com.greytest.entity.enums.SourceUpdateAction.CREATE);
        item.setBeforeData(fixture.mapper.writeValueAsString(active));
        item.setAfterData("{\"method_id\":3,\"description\":\"AI original\"}");
        when(repository.findById(10L)).thenReturn(Optional.of(active));
        when(fixture.items.findById(4L)).thenReturn(Optional.of(item));
        when(fixture.items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(2L)).thenReturn(List.of(item));
        String envelope=fixture.mapper.writeValueAsString(Map.of("itemIds",List.of(4L),"revisions",Map.of("4",0L),"outputFingerprint","out"));
        when(fixture.store.find(2L,"BUSINESS_RULE","3")).thenReturn(Optional.of(new GenerationCheckpointStore.Checkpoint(
                "BUSINESS_RULE","3","same","{}",envelope,IncrementalCheckpointCoordinator.outputDigest(fixture.mapper,List.of(item)),"SUCCEEDED")));
        active.setDescription("user edit approved");

        assertThatThrownBy(() -> fixture.run(() -> fixture.coordinator.executeBatch(1L,2L,GenerationProgressStage.BUSINESS_RULE,
                Set.of(3L),Path.of("candidate"),BusinessRuleResponseDto.class,
                ids -> { throw new AssertionError("LLM must not be called"); }, (ids,output) -> { throw new AssertionError("No writes"); })))
                .isInstanceOf(GenerationConflictException.class);
        assertThat(active.getDescription()).isEqualTo("user edit approved");
        verify(fixture.store).mark(any(),eq("BUSINESS_RULE"),eq("3"),eq("CONFLICT"),any(),any(),contains("activeSnapshot"),anyString());
    }

    @Test void regeneratedMetricCountsDistinctProducedArtifactsRatherThanInputTargets() throws Exception {
        var fixture = new Fixture();
        var rules = List.of(new GeneratedBusinessRuleDto(3L,"first","BUSINESS_LOGIC","STMT-1"),
                new GeneratedBusinessRuleDto(3L,"second","BUSINESS_LOGIC","STMT-2"));
        var produced = new java.util.ArrayList<SourceUpdateItem>();
        for (int i=0;i<rules.size();i++) {
            var item = new SourceUpdateItem(); item.setId(4L+i); item.setSourceUpdateId(2L);
            item.setTargetType(com.greytest.entity.enums.SourceUpdateTargetType.BUSINESS_RULE);
            item.setAfterData(fixture.mapper.writeValueAsString(rules.get(i))); produced.add(item);
        }
        var earlier = new SourceUpdateItem(); earlier.setId(6L); earlier.setSourceUpdateId(2L);
        earlier.setTargetType(com.greytest.entity.enums.SourceUpdateTargetType.BUSINESS_RULE);
        earlier.setAfterData(fixture.mapper.writeValueAsString(new GeneratedBusinessRuleDto(3L,"earlier unrelated output","BUSINESS_LOGIC","STMT-3")));
        produced.add(earlier);
        when(fixture.items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(2L)).thenReturn(produced);
        var metrics=fixture.run(() -> {
            fixture.coordinator.executeBatch(1L,2L,GenerationProgressStage.BUSINESS_RULE,Set.of(3L),Path.of("candidate"),
                    BusinessRuleResponseDto.class,ids -> new BusinessRuleResponseDto(rules),(ids,output) -> {});
            return com.greytest.service.GenerationJobContext.metrics();
        });
        assertThat(metrics.get("artifact.regenerated")).isEqualTo(2L);
    }
    @Test void outputDigestDetectsUserEditsAndDecisionChanges() {
        var mapper = new ObjectMapper();
        var item = new SourceUpdateItem(); item.setId(1L); item.setAfterData("{\"description\":\"initial\"}");
        item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
        String initial = IncrementalCheckpointCoordinator.outputDigest(mapper, List.of(item));
        item.setAfterData("{\"description\":\"edited\"}");
        assertThat(IncrementalCheckpointCoordinator.outputDigest(mapper,List.of(item))).isNotEqualTo(initial);
        item.setAfterData("{\"description\":\"initial\"}");
        item.setReviewStatus(SourceUpdateReviewStatus.REJECTED);
        assertThat(IncrementalCheckpointCoordinator.outputDigest(mapper,List.of(item))).isNotEqualTo(initial);
    }
    @Test void stableDigestIgnoresRepositoryOrdering() {
        var a = new SourceUpdateItem(); a.setId(1L); a.setAfterData("a");
        var b = new SourceUpdateItem(); b.setId(2L); b.setAfterData("b");
        assertThat(IncrementalCheckpointCoordinator.outputDigest(new ObjectMapper(),List.of(a,b)))
            .isEqualTo(IncrementalCheckpointCoordinator.outputDigest(new ObjectMapper(),List.of(b,a)));
    }

    @Test void skipsUnchangedValidatedBytesWithoutCallingLlmOrWritingCheckpoint() throws Exception {
        var fixture=new Fixture();
        var item=new SourceUpdateItem(); item.setId(4L); item.setSourceUpdateId(2L); item.setAfterData("original");
        String envelope=fixture.mapper.writeValueAsString(Map.of("itemIds",List.of(4L),"revisions",Map.of("4",0L),"outputFingerprint","out"));
        when(fixture.items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(2L)).thenReturn(List.of(item));
        when(fixture.store.find(2L,"BUSINESS_RULE","3")).thenReturn(Optional.of(
                new GenerationCheckpointStore.Checkpoint("BUSINESS_RULE","3","same","{}",envelope,
                    IncrementalCheckpointCoordinator.outputDigest(fixture.mapper,List.of(item)),"SUCCEEDED")));
        var result=fixture.run(()->fixture.coordinator.executeBatch(1L,2L,GenerationProgressStage.BUSINESS_RULE,Set.of(3L),Path.of("candidate"),
            BusinessRuleResponseDto.class,ids->{throw new AssertionError("LLM must be skipped");},(ids,output)->{throw new AssertionError("output must not be rewritten");}));
        assertThat(result).isNull();
        verify(fixture.store,never()).complete(any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }

    @Test void staleInputCannotPersistUnderAnOtherwiseValidLease() {
        var fixture=new Fixture();
        when(fixture.fingerprints.fingerprint(anyString(),any(),any())).thenReturn(
            new GenerationFingerprintService.Fingerprint("same","{}",true),
            new GenerationFingerprintService.Fingerprint("changed","{}",true));
        var persistence=mock(java.util.function.BiConsumer.class);
        assertThatThrownBy(()->fixture.run(()->fixture.coordinator.executeBatch(1L,2L,GenerationProgressStage.BUSINESS_RULE,Set.of(3L),Path.of("candidate"),
            BusinessRuleResponseDto.class,ids->new BusinessRuleResponseDto(List.of(new GeneratedBusinessRuleDto(3L,"Rule","BUSINESS_LOGIC","STMT-1"))),persistence)))
            .hasMessageContaining("input changed");
        verifyNoInteractions(persistence);
        verify(fixture.store,never()).complete(any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }

    @Test void expiredWorkerCannotPersistArtifacts() {
        var fixture=new Fixture();
        doThrow(new IllegalStateException("expired lease")).when(fixture.store).assertLease(any());
        var persistence=mock(java.util.function.BiConsumer.class);
        assertThatThrownBy(()->fixture.run(()->fixture.coordinator.executeBatch(1L,2L,GenerationProgressStage.BUSINESS_RULE,Set.of(3L),Path.of("candidate"),
            BusinessRuleResponseDto.class,ids->new BusinessRuleResponseDto(List.of(new GeneratedBusinessRuleDto(3L,"Rule","BUSINESS_LOGIC","STMT-1"))),persistence)))
            .hasMessageContaining("expired lease");
        verifyNoInteractions(persistence);
    }

    @Test void changedOutputOnlyDependencyInvalidatesCheckpointEvenWhenInputAndOutputBytesAreUnchanged() throws Exception {
        var fixture=new Fixture();
        var output=new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(3L,"ExampleTest","testExample","demo","NEW_TEST","class ExampleTest {}")));
        var item=new SourceUpdateItem(); item.setId(4L); item.setSourceUpdateId(2L);
        item.setTargetType(com.greytest.entity.enums.SourceUpdateTargetType.UNIT_TEST);
        item.setAfterData(fixture.mapper.writeValueAsString(output.unitTests().get(0)));
        when(fixture.items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(2L)).thenReturn(List.of(item));
        var recorded=Map.of("response",output,"itemIds",List.of(4L),"revisions",Map.of("4",0L),"outputFingerprint","old-factory");
        when(fixture.store.find(2L,"UNIT_TEST","3")).thenReturn(Optional.of(new GenerationCheckpointStore.Checkpoint("UNIT_TEST","3","same","{}",
            fixture.mapper.writeValueAsString(recorded),IncrementalCheckpointCoordinator.outputDigest(fixture.mapper,List.of(item)),"SUCCEEDED")));
        when(fixture.fingerprints.outputFingerprint(any(),any(),any())).thenReturn(new GenerationFingerprintService.Fingerprint("changed-factory","{}",true));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        fixture.run(()->fixture.coordinator.executeBatch(1L,2L,GenerationProgressStage.UNIT_TEST,Set.of(3L),Path.of("candidate"),UnitTestResponseDto.class,
            ids->{calls.incrementAndGet();return output;},(ids,result)->{}));
        assertThat(calls).hasValue(1);
        verify(fixture.store).complete(any(),eq("UNIT_TEST"),eq("3"),eq("same"),anyString(),contains("changed-factory"),anyString());
    }

    @Test void unresolvedOutputIsPersistedAsBlockedAndNeverAdvertisedAsSucceeded() throws Exception {
        var fixture=new Fixture();
        var output=new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(3L,"ExampleTest","testExample","demo","NEW_TEST","class ExampleTest {}")));
        var item=new SourceUpdateItem(); item.setId(4L); item.setSourceUpdateId(2L);
        item.setTargetType(com.greytest.entity.enums.SourceUpdateTargetType.UNIT_TEST);
        item.setAfterData(fixture.mapper.writeValueAsString(output.unitTests().get(0)));
        when(fixture.items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(2L)).thenReturn(List.of(item));
        when(fixture.fingerprints.outputFingerprint(any(),any(),any())).thenReturn(new GenerationFingerprintService.Fingerprint("unknown","{\"problems\":[\"unknown production call\"]}",false));
        var persisted=new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(()->fixture.run(()->fixture.coordinator.executeBatch(1L,2L,GenerationProgressStage.UNIT_TEST,Set.of(3L),Path.of("candidate"),UnitTestResponseDto.class,
            ids->output,(ids,result)->persisted.set(true))))
            .isInstanceOf(com.greytest.service.agent.TestFeasibilityBlockedException.class);
        assertThat(persisted).isTrue();
        verify(fixture.store,never()).complete(any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
        verify(fixture.store).mark(any(),eq("UNIT_TEST"),eq("3"),eq("BLOCKED"),eq("same"),anyString(),contains("unknown"),anyString());
    }

    @Test void retainedCaseRevalidatesSavedDraftRatherThanOverwritingCheckpointWithActiveOutput() throws Exception {
        var fixture = new Fixture();
        var active = new com.greytest.entity.UnitTest(); active.setId(9L); active.setTestCaseId(3L);
        active.setSourceCode("class ActiveTest {}");
        var draft = new GeneratedUnitTestDto(3L, "DraftTest", "testDraft", "demo", "NEW_TEST", "class SavedDraftTest {}");
        var item = new SourceUpdateItem(); item.setId(4L); item.setSourceUpdateId(2L); item.setTargetId(9L); item.setUserRevision(0L);
        item.setTargetType(com.greytest.entity.enums.SourceUpdateTargetType.UNIT_TEST);
        item.setAction(com.greytest.entity.enums.SourceUpdateAction.UPDATE);
        item.setAfterData(fixture.mapper.writeValueAsString(draft));
        when(fixture.agent.parseSnapshotOutput(eq(GenerationProgressStage.UNIT_TEST), anyString())).thenReturn(new UnitTestResponseDto(List.of(draft)));
        when(fixture.items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(2L)).thenReturn(List.of(item));
        fixture.run(() -> { fixture.coordinator.recordRetainedUnitTest(1L, 2L, 3L, active, Path.of("candidate")); return null; });
        verify(fixture.store).complete(any(), eq("UNIT_TEST"), eq("3"), anyString(), anyString(), contains("SavedDraftTest"), anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"invalid", "unresolved"})
    void invalidRetainedDraftCannotSilentlyDisappearFromStageValidation(String mode) throws Exception {
        var fixture = new Fixture();
        var active = new com.greytest.entity.UnitTest(); active.setId(9L); active.setTestCaseId(3L); active.setSourceCode("class ActiveTest {}");
        var output = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(3L, "DraftTest", "testDraft", "demo", "NEW_TEST", "class DraftTest {}")));
        var item = new SourceUpdateItem(); item.setId(4L); item.setSourceUpdateId(2L); item.setTargetId(9L); item.setUserRevision(0L);
        item.setTargetType(com.greytest.entity.enums.SourceUpdateTargetType.UNIT_TEST); item.setAction(com.greytest.entity.enums.SourceUpdateAction.UPDATE);
        item.setAfterData(fixture.mapper.writeValueAsString(output.unitTests().get(0)));
        when(fixture.items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(2L)).thenReturn(List.of(item));
        when(fixture.agent.parseSnapshotOutput(eq(GenerationProgressStage.UNIT_TEST), anyString())).thenReturn(output);
        if (mode.equals("invalid")) doThrow(new com.greytest.service.agent.LlmResponseException("invalid draft"))
                .when(fixture.agent).validateSnapshotOutput(any(), anyLong(), any(), any(), any());
        else when(fixture.fingerprints.outputFingerprint(any(), any(), any())).thenReturn(new GenerationFingerprintService.Fingerprint("bad", "{}", false));
        assertThatThrownBy(() -> fixture.run(() -> { fixture.coordinator.recordRetainedUnitTest(1L, 2L, 3L, active, Path.of("candidate")); return null; }))
                .isInstanceOf(com.greytest.service.agent.TestFeasibilityBlockedException.class);
        verify(fixture.store, never()).complete(any(), eq("UNIT_TEST"), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void applyRevalidatesDraftWhenOnlyAProvenApplicationRepackChangedRuntimeHash() throws Exception {
        var fixture = runtimeApplyFixture("");
        try (var runtime = mockStatic(ParserDependencyClasspath.class)) {
            runtime.when(() -> ParserDependencyClasspath.compatibleRuntimeIdentity("old-runtime", "new-runtime")).thenReturn(true);
            fixture.coordinator.assertReadyForApply(2L);
            runtime.verify(() -> ParserDependencyClasspath.compatibleRuntimeIdentity("old-runtime", "new-runtime"), times(1));
            verify(fixture.agent).validateSnapshotOutput(eq(GenerationProgressStage.BUSINESS_RULE), eq(1L), any(), any(), any());
            verify(fixture.agent, never()).generateSnapshotBusinessRules(anyLong(), anySet(), any());
            verify(fixture.store, never()).complete(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"source", "configuration", "output", "libraries", "semantic"})
    void runtimeRepackDoesNotBypassRealInputOutputOrValidationChanges(String change) throws Exception {
        var fixture = runtimeApplyFixture(change);
        try (var runtime = mockStatic(ParserDependencyClasspath.class)) {
            runtime.when(() -> ParserDependencyClasspath.compatibleRuntimeIdentity("old-runtime", "new-runtime")).thenReturn(!change.equals("libraries"));
            assertThatThrownBy(() -> fixture.coordinator.assertReadyForApply(2L)).isInstanceOf(IllegalArgumentException.class);
            verify(fixture.agent, never()).generateSnapshotBusinessRules(anyLong(), anySet(), any());
        }
    }

    private Fixture runtimeApplyFixture(String change) throws Exception {
        var fixture = new Fixture();
        var updates = mock(com.greytest.repository.SourceUpdateRepository.class);
        var revisions = mock(com.greytest.repository.SourceRevisionRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.coordinator, "updates", updates);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.coordinator, "revisions", revisions);
        var update = new com.greytest.entity.SourceUpdate(); update.setId(2L); update.setProjectId(1L); update.setCandidateRevisionId(9L);
        update.setImpactSummary("{\"changedMethods\":[],\"affectedServiceMethods\":[],\"affectedBusinessRuleIds\":[],\"affectedTestPlanIds\":[],\"affectedTestCaseIds\":[],\"affectedUnitTestIds\":[]}");
        var revision = new com.greytest.entity.SourceRevision(); revision.setStoragePath("candidate");
        when(updates.findById(2L)).thenReturn(Optional.of(update)); when(revisions.findById(9L)).thenReturn(Optional.of(revision));
        for (var stage : GenerationProgressStage.values())
            when(fixture.store.find(2L, stage.name(), "__stage__")).thenReturn(Optional.of(new GenerationCheckpointStore.Checkpoint(
                    stage.name(), "__stage__", "receipt", "{}", stage == GenerationProgressStage.BUSINESS_RULE ? "{\"targets\":[3]}" : "{\"targets\":[]}", "digest", "SUCCEEDED")));
        var old = fixture.mapper.createObjectNode(); old.putObject("sourceDependencies").put("analysis-classpath-v1", "old-runtime").put("Subject#run()", "body");
        old.putObject("configuration").put("model", "same"); old.putArray("problems"); old.put("reusable", true);
        var current = old.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) current.path("sourceDependencies")).put("analysis-classpath-v1", "new-runtime");
        if (change.equals("source")) ((com.fasterxml.jackson.databind.node.ObjectNode) current.path("sourceDependencies")).put("Subject#run()", "changed");
        if (change.equals("configuration")) ((com.fasterxml.jackson.databind.node.ObjectNode) current.path("configuration")).put("model", "changed");
        when(fixture.fingerprints.fingerprint(anyString(), any(), any())).thenReturn(new GenerationFingerprintService.Fingerprint("new-input", current.toString(), true));
        var output = new BusinessRuleResponseDto(List.of(new GeneratedBusinessRuleDto(3L, "Rule", "BUSINESS_LOGIC", "STMT-1")));
        var envelope = fixture.mapper.createObjectNode().put("outputFingerprint", "old-output");
        envelope.set("outputProvenance", old); envelope.set("response", fixture.mapper.valueToTree(output));
        when(fixture.store.find(2L, "BUSINESS_RULE", "3")).thenReturn(Optional.of(new GenerationCheckpointStore.Checkpoint(
                "BUSINESS_RULE", "3", "old-input", old.toString(), envelope.toString(), "digest", "SUCCEEDED")));
        when(fixture.agent.parseSnapshotOutput(eq(GenerationProgressStage.BUSINESS_RULE), anyString())).thenReturn(output);
        var out = old.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) out.path("sourceDependencies")).put("analysis-classpath-v1", "new-runtime");
        if (change.equals("output")) ((com.fasterxml.jackson.databind.node.ObjectNode) out.path("sourceDependencies")).put("Subject#run()", "changed-output");
        when(fixture.fingerprints.outputFingerprint(any(), any(), any())).thenReturn(new GenerationFingerprintService.Fingerprint("new-output", out.toString(), true));
        if (change.equals("semantic")) doThrow(new com.greytest.service.agent.LlmResponseException("invalid draft"))
                .when(fixture.agent).validateSnapshotOutput(any(), anyLong(), any(), any(), any());
        return fixture;
    }

    private static class Fixture {
        final ObjectMapper mapper=new ObjectMapper();
        final GenerationCheckpointStore store=mock(GenerationCheckpointStore.class);
        final GenerationFingerprintService fingerprints=mock(GenerationFingerprintService.class);
        final SourceUpdateItemRepository items=mock(SourceUpdateItemRepository.class);
        final AIAgentService agent=mock(AIAgentService.class);
        final GenerationJobService jobs=mock(GenerationJobService.class);
        final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
        final IncrementalCheckpointCoordinator coordinator;
        Fixture() {
            when(jobs.executeMutation(eq(1L),any(Supplier.class))).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
            when(store.claim(eq(1L),eq(2L),any(Duration.class))).thenReturn(new GenerationCheckpointStore.Lease(1L,2L,"owner",1L));
            when(store.find(anyLong(),anyString(),anyString())).thenReturn(Optional.empty());
            when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(agent.snapshotContext(any(),anyLong(),anySet(),any())).thenReturn(Map.of("source","current"));
            when(fingerprints.fingerprint(anyString(),any(),any())).thenReturn(new GenerationFingerprintService.Fingerprint("same","{}",true));
            when(fingerprints.outputFingerprint(any(),any(),any())).thenReturn(new GenerationFingerprintService.Fingerprint("out","{}",true));
            coordinator=new IncrementalCheckpointCoordinator(store,fingerprints,items,agent,mapper,tx,jobs);
        }
        <T> T run(Supplier<T> task) { return coordinator.withRun(1L,2L,5L,task); }
    }
}
