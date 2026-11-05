package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.greytest.entity.BusinessRule;
import com.greytest.entity.CoverageReport;
import com.greytest.entity.Project;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.CoverageReportRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;

@ExtendWith(MockitoExtension.class)
class ServicePipelineStatusServiceTest {

    @Mock private BusinessRuleRepository rules;
    @Mock private TestPlanRepository plans;
    @Mock private com.greytest.repository.TestPlanCoveredRuleRepository coveredRules;
    @Mock private TestCaseRepository cases;
    @Mock private UnitTestRepository unitTests;
    @Mock private CoverageReportRepository reports;
    @Mock private ProjectRepository projects;
    @Mock private ServiceScopeResolver scopes;
    @InjectMocks private ServicePipelineStatusService statuses;

    @Test
    void reachedStatusKeepsLaterStepsOpenWhileAnEditedRuleWaitsForApproval() {
        BusinessRule edited = rule(1L, 11L, ReviewStatus.PENDING_REVIEW);
        BusinessRule untouched = rule(2L, 11L, ReviewStatus.APPROVED);
        TestPlan plan = plan(3L, untouched.getId());
        TestCase kept = testCase(4L);
        UnitTest unit = new UnitTest();
        unit.setTestCaseId(kept.getId());
        when(rules.findByProjectId(7L)).thenReturn(List.of(edited, untouched));
        when(plans.findByProjectId(7L)).thenReturn(List.of(plan));
        when(cases.findByTestPlanIdIn(List.of(plan.getId()))).thenReturn(List.of(kept));
        when(unitTests.findByTestCaseIdIn(List.of(kept.getId()))).thenReturn(List.of(unit));
        var scope = new ServiceScopeResolver.ServiceScope("svc", Set.of(101L), Set.of(11L));

        assertThat(statuses.status(7L, scope)).isEqualTo(ProjectStatus.BR_PENDING_REVIEW);
        assertThat(statuses.reachedStatus(7L, scope)).isEqualTo(ProjectStatus.TEST_GENERATED);
    }

    private final ServiceScopeResolver.ServiceScope gapScope =
            new ServiceScopeResolver.ServiceScope("svc", Set.of(101L), Set.of(11L));

    private UnitTest unitTest(TestCase testCase, java.time.LocalDateTime createdAt) {
        UnitTest unit = new UnitTest();
        unit.setTestCaseId(testCase.getId());
        unit.setCreatedAt(createdAt);
        return unit;
    }

    @Test
    void anApprovedRuleWithoutAPlanMakesTestPlanTheCurrentStep() {
        BusinessRule regenerated = rule(1L, 11L, ReviewStatus.APPROVED);
        BusinessRule kept = rule(2L, 11L, ReviewStatus.APPROVED);
        TestPlan plan = plan(3L, kept.getId());
        when(rules.findByProjectId(7L)).thenReturn(List.of(regenerated, kept));
        when(plans.findByProjectId(7L)).thenReturn(List.of(plan));
        when(coveredRules.findByTestPlanIdIn(List.of(3L))).thenReturn(List.of());
        TestCase testCase = testCase(4L);
        when(cases.findByTestPlanIdIn(List.of(3L))).thenReturn(List.of(testCase));
        when(unitTests.findByTestCaseIdIn(List.of(4L))).thenReturn(List.of(unitTest(testCase, null)));

        assertThat(statuses.status(7L, gapScope)).isEqualTo(ProjectStatus.BR_APPROVED);
        assertThat(statuses.reachedStatus(7L, gapScope)).isEqualTo(ProjectStatus.TEST_GENERATED);
    }

