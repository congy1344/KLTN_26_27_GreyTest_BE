package com.greytest.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.CoverageGapDto;
import com.greytest.dto.CoverageReportDto;
import com.greytest.dto.ExportReportDto;
import com.greytest.dto.TraceabilityMatrixDto;
import com.greytest.dto.TraceabilityRowDto;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.Project;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;

/** Xuất báo cáo tổng hợp của project dưới dạng JSON hoặc Markdown. */
@Service
public class ExportService {

    private final ProjectRepository projects;
    private final TraceabilityService traceability;
    private final CoverageService coverage;
    private final BusinessRuleRepository rules;
    private final TestPlanRepository plans;
    private final TestCaseRepository cases;
    private final UnitTestRepository unitTests;
    private final ServiceScopeResolver scopeResolver;
    private final ServicePipelineStatusService scopedStatuses;
    private final ObjectMapper objectMapper;
    // ponytail: inject tùy chọn để không đổi constructor; null trong unit test thì report bỏ phần chạy test
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.greytest.service.coverage.SandboxTestRunService sandbox;

    public ExportService(ProjectRepository projects, TraceabilityService traceability, CoverageService coverage,
            BusinessRuleRepository rules, TestPlanRepository plans, TestCaseRepository cases,
            UnitTestRepository unitTests, ServiceScopeResolver scopeResolver,
            ServicePipelineStatusService scopedStatuses, ObjectMapper objectMapper) {
        this.projects = projects;
        this.traceability = traceability;
        this.coverage = coverage;
        this.rules = rules;
        this.plans = plans;
        this.cases = cases;
        this.unitTests = unitTests;
        this.scopeResolver = scopeResolver;
        this.scopedStatuses = scopedStatuses;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String export(Long projectId, String format) {
        return export(projectId, format, null);
    }

    @Transactional
    public String export(Long projectId, String format, String servicePath) {
        if (!"json".equals(format) && !"markdown".equals(format)) {
            throw new IllegalArgumentException("format phải là json hoặc markdown");
        }
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        if (servicePath != null) {
            var scope = scopeResolver.resolve(projectId, servicePath);
            ProjectStatus status = scopedStatuses.status(projectId, scope);
            if (status != ProjectStatus.COVERAGE_ANALYZED && status != ProjectStatus.COMPLETED
                    && !scopedStatuses.onlyUnitTestsMissing(projectId, scope, ProjectStatus.COVERAGE_ANALYZED)) {
                throw new InvalidProjectStatusException("Chỉ xuất báo cáo sau khi đã duyệt BR, sinh đủ Test Plan, Test Case và phân tích coverage.");
            }
        } else if (project.getStatus() != ProjectStatus.COVERAGE_ANALYZED && project.getStatus() != ProjectStatus.COMPLETED) {
            throw new InvalidProjectStatusException("Chỉ xuất báo cáo sau khi đã phân tích coverage.");
        }
        // Xuất báo cáo là bước cuối của pipeline — cập nhật cả khi export theo service
        if (project.getStatus() != ProjectStatus.COMPLETED) {
            project.setStatus(ProjectStatus.COMPLETED);
            projects.save(project);
        }
        ExportReportDto report = gather(project, servicePath);
        return "json".equals(format) ? toJson(report) : toMarkdown(report);
    }

    private ExportReportDto gather(Project project, String servicePath) {
        TraceabilityMatrixDto matrix = servicePath == null
                ? traceability.getMatrix(project.getId())
                : traceability.getMatrix(project.getId(), servicePath);
        CoverageReportDto cov = servicePath == null
                ? coverage.latest(project.getId()).orElse(null)
                : coverage.latest(project.getId(), servicePath).orElse(null);
        List<BusinessRule> allRules = rules.findByProjectId(project.getId());
        Set<Long> scopedRuleIds = servicePath == null ? null : scopeRuleIds(project.getId(), servicePath, allRules);
        List<BusinessRule> projectRules = allRules.stream()
                .filter(rule -> scopedRuleIds == null || scopedRuleIds.contains(rule.getId()))
                .toList();
        Set<Long> scopedPlanIds = servicePath == null ? null : matrix.rows().stream()
                .map(TraceabilityRowDto::planId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<TestPlan> projectPlans = plans.findByProjectId(project.getId()).stream()
                .filter(plan -> scopedRuleIds == null
                        || scopedRuleIds.contains(plan.getBusinessRuleId())
                        || scopedPlanIds.contains(plan.getId()))
                .toList();
        List<Long> planIds = projectPlans.stream().map(TestPlan::getId).toList();
        List<TestCase> projectCases = planIds.isEmpty() ? List.of() : cases.findByTestPlanIdIn(planIds);
        List<Long> caseIds = projectCases.stream().map(TestCase::getId).toList();
        List<UnitTest> projectUnitTests = caseIds.isEmpty() ? List.of() : unitTests.findByTestCaseIdIn(caseIds);
        Map<Long, String> planCodes = projectPlans.stream()
                .collect(Collectors.toMap(TestPlan::getId, TestPlan::getPlanCode));
        Map<Long, String> caseCodes = projectCases.stream()
                .collect(Collectors.toMap(TestCase::getId, TestCase::getCaseCode));
        return new ExportReportDto(
                project.getName(),
                project.getStatus().name(),
                LocalDateTime.now(),
                cov == null ? null : cov.requirementCoverage(),
                cov == null ? null : cov.lineCoverage(),
                cov == null ? null : cov.branchCoverage(),
                projectRules.size(),
                projectPlans.size(),
                projectCases.size(),
                projectUnitTests.size(),
                projectRules.stream().map(this::ruleItem).toList(),
                projectPlans.stream().map(this::planItem).toList(),
                projectCases.stream().map(testCase -> caseItem(testCase, planCodes)).toList(),
                projectUnitTests.stream().map(unitTest -> unitTestItem(unitTest, caseCodes)).toList(),
                matrix.rows(),
                cov == null ? List.of() : cov.gaps().stream().filter(CoverageGapDto::refinable).toList(),
                matrix.uncoveredRules().stream().map(TraceabilityRowDto::ruleCode).toList(),
                testRun(project.getId(), servicePath, cov));
    }

    /** Lượt chạy test gần nhất, chỉ khi chạy đúng phạm vi service đang xuất và có test được chạy. */
    private ExportReportDto.TestRunSummary testRun(Long projectId, String servicePath, CoverageReportDto cov) {
        var state = sandbox == null ? null : sandbox.state(projectId);
        var status = state == null ? com.greytest.service.coverage.SandboxTestRunService.Status.IDLE : state.status();
        if (status == com.greytest.service.coverage.SandboxTestRunService.Status.RUNNING) return null;
        if (status != com.greytest.service.coverage.SandboxTestRunService.Status.IDLE
                && Objects.equals(sandbox.lastRunServicePath(projectId), Objects.toString(servicePath, ""))) {
            return state.tests() == 0 ? null : new ExportReportDto.TestRunSummary(status.name(), state.tests(),
                    state.tests() - state.failed(), state.failed(), state.message());
        }
        // Backend vừa khởi động lại (trạng thái trong bộ nhớ đã mất): dùng số test đã lưu cùng báo cáo coverage của phạm vi này.
        if (status != com.greytest.service.coverage.SandboxTestRunService.Status.IDLE
                || cov == null || cov.testsRun() == null || cov.testsRun() == 0) return null;
        int failed = cov.testsFailed() == null ? 0 : cov.testsFailed();
        return new ExportReportDto.TestRunSummary(failed > 0 ? "TESTS_FAILED" : "SUCCEEDED", cov.testsRun(),
                cov.testsRun() - failed, failed, null);
    }

    private Set<Long> scopeRuleIds(Long projectId, String servicePath, List<BusinessRule> projectRules) {
        Set<Long> methodIds = scopeResolver.resolve(projectId, servicePath).methodIds();
        return projectRules.stream()
                .filter(rule -> rule.getMethodId() != null && methodIds.contains(rule.getMethodId()))
                .map(BusinessRule::getId)
                .collect(Collectors.toSet());
    }

    private String toJson(ExportReportDto report) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được báo cáo JSON", e);
        }
    }

