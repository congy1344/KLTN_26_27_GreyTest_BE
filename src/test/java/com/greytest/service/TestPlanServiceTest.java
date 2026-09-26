package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.greytest.dto.UpdateTestPlanRequest;
import com.greytest.dto.TestPlanDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestPlanDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.Project;
import com.greytest.entity.TestPlan;
import com.greytest.entity.TestPlanCoveredRule;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.TestType;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.service.agent.LlmResponseException;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestPlanCoveredRuleRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.service.agent.AIAgentService;

@ExtendWith(MockitoExtension.class)
class TestPlanServiceTest {

    @Mock private TestPlanRepository testPlanRepository;
    @Mock private TestPlanCoveredRuleRepository testPlanCoveredRuleRepository;
    @Mock private BusinessRuleRepository businessRuleRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private AIAgentService aiAgentService;
    @Mock private GenerationProgressService generationProgressService;
    @Mock private ServiceScopeResolver scopeResolver;
    @BeforeEach
    void configureSemanticRetryMock() {
        org.mockito.Mockito.lenient().when(aiAgentService.generateTestPlan(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anySet(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    throw new LlmResponseException(invocation.getArgument(2));
                });
    }

    @Test
    void generatePersistsValidAiPlansAndDeletesOldPlans() {
        Project project = mockProject(ProjectStatus.BR_APPROVED);
        BusinessRule rule = approvedRule(7L);
        TestPlan oldPlan = plan(99L, 7L, "Old plan");
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED)).thenReturn(List.of(rule));
        when(aiAgentService.generateTestPlan(eq(1L), anySet())).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(11L, 7L, List.of(7L), "Happy path"))));
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(oldPlan));
        mockTestPlanSaveAll();
        mockProjectSave();

        List<TestPlanDto> plans = service().generate(1L);

        assertThat(plans).singleElement().satisfies(plan -> {
            assertThat(plan.businessRuleId()).isEqualTo(7L);
            assertThat(plan.planCode()).isEqualTo("TP-001");
            assertThat(plan.status()).isEqualTo(ReviewStatus.PENDING_REVIEW);
        });
        verify(testPlanRepository).deleteAll(List.of(oldPlan));
        verify(testPlanRepository).flush();
        verify(aiAgentService).generateTestPlan(1L, Set.of(7L));
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.PLAN_PENDING_REVIEW);
    }

    @Test
    void generateBatchesApprovedRulesToAvoidTruncatedJson() {
        Project project = mockProject(ProjectStatus.BR_APPROVED);
        List<BusinessRule> rules = List.of(
                approvedRule(1L, 101L), approvedRule(2L, 102L), approvedRule(3L, 103L),
                approvedRule(4L, 104L), approvedRule(5L, 105L), approvedRule(6L, 106L),
                approvedRule(7L, 107L), approvedRule(8L, 108L), approvedRule(9L, 109L));
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED)).thenReturn(rules);
        when(aiAgentService.generateTestPlan(1L, Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L))).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(101L, 1L, List.of(1L), "Plan 1"),
                generatedPlan(102L, 2L, List.of(2L), "Plan 2"),
                generatedPlan(103L, 3L, List.of(3L), "Plan 3"),
                generatedPlan(104L, 4L, List.of(4L), "Plan 4"),
                generatedPlan(105L, 5L, List.of(5L), "Plan 5"),
                generatedPlan(106L, 6L, List.of(6L), "Plan 6"),
                generatedPlan(107L, 7L, List.of(7L), "Plan 7"),
                generatedPlan(108L, 8L, List.of(8L), "Plan 8"))));
        when(aiAgentService.generateTestPlan(1L, Set.of(9L))).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(109L, 9L, List.of(9L), "Plan 9"))));
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of());
        mockTestPlanSaveAll();
        mockProjectSave();

        List<TestPlanDto> plans = service().generate(1L);

        assertThat(plans).hasSize(9);
        assertThat(plans).extracting(TestPlanDto::planCode)
                .containsExactly("TP-001", "TP-002", "TP-003", "TP-004", "TP-005", "TP-006", "TP-007", "TP-008", "TP-009");
        verify(aiAgentService).generateTestPlan(1L, Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L));
        verify(aiAgentService).generateTestPlan(1L, Set.of(9L));
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.PLAN_PENDING_REVIEW);
    }

    @Test
    void generateRetriesCurrentBatchWhenAiMissesApprovedRule() {
        Project project = mockProject(ProjectStatus.BR_APPROVED);
        List<BusinessRule> rules = List.of(approvedRule(7L, 11L), approvedRule(8L, 11L));
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED)).thenReturn(rules);
        when(aiAgentService.generateTestPlan(1L, Set.of(7L, 8L))).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(11L, 7L, List.of(7L), "Plan 7"))));
        when(aiAgentService.generateTestPlan(
                eq(1L), eq(Set.of(7L, 8L)), org.mockito.ArgumentMatchers.contains("[7, 8]")))
                .thenReturn(new TestPlanResponseDto(List.of(
                        generatedPlan(11L, 7L, List.of(7L), "Plan 7"),
                        generatedPlan(11L, 8L, List.of(8L), "Plan 8"))));
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of());
        mockTestPlanSaveAll();
        mockProjectSave();

        assertThat(service().generate(1L)).hasSize(2);
        verify(aiAgentService).generateTestPlan(1L, Set.of(7L, 8L));
        verify(aiAgentService).generateTestPlan(
                eq(1L), eq(Set.of(7L, 8L)), org.mockito.ArgumentMatchers.contains("[7, 8]"));
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.PLAN_PENDING_REVIEW);
    }

    @Test
    void retryReportsAllMappingErrorsAndPersistsOnlyCorrectedBatch() {
        mockProject(ProjectStatus.BR_APPROVED);
        Set<Long> ids = Set.of(919L, 920L, 921L, 922L, 923L);
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED))
                .thenReturn(List.of(approvedRule(919L, 95236L), approvedRule(920L, 95237L),
                        approvedRule(921L, 95237L), approvedRule(922L, 95237L), approvedRule(923L, 95238L)));
        when(aiAgentService.generateTestPlan(1L, ids)).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(95236L, 919L, List.of(919L, 920L), "Tax with wrong rule"),
                generatedPlan(95237L, 920L, List.of(920L, 921L), "Shipping missing VIP"))));
        org.mockito.Mockito.doAnswer(invocation -> {
            String correction = invocation.getArgument(2);
            assertThat(correction)
                    .contains("method 95236: expected=[919]; actual=[919, 920]; unexpected=[920]")
                    .contains("method 95237: expected=[920, 921, 922]; actual=[920, 921]; missing=[922]")
                    .contains("method: [95238]")
                    .contains("method 95238: expected=[923]; actual=[]; missing=[923]");
            return new TestPlanResponseDto(List.of(
                    generatedPlan(95236L, 919L, List.of(919L), "Tax"),
                    generatedPlan(95237L, 920L, List.of(920L, 921L, 922L), "Shipping including VIP"),
                    generatedPlan(95238L, 923L, List.of(923L), "Deadline")));
        }).when(aiAgentService).generateTestPlan(eq(1L), eq(ids), org.mockito.ArgumentMatchers.anyString());
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of());
        mockTestPlanSaveAll();
        mockProjectSave();

        assertThat(service().generate(1L)).hasSize(3);
        verify(aiAgentService).generateTestPlan(eq(1L), eq(ids), org.mockito.ArgumentMatchers.anyString());
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<TestPlanCoveredRule>> links = ArgumentCaptor.forClass((Class) List.class);
        verify(testPlanCoveredRuleRepository).saveAll(links.capture());
        assertThat(links.getValue()).extracting(TestPlanCoveredRule::getBusinessRuleId)
                .containsExactly(919L, 920L, 921L, 922L, 923L);
    }

    @Test
    void retryStillMissingRuleFailsWithoutReplacingExistingPlans() {
        Project project = mockProject(ProjectStatus.BR_APPROVED);
        Set<Long> ids = Set.of(920L, 921L, 922L);
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED))
                .thenReturn(List.of(approvedRule(920L, 95237L), approvedRule(921L, 95237L),
                        approvedRule(922L, 95237L)));
        TestPlanResponseDto incomplete = new TestPlanResponseDto(List.of(
                generatedPlan(95237L, 920L, List.of(920L, 921L), "Shipping missing VIP")));
        when(aiAgentService.generateTestPlan(1L, ids)).thenReturn(incomplete);
        org.mockito.Mockito.doReturn(incomplete).when(aiAgentService)
                .generateTestPlan(eq(1L), eq(ids), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(LlmResponseException.class).hasMessageContaining("missing=[922]");
        verify(aiAgentService).generateTestPlan(eq(1L), eq(ids), org.mockito.ArgumentMatchers.anyString());
        verify(testPlanRepository, never()).deleteAll(any());
        verify(testPlanRepository, never()).saveAll(any());
        verify(testPlanCoveredRuleRepository, never()).saveAll(any());
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.BR_APPROVED);
    }

    @Test
    void generateRetriesWhenCoveredRuleIdsContainNull() {
        Project project = mockProject(ProjectStatus.BR_APPROVED);
        List<BusinessRule> rules = List.of(approvedRule(7L, 11L), approvedRule(8L, 11L));
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED)).thenReturn(rules);
        when(aiAgentService.generateTestPlan(1L, Set.of(7L, 8L))).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(11L, 7L, java.util.Arrays.asList(7L, null), "Invalid plan"))));
        when(aiAgentService.generateTestPlan(
                eq(1L), eq(Set.of(7L, 8L)), org.mockito.ArgumentMatchers.contains("covered_rule_ids")))
                .thenReturn(new TestPlanResponseDto(List.of(
                        generatedPlan(11L, 7L, List.of(7L), "Plan 7"),
                        generatedPlan(11L, 8L, List.of(8L), "Plan 8"))));
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of());
        mockTestPlanSaveAll();
        mockProjectSave();

        assertThat(service().generate(1L)).hasSize(2);
        verify(aiAgentService).generateTestPlan(
                eq(1L), eq(Set.of(7L, 8L)), org.mockito.ArgumentMatchers.contains("covered_rule_ids"));
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.PLAN_PENDING_REVIEW);
    }

    @Test
    void generateReportsSaveStepWhenFailureHappensAfterLastBatch() {
        mockProject(ProjectStatus.BR_APPROVED);
        BusinessRule rule = approvedRule(7L);
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED)).thenReturn(List.of(rule));
        when(aiAgentService.generateTestPlan(eq(1L), anySet())).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(11L, 7L, List.of(7L), "Happy path"))));
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of());
        mockTestPlanSaveAll();
        when(projectRepository.save(any(Project.class))).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service().generate(1L)).isInstanceOf(IllegalStateException.class);

        verify(generationProgressService).fail(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.TEST_PLAN),
                org.mockito.ArgumentMatchers.contains("bước kiểm tra và lưu Test Plan"));
    }

    @Test
    void generateAllowsMultiplePlansPerMethodAndPersistsCoveredRules() {
        Project project = mockProject(ProjectStatus.BR_APPROVED);
        List<BusinessRule> rules = List.of(approvedRule(1L, 11L), approvedRule(2L, 11L), approvedRule(3L, 12L));
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED)).thenReturn(rules);
        when(aiAgentService.generateTestPlan(1L, Set.of(1L, 2L, 3L))).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(11L, 1L, List.of(1L), "Plan method 11 happy"),
                generatedPlan(11L, 2L, List.of(2L), "Plan method 11 exception"),
                generatedPlan(12L, 3L, List.of(3L), "Plan method 12"))));
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of());
        mockTestPlanSaveAll();
        mockProjectSave();

        List<TestPlanDto> plans = service().generate(1L);

        assertThat(plans).hasSize(3);
        assertThat(plans).extracting(TestPlanDto::businessRuleId).containsExactly(1L, 2L, 3L);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<TestPlanCoveredRule>> links = ArgumentCaptor.forClass((Class) List.class);
        verify(testPlanCoveredRuleRepository).saveAll(links.capture());
        assertThat(links.getValue()).extracting(TestPlanCoveredRule::getBusinessRuleId)
                .containsExactly(1L, 2L, 3L);
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.PLAN_PENDING_REVIEW);
    }

    @Test
    void generateRejectsCoveredRuleFromDifferentMethodBeforeDeletingOldPlans() {
        mockProject(ProjectStatus.BR_APPROVED);
        List<BusinessRule> rules = List.of(
                approvedRule(1L, 101L), approvedRule(2L, 102L), approvedRule(3L, 103L),
                approvedRule(4L, 104L), approvedRule(5L, 105L), approvedRule(6L, 106L));
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED)).thenReturn(rules);
        when(aiAgentService.generateTestPlan(1L, Set.of(1L, 2L, 3L, 4L, 5L, 6L))).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(101L, 1L, List.of(1L, 2L), "Plan 1"),
                generatedPlan(103L, 3L, List.of(3L), "Plan 3"),
                generatedPlan(104L, 4L, List.of(4L), "Plan 4"),
                generatedPlan(105L, 5L, List.of(5L), "Plan 5"),
                generatedPlan(106L, 6L, List.of(6L), "Plan 6"))));

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("nam ngoai method");
        verify(testPlanRepository, never()).deleteAll(any());
    }

    @Test
    void generateFailsWhenThereAreNoApprovedRules() {
        mockProject(ProjectStatus.BR_APPROVED);
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED)).thenReturn(List.of());

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("Business Rule APPROVED");
    }

    @Test
    void generateKeepsOldPlansWhenAiMissesAnApprovedRule() {
        mockProject(ProjectStatus.PLAN_PENDING_REVIEW);
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED))
                .thenReturn(List.of(approvedRule(7L, 11L), approvedRule(8L, 11L)));
        when(aiAgentService.generateTestPlan(eq(1L), anySet())).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(11L, 7L, List.of(7L), "Happy path"))));

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("[7, 8]");
        verify(testPlanRepository, never()).deleteAll(any());
    }

    @Test
    void updatePreservesCoveredRuleLinksWhenAnchorRuleIsUnchanged() {
        Project project = mockProject(ProjectStatus.PLAN_PENDING_REVIEW);
        TestPlan plan = plan(3L, 7L, "Happy path");
        BusinessRule rule = approvedRule(7L);
        when(testPlanRepository.findById(3L)).thenReturn(Optional.of(plan));
        when(businessRuleRepository.findById(7L)).thenReturn(Optional.of(rule));
        when(testPlanRepository.save(any(TestPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        mockProjectSave();

        TestPlanDto updated = service().update(3L, new UpdateTestPlanRequest(
                7L, "Updated plan", "Updated description", TestType.BOUNDARY));

        assertThat(updated.testType()).isEqualTo(TestType.BOUNDARY);
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.PLAN_PENDING_REVIEW);
        verify(testPlanCoveredRuleRepository, never()).deleteByTestPlanId(3L);
        verify(testPlanCoveredRuleRepository, never()).saveAll(any());
    }

    @Test
    void updateRewritesCoveredRuleLinksWhenAnchorRuleChanges() {
        mockProject(ProjectStatus.PLAN_PENDING_REVIEW);
        TestPlan plan = plan(3L, 7L, "Happy path");
        BusinessRule rule = approvedRule(8L);
        when(testPlanRepository.findById(3L)).thenReturn(Optional.of(plan));
        when(businessRuleRepository.findById(8L)).thenReturn(Optional.of(rule));
        when(testPlanRepository.save(any(TestPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(testPlanCoveredRuleRepository.findByTestPlanId(3L))
                .thenReturn(List.of(coveredRule(3L, 7L), coveredRule(3L, 9L)));
        mockProjectSave();

        service().update(3L, new UpdateTestPlanRequest(
                8L, "Updated plan", "Updated description", TestType.BOUNDARY));

        verify(testPlanCoveredRuleRepository).deleteByTestPlanId(3L);
        verify(testPlanCoveredRuleRepository).flush();
        verify(testPlanCoveredRuleRepository).saveAll(any());
    }

    @Test
    void approveMarksPlansAndProjectApproved() {
        Project project = mockProject(ProjectStatus.PLAN_PENDING_REVIEW);
        TestPlan plan = plan(3L, 7L, "Happy path");
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(plan));
        when(testPlanRepository.save(any(TestPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        mockProjectSave();

        List<TestPlanDto> approved = service().approve(1L);

        assertThat(approved).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(ReviewStatus.APPROVED));
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.PLAN_APPROVED);
    }

    @Test
    void generateChoPhepRegenerateKhiDaHoanTatPipeline() {
        // Guard mở cho vòng regenerate: ở COMPLETED vẫn gọi được generate — lỗi ném ra
        // phải là "thiếu BR approved" (bước sau guard), không phải lỗi chặn status
        mockProject(ProjectStatus.COMPLETED);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("it nhat mot Business Rule");
    }

    private TestPlanService service() {
        return new TestPlanService(
                testPlanRepository,
                testPlanCoveredRuleRepository,
                businessRuleRepository,
                projectRepository,
                aiAgentService,
                generationProgressService,
                null,
                scopeResolver);
    }

    private Project mockProject(ProjectStatus status) {
        Project project = new Project();
        project.setId(1L);
        project.setName("demo");
        project.setStatus(status);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        return project;
    }

    private void mockProjectSave() {
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void mockTestPlanSaveAll() {
        AtomicLong ids = new AtomicLong(100);
        when(testPlanRepository.saveAll(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Iterable<TestPlan> plans = invocation.getArgument(0);
            for (TestPlan plan : plans) {
                if (plan.getId() == null) {
                    plan.setId(ids.getAndIncrement());
                }
            }
            return plans;
        });
    }

    private BusinessRule approvedRule(Long id) {
        return approvedRule(id, 11L);
    }

    private BusinessRule approvedRule(Long id, Long methodId) {
        BusinessRule rule = new BusinessRule();
        rule.setId(id);
        rule.setProjectId(1L);
        rule.setMethodId(methodId);
        rule.setRuleCode("BR-001");
        rule.setDescription("Input phai hop le.");
        rule.setSource(RuleSource.AI_GENERATED);
        rule.setStatus(ReviewStatus.APPROVED);
        rule.setIsModified(false);
        return rule;
    }

    private GeneratedTestPlanDto generatedPlan(Long methodId, Long ruleId, List<Long> coveredRuleIds, String title) {
        return new GeneratedTestPlanDto(
                methodId,
                ruleId,
                coveredRuleIds,
                title,
                "Du lieu hop le thi thanh cong.",
                "HAPPY_PATH");
    }

    @Test
    void generateForScopedServiceContinuesCodeSequenceFromExistingPlans() {
        Project project = mockProject(ProjectStatus.BR_APPROVED);
        BusinessRule ruleServiceA = approvedRule(1L, 101L);
        BusinessRule ruleServiceB = approvedRule(2L, 202L);

        TestPlan existingPlan1 = plan(91L, 1L, "Service A Plan 1");
        existingPlan1.setPlanCode("TP-001");
        TestPlan existingPlan2 = plan(92L, 1L, "Service A Plan 2");
        existingPlan2.setPlanCode("TP-002");

        when(scopeResolver.resolve(1L, "service-b")).thenReturn(
                new ServiceScopeResolver.ServiceScope("service-b", Set.of(), Set.of(202L)));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(ruleServiceA, ruleServiceB));
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(existingPlan1, existingPlan2));
        when(aiAgentService.generateTestPlan(eq(1L), eq(Set.of(2L)))).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(202L, 2L, List.of(2L), "Service B Plan 1"))));
        mockTestPlanSaveAll();
        mockProjectSave();

        // Sinh chỉ cho service B (scoped method 202L)
        service().generate(1L, "service-b", false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestPlan>> captor = ArgumentCaptor.forClass(List.class);
        verify(testPlanRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(savedPlan -> {
            assertThat(savedPlan.getPlanCode()).isEqualTo("TP-003");
            assertThat(savedPlan.getTitle()).isEqualTo("Service B Plan 1");
        });
    }

    private TestPlan plan(Long id, Long ruleId, String title) {
        TestPlan plan = new TestPlan();
        plan.setId(id);
        plan.setProjectId(1L);
        plan.setBusinessRuleId(ruleId);
        plan.setPlanCode("TP-001");
        plan.setTitle(title);
        plan.setDescription("Mo ta");
        plan.setTestType(TestType.HAPPY_PATH);
        plan.setStatus(ReviewStatus.PENDING_REVIEW);
        plan.setIsModified(false);
        return plan;
    }

    private TestPlanCoveredRule coveredRule(Long planId, Long ruleId) {
        TestPlanCoveredRule link = new TestPlanCoveredRule();
        link.setTestPlanId(planId);
        link.setBusinessRuleId(ruleId);
        return link;
    }
}