    @Test
    void aRuleCoveredByAnotherAnchorsPlanIsNotMissing() {
        BusinessRule covered = rule(1L, 11L, ReviewStatus.APPROVED);
        BusinessRule anchor = rule(2L, 11L, ReviewStatus.APPROVED);
        TestPlan plan = plan(3L, anchor.getId());
        var link = new com.greytest.entity.TestPlanCoveredRule();
        link.setTestPlanId(3L);
        link.setBusinessRuleId(1L);
        when(rules.findByProjectId(7L)).thenReturn(List.of(covered, anchor));
        when(plans.findByProjectId(7L)).thenReturn(List.of(plan));
        when(coveredRules.findByTestPlanIdIn(List.of(3L))).thenReturn(List.of(link));

        assertThat(statuses.status(7L, gapScope)).isEqualTo(ProjectStatus.PLAN_APPROVED);
    }

    @Test
    void anApprovedPlanWithoutCasesMakesTestCaseTheCurrentStep() {
        BusinessRule first = rule(1L, 11L, ReviewStatus.APPROVED);
        BusinessRule second = rule(2L, 11L, ReviewStatus.APPROVED);
        TestPlan regenerated = plan(3L, first.getId());
        TestPlan kept = plan(5L, second.getId());
        TestCase keptCase = testCase(4L);
        keptCase.setTestPlanId(5L);
        when(rules.findByProjectId(7L)).thenReturn(List.of(first, second));
        when(plans.findByProjectId(7L)).thenReturn(List.of(regenerated, kept));
        when(coveredRules.findByTestPlanIdIn(List.of(3L, 5L))).thenReturn(List.of());
        when(cases.findByTestPlanIdIn(List.of(3L, 5L))).thenReturn(List.of(keptCase));

        assertThat(statuses.status(7L, gapScope)).isEqualTo(ProjectStatus.PLAN_APPROVED);
    }

