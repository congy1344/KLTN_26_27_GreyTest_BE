package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.Project;
import com.greytest.entity.TestPlan;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.SourceType;
import com.greytest.entity.enums.TestType;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.service.agent.AIAgentService;

@SpringBootTest
class TestCaseGenerationConcurrencyIntegrationTest {

    @Autowired private TestCaseService service;
    @Autowired private ProjectRepository projects;
    @Autowired private BusinessRuleRepository rules;
    @Autowired private TestPlanRepository plans;
    @Autowired private TestCaseRepository cases;
    @MockBean private AIAgentService ai;

    private Long projectId;

    @AfterEach
    void cleanUp() {
        if (projectId != null && projects.existsById(projectId)) {
            projects.deleteById(projectId);
        }
    }

    @Test
    void concurrentGenerationLeavesOneReplacementSet() throws Exception {
        Project project = new Project();
        project.setName("concurrency-" + System.nanoTime());
        project.setSourceType(SourceType.ZIP);
        project.setStatus(ProjectStatus.PLAN_APPROVED);
        project = projects.saveAndFlush(project);
        projectId = project.getId();

        BusinessRule rule = new BusinessRule();
        rule.setProjectId(projectId);
        rule.setRuleCode("BR-001");
        rule.setDescription("Concurrent rule");
        rule.setSource(RuleSource.USER_ADDED);
        rule.setStatus(ReviewStatus.APPROVED);
        rule.setIsModified(false);
        rule = rules.saveAndFlush(rule);

        TestPlan plan = new TestPlan();
        plan.setProjectId(projectId);
        plan.setBusinessRuleId(rule.getId());
        plan.setPlanCode("TP-001");
        plan.setTitle("Concurrent plan");
        plan.setDescription("Concurrent plan");
        plan.setTestType(TestType.HAPPY_PATH);
        plan.setStatus(ReviewStatus.APPROVED);
        plan.setIsModified(false);
        plan = plans.saveAndFlush(plan);

        CountDownLatch bothRequestsReachedAi = new CountDownLatch(2);
        Long planId = plan.getId();
        when(ai.generateTestCases(eq(projectId), anySet())).thenAnswer(invocation -> {
            bothRequestsReachedAi.countDown();
            if (!bothRequestsReachedAi.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Second request did not reach AI phase");
            }
            return new TestCaseResponseDto(List.of(new GeneratedTestCaseDto(
                    planId,
                    "HAPPY_PATH",
                    "Concurrent case",
                    "ready",
                    java.util.Map.of(),
                    "success",
                    "HIGH",
                    "BR-001 -> TP-001")));
        });

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.generate(projectId));
            var second = executor.submit(() -> service.generate(projectId));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(cases.findByTestPlanId(planId)).hasSize(1);
    }
}
