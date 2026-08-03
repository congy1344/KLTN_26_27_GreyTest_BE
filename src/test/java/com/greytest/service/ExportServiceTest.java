package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.TraceabilityMatrixDto;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.Project;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.Priority;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.TestType;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;

class ExportServiceTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final TraceabilityService traceability = mock(TraceabilityService.class);
    private final CoverageService coverage = mock(CoverageService.class);
    private final BusinessRuleRepository rules = mock(BusinessRuleRepository.class);
    private final TestPlanRepository plans = mock(TestPlanRepository.class);
    private final TestCaseRepository cases = mock(TestCaseRepository.class);
    private final UnitTestRepository unitTests = mock(UnitTestRepository.class);
    private ExportService service;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new ExportService(
                projects, traceability, coverage, rules, plans, cases, unitTests,
                new ObjectMapper().findAndRegisterModules());
        project = project();
        BusinessRule rule = rule();
        TestPlan plan = plan();
        TestCase testCase = testCase();
        UnitTest unitTest = unitTest();
        TestPlan secondPlan = plan();
        secondPlan.setId(21L);
        secondPlan.setPlanCode("TP-002");
        TestCase secondCase = testCase();
        secondCase.setId(31L);
        secondCase.setTestPlanId(21L);
        secondCase.setCaseCode("TC-002");
        UnitTest secondUnit = unitTest();
        secondUnit.setTestCaseId(31L);
        secondUnit.setTestMethodName("getById_missing");

        when(projects.findById(5L)).thenReturn(Optional.of(project));
        when(traceability.getMatrix(5L)).thenReturn(new TraceabilityMatrixDto(5L, List.of(), List.of()));
        when(coverage.latest(5L)).thenReturn(Optional.empty());
        when(rules.findByProjectId(5L)).thenReturn(List.of(rule));
        when(plans.findByProjectId(5L)).thenReturn(List.of(plan, secondPlan));
        when(cases.findByTestPlanIdIn(List.of(20L, 21L))).thenReturn(List.of(testCase, secondCase));
        when(unitTests.findByTestCaseIdIn(List.of(30L, 31L))).thenReturn(List.of(unitTest, secondUnit));
    }

    @Test
    void exportChuaNoiDungDaSinhTrongMarkdownVaJson() {
        String json = service.export(5L, "json");
        String markdown = service.export(5L, "markdown");

        assertThat(markdown).contains(
                "## Business Rules", "BR-001", "User phải tồn tại",
                "## Test Plans", "TP-001", "Kiểm tra lấy user",
                "## Test Cases", "TC-001", "Trả về đúng user",
                "HAPPY_PATH", "APPROVED", "BR-001 -> TP-001",
                "## Unit Tests", "UserServiceTest", "getById_existing");
        assertThat(json).contains(
                "\"businessRules\"", "\"testPlans\"", "\"testCases\"", "\"unitTests\"",
                "\"status\" : \"COMPLETED\"",
                "\"testType\" : \"HAPPY_PATH\"",
                "\"traceSource\" : \"BR-001 -> TP-001\"",
                "\"expectedResult\" : \"Trả về đúng user\"",
                "\"filePath\" : \"src/test/java/UserServiceTest.java\"");
        assertThat(json).doesNotContain("sourceCode");
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.COMPLETED);
        verify(projects, times(2)).save(project);
        verify(cases, times(2)).findByTestPlanIdIn(List.of(20L, 21L));
        verify(unitTests, times(2)).findByTestCaseIdIn(List.of(30L, 31L));
        verify(cases, never()).findByTestPlanId(20L);
        verify(unitTests, never()).findByTestCaseId(30L);
    }

    private Project project() {
        Project value = new Project();
        value.setId(5L);
        value.setName("demo");
        value.setStatus(ProjectStatus.COVERAGE_ANALYZED);
        return value;
    }

    private BusinessRule rule() {
        BusinessRule value = new BusinessRule();
        value.setRuleCode("BR-001");
        value.setDescription("User phải tồn tại");
        value.setStatus(ReviewStatus.APPROVED);
        return value;
    }

    private TestPlan plan() {
        TestPlan value = new TestPlan();
        value.setId(20L);
        value.setPlanCode("TP-001");
        value.setTitle("Kiểm tra lấy user");
        value.setDescription("Kiểm tra luồng lấy user theo ID");
        value.setTestType(TestType.HAPPY_PATH);
        value.setStatus(ReviewStatus.APPROVED);
        return value;
    }

    private TestCase testCase() {
        TestCase value = new TestCase();
        value.setId(30L);
        value.setTestPlanId(20L);
        value.setCaseCode("TC-001");
        value.setDescription("Lấy user đã tồn tại");
        value.setTestType(TestType.HAPPY_PATH);
        value.setPreconditions("Repository có user");
        value.setTestData(Map.of("id", 1));
        value.setExpectedResult("Trả về đúng user");
        value.setPriority(Priority.HIGH);
        value.setTraceSource("BR-001 -> TP-001");
        value.setStatus(ReviewStatus.APPROVED);
        return value;
    }

    private UnitTest unitTest() {
        UnitTest value = new UnitTest();
        value.setTestCaseId(30L);
        value.setTestClassName("UserServiceTest");
        value.setTestMethodName("getById_existing");
        value.setPackageName("com.example");
        value.setGenerationType("NEW_TEST");
        value.setFilePath("src/test/java/UserServiceTest.java");
        value.setSourceCode("không được đưa vào report");
        return value;
    }
}