    @Test
    void onlyCasesNewerThanTheLatestUnitTestMakeUnitTestTheCurrentStep() {
        var earlier = java.time.LocalDateTime.of(2026, 11, 5, 10, 0);
        BusinessRule rule = rule(1L, 11L, ReviewStatus.APPROVED);
        TestPlan plan = plan(3L, rule.getId());
        TestCase withTest = testCase(4L);
        withTest.setCreatedAt(earlier);
        // Case cũ không có test: AI đã thử và chặn, không được khóa bước Coverage mãi.
        TestCase blocked = testCase(5L);
        blocked.setCreatedAt(earlier);
        TestCase regenerated = testCase(6L);
        regenerated.setCreatedAt(earlier.plusHours(2));
        when(rules.findByProjectId(7L)).thenReturn(List.of(rule));
        when(plans.findByProjectId(7L)).thenReturn(List.of(plan));
        when(coveredRules.findByTestPlanIdIn(List.of(3L))).thenReturn(List.of());
        when(unitTests.findByTestCaseIdIn(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(unitTest(withTest, earlier.plusHours(1))));

        when(cases.findByTestPlanIdIn(List.of(3L))).thenReturn(List.of(withTest, blocked));
        assertThat(statuses.status(7L, gapScope)).isEqualTo(ProjectStatus.TEST_GENERATED);

        when(cases.findByTestPlanIdIn(List.of(3L))).thenReturn(List.of(withTest, blocked, regenerated));
        assertThat(statuses.status(7L, gapScope)).isEqualTo(ProjectStatus.CASE_APPROVED);
    }

    @Test
    void newCasesWithoutTestsDoNotLockCoverageButAMissingPlanDoes() {
        var earlier = java.time.LocalDateTime.of(2026, 11, 5, 10, 0);
        BusinessRule rule = rule(1L, 11L, ReviewStatus.APPROVED);
        TestPlan plan = plan(3L, rule.getId());
        TestCase withTest = testCase(4L);
        withTest.setCreatedAt(earlier);
        TestCase regenerated = testCase(6L);
        regenerated.setCreatedAt(earlier.plusHours(2));
        when(rules.findByProjectId(7L)).thenReturn(List.of(rule));
        when(plans.findByProjectId(7L)).thenReturn(List.of(plan));
        when(cases.findByTestPlanIdIn(List.of(3L))).thenReturn(List.of(withTest, regenerated));
        when(unitTests.findByTestCaseIdIn(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(unitTest(withTest, earlier.plusHours(1))));

        assertThat(statuses.onlyUnitTestsMissing(7L, gapScope, ProjectStatus.TEST_GENERATED)).isTrue();
        assertThat(statuses.onlyUnitTestsMissing(7L, gapScope, ProjectStatus.COVERAGE_ANALYZED)).isFalse();

        when(rules.findByProjectId(7L)).thenReturn(List.of(rule, rule(2L, 11L, ReviewStatus.APPROVED)));
        assertThat(statuses.onlyUnitTestsMissing(7L, gapScope, ProjectStatus.TEST_GENERATED)).isFalse();
    }

    @Test
    void reachedStatusOpensTheNextStepOnceItsInputIsApproved() {
        BusinessRule missing = rule(1L, 11L, ReviewStatus.APPROVED);
        BusinessRule kept = rule(2L, 11L, ReviewStatus.APPROVED);
        TestPlan plan = plan(3L, kept.getId());
        when(rules.findByProjectId(7L)).thenReturn(List.of(missing, kept));
        when(plans.findByProjectId(7L)).thenReturn(List.of(plan));
        when(coveredRules.findByTestPlanIdIn(List.of(3L))).thenReturn(List.of());

        when(cases.findByTestPlanIdIn(List.of(3L))).thenReturn(List.of());
        assertThat(statuses.reachedStatus(7L, gapScope)).isEqualTo(ProjectStatus.PLAN_APPROVED);

        when(cases.findByTestPlanIdIn(List.of(3L))).thenReturn(List.of(testCase(4L)));
        when(unitTests.findByTestCaseIdIn(List.of(4L))).thenReturn(List.of());
        assertThat(statuses.reachedStatus(7L, gapScope)).isEqualTo(ProjectStatus.CASE_APPROVED);
    }

    @Test
    void reachedStatusStaysAtTheCurrentStepWhenNothingLaterExists() {
        BusinessRule edited = rule(1L, 11L, ReviewStatus.PENDING_REVIEW);
        when(rules.findByProjectId(7L)).thenReturn(List.of(edited));
        when(plans.findByProjectId(7L)).thenReturn(List.of());
        var scope = new ServiceScopeResolver.ServiceScope("svc", Set.of(101L), Set.of(11L));

        assertThat(statuses.reachedStatus(7L, scope)).isEqualTo(ProjectStatus.BR_PENDING_REVIEW);
    }

    @Test
    void calculatesPipelineStatusIndependentlyForEachService() {
        BusinessRule accountRule = rule(1L, 11L, ReviewStatus.PENDING_REVIEW);
        BusinessRule authRule = rule(2L, 22L, ReviewStatus.APPROVED);
        TestPlan authPlan = plan(3L, authRule.getId());
        TestCase authCase = testCase(4L);
        UnitTest authUnit = new UnitTest();
        authUnit.setId(5L);
        authUnit.setTestCaseId(authCase.getId());

        when(rules.findByProjectId(7L)).thenReturn(List.of(accountRule, authRule));
        when(plans.findByProjectId(7L)).thenReturn(List.of(authPlan));
        when(cases.findByTestPlanIdIn(List.of(authPlan.getId()))).thenReturn(List.of(authCase));
        when(unitTests.findByTestCaseIdIn(List.of(authCase.getId()))).thenReturn(List.of(authUnit));
        when(reports.findTopByProjectIdAndServicePathOrderByIdDesc(7L, "auth-service"))
                .thenReturn(Optional.of(new CoverageReport()));

        var accountScope = new ServiceScopeResolver.ServiceScope("account-service", Set.of(101L), Set.of(11L));
        var authScope = new ServiceScopeResolver.ServiceScope("auth-service", Set.of(202L), Set.of(22L));
        // Project global status chưa COMPLETED → scoped status trả COVERAGE_ANALYZED
        Project project = new Project(); project.setStatus(ProjectStatus.COVERAGE_ANALYZED);
        when(projects.findById(7L)).thenReturn(Optional.of(project));

        assertThat(statuses.status(7L, accountScope)).isEqualTo(ProjectStatus.BR_PENDING_REVIEW);
        assertThat(statuses.status(7L, authScope)).isEqualTo(ProjectStatus.COVERAGE_ANALYZED);
    }
    @Test
    void nestedSingleModuleRecognizesLegacyRootCoverage() {
        BusinessRule rule = rule(2L, 22L, ReviewStatus.APPROVED);
        TestPlan plan = plan(3L, rule.getId());
        TestCase testCase = testCase(4L);
        UnitTest unitTest = new UnitTest();
        unitTest.setId(5L);
        unitTest.setTestCaseId(testCase.getId());
        var scope = new ServiceScopeResolver.ServiceScope(
                "orders", Set.of(202L), Set.of(22L));

        when(rules.findByProjectId(7L)).thenReturn(List.of(rule));
        when(plans.findByProjectId(7L)).thenReturn(List.of(plan));
        when(cases.findByTestPlanIdIn(List.of(plan.getId()))).thenReturn(List.of(testCase));
        when(unitTests.findByTestCaseIdIn(List.of(testCase.getId()))).thenReturn(List.of(unitTest));
        when(reports.findTopByProjectIdAndServicePathOrderByIdDesc(7L, "orders"))
                .thenReturn(Optional.empty());
        when(scopes.listScopes(7L)).thenReturn(List.of(scope));
        when(reports.findTopByProjectIdAndServicePathOrderByIdDesc(7L, "."))
                .thenReturn(Optional.of(new CoverageReport()));
        // Project global status chưa COMPLETED
        Project project = new Project(); project.setStatus(ProjectStatus.COVERAGE_ANALYZED);
        when(projects.findById(7L)).thenReturn(Optional.of(project));

        assertThat(statuses.status(7L, scope)).isEqualTo(ProjectStatus.COVERAGE_ANALYZED);
    }

    @Test
    void returnsCompletedWhenProjectIsCompleted() {
        BusinessRule rule = rule(2L, 22L, ReviewStatus.APPROVED);
        TestPlan plan = plan(3L, rule.getId());
        TestCase testCase = testCase(4L);
        UnitTest unitTest = new UnitTest();
        unitTest.setId(5L);
        unitTest.setTestCaseId(testCase.getId());
        var scope = new ServiceScopeResolver.ServiceScope(
                "auth-service", Set.of(202L), Set.of(22L));

        when(rules.findByProjectId(7L)).thenReturn(List.of(rule));
        when(plans.findByProjectId(7L)).thenReturn(List.of(plan));
        when(cases.findByTestPlanIdIn(List.of(plan.getId()))).thenReturn(List.of(testCase));
        when(unitTests.findByTestCaseIdIn(List.of(testCase.getId()))).thenReturn(List.of(unitTest));
        when(reports.findTopByProjectIdAndServicePathOrderByIdDesc(7L, "auth-service"))
                .thenReturn(Optional.of(new CoverageReport()));

        // Project global status = COMPLETED → scoped status cũng trả COMPLETED
        Project project = new Project(); project.setStatus(ProjectStatus.COMPLETED);
        when(projects.findById(7L)).thenReturn(Optional.of(project));

        assertThat(statuses.status(7L, scope)).isEqualTo(ProjectStatus.COMPLETED);
    }

    private BusinessRule rule(Long id, Long methodId, ReviewStatus status) {
        BusinessRule rule = new BusinessRule();
        rule.setId(id);
        rule.setProjectId(7L);
        rule.setMethodId(methodId);
        rule.setStatus(status);
        return rule;
    }

    private TestPlan plan(Long id, Long businessRuleId) {
        TestPlan plan = new TestPlan();
        plan.setId(id);
        plan.setProjectId(7L);
        plan.setBusinessRuleId(businessRuleId);
        plan.setStatus(ReviewStatus.APPROVED);
        return plan;
    }

    private TestCase testCase(Long id) {
        TestCase testCase = new TestCase();
        testCase.setId(id);
        testCase.setStatus(ReviewStatus.APPROVED);
        testCase.setTestPlanId(3L);
        return testCase;
    }
}
