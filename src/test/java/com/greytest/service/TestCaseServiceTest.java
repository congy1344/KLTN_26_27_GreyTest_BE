package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto;
import com.greytest.dto.CoverageGapDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto;
import com.greytest.entity.Project;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.enums.Priority;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.TestType;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.LlmResponseException;

@ExtendWith(MockitoExtension.class)
class TestCaseServiceTest {

    @Mock private TestCaseRepository cases;
    @Mock private TestPlanRepository plans;
    @Mock private ProjectRepository projects;
    @Mock private AIAgentService ai;
    @Mock private BusinessRuleRepository rules;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private GenerationProgressService generationProgress;

    @InjectMocks private TestCaseService service;

    private Project project(ProjectStatus status) {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(status);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        org.mockito.Mockito.lenient()
                .when(projects.findByIdForUpdate(1L))
                .thenReturn(Optional.of(project));
        return project;
    }

    @Test
    void generateChoPhepRegenerateSauKhiDaCoCoverage() {
        // Guard mở cho vòng regenerate: ở COVERAGE_ANALYZED vẫn qua được guard —
        // lỗi ném ra là của bước sau (AI trả rỗng), không phải lỗi chặn status
        project(ProjectStatus.COVERAGE_ANALYZED);
        when(plans.findByProjectId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.generate(1L))
                .isInstanceOf(LlmResponseException.class);
    }

    @Test
    void generateBatchesApprovedPlansToKeepLlmRequestsBounded() {
        project(ProjectStatus.PLAN_APPROVED);
        List<TestPlan> approvedPlans = LongStream.rangeClosed(1, 11)
                .mapToObj(id -> {
                    TestPlan plan = new TestPlan();
                    plan.setId(id);
                    plan.setProjectId(1L);
                    plan.setStatus(ReviewStatus.APPROVED);
                    when(plans.existsById(id)).thenReturn(true);
                    when(plans.findById(id)).thenReturn(Optional.of(plan));
                    return plan;
                })
                .toList();
        when(plans.findByProjectId(1L)).thenReturn(approvedPlans);
        when(ai.generateTestCases(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.<Set<Long>>any()))
                .thenAnswer(invocation -> {
                    Set<Long> planIds = invocation.getArgument(1);
                    return new TestCaseResponseDto(planIds.stream()
                            .map(id -> generatedCase(id, "case " + id))
                            .toList());
                });
        when(cases.saveAll(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generate(1L);

        assertThat(result).hasSize(11);
        ArgumentCaptor<Set<Long>> batches = ArgumentCaptor.forClass(Set.class);
        verify(ai, org.mockito.Mockito.times(3)).generateTestCases(
                org.mockito.ArgumentMatchers.eq(1L), batches.capture());
        assertThat(batches.getAllValues()).extracting(Set::size).containsExactly(5, 5, 1);
        assertThat(batches.getAllValues().stream().flatMap(Set::stream))
                .containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 11).boxed().toList());
    }

