package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.greytest.dto.BusinessRuleDto;
import com.greytest.dto.BusinessRuleReviewDto;
import com.greytest.dto.CreateBusinessRuleRequest;
import com.greytest.dto.ReviewedBusinessRuleDto;
import com.greytest.dto.UpdateBusinessRuleRequest;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleReviewResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedBusinessRuleDto;
import com.greytest.dto.agent.GenerationResponseDtos.ReviewedBusinessRuleSuggestionDto;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.AuthUser;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.Project;
import com.greytest.entity.enums.ClassType;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.UserTier;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.agent.AIAgentService;

@ExtendWith(MockitoExtension.class)
class BusinessRuleServiceTest {

    @Mock private BusinessRuleRepository businessRuleRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private JavaClassRepository javaClassRepository;
    @Mock private JavaMethodRepository javaMethodRepository;
    @Mock private AIAgentService aiAgentService;
    @Mock private GenerationProgressService generationProgressService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;
    @Mock private ServiceScopeResolver scopeResolver;
    @Mock private ServicePipelineStatusService scopedStatuses;
    @Mock private AuthUserRepository users;
    private LlmBatchExecutor batchExecutor;

    @AfterEach
    void cleanUpBatches() {
        GenerationJobContext.clear();
        if (batchExecutor != null) batchExecutor.shutdown();
    }

