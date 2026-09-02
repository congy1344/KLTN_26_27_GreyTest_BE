package com.greytest.service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.entity.BusinessRule;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.CoverageReportRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.ServiceScopeResolver.ServiceScope;

/**
 * Tính trạng thái pipeline độc lập cho từng module từ artifact thực tế.
 */
@Service
public class ServicePipelineStatusService {

    private final BusinessRuleRepository rules;
    private final TestPlanRepository plans;
    private final TestCaseRepository cases;
    private final UnitTestRepository unitTests;
    private final CoverageReportRepository reports;
    private final ServiceScopeResolver scopes;

    public ServicePipelineStatusService(
            BusinessRuleRepository rules,
            TestPlanRepository plans,
            TestCaseRepository cases,
            UnitTestRepository unitTests,
            CoverageReportRepository reports,
            ServiceScopeResolver scopes) {
        this.rules = rules;
        this.plans = plans;
        this.cases = cases;
        this.unitTests = unitTests;
        this.reports = reports;
        this.scopes = scopes;
    }

    @Transactional(readOnly = true)
    public ProjectStatus status(Long projectId, ServiceScope scope) {
        List<BusinessRule> scopedRules = rules(projectId, scope);
        if (scopedRules.isEmpty()) return ProjectStatus.ANALYZED;
        if (scopedRules.stream().anyMatch(rule -> rule.getStatus() != ReviewStatus.APPROVED)) {
            return ProjectStatus.BR_PENDING_REVIEW;
        }

        List<TestPlan> scopedPlans = plans(projectId, scopedRules);
        if (scopedPlans.isEmpty()) return ProjectStatus.BR_APPROVED;
        if (scopedPlans.stream().anyMatch(plan -> plan.getStatus() != ReviewStatus.APPROVED)) {
            return ProjectStatus.PLAN_PENDING_REVIEW;
        }

        List<TestCase> scopedCases = cases(scopedPlans);
        if (scopedCases.isEmpty()) return ProjectStatus.PLAN_APPROVED;
        if (scopedCases.stream().anyMatch(testCase -> testCase.getStatus() != ReviewStatus.APPROVED)) {
            return ProjectStatus.CASE_PENDING_REVIEW;
        }

        List<Long> caseIds = scopedCases.stream().map(TestCase::getId).toList();
        if (unitTests.findByTestCaseIdIn(caseIds).isEmpty()) return ProjectStatus.CASE_APPROVED;
        if (hasCoverage(projectId, scope)) {
            return ProjectStatus.COVERAGE_ANALYZED;
        }
        return ProjectStatus.TEST_GENERATED;
    }

    private boolean hasCoverage(Long projectId, ServiceScope scope) {
        if (reports.findTopByProjectIdAndServicePathOrderByIdDesc(
                projectId, scope.servicePath()).isPresent()) {
            return true;
        }
        return !".".equals(scope.servicePath())
                && scopes.listScopes(projectId).size() == 1
                && reports.findTopByProjectIdAndServicePathOrderByIdDesc(projectId, ".").isPresent();
    }

    List<BusinessRule> rules(Long projectId, ServiceScope scope) {
        return rules.findByProjectId(projectId).stream()
                .filter(rule -> scope.methodIds().contains(rule.getMethodId()))
                .toList();
    }

    List<TestPlan> plans(Long projectId, ServiceScope scope) {
        return plans(projectId, rules(projectId, scope));
    }

    List<TestCase> cases(Long projectId, ServiceScope scope) {
        return cases(plans(projectId, scope));
    }

    private List<TestPlan> plans(Long projectId, List<BusinessRule> scopedRules) {
        Set<Long> ruleIds = scopedRules.stream().map(BusinessRule::getId).collect(Collectors.toSet());
        return plans.findByProjectId(projectId).stream()
                .filter(plan -> ruleIds.contains(plan.getBusinessRuleId()))
                .toList();
    }

    private List<TestCase> cases(List<TestPlan> scopedPlans) {
        List<Long> planIds = scopedPlans.stream().map(TestPlan::getId).toList();
        return planIds.isEmpty() ? List.of() : cases.findByTestPlanIdIn(planIds);
    }
}
