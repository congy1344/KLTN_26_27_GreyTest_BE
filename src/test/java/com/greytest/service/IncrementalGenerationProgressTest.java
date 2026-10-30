package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.GenerationProgressDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.GenerationProgressStepStatus;
import com.greytest.dto.agent.GenerationResponseDtos.*;
import com.greytest.entity.*;
import com.greytest.entity.enums.SourceUpdateStatus;
import com.greytest.mapper.SourceUpdateMapper;
import com.greytest.repository.*;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.LlmResponseException;

class IncrementalGenerationProgressTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void preservesEachBatchFailureAndAnySuccessfulBatch(boolean lastBatchSucceeds) {
        update.setImpactSummary("{\"affectedTestPlanIds\":[1,2,3,4,5,6,7,8,9]}");
        when(agent.generateTestCases(eq(10L), any())).thenAnswer(call -> {
            Set<Long> ids = call.getArgument(1);
            if (ids.contains(1L)) throw new LlmResponseException("semantic validation failed", false);
            if (ids.contains(5L)) throw new LlmResponseException("HTTP 504", false);
            if (!lastBatchSucceeds) throw new LlmResponseException("EOF reached while reading", false);
            return new TestCaseResponseDto(List.of());
        });

        assertThatThrownBy(() -> service.generateIncrementalStage(
                10L, 200L, GenerationProgressStage.TEST_CASE, new AuthUser()))
                .isInstanceOf(LlmBatchExecutor.BatchExecutionException.class);