    private String toMarkdown(ExportReportDto r) {
        StringBuilder md = new StringBuilder();
        md.append("# GreyTest Report — ").append(r.projectName()).append("\n\n");
        md.append("## Coverage Overview\n\n");
        md.append("| Metric | Value |\n| --- | --- |\n");
        md.append("| Requirement Coverage | ").append(pct(r.requirementCoverage())).append(" |\n");
        md.append("| Line Coverage | ").append(pct(r.lineCoverage())).append(" |\n");
        md.append("| Branch Coverage | ").append(pct(r.branchCoverage())).append(" |\n\n");
        md.append("## Tóm tắt\n\n");
        md.append("| Artifact | Total |\n| --- | ---: |\n");
        md.append("| Business Rules | ").append(r.totalBusinessRules()).append(" |\n");
        md.append("| Test Plans | ").append(r.totalTestPlans()).append(" |\n");
        md.append("| Test Cases | ").append(r.totalTestCases()).append(" |\n");
        md.append("| Unit Tests | ").append(r.totalUnitTests()).append(" |\n\n");
        if (r.testRun() != null) {
            md.append("## Kết quả chạy test\n\n");
            md.append("| Trạng thái | Tổng | Đạt | Không đạt |\n| --- | ---: | ---: | ---: |\n");
            md.append("| ").append(cell(r.testRun().status())).append(" | ").append(r.testRun().tests())
                    .append(" | ").append(r.testRun().passed()).append(" | ").append(r.testRun().failed()).append(" |\n\n");
            if (r.testRun().message() != null && !r.testRun().message().isBlank()) md.append(r.testRun().message()).append("\n\n");
        }
        md.append("## Business Rules\n\n");
        md.append("| Code | Status | Description |\n| --- | --- | --- |\n");
        r.businessRules().forEach(rule -> md.append("| ").append(cell(rule.ruleCode()))
                .append(" | ").append(cell(rule.status()))
                .append(" | ").append(cell(rule.description())).append(" |\n"));
        md.append("\n## Test Plans\n\n");
        r.testPlans().forEach(plan -> {
            md.append("### ").append(cell(plan.planCode())).append(" — ").append(cell(plan.title())).append("\n\n");
            md.append("| Field | Value |\n| --- | --- |\n");
            field(md, "Type", plan.testType());
            field(md, "Status", plan.status());
            field(md, "Description", plan.description());
            md.append("\n");
        });
        md.append("\n## Test Cases\n\n");
        r.testCases().forEach(testCase -> {
            md.append("### ").append(cell(testCase.caseCode())).append("\n\n");
            md.append("| Field | Value |\n| --- | --- |\n");
            field(md, "Plan", testCase.planCode());
            field(md, "Type", testCase.testType());
            field(md, "Priority", testCase.priority());
            field(md, "Status", testCase.status());
            field(md, "Description", testCase.description());
            field(md, "Preconditions", testCase.preconditions());
            field(md, "Test data", testCase.testData());
            field(md, "Expected result", testCase.expectedResult());
            field(md, "Trace source", testCase.traceSource());
            md.append("\n");
        });
        md.append("\n## Unit Tests\n\n");
        r.unitTests().forEach(unitTest -> {
            md.append("### ").append(cell(unitTest.testClassName())).append("#")
                    .append(cell(unitTest.testMethodName())).append("\n\n");
            md.append("| Field | Value |\n| --- | --- |\n");
            field(md, "Test case", unitTest.caseCode());
            field(md, "Package", unitTest.packageName());
            field(md, "Generation", unitTest.generationType());
            field(md, "File", unitTest.filePath());
            md.append("\n");
        });
        md.append("\n## Traceability Matrix\n\n");
        md.append("| Business Rule | Test Plan | Test Case | Unit Test |\n");
        md.append("| --- | --- | --- | --- |\n");
        for (TraceabilityRowDto row : r.traceability().stream().filter(row -> row.unitTestId() != null).toList()) {
            md.append("| ").append(cell(row.ruleCode()))
                    .append(" | ").append(cell(row.planCode()))
                    .append(" | ").append(cell(row.caseCode()))
                    .append(" | ").append(cell(row.unitTestName())).append(" |\n");
        }
        md.append("\n## Coverage Gaps\n\n");
        if (r.coverageGaps().isEmpty()) {
            md.append("Không phát hiện coverage gap.\n");
        } else {
            md.append("| Location | Line | Branch | Risk | Suggestion |\n");
            md.append("| --- | ---: | ---: | --- | --- |\n");
            for (CoverageGapDto gap : r.coverageGaps()) {
                md.append("| ").append(cell(gap.className() + "." + gap.methodName()))
                        .append(" | ").append(cell(pct(gap.lineCoverage())))
                        .append(" | ").append(cell(pct(gap.branchCoverage())))
                        .append(" | ").append(cell(gap.risk()))
                        .append(" | ").append(cell(gap.suggestion())).append(" |\n");
            }
        }
        return md.toString();
    }

