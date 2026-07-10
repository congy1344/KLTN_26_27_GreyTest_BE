package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.greytest.dto.TestPlanDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestPlanDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.Project;
import com.greytest.entity.TestPlan;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.TestType;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.service.agent.LlmResponseException;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.service.agent.AIAgentService;

@ExtendWith(MockitoExtension.class)
class TestPlanServiceTest {

    @Mock private TestPlanRepository testPlanRepository;
    @Mock private BusinessRuleRepository businessRuleRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private AIAgentService aiAgentService;

    @Test
    void generatePersistsValidAiPlansAndDeletesOldPlans() {
        Project project = mockProject(ProjectStatus.BR_APPROVED);
        BusinessRule rule = approvedRule(7L);
        TestPlan oldPlan = plan(99L, 7L, "Old plan");
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED)).thenReturn(List.of(rule));
        when(aiAgentService.generateTestPlan(1L)).thenReturn(new TestPlanResponseDto(List.of(
                new GeneratedTestPlanDto(7L, "Happy path", "Du lieu hop le thi thanh cong.", "HAPPY_PATH"),
                new GeneratedTestPlanDto(999L, "Sai rule", "Bi bo qua.", "EDGE"))));
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
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.PLAN_PENDING_REVIEW);
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
        TestPlan oldPlan = plan(99L, 7L, "Old plan");
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED))
                .thenReturn(List.of(approvedRule(7L), approvedRule(8L)));
        when(aiAgentService.generateTestPlan(1L)).thenReturn(new TestPlanResponseDto(List.of(
                new GeneratedTestPlanDto(7L, "Happy path", "Du lieu hop le.", "HAPPY_PATH"))));

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("8");
        verify(testPlanRepository, never()).deleteAll(List.of(oldPlan));
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

    private TestPlanService service() {
        return new TestPlanService(testPlanRepository, businessRuleRepository, projectRepository, aiAgentService);
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
        BusinessRule rule = new BusinessRule();
        rule.setId(id);
        rule.setProjectId(1L);
        rule.setMethodId(11L);
        rule.setRuleCode("BR-001");
        rule.setDescription("Input phai hop le.");
        rule.setSource(RuleSource.AI_GENERATED);
        rule.setStatus(ReviewStatus.APPROVED);
        rule.setIsModified(false);
        return rule;
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
}
