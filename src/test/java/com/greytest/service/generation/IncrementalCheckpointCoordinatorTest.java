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
        when(fixture.fingerprints.outputFingerprint(any(),any())).thenReturn(new GenerationFingerprintService.Fingerprint("changed-factory","{}",true));
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
        when(fixture.fingerprints.outputFingerprint(any(),any())).thenReturn(new GenerationFingerprintService.Fingerprint("unknown","{\"problems\":[\"unknown production call\"]}",false));
        var persisted=new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(()->fixture.run(()->fixture.coordinator.executeBatch(1L,2L,GenerationProgressStage.UNIT_TEST,Set.of(3L),Path.of("candidate"),UnitTestResponseDto.class,
            ids->output,(ids,result)->persisted.set(true))))
            .isInstanceOf(com.greytest.service.agent.TestFeasibilityBlockedException.class);
        assertThat(persisted).isTrue();
        verify(fixture.store,never()).complete(any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
        verify(fixture.store).mark(any(),eq("UNIT_TEST"),eq("3"),eq("BLOCKED"),eq("same"),anyString(),contains("unknown"),anyString());
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
            when(fingerprints.outputFingerprint(any(),any())).thenReturn(new GenerationFingerprintService.Fingerprint("out","{}",true));
            coordinator=new IncrementalCheckpointCoordinator(store,fingerprints,items,agent,mapper,tx,jobs);
        }
        <T> T run(Supplier<T> task) { return coordinator.withRun(1L,2L,5L,task); }
    }
}
