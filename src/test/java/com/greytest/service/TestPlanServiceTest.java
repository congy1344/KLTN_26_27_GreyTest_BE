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
                approvedRule(4L, 104L), approvedRule(5L, 105L), approvedRule(6L, 106L));
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED)).thenReturn(rules);
        when(aiAgentService.generateTestPlan(1L, Set.of(1L, 2L, 3L, 4L, 5L))).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(101L, 1L, List.of(1L), "Plan 1"),
                generatedPlan(102L, 2L, List.of(2L), "Plan 2"),
                generatedPlan(103L, 3L, List.of(3L), "Plan 3"),
                generatedPlan(104L, 4L, List.of(4L), "Plan 4"),
                generatedPlan(105L, 5L, List.of(5L), "Plan 5"))));
        when(aiAgentService.generateTestPlan(1L, Set.of(6L))).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(106L, 6L, List.of(6L), "Plan 6"))));
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of());
        mockTestPlanSaveAll();
        mockProjectSave();

        List<TestPlanDto> plans = service().generate(1L);

        assertThat(plans).hasSize(6);
        assertThat(plans).extracting(TestPlanDto::planCode)
                .containsExactly("TP-001", "TP-002", "TP-003", "TP-004", "TP-005", "TP-006");
        verify(aiAgentService).generateTestPlan(1L, Set.of(1L, 2L, 3L, 4L, 5L));
        verify(aiAgentService).generateTestPlan(1L, Set.of(6L));
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.PLAN_PENDING_REVIEW);
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
        when(aiAgentService.generateTestPlan(1L, Set.of(1L, 2L, 3L, 4L, 5L))).thenReturn(new TestPlanResponseDto(List.of(
                generatedPlan(101L, 1L, List.of(1L, 2L), "Plan 1"),
                generatedPlan(103L, 3L, List.of(3L), "Plan 3"),
                generatedPlan(104L, 4L, List.of(4L), "Plan 4"),
                generatedPlan(105L, 5L, List.of(5L), "Plan 5"))));

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
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED)).thenReturn(List.of());

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("it nhat mot Business Rule APPROVED");
    }

    private TestPlanService service() {
        return new TestPlanService(
                testPlanRepository,
                testPlanCoveredRuleRepository,
                businessRuleRepository,
                projectRepository,
                aiAgentService);
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
