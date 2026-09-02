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
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.CoverageReportRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;

@ExtendWith(MockitoExtension.class)
class ServicePipelineStatusServiceTest {

    @Mock private BusinessRuleRepository rules;
    @Mock private TestPlanRepository plans;
    @Mock private TestCaseRepository cases;
    @Mock private UnitTestRepository unitTests;
    @Mock private CoverageReportRepository reports;
    @Mock private ServiceScopeResolver scopes;
    @InjectMocks private ServicePipelineStatusService statuses;

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

        assertThat(statuses.status(7L, scope)).isEqualTo(ProjectStatus.COVERAGE_ANALYZED);
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
        return testCase;
    }
}