        var steps = progress.get(10L, GenerationProgressStage.TEST_CASE).steps();
        assertThat(steps.get(0).errorMessage()).contains("semantic validation failed").doesNotContain("HTTP 504");
        assertThat(steps.get(1).errorMessage()).contains("HTTP 504").doesNotContain("semantic");
        if (lastBatchSucceeds) {
            assertThat(steps.get(2).status()).isEqualTo(GenerationProgressStepStatus.COMPLETED);
            assertThat(steps.get(2).errorMessage()).isNull();
        } else {
            assertThat(steps.get(2).errorMessage()).contains("EOF reached while reading").doesNotContain("semantic");
        }
    }

    private final SourceUpdateRepository updates = mock(SourceUpdateRepository.class);
    private final JavaClassRepository classes = mock(JavaClassRepository.class);
    private final JavaMethodRepository methods = mock(JavaMethodRepository.class);
    private final AIAgentService agent = mock(AIAgentService.class);
    private final GenerationProgressService progress = spy(new GenerationProgressService());
    private final List<GenerationProgressDto> completions = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final SourceUpdate update = new SourceUpdate();
    private LlmBatchExecutor executor;
    private IncrementalGenerationService service;

    @BeforeEach
    void setUp() {
        executor = new LlmBatchExecutor(3, 2, 0);
        var projects = mock(ProjectRepository.class);
        var project = new Project(); project.setId(10L);
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        update.setId(200L); update.setProjectId(10L); update.setStatus(SourceUpdateStatus.ANALYZED);
        when(updates.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));
        when(updates.save(any())).thenAnswer(call -> call.getArgument(0));
        service = new IncrementalGenerationService(projects, updates, mock(SourceUpdateItemRepository.class),
                classes, methods, mock(UnitTestRepository.class), null, null, null,
                mock(SourceUpdateService.class), agent, mock(SourceUpdateMapper.class), new ObjectMapper(), null, executor);
        ReflectionTestUtils.setField(service, "progressService", progress);
        doAnswer(call -> {
            synchronized (progress) {
                call.callRealMethod();
                completions.add(progress.get(10L, call.getArgument(1)));
            }
            return null;
        }).when(progress).advanceStep(eq(10L), any(), anyInt(), any());
    }

    @AfterEach
    void cleanUp() {
        executor.shutdown();
        GenerationJobContext.clear();
    }

    @ParameterizedTest
    @EnumSource(GenerationProgressStage.class)
    void retryCompletesTheOriginalStepAndShowsItsReason(GenerationProgressStage stage) {
        update.setImpactSummary("""
                {"affectedBusinessRuleIds":[101],"affectedTestPlanIds":[101],"affectedTestCaseIds":[101],
                 "changedMethods":[{"qualifiedClassName":"demo.Service","methodName":"run",
                   "signature":"run()","diffType":"MODIFIED"}]}
                """);
        Object response;
        switch (stage) {
            case BUSINESS_RULE -> {
                var javaClass = new JavaClass(); javaClass.setId(50L); javaClass.setQualifiedName("demo.Service");
                when(classes.findByProjectId(10L)).thenReturn(List.of(javaClass));
                var method = new JavaMethod(); method.setId(101L); method.setMethodName("run");
                when(methods.findByClassId(50L)).thenReturn(List.of(method));
                response = new BusinessRuleResponseDto(List.of());
                when(agent.generateBusinessRules(10L, Set.of(101L)))
                        .thenThrow(new LlmResponseException("HTTP 504", true)).thenReturn((BusinessRuleResponseDto) response);
            }
            case TEST_PLAN -> {
                response = new TestPlanResponseDto(List.of());
                when(agent.generateTestPlan(10L, Set.of(101L)))
                        .thenThrow(new LlmResponseException("HTTP 504", true)).thenReturn((TestPlanResponseDto) response);
            }
            case TEST_CASE -> {
                response = new TestCaseResponseDto(List.of());
                when(agent.generateTestCases(10L, Set.of(101L)))
                        .thenThrow(new LlmResponseException("HTTP 504", true)).thenReturn((TestCaseResponseDto) response);
            }
            case UNIT_TEST -> {
                response = new UnitTestResponseDto(List.of());
                when(agent.generateSnapshotUnitTests(10L, Set.of(101L), null))
                        .thenThrow(new LlmResponseException("HTTP 504", true)).thenReturn((UnitTestResponseDto) response);
            }
        }

        var outerLogs = new java.util.ArrayList<String>();
        GenerationJobContext.bind(42L, outerLogs::add);
        service.generateIncrementalStage(10L, 200L, stage, new AuthUser());
        GenerationJobContext.log("outer restored");
        assertThat(outerLogs).containsExactly("outer restored");
        assertThat(GenerationJobContext.actorUserId()).isEqualTo(42L);

        assertThat(completions).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.completedSteps()).isEqualTo(1);
            assertThat(snapshot.percent()).isEqualTo(100);
        });
        assertThat(progress.get(10L, stage).logs()).extracting(log -> log.message())
                .anySatisfy(message -> assertThat(message).contains("HTTP 504", "2/3"))
                .noneSatisfy(message -> assertThat(message).contains("batch 2/1"));
    }

    @Test
    void restoresOuterLogConsumerWhenGenerationFails() {
        update.setImpactSummary("{\"affectedTestPlanIds\":[101]}");
        var outerLogs = new java.util.ArrayList<String>();
        GenerationJobContext.bind(42L, outerLogs::add);
        when(agent.generateTestCases(10L, Set.of(101L))).thenAnswer(call -> {
            GenerationJobContext.log("provider detail");
            throw new LlmResponseException("provider unavailable", false);
        });

        assertThatThrownBy(() -> service.generateIncrementalStage(
                10L, 200L, GenerationProgressStage.TEST_CASE, new AuthUser()))
                .hasMessageContaining("provider unavailable");

        GenerationJobContext.log("outer restored");
        assertThat(outerLogs).containsExactly("outer restored");
        assertThat(progress.get(10L, GenerationProgressStage.TEST_CASE).logs()).extracting(log -> log.message())
                .contains("provider detail");
    }

    @Test
    void parallelRetriesAdvanceThreeDistinctStepsBeforeStageCompletion() {
        update.setImpactSummary("{\"affectedTestPlanIds\":[1,2,3,4,5,6,7,8,9]}");
        var attempts = new ConcurrentHashMap<Set<Long>, AtomicInteger>();
        var retryStatuses = new java.util.concurrent.CopyOnWriteArrayList<GenerationProgressStepStatus>();
        when(agent.generateTestCases(eq(10L), any())).thenAnswer(call -> {
            Set<Long> ids = call.getArgument(1);
            if (attempts.computeIfAbsent(ids, ignored -> new AtomicInteger()).getAndIncrement() == 0)
                throw new LlmResponseException("EOF reached while reading", true);
            var snapshot = progress.get(10L, GenerationProgressStage.TEST_CASE);
            int index = ids.contains(1L) ? 0 : ids.contains(5L) ? 1 : 2;
            retryStatuses.add(snapshot.steps().get(index).status());
            return new TestCaseResponseDto(List.of());
        });

        service.generateIncrementalStage(10L, 200L, GenerationProgressStage.TEST_CASE, new AuthUser());

        assertThat(attempts).hasSize(3);
        assertThat(retryStatuses).containsOnly(GenerationProgressStepStatus.RUNNING);
        assertThat(attempts.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(2));
        assertThat(completions).extracting(GenerationProgressDto::completedSteps).contains(1, 2, 3);
        assertThat(progress.get(10L, GenerationProgressStage.TEST_CASE).logs()).extracting(log -> log.message())
                .noneSatisfy(message -> assertThat(message).containsPattern("batch [4-9]/3"));
    }
}
