package com.greytest.service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.entity.BusinessRule;
import com.greytest.entity.Project;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.TestPlanCoveredRule;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.CoverageReportRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanCoveredRuleRepository;
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
    private final TestPlanCoveredRuleRepository coveredRules;
    private final TestCaseRepository cases;
    private final UnitTestRepository unitTests;
    private final CoverageReportRepository reports;
    private final ProjectRepository projects;
    private final ServiceScopeResolver scopes;

    public ServicePipelineStatusService(
            BusinessRuleRepository rules,
            TestPlanRepository plans,
            TestPlanCoveredRuleRepository coveredRules,
            TestCaseRepository cases,
            UnitTestRepository unitTests,
            CoverageReportRepository reports,
            ProjectRepository projects,
            ServiceScopeResolver scopes) {
        this.rules = rules;
        this.plans = plans;
        this.coveredRules = coveredRules;
        this.cases = cases;
        this.unitTests = unitTests;
        this.reports = reports;
        this.projects = projects;
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
        // Sửa BR chỉ xóa phần liên quan, nên phải báo đúng bước còn thiếu dù phần khác đã xong.
        if (hasRuleWithoutPlan(scopedRules, scopedPlans)) return ProjectStatus.BR_APPROVED;

        List<TestCase> scopedCases = cases(scopedPlans);
        if (scopedCases.isEmpty()) return ProjectStatus.PLAN_APPROVED;
        if (scopedCases.stream().anyMatch(testCase -> testCase.getStatus() != ReviewStatus.APPROVED)) {
            return ProjectStatus.CASE_PENDING_REVIEW;
        }
        if (hasPlanWithoutCase(scopedPlans, scopedCases)) return ProjectStatus.PLAN_APPROVED;

        List<Long> caseIds = scopedCases.stream().map(TestCase::getId).toList();
        List<UnitTest> scopedTests = unitTests.findByTestCaseIdIn(caseIds);
        if (scopedTests.isEmpty() || hasCaseNewerThanTests(scopedCases, scopedTests)) return ProjectStatus.CASE_APPROVED;
        if (hasCoverage(projectId, scope)) {
            // Nếu project đã hoàn thành pipeline (đã xuất report), trả COMPLETED cho scoped status
            Project project = projects.findById(projectId).orElse(null);
            if (project != null && project.getStatus() == ProjectStatus.COMPLETED) {
                return ProjectStatus.COMPLETED;
            }
            return ProjectStatus.COVERAGE_ANALYZED;
        }
        return ProjectStatus.TEST_GENERATED;
    }

    /** Bước xa nhất đã có dữ liệu, để vẫn mở được trang sau khi một BR vừa sửa đang chờ duyệt lại. */
    @Transactional(readOnly = true)
    public ProjectStatus reachedStatus(Long projectId, ServiceScope scope) {
        ProjectStatus current = status(projectId, scope);
        List<TestPlan> scopedPlans = plans(projectId, scope);
        List<TestCase> scopedCases = cases(scopedPlans);
        ProjectStatus reached;
        if (scopedPlans.isEmpty()) reached = current;
        else if (scopedCases.isEmpty()) {
            reached = scopedPlans.stream().allMatch(plan -> plan.getStatus() == ReviewStatus.APPROVED)
                    ? ProjectStatus.PLAN_APPROVED : ProjectStatus.PLAN_PENDING_REVIEW;
        } else if (unitTests.findByTestCaseIdIn(scopedCases.stream().map(TestCase::getId).toList()).isEmpty()) {
            reached = scopedCases.stream().allMatch(testCase -> testCase.getStatus() == ReviewStatus.APPROVED)
                    ? ProjectStatus.CASE_APPROVED : ProjectStatus.CASE_PENDING_REVIEW;
        } else reached = hasCoverage(projectId, scope) ? ProjectStatus.COVERAGE_ANALYZED : ProjectStatus.TEST_GENERATED;
        return reached.compareTo(current) > 0 ? reached : current;
    }

    /** Chỉ còn case mới chưa có Unit Test: AI có thể đã chặn chúng, nên không khóa Coverage và Report. */
    @Transactional(readOnly = true)
    public boolean onlyUnitTestsMissing(Long projectId, ServiceScope scope, ProjectStatus reachedAtLeast) {
        return status(projectId, scope) == ProjectStatus.CASE_APPROVED
                && reachedStatus(projectId, scope).compareTo(reachedAtLeast) >= 0;
    }

    private boolean hasRuleWithoutPlan(List<BusinessRule> scopedRules, List<TestPlan> scopedPlans) {
        Set<Long> covered = coveredRules.findByTestPlanIdIn(scopedPlans.stream().map(TestPlan::getId).toList()).stream()
                .map(TestPlanCoveredRule::getBusinessRuleId)
                .collect(Collectors.toCollection(java.util.HashSet::new));
        scopedPlans.forEach(plan -> covered.add(plan.getBusinessRuleId()));
        return scopedRules.stream().anyMatch(rule -> !covered.contains(rule.getId()));
    }

    private boolean hasPlanWithoutCase(List<TestPlan> scopedPlans, List<TestCase> scopedCases) {
        Set<Long> plansWithCases = scopedCases.stream().map(TestCase::getTestPlanId).collect(Collectors.toSet());
        return scopedPlans.stream().anyMatch(plan -> !plansWithCases.contains(plan.getId()));
    }

    /**
     * Case có trước lần sinh Unit Test gần nhất mà không có test là do AI đã thử và chặn, không được khóa Coverage.
     * Chỉ case mới hơn mọi Unit Test mới là phần chưa từng được sinh.
     */
    private boolean hasCaseNewerThanTests(List<TestCase> scopedCases, List<UnitTest> scopedTests) {
        Set<Long> tested = scopedTests.stream().map(UnitTest::getTestCaseId).collect(Collectors.toSet());
        var latestTest = scopedTests.stream().map(UnitTest::getCreatedAt).filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.naturalOrder()).orElse(null);
        return latestTest != null && scopedCases.stream().anyMatch(testCase -> !tested.contains(testCase.getId())
                && testCase.getCreatedAt() != null && testCase.getCreatedAt().isAfter(latestTest));
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
                .filter(rule -> rule.getMethodId() != null && scope.methodIds().contains(rule.getMethodId()))
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
                .filter(plan -> plan.getBusinessRuleId() != null && ruleIds.contains(plan.getBusinessRuleId()))
                .toList();
    }

    private List<TestCase> cases(List<TestPlan> scopedPlans) {
        List<Long> planIds = scopedPlans.stream().map(TestPlan::getId).toList();
        return planIds.isEmpty() ? List.of() : cases.findByTestPlanIdIn(planIds);
    }
}
