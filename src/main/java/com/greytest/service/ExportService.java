package com.greytest.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private final ObjectMapper objectMapper;

    public ExportService(ProjectRepository projects, TraceabilityService traceability, CoverageService coverage,
            BusinessRuleRepository rules, TestPlanRepository plans, TestCaseRepository cases,
            UnitTestRepository unitTests, ObjectMapper objectMapper) {
        this.projects = projects;
        this.traceability = traceability;
        this.coverage = coverage;
        this.rules = rules;
        this.plans = plans;
        this.cases = cases;
        this.unitTests = unitTests;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String export(Long projectId, String format) {
        if (!"json".equals(format) && !"markdown".equals(format)) {
            throw new IllegalArgumentException("format phải là json hoặc markdown");
        }
        Project project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        if (project.getStatus() != ProjectStatus.COVERAGE_ANALYZED && project.getStatus() != ProjectStatus.COMPLETED) {
            throw new InvalidProjectStatusException("Chỉ xuất báo cáo sau khi đã phân tích coverage.");
        }
        // Xuất báo cáo là bước cuối của pipeline
        project.setStatus(ProjectStatus.COMPLETED);
        projects.save(project);
        ExportReportDto report = gather(project);
        return "json".equals(format) ? toJson(report) : toMarkdown(report);
    }

    private ExportReportDto gather(Project project) {
        TraceabilityMatrixDto matrix = traceability.getMatrix(project.getId());
        CoverageReportDto cov = coverage.latest(project.getId()).orElse(null);
        List<BusinessRule> projectRules = rules.findByProjectId(project.getId());
        List<TestPlan> projectPlans = plans.findByProjectId(project.getId());
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
                cov == null ? List.of() : cov.gaps(),
                matrix.uncoveredRules().stream().map(TraceabilityRowDto::ruleCode).toList());
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
        md.append("## Tóm tắt\n\n");
        md.append("- Requirement Coverage: ").append(pct(r.requirementCoverage())).append('\n');
        md.append("- Line Coverage: ").append(pct(r.lineCoverage())).append('\n');
        md.append("- Branch Coverage: ").append(pct(r.branchCoverage())).append('\n');
        md.append("- Business Rules: ").append(r.totalBusinessRules()).append('\n');
        md.append("- Test Plans: ").append(r.totalTestPlans()).append('\n');
        md.append("- Test Cases: ").append(r.totalTestCases()).append('\n');
        md.append("- Unit Tests: ").append(r.totalUnitTests()).append("\n\n");
        md.append("## Business Rules\n\n");
        md.append("| Code | Status | Description |\n| --- | --- | --- |\n");
        r.businessRules().forEach(rule -> md.append("| ").append(cell(rule.ruleCode()))
                .append(" | ").append(cell(rule.status()))
                .append(" | ").append(cell(rule.description())).append(" |\n"));
        md.append("\n## Test Plans\n\n");
        md.append("| Code | Type | Status | Title | Description |\n| --- | --- | --- | --- | --- |\n");
        r.testPlans().forEach(plan -> md.append("| ").append(cell(plan.planCode()))
                .append(" | ").append(cell(plan.testType()))
                .append(" | ").append(cell(plan.status()))
                .append(" | ").append(cell(plan.title()))
                .append(" | ").append(cell(plan.description())).append(" |\n"));
        md.append("\n## Test Cases\n\n");
        md.append("| Code | Plan | Type | Priority | Status | Description | Preconditions | Test Data | Expected Result | Trace Source |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        r.testCases().forEach(testCase -> md.append("| ").append(cell(testCase.caseCode()))
                .append(" | ").append(cell(testCase.planCode()))
                .append(" | ").append(cell(testCase.testType()))
                .append(" | ").append(cell(testCase.priority()))
                .append(" | ").append(cell(testCase.status()))
                .append(" | ").append(cell(testCase.description()))
                .append(" | ").append(cell(testCase.preconditions()))
                .append(" | ").append(cell(testCase.testData()))
                .append(" | ").append(cell(testCase.expectedResult()))
                .append(" | ").append(cell(testCase.traceSource())).append(" |\n"));
        md.append("\n## Unit Tests\n\n");
        md.append("| Test Case | Class | Method | Package | Generation | File |\n");
        md.append("| --- | --- | --- | --- | --- | --- |\n");
        r.unitTests().forEach(unitTest -> md.append("| ").append(cell(unitTest.caseCode()))
                .append(" | ").append(cell(unitTest.testClassName()))
                .append(" | ").append(cell(unitTest.testMethodName()))
                .append(" | ").append(cell(unitTest.packageName()))
                .append(" | ").append(cell(unitTest.generationType()))
                .append(" | ").append(cell(unitTest.filePath())).append(" |\n"));
        md.append("\n## Traceability Matrix\n\n");
        md.append("| Business Rule | Test Plan | Test Case | Unit Test |\n");
        md.append("| --- | --- | --- | --- |\n");
        for (TraceabilityRowDto row : r.traceability()) {
            md.append("| ").append(cell(row.ruleCode()))
                    .append(" | ").append(cell(row.planCode()))
                    .append(" | ").append(cell(row.caseCode()))
                    .append(" | ").append(cell(row.unitTestName())).append(" |\n");
        }
        if (!r.uncoveredRuleCodes().isEmpty()) {
            md.append("\n## Business Rule chưa được cover\n\n");
            r.uncoveredRuleCodes().forEach(code -> md.append("- ").append(code).append('\n'));
        }
        md.append("\n## Coverage Gaps\n\n");
        if (r.coverageGaps().isEmpty()) {
            md.append("Không phát hiện coverage gap.\n");
        } else {
            for (CoverageGapDto gap : r.coverageGaps()) {
                md.append("- **").append(gap.className()).append('.').append(gap.methodName())
                        .append("** (line ").append(pct(gap.lineCoverage()))
                        .append(", branch ").append(pct(gap.branchCoverage()))
                        .append(", risk ").append(gap.risk()).append("): ")
                        .append(gap.suggestion()).append('\n');
            }
        }
        return md.toString();
    }

    private String pct(java.math.BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString() + "%";
    }

    private ExportReportDto.BusinessRuleItem ruleItem(BusinessRule rule) {
        return new ExportReportDto.BusinessRuleItem(
                rule.getRuleCode(), rule.getDescription(), name(rule.getStatus()));
    }

    private ExportReportDto.TestPlanItem planItem(TestPlan plan) {
        return new ExportReportDto.TestPlanItem(
                plan.getPlanCode(), plan.getTitle(), plan.getDescription(),
                name(plan.getTestType()), name(plan.getStatus()));
    }

    private ExportReportDto.TestCaseItem caseItem(TestCase testCase, Map<Long, String> planCodes) {
        return new ExportReportDto.TestCaseItem(
                testCase.getCaseCode(), planCodes.get(testCase.getTestPlanId()), name(testCase.getTestType()),
                testCase.getDescription(),
                testCase.getPreconditions(), testCase.getTestData(), testCase.getExpectedResult(),
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
        return value.toString().replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