    @Test
    void generateReportsSaveStepWhenFailureHappensAfterLastBatch() {
        project(ProjectStatus.PLAN_APPROVED);
        TestPlan plan = approvedPlan(20L, false);
        when(plans.findByProjectId(1L)).thenReturn(List.of(plan));
        when(plans.existsById(20L)).thenReturn(true);
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateTestCases(1L, Set.of(20L))).thenReturn(new TestCaseResponseDto(List.of(
                generatedCase(20L, "valid case"))));
        when(cases.saveAll(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.generate(1L)).isInstanceOf(IllegalStateException.class);

        verify(generationProgress).fail(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.TEST_CASE),
                org.mockito.ArgumentMatchers.contains("bước kiểm tra và lưu Test Case"));
    }

    @Test
    void generateRemovesEquivalentScenarioWhenEveryPlanStillHasACase() {
        project(ProjectStatus.PLAN_APPROVED);
        TestPlan normalPlan = approvedPlan(20L, false);
        TestPlan edgePlan = approvedPlan(21L, false);
        edgePlan.setBusinessRuleId(8L);
        BusinessRule normalRule = new BusinessRule();
        normalRule.setId(7L);
        normalRule.setMethodId(11L);
        BusinessRule edgeRule = new BusinessRule();
        edgeRule.setId(8L);
        edgeRule.setMethodId(11L);
        when(plans.findByProjectId(1L)).thenReturn(List.of(normalPlan, edgePlan));
        when(plans.existsById(20L)).thenReturn(true);
        when(plans.existsById(21L)).thenReturn(true);
        when(plans.findById(20L)).thenReturn(Optional.of(normalPlan));
        when(plans.findById(21L)).thenReturn(Optional.of(edgePlan));
        when(rules.findById(7L)).thenReturn(Optional.of(normalRule));
        when(rules.findById(8L)).thenReturn(Optional.of(edgeRule));
        var values = java.util.Map.<String, Object>of("values", java.util.List.of(10, 20));
        when(ai.generateTestCases(1L, Set.of(20L, 21L))).thenReturn(new TestCaseResponseDto(List.of(
                generatedCase(20L, "average with valid values", values, "15"),
                generatedCase(21L, "mixed values return their average", values, "15"),
                generatedCase(21L, "empty values return zero", java.util.Map.of("values", List.of()), "0"))));
        when(cases.saveAll(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generate(1L);

        assertThat(result).extracting("description")
                .containsExactly("average with valid values", "empty values return zero");
        assertThat(result).extracting("testPlanId").containsExactly(20L, 21L);
    }

    @Test
    void generateKeepsEquivalentScenarioWhenItIsTheOnlyCaseOfAPlan() {
        project(ProjectStatus.PLAN_APPROVED);
        TestPlan firstPlan = approvedPlan(20L, false);
        TestPlan secondPlan = approvedPlan(21L, false);
        secondPlan.setBusinessRuleId(8L);
        BusinessRule firstRule = new BusinessRule();
        firstRule.setId(7L);
        firstRule.setMethodId(11L);
        BusinessRule secondRule = new BusinessRule();
        secondRule.setId(8L);
        secondRule.setMethodId(11L);
        when(plans.findByProjectId(1L)).thenReturn(List.of(firstPlan, secondPlan));
        when(plans.existsById(20L)).thenReturn(true);
        when(plans.existsById(21L)).thenReturn(true);
        when(plans.findById(20L)).thenReturn(Optional.of(firstPlan));
        when(plans.findById(21L)).thenReturn(Optional.of(secondPlan));
        when(rules.findById(7L)).thenReturn(Optional.of(firstRule));
        when(rules.findById(8L)).thenReturn(Optional.of(secondRule));
        var values = java.util.Map.<String, Object>of("score", 90);
        when(ai.generateTestCases(1L, Set.of(20L, 21L))).thenReturn(new TestCaseResponseDto(List.of(
                generatedCase(20L, "first trace", values, "EXCELLENT"),
                generatedCase(21L, "second trace", values, "EXCELLENT"))));
        when(cases.saveAll(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.generate(1L)).extracting("testPlanId").containsExactly(20L, 21L);
    }


    @Test
    void generateKeepsSameInputAndOutputWhenPreconditionsDiffer() {
        project(ProjectStatus.PLAN_APPROVED);
        TestPlan plan = approvedPlan(20L, false);
        BusinessRule rule = new BusinessRule();
        rule.setId(7L);
        rule.setMethodId(11L);
        when(plans.findByProjectId(1L)).thenReturn(List.of(plan));
        when(plans.existsById(20L)).thenReturn(true);
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(rules.findById(7L)).thenReturn(Optional.of(rule));
        var testData = java.util.Map.<String, Object>of("id", 1);
        when(ai.generateTestCases(1L, Set.of(20L))).thenReturn(new TestCaseResponseDto(List.of(
                generatedCase(20L, "repository miss", "repository returns empty", testData, "not found"),
                generatedCase(20L, "inactive user", "repository returns an inactive user", testData, "not found"))));
        when(cases.saveAll(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.generate(1L)).extracting("preconditions")
                .containsExactly("repository returns empty", "repository returns an inactive user");
    }

    @Test
    void generateDoesNotPersistWhenALateBatchFails() {
        project(ProjectStatus.PLAN_APPROVED);
        List<TestPlan> approvedPlans = LongStream.rangeClosed(1, 6)
                .mapToObj(id -> {
                    TestPlan plan = new TestPlan();
                    plan.setId(id);
                    plan.setProjectId(1L);
                    plan.setStatus(ReviewStatus.APPROVED);
                    if (id <= 5) {
                        when(plans.existsById(id)).thenReturn(true);
                        when(plans.findById(id)).thenReturn(Optional.of(plan));
                    }
                    return plan;
                })
                .toList();
        when(plans.findByProjectId(1L)).thenReturn(approvedPlans);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(ai.generateTestCases(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.<Set<Long>>any()))
                .thenAnswer(invocation -> {
                    if (calls.incrementAndGet() == 2) {
                        throw new LlmResponseException("second batch failed");
                    }
                    Set<Long> planIds = invocation.getArgument(1);
                    return new TestCaseResponseDto(planIds.stream()
                            .map(id -> generatedCase(id, "case " + id))
                            .toList());
                });

        assertThatThrownBy(() -> service.generate(1L))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("second batch failed");
        verify(cases, org.mockito.Mockito.never()).deleteAll(org.mockito.ArgumentMatchers.anyList());
        verify(cases, org.mockito.Mockito.never()).saveAll(org.mockito.ArgumentMatchers.anyList());
        verify(projects, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void generateRejectsPlansChangedBeforeFinalPersistence() {
        project(ProjectStatus.PLAN_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        plan.setStatus(ReviewStatus.APPROVED);
        when(plans.findByProjectId(1L)).thenReturn(List.of(plan), List.of());
        when(plans.existsById(20L)).thenReturn(true);
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateTestCases(1L, Set.of(20L)))
                .thenReturn(new TestCaseResponseDto(List.of(generatedCase(20L, "case 20"))));

        assertThatThrownBy(() -> service.generate(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("thay doi");
        verify(cases, org.mockito.Mockito.never()).deleteAll(org.mockito.ArgumentMatchers.anyList());
        verify(cases, org.mockito.Mockito.never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void generateBiChanTruocKhiTestPlanApprove() {
        project(ProjectStatus.BR_APPROVED);

        assertThatThrownBy(() -> service.generate(1L))
                .isInstanceOf(InvalidProjectStatusException.class);
    }

    @Test
    void generateWithoutPlanIdCannotReplaceExistingCases() {
        project(ProjectStatus.CASE_APPROVED);
        TestPlan plan = approvedPlan(20L, false);
        TestCase existingCase = existingCase(30L, 20L, "TC-001");
        when(plans.findByProjectId(1L)).thenReturn(List.of(plan));
        when(cases.findByTestPlanId(20L)).thenReturn(List.of(existingCase));

        assertThatThrownBy(() -> service.generate(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("chon mot Test Plan");

        verify(ai, org.mockito.Mockito.never()).generateTestCases(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.<Set<Long>>any());
        verify(cases, org.mockito.Mockito.never()).deleteAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void regenerateReplacesOnlyCasesOfSelectedPlan() {
        Project project = project(ProjectStatus.PLAN_APPROVED);
        TestPlan targetPlan = approvedPlan(20L, true);
        TestPlan untouchedPlan = approvedPlan(21L, false);
        TestCase targetCase = existingCase(30L, 20L, "TC-010");
        TestCase untouchedCase = existingCase(31L, 21L, "TC-011");
        when(plans.findById(20L)).thenReturn(Optional.of(targetPlan));
        when(plans.existsById(20L)).thenReturn(true);
        when(ai.generateTestCases(1L, Set.of(20L)))
                .thenReturn(new TestCaseResponseDto(List.of(generatedCase(20L, "new target case"))));
        when(cases.findByTestPlanId(20L)).thenReturn(List.of(targetCase));
        when(cases.findAll()).thenReturn(List.of(untouchedCase));
        when(cases.saveAll(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.regenerate(1L, 20L);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.testPlanId()).isEqualTo(20L);
            assertThat(item.caseCode()).isEqualTo("TC-012");
        });
        verify(cases).deleteAll(List.of(targetCase));
        verify(cases, org.mockito.Mockito.never()).deleteAll(List.of(untouchedCase));
        assertThat(targetPlan.getIsModified()).isFalse();
        assertThat(untouchedPlan.getIsModified()).isFalse();
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.CASE_PENDING_REVIEW);
    }

    @Test
    void regenerateRejectsAiCasesForAnotherPlanBeforeDeletingAnything() {
        project(ProjectStatus.PLAN_APPROVED);
        TestPlan targetPlan = approvedPlan(20L, true);
        when(plans.findById(20L)).thenReturn(Optional.of(targetPlan));
        when(ai.generateTestCases(1L, Set.of(20L)))
                .thenReturn(new TestCaseResponseDto(List.of(generatedCase(21L, "wrong plan"))));

        assertThatThrownBy(() -> service.regenerate(1L, 20L))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("chi cho Test Plan");

        verify(cases, org.mockito.Mockito.never()).deleteAll(org.mockito.ArgumentMatchers.anyList());
        verify(cases, org.mockito.Mockito.never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void refinementAddsApprovedCasesWithoutDeletingRoundOne() {
        Project project = project(ProjectStatus.COVERAGE_ANALYZED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        plan.setBusinessRuleId(7L);
        plan.setStatus(ReviewStatus.APPROVED);
        BusinessRule rule = new BusinessRule();
        rule.setId(7L);
        rule.setMethodId(11L);
        TestCase oldCase = new TestCase();
        oldCase.setId(30L);
        oldCase.setCaseCode("TC-001");
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(plans.existsById(20L)).thenReturn(true);
        when(plans.findByProjectId(1L)).thenReturn(List.of(plan));
        when(rules.findById(7L)).thenReturn(Optional.of(rule));
        when(cases.findAll()).thenReturn(List.of(oldCase));
        when(ai.generateCoverageRefinement(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new TestCaseResponseDto(List.of(new GeneratedTestCaseDto(
                        20L, "EXCEPTION", "missing branch", "ready", java.util.Map.of(),
                        "throws", "HIGH", "BR-001 -> TP-001"))));
        when(cases.saveAll(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generateSupplemental(1L, List.of(new CoverageGapDto(
                11L, "OrderService", "createOrder", java.math.BigDecimal.valueOf(40),
                java.math.BigDecimal.valueOf(50), List.of(12), List.of(12), "HIGH", "cover branch", true)), 2);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).caseCode()).isEqualTo("TC-002");
        assertThat(result.get(0).status()).isEqualTo(ReviewStatus.APPROVED);
        verify(cases, org.mockito.Mockito.never()).deleteAll(org.mockito.ArgumentMatchers.anyList());
        verify(projects).save(project);
    }

    @Test
    void refinementRejectsAnExistingScenario() {
        project(ProjectStatus.COVERAGE_ANALYZED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        plan.setBusinessRuleId(7L);
        plan.setStatus(ReviewStatus.APPROVED);
        BusinessRule rule = new BusinessRule();
        rule.setId(7L);
        rule.setMethodId(11L);
        TestCase oldCase = new TestCase();
        oldCase.setTestPlanId(20L);
        oldCase.setDescription("Repository has no matching entity");
        oldCase.setPreconditions("ready");
        oldCase.setTestData(java.util.Map.of());
        oldCase.setExpectedResult("Throws");
        when(plans.findByProjectId(1L)).thenReturn(List.of(plan));
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(plans.existsById(20L)).thenReturn(true);
        when(rules.findById(7L)).thenReturn(Optional.of(rule));
        when(cases.findByTestPlanId(20L)).thenReturn(List.of(oldCase));
        when(ai.generateCoverageRefinement(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new TestCaseResponseDto(List.of(new GeneratedTestCaseDto(
                        20L, "EXCEPTION", "Different wording for the same scenario", "ready", java.util.Map.of(),
                        "throws", "HIGH", "BR-001 -> TP-001"))));

        assertThatThrownBy(() -> service.generateSupplemental(1L, List.of(new CoverageGapDto(
                11L, "OrderService", "createOrder", java.math.BigDecimal.valueOf(40),
                java.math.BigDecimal.valueOf(50), List.of(12), List.of(12), "HIGH", "cover branch", true)), 2))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("trùng");
    }

    private GeneratedTestCaseDto generatedCase(Long planId, String description) {
        return generatedCase(planId, description, java.util.Map.of(), "success");
    }

    private GeneratedTestCaseDto generatedCase(
            Long planId, String description, java.util.Map<String, Object> testData, String expectedResult) {
        return generatedCase(planId, description, "ready", testData, expectedResult);
    }

    private GeneratedTestCaseDto generatedCase(
            Long planId, String description, String preconditions,
            java.util.Map<String, Object> testData, String expectedResult) {
        return new GeneratedTestCaseDto(
                planId, "HAPPY_PATH", description, preconditions, testData,
                expectedResult, "HIGH", "BR -> TP");
    }

    private TestPlan approvedPlan(Long id, boolean modified) {
        TestPlan plan = new TestPlan();
        plan.setId(id);
        plan.setProjectId(1L);
        plan.setBusinessRuleId(7L);
        plan.setTitle("Plan " + id);
        plan.setDescription("Description " + id);
        plan.setTestType(TestType.HAPPY_PATH);
        plan.setStatus(ReviewStatus.APPROVED);
        plan.setIsModified(modified);
        return plan;
    }

    private TestCase existingCase(Long id, Long planId, String code) {
        TestCase testCase = new TestCase();
        testCase.setId(id);
        testCase.setTestPlanId(planId);
        testCase.setCaseCode(code);
        return testCase;
    }
}