    private void field(StringBuilder md, String label, Object value) {
        md.append("| ").append(label).append(" | ").append(cell(value)).append(" |\n");
    }

    private String pct(java.math.BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString() + "%";
    }

    private ExportReportDto.BusinessRuleItem ruleItem(BusinessRule rule) {
        return new ExportReportDto.BusinessRuleItem(
                rule.getRuleCode(), com.greytest.util.TextSanitizer.cleanAiText(rule.getDescription()), name(rule.getStatus()));
    }

    private ExportReportDto.TestPlanItem planItem(TestPlan plan) {
        return new ExportReportDto.TestPlanItem(
                plan.getPlanCode(), com.greytest.util.TextSanitizer.cleanAiText(plan.getTitle()),
                com.greytest.util.TextSanitizer.cleanAiText(plan.getDescription()),
                name(plan.getTestType()), name(plan.getStatus()));
    }

    private ExportReportDto.TestCaseItem caseItem(TestCase testCase, Map<Long, String> planCodes) {
        return new ExportReportDto.TestCaseItem(
                testCase.getCaseCode(), planCodes.get(testCase.getTestPlanId()), name(testCase.getTestType()),
                com.greytest.util.TextSanitizer.cleanAiText(testCase.getDescription()),
                com.greytest.util.TextSanitizer.cleanAiText(testCase.getPreconditions()), testCase.getTestData(),
                com.greytest.util.TextSanitizer.cleanAiText(testCase.getExpectedResult()),
                name(testCase.getPriority()), testCase.getTraceSource(), name(testCase.getStatus()));
    }

    private ExportReportDto.UnitTestItem unitTestItem(UnitTest unitTest, Map<Long, String> caseCodes) {
        return new ExportReportDto.UnitTestItem(
                caseCodes.get(unitTest.getTestCaseId()), unitTest.getTestClassName(), unitTest.getTestMethodName(),
                unitTest.getPackageName(), unitTest.getGenerationType(), unitTest.getFilePath());
    }

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private String cell(Object value) {
        if (value == null || value.toString().isBlank()) return "—";
        String cleaned = com.greytest.util.TextSanitizer.cleanAiText(value.toString());
        return cleaned.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