    @BeforeEach
    void configureTransactions() {
        org.mockito.Mockito.lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        org.mockito.Mockito.lenient().when(aiAgentService.generateBusinessRules(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anySet(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    throw new com.greytest.service.agent.LlmResponseException(invocation.getArgument(2));
                });
    }

    @Test
    void generatesIndependentBatchesConcurrentlyWithoutDuplicateRuleCodes() throws Exception {
        mockProject();
        mockProjectSave();
        mockServiceMethods(method(11L, "first"), method(12L, "second"),
                method(13L, "third"), method(14L, "fourth"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        mockBusinessRuleSave();
        var started = new java.util.concurrent.CountDownLatch(2);
        when(aiAgentService.generateBusinessRules(org.mockito.ArgumentMatchers.eq(1L), any(Set.class)))
                .thenAnswer(invocation -> {
                    assertThat(GenerationJobContext.actorUserId()).isEqualTo(42L);
                    started.countDown();
                    assertThat(started.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    Set<Long> ids = invocation.getArgument(1);
                    return new BusinessRuleResponseDto(ids.stream().map(id ->
                            new GeneratedBusinessRuleDto(id, "Rule for method " + id, "BUSINESS_LOGIC")).toList());
                });
        var executor = new LlmBatchExecutor(2, 0, 0);
        GenerationJobContext.bind(42L, ignored -> { });
        try {
            var service = new BusinessRuleService(businessRuleRepository, projectRepository,
                    javaClassRepository, javaMethodRepository, aiAgentService, generationProgressService,
                    transactionManager, scopeResolver, scopedStatuses, executor);
            var rules = service.generate(1L);
            assertThat(rules).hasSize(4);
            assertThat(rules).extracting(BusinessRuleDto::ruleCode).doesNotHaveDuplicates();
            assertThat(rules).extracting(BusinessRuleDto::methodId).containsExactlyInAnyOrder(11L, 12L, 13L, 14L);
        } finally {
            GenerationJobContext.clear();
            executor.shutdown();
        }
    }

    @Test
    void concurrentFailureKeepsLaterCompletedBatchAndResumeSkipsIt() {
        mockProject();
        mockProjectSave();
        mockSevenMethods();
        List<BusinessRule> persisted = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(businessRuleRepository.findByProjectId(1L)).thenAnswer(ignored -> List.copyOf(persisted));
        AtomicLong ids = new AtomicLong(100);
        when(businessRuleRepository.save(any(BusinessRule.class))).thenAnswer(invocation -> {
            BusinessRule rule = invocation.getArgument(0);
            rule.setId(ids.getAndIncrement());
            persisted.add(rule);
            return rule;
        });
        CountDownLatch secondSaved = new CountDownLatch(1);
        doAnswer(ignored -> { secondSaved.countDown(); return null; }).when(generationProgressService)
                .advanceStep(org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE),
                        org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.anyString());
        AtomicBoolean firstAttempt = new AtomicBoolean(true);
        var original = new com.greytest.service.agent.LlmResponseException("provider failed");
        when(aiAgentService.generateBusinessRules(org.mockito.ArgumentMatchers.eq(1L), any(Set.class)))
                .thenAnswer(invocation -> {
                    Set<Long> methodIds = invocation.getArgument(1);
                    if (methodIds.contains(11L) && firstAttempt.getAndSet(false)) {
                        assertThat(secondSaved.await(5, TimeUnit.SECONDS)).isTrue();
                        throw original;
                    }
                    return generatedRules(methodIds);
                });
        var executor = new LlmBatchExecutor(2, 0, 0);
        try {
            var service = new BusinessRuleService(businessRuleRepository, projectRepository,
                    javaClassRepository, javaMethodRepository, aiAgentService, generationProgressService,
                    transactionManager, scopeResolver, scopedStatuses, executor);
            assertThatThrownBy(() -> service.generate(1L)).isSameAs(original);
            assertThat(persisted).extracting(BusinessRule::getMethodId).containsExactly(14L, 15L, 16L);
            assertThat(service.generate(1L)).extracting(BusinessRuleDto::methodId)
                    .containsExactlyInAnyOrder(11L, 12L, 13L, 17L);
            assertThat(persisted).hasSize(7).extracting(BusinessRule::getRuleCode).doesNotHaveDuplicates();
            verify(generationProgressService).resume(org.mockito.ArgumentMatchers.eq(1L),
                    org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE),
                    org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq(Set.of(1)),
                    org.mockito.ArgumentMatchers.anyString());
            verify(aiAgentService).generateBusinessRules(1L, Set.of(14L, 15L, 16L));
        } finally {
            executor.shutdown();
        }
    }
    @Test
    void pauseLetsRunningBatchesPersistAndSkipsQueuedBatches() {
        mockProject();
        mockServiceMethods(method(11L, "first"), method(12L, "second"), method(13L, "third"),
                method(14L, "fourth"), method(15L, "fifth"), method(16L, "sixth"), method(17L, "seventh"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        mockBusinessRuleSave();
        var progress = new GenerationProgressService();
        var started = new java.util.concurrent.CountDownLatch(2);
        when(aiAgentService.generateBusinessRules(org.mockito.ArgumentMatchers.eq(1L), any(Set.class)))
                .thenAnswer(invocation -> {
                    started.countDown();
                    assertThat(started.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    progress.pause(1L, com.greytest.dto.GenerationProgressStage.BUSINESS_RULE, "Pause requested");
                    Set<Long> methodIds = invocation.getArgument(1);
                    return new BusinessRuleResponseDto(methodIds.stream().map(id ->
                            new GeneratedBusinessRuleDto(id, "Rule for method " + id, "BUSINESS_LOGIC")).toList());
                });
        var executor = new LlmBatchExecutor(2, 0, 0);
        try {
            var service = new BusinessRuleService(businessRuleRepository, projectRepository,
                    javaClassRepository, javaMethodRepository, aiAgentService, progress,
                    transactionManager, scopeResolver, scopedStatuses, executor);
            assertThat(service.generate(1L)).hasSize(6);
            verify(aiAgentService, org.mockito.Mockito.never()).generateBusinessRules(1L, Set.of(17L));
            assertThat(progress.isPaused(1L, com.greytest.dto.GenerationProgressStage.BUSINESS_RULE)).isTrue();
            verify(projectRepository, org.mockito.Mockito.never()).save(any());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void generatePersistsAiGeneratedRules() {
        mockProject();
        mockProjectSave();
        mockServiceMethods(method(11L, "createUser"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(new BusinessRuleResponseDto(List.of(
                new GeneratedBusinessRuleDto(11L, "Email phai hop le truoc khi tao user.", "VALIDATION"),
                new GeneratedBusinessRuleDto(11L, "User moi khong duoc trung email da ton tai.", "BUSINESS_LOGIC"))));
        mockBusinessRuleSave();

        List<BusinessRuleDto> rules = service().generate(1L);

        assertThat(rules).hasSize(2);
        assertThat(rules).extracting(BusinessRuleDto::description)
                .containsExactly(
                        "Email phai hop le truoc khi tao user.",
                        "User moi khong duoc trung email da ton tai.");
        assertThat(rules).extracting(BusinessRuleDto::source)
                .containsOnly(RuleSource.AI_GENERATED);
    }

    @Test
    void generateGroupsUpToThreeMethodsInOneRequest() {
        mockProject();
        mockProjectSave();
        mockServiceMethods(method(11L, "createUser"), method(12L, "updateUser"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L, 12L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(11L, "User moi phai co email hop le.", "VALIDATION"),
                        new GeneratedBusinessRuleDto(12L, "Chi cap nhat user dang ton tai.", "BUSINESS_LOGIC"))));
        mockBusinessRuleSave();

        List<BusinessRuleDto> rules = service().generate(1L);

        assertThat(rules).extracting(BusinessRuleDto::methodId).containsExactly(11L, 12L);
        verify(aiAgentService).generateBusinessRules(1L, Set.of(11L, 12L));
    }

    @ParameterizedTest
    @CsvSource({"FREE,2", "PLUS,3", "PRO,3"})
    void generateUsesTierConcurrencyAndAssignsUniqueCodes(UserTier tier, int expectedPeak) {
        mockProject();
        mockProjectSave();
        mockSevenMethods();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        mockBusinessRuleSave();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch firstWave = new CountDownLatch(expectedPeak);
        when(aiAgentService.generateBusinessRules(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.<Set<Long>>any())).thenAnswer(invocation -> {
                    assertThat(GenerationJobContext.actorUserId()).isEqualTo(42L);
                    int running = active.incrementAndGet();
                    peak.accumulateAndGet(running, Math::max);
                    firstWave.countDown();
                    try {
                        assertThat(firstWave.await(5, TimeUnit.SECONDS)).isTrue();
                        return generatedRules(invocation.getArgument(1));
                    } finally {
                        active.decrementAndGet();
                    }
                });

        List<BusinessRuleDto> rules = parallelService(tier).generate(1L);

        assertThat(peak.get()).isEqualTo(expectedPeak);
        assertThat(rules).extracting(BusinessRuleDto::methodId)
                .containsExactlyInAnyOrder(11L, 12L, 13L, 14L, 15L, 16L, 17L);
        assertThat(rules).extracting(BusinessRuleDto::ruleCode)
                .containsExactlyInAnyOrder("BR-001", "BR-002", "BR-003", "BR-004", "BR-005", "BR-006", "BR-007");
    }

    @Test
    void generateReportsParallelSaveFailureAndKeepsEarlierCommit() {
        mockProject();
        mockServiceMethods(method(11L, "m11"), method(12L, "m12"), method(13L, "m13"), method(14L, "m14"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        CountDownLatch firstSaved = new CountDownLatch(1);
        doAnswer(ignored -> { firstSaved.countDown(); return null; }).when(generationProgressService)
                .advanceStep(org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE),
                        org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.anyString());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L, 12L, 13L)))
                .thenReturn(generatedRules(Set.of(11L, 12L, 13L)));
        when(aiAgentService.generateBusinessRules(1L, Set.of(14L))).thenAnswer(ignored -> {
            assertThat(firstSaved.await(5, TimeUnit.SECONDS)).isTrue();
            return generatedRules(Set.of(14L));
        });
        AtomicLong saves = new AtomicLong();
        when(businessRuleRepository.save(any(BusinessRule.class))).thenAnswer(invocation -> {
            BusinessRule rule = invocation.getArgument(0);
            if (rule.getMethodId().equals(14L)) throw new IllegalStateException("database unavailable");
            rule.setId(saves.incrementAndGet());
            return rule;
        });

        assertThatThrownBy(() -> parallelService(UserTier.PLUS, 2).generate(1L))
                .isInstanceOf(IllegalStateException.class).hasMessage("database unavailable");

        assertThat(saves.get()).isEqualTo(3);
        verify(generationProgressService).fail(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE),
                org.mockito.ArgumentMatchers.contains("Dừng ở batch 2/2. Đã lưu 3 Business Rule"));
        verify(transactionManager).commit(transactionStatus);
        verify(transactionManager).rollback(transactionStatus);
    }
    @Test
    void generateResumesOnlyFailedMiddleBatchAfterLaterInFlightBatchWasSaved() {
        mockProject();
        mockProjectSave();
        mockSevenMethods();
        List<BusinessRule> persisted = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(businessRuleRepository.findByProjectId(1L)).thenAnswer(ignored -> List.copyOf(persisted));
        when(businessRuleRepository.save(any(BusinessRule.class))).thenAnswer(invocation -> {
            BusinessRule rule = invocation.getArgument(0);
            rule.setId(100L + persisted.size());
            persisted.add(rule);
            return rule;
        });
        CountDownLatch allStarted = new CountDownLatch(3);
        CountDownLatch thirdSaved = new CountDownLatch(1);
        doAnswer(ignored -> { thirdSaved.countDown(); return null; }).when(generationProgressService)
                .advanceStep(org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE),
                        org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.anyString());
        AtomicBoolean firstAttempt = new AtomicBoolean(true);
        var failure = new com.greytest.service.agent.LlmResponseException("AI batch failed");
        when(aiAgentService.generateBusinessRules(org.mockito.ArgumentMatchers.eq(1L), any(Set.class)))
                .thenAnswer(invocation -> {
                    Set<Long> methodIds = invocation.getArgument(1);
                    allStarted.countDown();
                    assertThat(allStarted.await(5, TimeUnit.SECONDS)).isTrue();
                    if (methodIds.contains(14L) && firstAttempt.getAndSet(false)) {
                        assertThat(thirdSaved.await(5, TimeUnit.SECONDS)).isTrue();
                        throw failure;
                    }
                    return generatedRules(methodIds);
                });
        BusinessRuleService service = parallelService(UserTier.PRO);

        assertThatThrownBy(() -> service.generate(1L)).isSameAs(failure);
        assertThat(persisted).extracting(BusinessRule::getMethodId).containsExactlyInAnyOrder(11L, 12L, 13L, 17L);
        verify(generationProgressService).fail(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE),
                org.mockito.ArgumentMatchers.contains("Dừng ở batch 2/3."));

        List<BusinessRuleDto> resumed = service.generate(1L);

        assertThat(resumed).extracting(BusinessRuleDto::methodId).containsExactly(14L, 15L, 16L);
        assertThat(resumed).extracting(BusinessRuleDto::ruleCode).containsExactly("BR-005", "BR-006", "BR-007");
        verify(aiAgentService).generateBusinessRules(1L, Set.of(11L, 12L, 13L));
        verify(aiAgentService).generateBusinessRules(1L, Set.of(17L));
        verify(generationProgressService).resume(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq(Set.of(0, 2)),
                org.mockito.ArgumentMatchers.anyString());
    }
    @Test
    void generateStopsQueuedAiCallsAfterInFlightBatchesFail() {
        mockProject();
        mockServiceMethods(java.util.stream.LongStream.rangeClosed(11, 22)
                .mapToObj(id -> method(id, "m" + id)).toArray(JavaMethod[]::new));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        CountDownLatch firstWave = new CountDownLatch(2);
        AtomicInteger requests = new AtomicInteger();
        var failure = new com.greytest.service.agent.LlmResponseException("provider unavailable");
        when(aiAgentService.generateBusinessRules(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.<Set<Long>>any())).thenAnswer(ignored -> {
                    requests.incrementAndGet();
                    firstWave.countDown();
                    assertThat(firstWave.await(5, TimeUnit.SECONDS)).isTrue();
                    throw failure;
                });

        assertThatThrownBy(() -> parallelService(UserTier.PLUS, 2).generate(1L)).isSameAs(failure);

        assertThat(requests.get()).isEqualTo(2);
        verify(businessRuleRepository, org.mockito.Mockito.never()).save(any(BusinessRule.class));
        verify(generationProgressService).fail(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE),
                org.mockito.ArgumentMatchers.contains("Dừng ở batch 1/4. Chưa lưu"));
    }

    @Test
    void generatePauseSavesInFlightBatchesAndSkipsQueuedBatch() {
        mockProject();
        mockSevenMethods();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        mockBusinessRuleSave();
        AtomicBoolean paused = new AtomicBoolean();
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch firstSaved = new CountDownLatch(1);
        when(generationProgressService.isPaused(1L, com.greytest.dto.GenerationProgressStage.BUSINESS_RULE))
                .thenAnswer(ignored -> paused.get());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L, 12L, 13L))).thenAnswer(ignored -> {
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            return generatedRules(Set.of(11L, 12L, 13L));
        });
        when(aiAgentService.generateBusinessRules(1L, Set.of(14L, 15L, 16L))).thenAnswer(ignored -> {
            secondStarted.countDown();
            assertThat(firstSaved.await(5, TimeUnit.SECONDS)).isTrue();
            return generatedRules(Set.of(14L, 15L, 16L));
        });
        doAnswer(ignored -> {
            paused.set(true);
            firstSaved.countDown();
            return null;
        }).when(generationProgressService).advanceStep(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE),
                org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.anyString());

        List<BusinessRuleDto> rules = parallelService(UserTier.PLUS, 2).generate(1L);

        assertThat(rules).extracting(BusinessRuleDto::methodId).containsExactly(11L, 12L, 13L, 14L, 15L, 16L);
        verify(aiAgentService, org.mockito.Mockito.never()).generateBusinessRules(1L, Set.of(17L));
        verify(projectRepository, org.mockito.Mockito.never()).save(any(Project.class));
    }

    @Test
    void generateKeepsIdenticalMethodLevelRulesForDifferentMethods() {
        mockProject();
        mockProjectSave();
        mockServiceMethods(method(11L, "createUser"), method(12L, "updateUser"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L, 12L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(11L, "Tai khoan duoc cap nhat.", "BUSINESS_LOGIC"),
                        new GeneratedBusinessRuleDto(12L, "Tai khoan duoc cap nhat.", "BUSINESS_LOGIC"))));
        mockBusinessRuleSave();

        List<BusinessRuleDto> rules = service().generate(1L);

        assertThat(rules).extracting(BusinessRuleDto::methodId).containsExactly(11L, 12L);
        verify(aiAgentService).generateBusinessRules(1L, Set.of(11L, 12L));
    }    @Test
    void generateKeepsEarlierCommittedBatchWhenLaterBatchFails() {
        mockProject();
        mockServiceMethods(
                method(11L, "firstMethod"), method(12L, "secondMethod"),
                method(13L, "thirdMethod"), method(14L, "fourthMethod"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L, 12L, 13L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(11L, "Rule cho method dau.", "BUSINESS_LOGIC"),
                        new GeneratedBusinessRuleDto(12L, "Rule cho method hai.", "BUSINESS_LOGIC"),
                        new GeneratedBusinessRuleDto(13L, "Rule cho method ba.", "BUSINESS_LOGIC"))));
        when(aiAgentService.generateBusinessRules(1L, Set.of(14L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(14L, "Rule cho method sau.", "BUSINESS_LOGIC"))));
        AtomicLong saveCount = new AtomicLong();
        when(businessRuleRepository.save(any(BusinessRule.class))).thenAnswer(invocation -> {
            BusinessRule rule = invocation.getArgument(0);
            if (saveCount.incrementAndGet() == 4) throw new IllegalStateException("database unavailable");
            rule.setId(100L);
            return rule;
        });

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");
        verify(generationProgressService).fail(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE),
                org.mockito.ArgumentMatchers.contains("Đã lưu 3 Business Rule trong phiên này."));
        verify(transactionManager).commit(transactionStatus);
        verify(transactionManager).rollback(transactionStatus);
    }    @Test
    void retrySkipsPersistedBatchesAndStartsAtFailedMethod() {
        mockProject();
        mockProjectSave();
        mockServiceMethods(
                method(11L, "firstMethod"), method(12L, "secondMethod"),
                method(13L, "thirdMethod"), method(14L, "failedMethod"));
        List<BusinessRule> persistedRules = new ArrayList<>();
        when(businessRuleRepository.findByProjectId(1L))
                .thenAnswer(ignored -> List.copyOf(persistedRules));
        AtomicLong ids = new AtomicLong(100);
        when(businessRuleRepository.save(any(BusinessRule.class))).thenAnswer(invocation -> {
            BusinessRule rule = invocation.getArgument(0);
            rule.setId(ids.getAndIncrement());
            persistedRules.add(rule);
            return rule;
        });
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L, 12L, 13L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(11L, "Rule da luu 1.", "BUSINESS_LOGIC"),
                        new GeneratedBusinessRuleDto(12L, "Rule da luu 2.", "BUSINESS_LOGIC"),
                        new GeneratedBusinessRuleDto(13L, "Rule da luu 3.", "BUSINESS_LOGIC"))));
        when(aiAgentService.generateBusinessRules(1L, Set.of(14L)))
                .thenThrow(new com.greytest.service.agent.LlmResponseException("batch failed"))
                .thenReturn(new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(14L, "Rule cua batch loi.", "BUSINESS_LOGIC"))));

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(com.greytest.service.agent.LlmResponseException.class)
                .hasMessageContaining("batch failed");

        List<BusinessRuleDto> resumed = service().generate(1L);

        assertThat(resumed).extracting(BusinessRuleDto::methodId).containsExactly(14L);
        verify(aiAgentService, times(1)).generateBusinessRules(1L, Set.of(11L, 12L, 13L));
        verify(aiAgentService, times(2)).generateBusinessRules(1L, Set.of(14L));
    }
    @Test
    void retryFinalizesStatusWhenRulesWereSavedBeforeFinalStatusFailure() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.ANALYZED);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        mockServiceMethods(method(11L, "createUser"));
        BusinessRule persistedRule = rule(100L, 11L, "Email phai hop le.");
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(), List.of(persistedRule));
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(new BusinessRuleResponseDto(List.of(
                new GeneratedBusinessRuleDto(11L, "Email phai hop le.", "VALIDATION"))));
        mockBusinessRuleSave();
        when(projectRepository.save(any(Project.class)))
                .thenThrow(new IllegalStateException("status update unavailable"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(IllegalStateException.class);
        // Mô phỏng DB vẫn giữ ANALYZED vì lần save trạng thái cuối đã rollback.
        project.setStatus(ProjectStatus.ANALYZED);
        assertThat(service().generate(1L)).isEmpty();

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.BR_PENDING_REVIEW);
        verify(aiAgentService).generateBusinessRules(1L, Set.of(11L));
        verify(projectRepository, org.mockito.Mockito.times(2)).save(any(Project.class));
    }

    @Test
    void generateReportsSaveStepWhenFailureHappensAfterLastBatch() {
        mockProject();
        mockServiceMethods(method(11L, "createUser"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(new BusinessRuleResponseDto(List.of(
                new GeneratedBusinessRuleDto(11L, "Email phai hop le.", "VALIDATION"))));
        mockBusinessRuleSave();
        when(projectRepository.save(any(Project.class))).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service().generate(1L)).isInstanceOf(IllegalStateException.class);

        verify(generationProgressService).fail(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.BUSINESS_RULE),
                org.mockito.ArgumentMatchers.contains("bước kiểm tra và lưu Business Rule"));
    }

    @Test
    void generateRejectsMethodOutsideCurrentBatch() {
        mockProject();
        mockServiceMethods(
                method(11L, "m11"), method(12L, "m12"), method(13L, "m13"),
                method(14L, "m14"), method(15L, "m15"), method(16L, "m16"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L, 12L, 13L))).thenReturn(new BusinessRuleResponseDto(List.of(
                new GeneratedBusinessRuleDto(14L, "Rule ngoai batch hien tai.", "BUSINESS_LOGIC"))));

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(com.greytest.service.agent.LlmResponseException.class)
                .hasMessageContaining("ngoài Service method");
    }

    @Test
    void generateKeepsEachServiceSeparateAndUsesSourceOrder() {
        mockProject();
        mockProjectSave();
        JavaClass firstService = serviceClass(10L, "src/main/java/a/AccountService.java", "a.AccountService");
        JavaClass secondService = serviceClass(20L, "src/main/java/b/PaymentService.java", "b.PaymentService");
        JavaMethod laterMethod = method(12L, "closeAccount");
        laterMethod.setLineStart(40);
        JavaMethod earlierMethod = method(11L, "openAccount");
        earlierMethod.setLineStart(20);
        JavaMethod paymentMethod = method(21L, "pay");
        paymentMethod.setLineStart(15);
        when(javaClassRepository.findByProjectIdAndClassType(1L, ClassType.SERVICE))
                .thenReturn(List.of(secondService, firstService));
        when(javaMethodRepository.findByClassIdIn(List.of(10L))).thenReturn(List.of(laterMethod, earlierMethod));
        when(javaMethodRepository.findByClassIdIn(List.of(20L))).thenReturn(List.of(paymentMethod));
        when(javaMethodRepository.findById(11L)).thenReturn(Optional.of(earlierMethod));
        when(javaMethodRepository.findById(12L)).thenReturn(Optional.of(laterMethod));
        when(javaMethodRepository.findById(21L)).thenReturn(Optional.of(paymentMethod));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        List<List<Long>> requestedBatches = new ArrayList<>();
        when(aiAgentService.generateBusinessRules(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.<Set<Long>>any())).thenAnswer(invocation -> {
                    Set<Long> methodIds = invocation.getArgument(1);
                    requestedBatches.add(List.copyOf(methodIds));
                    return new BusinessRuleResponseDto(methodIds.stream()
                            .map(id -> new GeneratedBusinessRuleDto(
                                    id, "Rule for method " + id, "BUSINESS_LOGIC"))
                            .toList());
                });
        mockBusinessRuleSave();

        service().generate(1L);

        assertThat(requestedBatches).containsExactly(
                List.of(11L, 12L),
                List.of(21L));
    }

    @Test
    void generateDoesNotRetryOrFabricateWhenSourceHasNoRule() {
        mockProject();
        mockProjectSave();
        mockServiceMethods(method(11L, "technicalHelper"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L)))
                .thenReturn(new BusinessRuleResponseDto(List.of()));

        assertThat(service().generate(1L)).isEmpty();

        verify(aiAgentService).generateBusinessRules(1L, Set.of(11L));
    }

    @Test
    void generateAcceptsOneBusinessRuleForAnIfDecision() {
        mockProject();
        mockProjectSave();
        JavaMethod branchedMethod = method(11L, "findUser");
        branchedMethod.setLineStart(20);
        branchedMethod.setSourceCode("""
                public String findUser(boolean exists) {
                    if (exists) {
                        return "found";
                    } else {
                        return "missing";
                    }
                }
                """);
        mockServiceMethods(branchedMethod);
        when(javaMethodRepository.findById(11L)).thenReturn(Optional.of(branchedMethod));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(
                                11L,
                                "Tra ve user theo ket qua kiem tra ton tai.",
                                "BUSINESS_LOGIC",
                                "IF-1"))));
        mockBusinessRuleSave();

        assertThat(service().generate(1L)).extracting(BusinessRuleDto::sourceBranchId)
                .containsExactly("IF-1");
    }

    @Test
    void generateAcceptsMethodLevelRuleAlongsideBranchRules() {
        mockProject();
        mockProjectSave();
        JavaMethod branchedMethod = method(11L, "calculateItemTotal");
        branchedMethod.setLineStart(11);
        branchedMethod.setSourceCode("""
                public BigDecimal calculateItemTotal(BigDecimal unitPrice, int quantity) {
                    if (unitPrice == null) throw new IllegalArgumentException();
                    if (unitPrice.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException();
                    if (quantity <= 0 || quantity > 10_000) throw new IllegalArgumentException();
                    return unitPrice.multiply(BigDecimal.valueOf(quantity));
                }
                """);
        mockServiceMethods(branchedMethod);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L)))
                .thenReturn(new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(11L, "Unit price khong duoc null.", "VALIDATION", "IF-1"),
                        new GeneratedBusinessRuleDto(11L, "Unit price phai duong.", "VALIDATION", "IF-2"),
                        new GeneratedBusinessRuleDto(11L, "Quantity phai nam trong gioi han.", "VALIDATION", "IF-3"),
                        new GeneratedBusinessRuleDto(11L, "Tong tien bang unit price nhan quantity.", "BUSINESS_LOGIC", "STMT-1"))));
        mockBusinessRuleSave();

        List<BusinessRuleDto> rules = service().generate(1L);

        assertThat(rules).hasSize(4);
        assertThat(rules).extracting(BusinessRuleDto::sourceBranchId)
                .containsExactly("IF-1", "IF-2", "IF-3", "STMT-1");
        verify(aiAgentService).generateBusinessRules(1L, Set.of(11L));
    }

    @Test
    void generateAcceptsStaticSourceAnchorForMethodLevelRule() {
        mockProject();
        JavaMethod branchlessMethod = method(11L, "getAppointmentById");
        branchlessMethod.setSourceCode("""
                public Appointment getAppointmentById(Long id) {
                    return repository.findById(id).orElseThrow();
                }
                """);
        mockServiceMethods(branchlessMethod);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(
                                11L, "Lich hen phai ton tai theo id.", "VALIDATION", "STMT-1"))));
        mockBusinessRuleSave();

        assertThat(service().generate(1L)).extracting(BusinessRuleDto::sourceBranchId)
                .containsExactly("STMT-1");
    }

    @Test
    void generateAllowsIndependentRulesForTheSameStatementAnchor() {
        mockProject();
        JavaMethod method = method(11L, "findUser");
        method.setSourceCode("""
                public User findUser(Long id) {
                    return repository.findById(id)
                            .map(this::enrich)
                            .orElseThrow(() -> new UserNotFoundException("User not found"));
                }
                """);
        mockServiceMethods(method);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(11L,
                                "Khi tìm thấy user, service enrich dữ liệu user.", "INTEGRATION", "STMT-1"),
                        new GeneratedBusinessRuleDto(11L,
                                "Khi không tìm thấy user, service ném UserNotFoundException với thông báo User not found.",
                                "VALIDATION", "STMT-1"))));
        mockBusinessRuleSave();

        assertThat(service().generate(1L)).extracting(BusinessRuleDto::sourceBranchId)
                .containsExactly("STMT-1", "STMT-1");
    }

    @Test
    void generateRejectsSourceAnchorOutsidePersistedMethodRange() {
        mockProject();
        JavaMethod method = method(11L, "findUser");
        method.setLineStart(20);
        method.setLineEnd(22);
        method.setSourceCode("""
                public String findUser(Long id) {
                    return repository
                            .findById(id)
                            .orElseThrow();
                }
                """);
        mockServiceMethods(method);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("Range source");
    }

    @Test
    void generateRejectsDuplicateDescriptionsAcrossDecisionsInSameMethod() {
        mockProject();
        JavaMethod branchedMethod = method(11L, "validateAppointment");
        branchedMethod.setSourceCode("""
                public String validateAppointment(boolean patientValid, boolean doctorValid) {
                    if (!patientValid) return "invalid";
                    if (!doctorValid) return "invalid";
                    return "valid";
                }
                """);
        mockServiceMethods(branchedMethod);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(11L, "Appointment is invalid.", "VALIDATION", "IF-1"),
                        new GeneratedBusinessRuleDto(11L, "Appointment is invalid.", "VALIDATION", "IF-2"))));

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(com.greytest.service.agent.LlmResponseException.class)
                .hasMessageContaining("trùng Business Rule");
    }

    @Test
    void generateRejectsUnknownBranchAlongsideMethodRule() {
        mockProject();
        JavaMethod branchedMethod = method(11L, "calculateItemTotal");
        branchedMethod.setSourceCode("""
                public BigDecimal calculateItemTotal(BigDecimal unitPrice) {
                    if (unitPrice == null) throw new IllegalArgumentException();
                    return unitPrice;
                }
                """);
        mockServiceMethods(branchedMethod);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(11L, "Rule hop le.", "VALIDATION", "IF-1"),
                        new GeneratedBusinessRuleDto(11L, "Rule sai.", "BUSINESS_LOGIC", "IF-99"),
                        new GeneratedBusinessRuleDto(11L, "Rule cap method.", "BUSINESS_LOGIC"))));

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(com.greytest.service.agent.LlmResponseException.class)
                .hasMessageContaining("source anchor không thuộc method");
    }

    @Test
    void generateRejectsBranchForMethodWithoutControlFlow() {
        mockProject();
        JavaMethod plainMethod = method(11L, "technicalHelper");
        plainMethod.setSourceCode("public String technicalHelper() { return \"ok\"; }");
        mockServiceMethods(plainMethod);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(11L, "Rule sai.", "BUSINESS_LOGIC", "IF-1"))));

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(com.greytest.service.agent.LlmResponseException.class)
                .hasMessageContaining("source anchor không thuộc method");
    }

    @Test
    void generateAcceptsOneBusinessRuleForEverySwitchDecision() {
        mockProject();
        mockProjectSave();
        JavaMethod method = method(11L, "taxRate");
        method.setLineStart(20);
        method.setSourceCode("""
                public int taxRate(String region) {
                    return switch (region) {
                        case "VN" -> 10;
                        case "US" -> 8;
                        default -> throw new IllegalArgumentException();
                    };
                }
                """);
        mockServiceMethods(method);
        when(javaMethodRepository.findById(11L)).thenReturn(Optional.of(method));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(
                                11L,
                                "Thue suat phu thuoc vao ma khu vuc hop le.",
                                "BUSINESS_LOGIC",
                                "SWITCH-1"))));
        mockBusinessRuleSave();

        assertThat(service().generate(1L)).extracting(BusinessRuleDto::sourceBranchId)
                .containsExactly("SWITCH-1");
    }
    @Test
    void generateStillRequiresOneRuleForEachDistinctIfDecision() {
        mockProject();
        JavaMethod branchedMethod = method(11L, "classifyUser");
        branchedMethod.setLineStart(20);
        branchedMethod.setSourceCode("""
                public String classifyUser(boolean exists, boolean active) {
                    if (!exists) return "missing";
                    if (active) return "active";
                    return "inactive";
                }
                """);
        mockServiceMethods(branchedMethod);
        when(javaMethodRepository.findById(11L)).thenReturn(Optional.of(branchedMethod));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(
                                11L, "User phai ton tai de phan loai.", "VALIDATION", "IF-1"))));

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(com.greytest.service.agent.LlmResponseException.class)
                .hasMessageContaining("chưa bao phủ đủ quyết định control-flow", "IF-2");
    }
    @Test
    void generateRetriesCurrentBatchWhenFirstResponseMissesBranch() {
        mockProject();
        mockProjectSave();
        JavaMethod branchedMethod = method(11L, "findUser");
        branchedMethod.setSourceCode("""
                public String findUser(boolean exists) {
                    if (exists) return "found";
                    return "missing";
                }
                """);
        mockServiceMethods(branchedMethod);
        when(javaMethodRepository.findById(11L)).thenReturn(Optional.of(branchedMethod));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L)))
                .thenReturn(new BusinessRuleResponseDto(List.of()))
                .thenReturn(new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(
                                11L, "Tra ve user theo trang thai ton tai.", "BUSINESS_LOGIC", "IF-1"))));
        when(aiAgentService.generateBusinessRules(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(Set.of(11L)),
                org.mockito.ArgumentMatchers.contains("Expected branch checklist: method_id 11: required branch_id values [IF-1]")))
                .thenReturn(new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(
                                11L, "Tra ve user theo trang thai ton tai.", "BUSINESS_LOGIC", "IF-1"))));
        mockBusinessRuleSave();

        assertThat(service().generate(1L)).extracting(BusinessRuleDto::sourceBranchId)
                .containsExactly("IF-1");
        verify(aiAgentService).generateBusinessRules(1L, Set.of(11L));
        verify(aiAgentService).generateBusinessRules(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(Set.of(11L)),
                org.mockito.ArgumentMatchers.contains("IF-1"));
    }

    @Test
    void generateStopsAfterThreeSemanticRetries() {
        mockProject();
        JavaMethod branchedMethod = method(11L, "findUser");
        branchedMethod.setSourceCode("""
                public String findUser(boolean exists) {
                    if (exists) return "found";
                    return "missing";
                }
                """);
        mockServiceMethods(branchedMethod);
        when(javaMethodRepository.findById(11L)).thenReturn(Optional.of(branchedMethod));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L)))
                .thenReturn(new BusinessRuleResponseDto(List.of()));

        when(aiAgentService.generateBusinessRules(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(Set.of(11L)),
                org.mockito.ArgumentMatchers.contains("Expected branch checklist: method_id 11: required branch_id values [IF-1]")))
                .thenReturn(new BusinessRuleResponseDto(List.of()))
                .thenReturn(new BusinessRuleResponseDto(List.of()));

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(com.greytest.service.agent.LlmResponseException.class)
                .hasMessageContaining("IF-1");
        verify(aiAgentService).generateBusinessRules(1L, Set.of(11L));
        verify(aiAgentService, times(2)).generateBusinessRules(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(Set.of(11L)),
                org.mockito.ArgumentMatchers.contains("IF-1"));
    }

    @Test
    void generateRejectsSeparateTrueAndFalseRulesForTheSameDecision() {
        mockProject();
        JavaMethod branchedMethod = method(11L, "findUser");
        branchedMethod.setLineStart(20);
        branchedMethod.setSourceCode("""
                public String findUser(boolean exists) {
                    if (exists) {
                        return "found";
                    }
                    return "missing";
                }
                """);
        mockServiceMethods(branchedMethod);
        when(javaMethodRepository.findById(11L)).thenReturn(Optional.of(branchedMethod));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(
                                11L, "Tra ve missing khi khong ton tai.", "BUSINESS_LOGIC", "IF-1-FALSE"),
                        new GeneratedBusinessRuleDto(
                                11L, "Tra ve user khi ton tai.", "BUSINESS_LOGIC", "IF-1-TRUE"))));
        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(com.greytest.service.agent.LlmResponseException.class)
                .hasMessageContaining("trùng Business Rule", "IF-1");
    }

    @Test
    void reviewPersistsAiReviewOnExistingRuleWithoutCreatingAnotherRule() {
        BusinessRule existingRule = rule(7L, 11L, "Email phai hop le.");
        mockProject();
        mockProjectSave();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existingRule));
        when(aiAgentService.reviewBusinessRules(1L)).thenReturn(new BusinessRuleReviewResponseDto(
                List.of(new ReviewedBusinessRuleSuggestionDto(
                        7L,
                        "NEEDS_REVISION",
                        "Email phai dung dinh dang va chua ton tai trong he thong.",
                        "Rule thieu dieu kien email duy nhat.")),
                List.of(new GeneratedBusinessRuleDto(
                        11L,
                        "He thong phai luu user moi sau khi validate thanh cong.",
                        "SIDE_EFFECT"))));
        mockBusinessRuleSave();

        BusinessRuleReviewDto review = service().review(1L);

        assertThat(review.reviewedRules()).singleElement()
                .satisfies(item -> {
                    assertThat(item.ruleId()).isEqualTo(7L);
                    assertThat(item.verdict()).isEqualTo("NEEDS_REVISION");
                });
        assertThat(existingRule.getReviewNote()).contains("NEEDS_REVISION", "Rule thieu dieu kien email duy nhat.");
        assertThat(existingRule.getIsModified()).isFalse();
        assertThat(review.suggestedRules()).isEmpty();
        verify(businessRuleRepository, times(1)).save(any(BusinessRule.class));
    }

    @Test
    void reviewIgnoresAdditionalRulesReturnedByAi() {
        BusinessRule existingRule = rule(7L, 11L, "Tra ve user khi ton tai.");
        mockProject();
        mockProjectSave();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existingRule));
        when(aiAgentService.reviewBusinessRules(1L)).thenReturn(new BusinessRuleReviewResponseDto(
                List.of(new ReviewedBusinessRuleSuggestionDto(7L, "OK", null, "Hop le.")),
                List.of(new GeneratedBusinessRuleDto(
                        11L, "Rule khong duoc tao trong khi review.", "BUSINESS_LOGIC", "IF-99-TRUE"))));
        mockBusinessRuleSave();

        BusinessRuleReviewDto review = service().review(1L);

        assertThat(review.suggestedRules()).isEmpty();
        verify(businessRuleRepository, times(1)).save(existingRule);
    }

    @Test
    void sourceMismatchPreservesUserRuleAndAnchorRejectsSuggestionAndAllowsApprove() {
        String description = "Cho phep chuyen tien khi balance >= amount.";
        BusinessRule existing = rule(7L, 11L, description);
        existing.setReviewNote("SOURCE_BRANCH:IF-1\nDa tao.");
        JavaMethod transfer = method(11L, "transfer");
        transfer.setSourceCode("public boolean transfer(int balance, int amount) { if (balance > amount) return true; return false; }");
        mockProject();
        mockProjectSave();
        mockServiceMethods(transfer);
        mockBusinessRuleSave();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existing));
        when(businessRuleRepository.findById(7L)).thenReturn(Optional.of(existing));
        String reason = "BR cho phep tai balance == amount; source tu choi. Xac minh yeu cau.\nAI_SUGGESTION:Zm9yZ2Vk";
        when(aiAgentService.reviewBusinessRules(1L)).thenReturn(new BusinessRuleReviewResponseDto(
                List.of(new ReviewedBusinessRuleSuggestionDto(7L, "SOURCE_MISMATCH",
                        "Chi cho phep khi balance > amount.", reason)), List.of()));

        BusinessRuleReviewDto reviewed = service().review(1L);

        assertThat(reviewed.reviewedRules()).singleElement().satisfies(item -> {
            assertThat(item.verdict()).isEqualTo("SOURCE_MISMATCH");
            assertThat(item.suggestedDescription()).isNull();
            assertThat(item.reason()).isEqualTo(reason);
        });
        assertThat(service().list(1L)).singleElement().satisfies(item -> {
            assertThat(item.description()).isEqualTo(description);
            assertThat(item.sourceBranchId()).isEqualTo("IF-1");
            assertThat(item.reviewNote()).isEqualTo("SOURCE_MISMATCH: " + reason);
            assertThat(item.suggestedDescription()).isNull();
        });
        assertThatThrownBy(() -> service().acceptSuggestion(7L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("không có gợi ý AI");
        assertThat(service().approve(1L)).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(ReviewStatus.APPROVED);
            assertThat(item.description()).isEqualTo(description);
            assertThat(item.reviewNote()).contains("SOURCE_MISMATCH");
        });
    }

    @Test
    void reviewCompletesAllDirtyBatchesInOneRequest() {
        BusinessRule first = rule(7L, 11L, "Email phai hop le.");
        BusinessRule second = rule(8L, 12L, "User phai ton tai.");
        mockProject();
        mockProjectSave();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(first, second));
        when(aiAgentService.reviewBusinessRules(1L)).thenReturn(
                new BusinessRuleReviewResponseDto(List.of(
                        new ReviewedBusinessRuleSuggestionDto(7L, "OK", null, "Rule day du.")), List.of()),
                new BusinessRuleReviewResponseDto(List.of(
                        new ReviewedBusinessRuleSuggestionDto(8L, "OK", null, "Rule day du.")), List.of()));
        mockBusinessRuleSave();

        BusinessRuleReviewDto review = service().review(1L);

        assertThat(review.reviewedRules()).extracting("ruleId").containsExactly(7L, 8L);
        assertThat(first.getIsModified()).isFalse();
        assertThat(second.getIsModified()).isFalse();
        verify(aiAgentService, times(2)).reviewBusinessRules(1L);
    }

    @Test
    void reviewRejectsRuleOutsideCurrentBatch() {
        List<BusinessRule> rules = java.util.stream.LongStream.rangeClosed(1, 11)
                .mapToObj(id -> rule(id, id, "Rule " + id))
                .toList();
        mockProject();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(rules);
        when(aiAgentService.reviewBusinessRules(1L)).thenReturn(new BusinessRuleReviewResponseDto(
                List.of(new ReviewedBusinessRuleSuggestionDto(11L, "OK", null, "Rule day du.")),
                List.of()));

        assertThatThrownBy(() -> service().review(1L))
                .isInstanceOf(com.greytest.service.agent.LlmResponseException.class)
                .hasMessageContaining("không review Business Rule nào");
    }

    @Test
    void generateFailsClearlyWhenProjectHasNoServiceMethods() {
        mockProject();
        when(javaClassRepository.findByProjectIdAndClassType(1L, ClassType.SERVICE)).thenReturn(List.of());

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("chưa có service method");
    }

    @Test
    void generateSkipsAiWhenEveryServiceMethodAlreadyHasBusinessRule() {
        mockProject();
        mockServiceMethods(method(11L, "createUser"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(
                rule(7L, 11L, "Email phai hop le truoc khi tao user.")));

        assertThat(service().generate(1L)).isEmpty();

        verifyNoInteractions(aiAgentService);
    }


    @Test
    void generateSkipsFullyCoveredUntouchedAiRule() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        JavaMethod branchedMethod = method(11L, "findUser");
        branchedMethod.setSourceCode("""
                public String findUser(boolean exists) {
                    if (exists) return "found";
                    return "missing";
                }
                """);
        mockServiceMethods(branchedMethod);
        BusinessRule existing = rule(7L, 11L, "Tra ve user neu dieu kien dung.");
        existing.setSource(RuleSource.AI_GENERATED);
        existing.setIsModified(false);
        existing.setReviewNote("SOURCE_BRANCH:IF-1\nChi mo ta nhanh if.");
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existing));
        List<BusinessRuleDto> regenerated = service().generate(1L);

        assertThat(regenerated).isEmpty();
        assertThat(existing.getDescription())
                .isEqualTo("Tra ve user neu dieu kien dung.");
        assertThat(existing.getSource()).isEqualTo(RuleSource.AI_GENERATED);
        org.mockito.Mockito.verifyNoInteractions(aiAgentService);
    }

    @Test
    void resumeWhilePendingReviewSkipsCompletedAiMethodsAndKeepsTheirText() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        mockProjectSave();
        mockServiceMethods(method(11L, "completed"), method(12L, "missing"));
        BusinessRule existing = rule(7L, 11L, "Keep completed rule.");
        existing.setSource(RuleSource.AI_GENERATED);
        existing.setIsModified(false);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existing));
        when(aiAgentService.generateBusinessRules(org.mockito.ArgumentMatchers.eq(1L), any(Set.class)))
                .thenAnswer(invocation -> {
                    Set<Long> ids = invocation.getArgument(1);
                    return new BusinessRuleResponseDto(ids.stream().map(id ->
                            new GeneratedBusinessRuleDto(id, "New rule for " + id, "BUSINESS_LOGIC")).toList());
                });
        mockBusinessRuleSave();

        assertThat(service().generate(1L)).extracting(BusinessRuleDto::methodId).containsExactly(12L);
        assertThat(existing.getDescription()).isEqualTo("Keep completed rule.");
        verify(aiAgentService).generateBusinessRules(1L, Set.of(12L));
    }

    @Test
    void regenerationPreservesEditedRuleWhileUpdatingUntouchedAiRule() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        mockProjectSave();
        JavaMethod first = method(11L, "first");
        JavaMethod second = method(12L, "second");
        first.setSourceCode("public boolean first(boolean ready) { if (ready) return true; return false; }");
        second.setSourceCode("public boolean second(boolean ready) { if (ready) return true; return false; }");
        mockServiceMethods(first, second);
        BusinessRule edited = rule(7L, 11L, "User edited requirement.");
        edited.setSource(RuleSource.AI_GENERATED);
        edited.setReviewNote("SOURCE_BRANCH:IF-1\nEdited.");
        BusinessRule untouched = rule(8L, 12L, "Old AI rule.");
        untouched.setRuleCode("BR-002");
        untouched.setSource(RuleSource.AI_GENERATED);
        untouched.setIsModified(false);
        untouched.setReviewNote("SOURCE_BRANCH:IF-1\nGenerated.");
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(edited, untouched));
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L, 12L))).thenReturn(new BusinessRuleResponseDto(List.of(
                new GeneratedBusinessRuleDto(11L, "AI replacement for edited rule.", "BUSINESS_LOGIC", "IF-1"),
                new GeneratedBusinessRuleDto(12L, "New AI rule.", "BUSINESS_LOGIC", "IF-1"))));
        mockBusinessRuleSave();

        assertThat(service().generate(1L, false)).extracting(BusinessRuleDto::id).containsExactly(8L);
        assertThat(edited.getDescription()).isEqualTo("User edited requirement.");
        assertThat(edited.getIsModified()).isTrue();
        assertThat(untouched.getDescription()).isEqualTo("New AI rule.");
        verify(businessRuleRepository, org.mockito.Mockito.never()).save(edited);
    }

    @Test
    void regenerationPreservesApprovedAiRuleWhileUpdatingPendingAiRule() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        mockProjectSave();
        JavaMethod first = method(11L, "first");
        JavaMethod second = method(12L, "second");
        first.setSourceCode("public boolean first(boolean ready) { if (ready) return true; return false; }");
        second.setSourceCode("public boolean second(boolean ready) { if (ready) return true; return false; }");
        mockServiceMethods(first, second);
        BusinessRule approved = rule(7L, 11L, "Approved requirement.");
        approved.setSource(RuleSource.AI_GENERATED);
        approved.setStatus(ReviewStatus.APPROVED);
        approved.setIsModified(false);
        approved.setReviewNote("SOURCE_BRANCH:IF-1\nApproved.");
        BusinessRule pending = rule(8L, 12L, "Old pending rule.");
        pending.setRuleCode("BR-002");
        pending.setSource(RuleSource.AI_GENERATED);
        pending.setIsModified(false);
        pending.setReviewNote("SOURCE_BRANCH:IF-1\nGenerated.");
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(approved, pending));
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L, 12L))).thenReturn(new BusinessRuleResponseDto(List.of(
                new GeneratedBusinessRuleDto(11L, "AI replacement for approved rule.", "BUSINESS_LOGIC", "IF-1"),
                new GeneratedBusinessRuleDto(12L, "New pending rule.", "BUSINESS_LOGIC", "IF-1"))));
        mockBusinessRuleSave();

        assertThat(service().generate(1L, false)).extracting(BusinessRuleDto::id).containsExactly(8L);

        assertThat(approved.getDescription()).isEqualTo("Approved requirement.");
        assertThat(approved.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(approved.getIsModified()).isFalse();
        assertThat(pending.getDescription()).isEqualTo("New pending rule.");
        verify(businessRuleRepository, org.mockito.Mockito.never()).save(approved);
    }

    @Test
    void generateOnlyAcceptsRulesForMethodsWithoutBusinessRule() {

        mockProject();
        mockProjectSave();
        mockServiceMethods(method(11L, "createUser"), method(12L, "updateUser"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(
                rule(7L, 11L, "Email phai hop le truoc khi tao user.")));
        when(aiAgentService.generateBusinessRules(1L, Set.of(12L))).thenReturn(new BusinessRuleResponseDto(List.of(
                new GeneratedBusinessRuleDto(12L, "User cap nhat phai ton tai.", "BUSINESS_LOGIC"))));
        mockBusinessRuleSave();

        List<BusinessRuleDto> rules = service().generate(1L);

        assertThat(rules).extracting(BusinessRuleDto::methodId).containsExactly(12L);
        verify(aiAgentService).generateBusinessRules(1L, Set.of(12L));
    }

    @Test
    void reviewFailsClearlyWhenThereAreNoBusinessRules() {
        mockProject();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service().review(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("Không có Business Rule", "cần AI review");
    }

    @Test
    void reviewSkipsUntouchedAiGeneratedRules() {
        BusinessRule aiRule = rule(7L, 11L, "Email phai hop le.");
        aiRule.setSource(RuleSource.AI_GENERATED);
        aiRule.setIsModified(false);
        mockProject();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(aiRule));

        assertThatThrownBy(() -> service().review(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("cần AI review");

        verifyNoInteractions(aiAgentService);
    }

    @Test
    void manualRuleCanCoverAnIfDecisionAndApproveWithoutAiReview() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.ANALYZED);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        mockProjectSave();

        JavaClass serviceClass = serviceClass(
                10L, "src/main/java/UserService.java", "demo.UserService");
        JavaMethod method = method(11L, "findUser");
        method.setClassId(10L);
        method.setSourceCode("""
                public String findUser(boolean exists) {
                    if (exists) return "found";
                    return "missing";
                }
                """);
        when(javaClassRepository.findById(10L)).thenReturn(Optional.of(serviceClass));
        when(javaClassRepository.findByProjectIdAndClassType(1L, ClassType.SERVICE))
                .thenReturn(List.of(serviceClass));
        when(javaMethodRepository.findById(11L)).thenReturn(Optional.of(method));
        when(javaMethodRepository.findByClassIdIn(List.of(10L))).thenReturn(List.of(method));

        List<BusinessRule> savedRules = new ArrayList<>();
        when(businessRuleRepository.findByProjectId(1L)).thenAnswer(ignored -> new ArrayList<>(savedRules));
        AtomicLong ids = new AtomicLong(1);
        when(businessRuleRepository.save(any(BusinessRule.class))).thenAnswer(invocation -> {
            BusinessRule rule = invocation.getArgument(0);
            if (rule.getId() == null) {
                rule.setId(ids.getAndIncrement());
                savedRules.add(rule);
            }
            return rule;
        });

        service().create(1L, new CreateBusinessRuleRequest(
                11L, "Tra ve ket qua theo trang thai ton tai cua user.", "IF-1"));
        service().approve(1L);

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.BR_APPROVED);
        assertThat(service().list(1L)).extracting(BusinessRuleDto::sourceBranchId)
                .containsExactly("IF-1");
    }

    @Test
    void approveAllowsDirtyRulesAsExplicitUserDecision() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        BusinessRule dirtyRule = rule(7L, 11L, "Email phai hop le.");
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(dirtyRule));
        mockProjectSave();

        service().approve(1L);

        assertThat(dirtyRule.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(dirtyRule.getIsModified()).isFalse();
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.BR_APPROVED);
    }

    @Test
    void approveTreatsLegacyTrueBranchMarkerAsItsIfDecision() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        JavaMethod branchedMethod = method(11L, "findUser");
        branchedMethod.setSourceCode("""
                public String findUser(boolean exists) {
                    if (exists) return "found";
                    return "missing";
                }
                """);
        mockServiceMethods(branchedMethod);
        BusinessRule trueRule = rule(7L, 11L, "Tra ve user khi ton tai.");
        trueRule.setIsModified(false);
        trueRule.setReviewNote("SOURCE_BRANCH:IF-1-TRUE\nDa review.");
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(trueRule));

        service().approve(1L);

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.BR_APPROVED);
        assertThat(service().list(1L)).extracting(BusinessRuleDto::sourceBranchId)
                .containsExactly("IF-1");
    }

    @Test
    void approveAllowsLegacyTrueFalsePairForOneIfDecision() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        JavaMethod branchedMethod = method(11L, "findUser");
        branchedMethod.setSourceCode("""
                public String findUser(boolean exists) {
                    if (exists) return "found";
                    return "missing";
                }
                """);
        mockServiceMethods(branchedMethod);
        BusinessRule trueRule = rule(7L, 11L, "Tra ve user khi ton tai.");
        trueRule.setIsModified(false);
        trueRule.setReviewNote("SOURCE_BRANCH:IF-1-TRUE\nDa review.");
        BusinessRule falseRule = rule(8L, 11L, "Tra ve missing khi khong ton tai.");
        falseRule.setIsModified(false);
        falseRule.setReviewNote("SOURCE_BRANCH:IF-1-FALSE\nDa review.");
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(trueRule, falseRule));

        service().approve(1L);

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.BR_APPROVED);
        assertThat(trueRule.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(falseRule.getStatus()).isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    void approveAllowsIndependentRulesForTheSameStatementAnchor() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        JavaMethod method = method(11L, "findUser");
        method.setSourceCode("""
                public User findUser(Long id) {
                    return repository.findById(id)
                            .map(this::enrich)
                            .orElseThrow(() -> new UserNotFoundException("User not found"));
                }
                """);
        mockServiceMethods(method);
        BusinessRule enrichRule = rule(7L, 11L, "Khi tìm thấy user, service enrich dữ liệu user.");
        enrichRule.setReviewNote("SOURCE_BRANCH:STMT-1\nDa review.");
        BusinessRule missingRule = rule(8L, 11L,
                "Khi không tìm thấy user, service ném UserNotFoundException.");
        missingRule.setReviewNote("SOURCE_BRANCH:STMT-1\nDa review.");
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(enrichRule, missingRule));
        mockProjectSave();
        mockBusinessRuleSave();

        assertThat(service().approve(1L)).extracting(BusinessRuleDto::sourceBranchId)
                .containsExactly("STMT-1", "STMT-1");
    }

    @Test
    void approveRejectsUnreadableMethodSourceInsteadOfAssumingNoBranches() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        JavaMethod unreadableMethod = method(11L, "findUser");
        unreadableMethod.setSourceCode("public String findUser() { if (");
        mockServiceMethods(unreadableMethod);
        BusinessRule rule = rule(7L, 11L, "Tra ve user.");
        rule.setIsModified(false);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(rule));

        assertThatThrownBy(() -> service().approve(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("Không thể xác minh", "phan tich lai project");
    }

    @Test
    void createRejectsDuplicateDescriptionInSameProject() {
        mockProject();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(rule(
                7L,
                11L,
                "Save a daily statistical data point for an account, capturing normalized incomes, expenses, and current exchange rates.")));

        assertThatThrownBy(() -> service().create(1L, new CreateBusinessRuleRequest(
                11L,
                " save a daily statistical data point for an account, capturing normalized incomes, expenses, and current exchange rates. ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Business Rule đã tồn tại");
    }
    @Test
    void createAllowsSameDescriptionForAnotherServiceMethod() {
        mockProject();
        mockProjectSave();
        mockProjectServiceMethod(22L);
        BusinessRule accountRule = rule(7L, 11L, "Entity phai ton tai.");
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(accountRule));
        when(scopeResolver.resolve(1L, "auth-service")).thenReturn(
                new ServiceScopeResolver.ServiceScope("auth-service", Set.of(20L), Set.of(22L)));
        mockBusinessRuleSave();

        BusinessRuleDto created = scopedService().create(1L, "auth-service",
                new CreateBusinessRuleRequest(22L, "Entity phai ton tai."));

        assertThat(created.methodId()).isEqualTo(22L);
        assertThat(created.description()).isEqualTo("Entity phai ton tai.");
    }

    @Test
    void scopedGenerateUsesSelectedServiceStatusInsteadOfGlobalStatus() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        JavaMethod authMethod = method(22L, "authenticate");
        mockServiceMethods(authMethod);
        BusinessRule existing = rule(8L, 22L, "User phai ton tai.");
        existing.setSource(RuleSource.AI_GENERATED);
        existing.setStatus(ReviewStatus.APPROVED);
        existing.setIsModified(false);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existing));
        var scope = new ServiceScopeResolver.ServiceScope(
                "auth-service", Set.of(20L), Set.of(22L));
        when(scopeResolver.resolve(1L, "auth-service")).thenReturn(scope);
        when(scopedStatuses.status(1L, scope)).thenReturn(ProjectStatus.BR_APPROVED);

        assertThat(scopedService().generate(1L, "auth-service")).isEmpty();

        verifyNoInteractions(aiAgentService);
    }

    @Test
    void createRejectsSecondRuleForSameSourceDecision() {
        BusinessRule existingRule = rule(7L, 11L, "Tra ve user khi ton tai.");
        existingRule.setReviewNote("SOURCE_BRANCH:IF-1\nDa tao.");
        mockProject();
        mockProjectServiceMethod(11L);
        JavaMethod method = method(11L, "findUser");
        method.setClassId(10L);
        method.setSourceCode("""
                public String findUser(boolean exists) {
                    if (exists) return "found";
                    return "missing";
                }
                """);
        when(javaMethodRepository.findById(11L)).thenReturn(Optional.of(method));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existingRule));

        assertThatThrownBy(() -> service().create(1L, new CreateBusinessRuleRequest(
                11L, "Mo ta khac cho cung mot quyet dinh.", "IF-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quyết định source đã có Business Rule", "IF-1");
    }

    @Test
    void updateAlwaysMarksRuleDirty() {
        BusinessRule existingRule = rule(7L, 11L, "Email phai hop le.");
        mockProject();
        mockProjectSave();
        mockProjectServiceMethod(11L);
        when(businessRuleRepository.findById(7L)).thenReturn(Optional.of(existingRule));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existingRule));
        when(businessRuleRepository.save(existingRule)).thenReturn(existingRule);

        BusinessRuleDto updated = service().update(7L, new UpdateBusinessRuleRequest(
                11L,
                "Email phai dung dinh dang hop le."));

        assertThat(updated.isModified()).isTrue();
        assertThat(updated.reviewNote()).contains("AI review lại là tùy chọn");
    }

    @Test
    void acceptSuggestionUsesStructuredServerValueWhenReasonContainsMarkerText() {
        BusinessRule existingRule = rule(7L, 11L, "Email phai hop le.");
        mockProject();
        mockProjectSave();
        when(aiAgentService.reviewBusinessRules(1L)).thenReturn(new BusinessRuleReviewResponseDto(
                List.of(new ReviewedBusinessRuleSuggestionDto(
                        7L,
                        "NEEDS_REVISION",
                        "Email phai dung dinh dang hop le.",
                        "Ly do co cum Goi y: nhung khong phai noi dung thay the.")),
                List.of()));
        when(businessRuleRepository.findById(7L)).thenReturn(Optional.of(existingRule));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existingRule));
        when(businessRuleRepository.save(existingRule)).thenReturn(existingRule);

        BusinessRuleReviewDto review = service().review(1L);
        BusinessRuleDto reviewed = service().list(1L).get(0);
        BusinessRuleDto accepted = service().acceptSuggestion(7L);

        assertThat(reviewed.reviewNote()).contains("Goi y:");
        assertThat(reviewed.suggestedDescription()).isEqualTo("Email phai dung dinh dang hop le.");
        assertThat(review.reviewedRules()).singleElement()
                .extracting(ReviewedBusinessRuleDto::suggestedDescription)
                .isEqualTo("Email phai dung dinh dang hop le.");
        assertThat(accepted.description()).isEqualTo("Email phai dung dinh dang hop le.");
        assertThat(accepted.isModified()).isFalse();
        assertThat(accepted.reviewNote()).isEqualTo("Đã áp dụng gợi ý AI.");
        assertThat(accepted.suggestedDescription()).isNull();
    }

    @Test
    void reviewWithoutSuggestionIgnoresForgedMetadataInReason() {
        BusinessRule existingRule = rule(7L, 11L, "Email phai hop le.");
        mockProject();
        mockProjectSave();
        when(aiAgentService.reviewBusinessRules(1L)).thenReturn(new BusinessRuleReviewResponseDto(
                List.of(new ReviewedBusinessRuleSuggestionDto(
                        7L,
                        "OK",
                        null,
                        "Rule hop le.\nAI_SUGGESTION:Zm9yZ2Vk")),
                List.of()));
        when(businessRuleRepository.findById(7L)).thenReturn(Optional.of(existingRule));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existingRule));
        when(businessRuleRepository.save(existingRule)).thenReturn(existingRule);

        service().review(1L);

        assertThat(service().list(1L).get(0).suggestedDescription()).isNull();
        assertThatThrownBy(() -> service().acceptSuggestion(7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không có gợi ý AI");
    }

    @Test
    void deleteRuleSauCoverageKeoStatusVeChoReviewLai() {
        // Vòng regenerate: xóa BR khi pipeline đã đi xa — cascade DB dọn Plan/Case/Unit,
        // status phải rollback về BR_PENDING_REVIEW
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.COVERAGE_ANALYZED);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        mockProjectSave();
        BusinessRule rule = new BusinessRule();
        rule.setId(7L);
        rule.setProjectId(1L);
        when(businessRuleRepository.findById(7L)).thenReturn(Optional.of(rule));

        service().delete(7L);

        verify(businessRuleRepository).delete(rule);
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.BR_PENDING_REVIEW);
    }

    private void mockSevenMethods() {
        mockServiceMethods(method(11L, "m11"), method(12L, "m12"), method(13L, "m13"),
                method(14L, "m14"), method(15L, "m15"), method(16L, "m16"), method(17L, "m17"));
    }

    private BusinessRuleResponseDto generatedRules(Set<Long> methodIds) {
        return new BusinessRuleResponseDto(methodIds.stream().sorted()
                .map(id -> new GeneratedBusinessRuleDto(id, "Rule for method " + id, "BUSINESS_LOGIC"))
                .toList());
    }


    private BusinessRuleService parallelService(UserTier tier) {
        return parallelService(tier, 3);
    }

    private BusinessRuleService parallelService(UserTier tier, int concurrency) {
        AuthUser actor = new AuthUser();
        actor.setTier(tier);
        when(users.findById(42L)).thenReturn(Optional.of(actor));
        GenerationJobContext.bind(42L, ignored -> { });
        batchExecutor = new LlmBatchExecutor(concurrency, users);
        return new BusinessRuleService(businessRuleRepository, projectRepository, javaClassRepository,
                javaMethodRepository, aiAgentService, generationProgressService, transactionManager,
                scopeResolver, scopedStatuses, batchExecutor);
    }

    private BusinessRuleService service() {
        return new BusinessRuleService(
                businessRuleRepository,
                projectRepository,
                javaClassRepository,
                javaMethodRepository,
                aiAgentService,
                generationProgressService,
                transactionManager);
    }
    private BusinessRuleService scopedService() {
        return new BusinessRuleService(
                businessRuleRepository,
                projectRepository,
                javaClassRepository,
                javaMethodRepository,
                aiAgentService,
                generationProgressService,
                transactionManager,
                scopeResolver,
                scopedStatuses,
                null);
    }

    private void mockProject() {
        Project project = new Project();
        project.setId(1L);
        project.setName("demo");
        project.setStatus(ProjectStatus.ANALYZED);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
    }

    private void mockProjectSave() {
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void mockServiceMethods(JavaMethod... methods) {
        JavaClass serviceClass = serviceClass(
                10L, "src/main/java/UserService.java", "demo.UserService");
        when(javaClassRepository.findByProjectIdAndClassType(1L, ClassType.SERVICE))
                .thenReturn(List.of(serviceClass));
        when(javaMethodRepository.findByClassIdIn(List.of(10L))).thenReturn(List.of(methods));
        for (JavaMethod method : methods) {
            org.mockito.Mockito.lenient().when(javaMethodRepository.findById(method.getId()))
                    .thenReturn(Optional.of(method));
        }
    }

    private JavaClass serviceClass(Long id, String filePath, String qualifiedName) {
        JavaClass serviceClass = new JavaClass();
        serviceClass.setId(id);
        serviceClass.setProjectId(1L);
        serviceClass.setClassType(ClassType.SERVICE);
        serviceClass.setClassName(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1));
        serviceClass.setQualifiedName(qualifiedName);
        serviceClass.setFilePath(filePath);
        return serviceClass;
    }

    private void mockProjectServiceMethod(Long methodId) {
        JavaMethod method = method(methodId, "createUser");
        method.setClassId(10L);
        JavaClass serviceClass = new JavaClass();
        serviceClass.setId(10L);
        serviceClass.setProjectId(1L);
        serviceClass.setClassType(ClassType.SERVICE);
        when(javaMethodRepository.findById(methodId)).thenReturn(Optional.of(method));
        when(javaClassRepository.findById(10L)).thenReturn(Optional.of(serviceClass));
    }

    private void mockBusinessRuleSave() {
        AtomicLong ids = new AtomicLong(100);
        when(businessRuleRepository.save(any(BusinessRule.class))).thenAnswer(invocation -> {
            BusinessRule rule = invocation.getArgument(0);
            if (rule.getId() == null) {
                rule.setId(ids.getAndIncrement());
            }
            return rule;
        });
    }

    private JavaMethod method(Long id, String name) {
        JavaMethod method = new JavaMethod();
        method.setId(id);
        method.setMethodName(name);
        method.setSourceCode("public void " + name + "() {}");
        return method;
    }

    private BusinessRule rule(Long id, Long methodId, String description) {
        BusinessRule rule = new BusinessRule();
        rule.setId(id);
        rule.setProjectId(1L);
        rule.setMethodId(methodId);
        rule.setRuleCode("BR-001");
        rule.setDescription(description);
        rule.setSource(RuleSource.USER_ADDED);
        rule.setStatus(ReviewStatus.PENDING_REVIEW);
        rule.setIsModified(true);
        return rule;
    }
}
