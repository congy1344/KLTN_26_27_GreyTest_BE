package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.entity.Project;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.LlmResponseException;

@ExtendWith(MockitoExtension.class)
class UnitTestServiceTest {

    @Mock private UnitTestRepository units;
    @Mock private TestCaseRepository cases;
    @Mock private TestPlanRepository plans;
    @Mock private ProjectRepository projects;
    @Mock private AIAgentService ai;
    @Mock private UnitTestFileService files;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private GenerationProgressService generationProgress;
    @InjectMocks private UnitTestService service;

    @Test
    void refinementGeneratesOnlyNewCasesAndPreservesOldUnitTests() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        TestCase testCase = new TestCase();
        testCase.setId(31L);
        testCase.setTestPlanId(20L);
        testCase.setCaseCode("TC-031");
        testCase.setTraceSource("BR-007 [IF-1-FALSE] -> TP-020");
        testCase.setStatus(ReviewStatus.APPROVED);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(cases.findById(31L)).thenReturn(Optional.of(testCase));
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateUnitTests(1L, java.util.Set.of(31L))).thenReturn(new UnitTestResponseDto(List.of(
                new GeneratedUnitTestDto(31L, "OrderServiceTest", "createOrder_missingBranch",
                        "demo", "NEW_TEST", "package demo; class OrderServiceTest { @org.junit.jupiter.api.Test void createOrder_missingBranch(){} }"))));
        when(units.saveAll(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generateSupplemental(1L, List.of(31L));

        assertThat(result).singleElement()
                .satisfies(test -> {
                    assertThat(test.generationType()).isEqualTo("SUPPLEMENT_EXISTING_TEST");
                    assertThat(test.sourceCode()).startsWith(
                            "// GreyTest trace: TC-031 | BR-007 [IF-1-FALSE] -> TP-020");
                });
        verify(units, never()).deleteAll();
        verify(projects).save(project);
        verify(generationProgress).completeAfterCommit(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.UNIT_TEST),
                org.mockito.ArgumentMatchers.contains("Unit Test bổ sung"));
    }

    @Test
    void retriesWhenGeneratedTestUsesUninitializedFixture() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        TestCase testCase = approvedCase(31L, 20L);

        GeneratedUnitTestDto invalid = new GeneratedUnitTestDto(
                31L, "ServiceTest", "case31", "demo", "NEW_TEST",
                "package demo; class ServiceTest { private Object fixture; private static Object staticFixture; private Object nullFixture = null; private Object ctorFixture; private Object ctorLocalFixture; private Object loopFixture; void case31(Object value) { fixture = new Object(); } ServiceTest(Object ctorFixture) { ctorFixture = new Object(); } ServiceTest() { Object ctorLocalFixture = new Object(); ctorLocalFixture = new Object(); } "
                        + "@org.junit.jupiter.api.Test void case31() { fixture.toString(); staticFixture.toString(); nullFixture.toString(); ctorFixture.toString(); ctorLocalFixture.toString(); for (Object loopFixture : new Object[0]) { loopFixture.toString(); } loopFixture.toString(); } "
                        + "@org.junit.jupiter.api.BeforeEach void initializeLater() { Runnable init = () -> fixture = new Object(); } }");

        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(cases.findAll()).thenReturn(List.of(testCase));
        when(cases.findById(31L)).thenReturn(Optional.of(testCase));
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateUnitTests(1L, Set.of(31L)))
                .thenReturn(new UnitTestResponseDto(List.of(invalid)))
                .thenReturn(new UnitTestResponseDto(List.of(
                        new GeneratedUnitTestDto(31L, "ServiceTest", "case31",
                                "demo", "NEW_TEST", validSource("ServiceTest", "case31")))));
        when(units.saveAll(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generate(1L);

        assertThat(result).singleElement().extracting("testMethodName").isEqualTo("case31");
        verify(ai, org.mockito.Mockito.times(2)).generateUnitTests(1L, Set.of(31L));
        verify(units).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void generationBatchesApprovedCasesToKeepLlmRequestsBounded() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        List<TestCase> approved = LongStream.rangeClosed(1, 11).mapToObj(id -> {
            TestCase testCase = new TestCase();
            testCase.setId(id);
            testCase.setTestPlanId(20L);
            testCase.setStatus(ReviewStatus.APPROVED);
            org.mockito.Mockito.lenient().when(cases.findById(id)).thenReturn(Optional.of(testCase));
            return testCase;
        }).toList();
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(cases.findAll()).thenReturn(approved);
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateUnitTests(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.<Set<Long>>any()))
                .thenAnswer(invocation -> {
                    Set<Long> ids = invocation.getArgument(1);
                    return new UnitTestResponseDto(ids.stream()
                            .map(id -> new GeneratedUnitTestDto(id, "ServiceTest", "case" + id,
                                    "demo", "NEW_TEST", validSource("ServiceTest", "case" + id)))
                            .toList());
                });
        when(units.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generate(1L);

        assertThat(result).hasSize(11);
        ArgumentCaptor<Set<Long>> batches = ArgumentCaptor.forClass(Set.class);
        verify(ai, org.mockito.Mockito.times(3)).generateUnitTests(
                org.mockito.ArgumentMatchers.eq(1L), batches.capture());
        assertThat(batches.getAllValues()).extracting(Set::size).containsExactly(5, 5, 1);
        verify(generationProgress).completeAfterCommit(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.UNIT_TEST),
                org.mockito.ArgumentMatchers.contains("Unit Test"));
    }

    @Test
    void generationSplitsAndRetriesOnlyMissingOrMalformedUnitTests() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        List<TestCase> approved = LongStream.rangeClosed(1, 5).mapToObj(id -> {
            TestCase testCase = new TestCase();
            testCase.setId(id);
            testCase.setTestPlanId(20L);
            testCase.setStatus(ReviewStatus.APPROVED);
            when(cases.findById(id)).thenReturn(Optional.of(testCase));
            return testCase;
        }).toList();
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(cases.findAll()).thenReturn(approved);
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateUnitTests(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.<Set<Long>>any()))
                .thenAnswer(invocation -> {
                    Set<Long> ids = invocation.getArgument(1);
                    if (ids.size() == 5) {
                        return new UnitTestResponseDto(List.of(
                                generated(1L, validSource("ServiceTest", "case1")),
                                generated(2L, "package demo; class ServiceTest { void broken(")));
                    }
                    return new UnitTestResponseDto(ids.stream()
                            .map(id -> generated(id, validSource("ServiceTest", "case" + id)))
                            .toList());
                });
        when(units.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generate(1L);

        assertThat(result).extracting("testCaseId").containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        ArgumentCaptor<Set<Long>> requests = ArgumentCaptor.forClass(Set.class);
        verify(ai, org.mockito.Mockito.times(3)).generateUnitTests(
                org.mockito.ArgumentMatchers.eq(1L), requests.capture());
        assertThat(requests.getAllValues()).containsExactly(
                Set.of(1L, 2L, 3L, 4L, 5L), Set.of(2L, 3L), Set.of(4L, 5L));
        verify(generationProgress).log(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.UNIT_TEST),
                org.mockito.ArgumentMatchers.contains("[2, 3, 4, 5]"));
    }

    @Test
    void generationRejectsParseableJavaWithoutDeclaredTestMethod() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        TestCase testCase = new TestCase();
        testCase.setId(31L);
        testCase.setTestPlanId(20L);
        testCase.setStatus(ReviewStatus.APPROVED);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(cases.findById(31L)).thenReturn(Optional.of(testCase));
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateUnitTests(1L, Set.of(31L))).thenReturn(new UnitTestResponseDto(List.of(
                generated(31L, "package demo; class ServiceTest {}"))));

        assertThatThrownBy(() -> service.generateSupplemental(1L, List.of(31L)))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("31");
        verify(units, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void generationAcceptsJava21SourceSyntax() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        TestCase testCase = new TestCase();
        testCase.setId(31L);
        testCase.setTestPlanId(20L);
        testCase.setStatus(ReviewStatus.APPROVED);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(cases.findById(31L)).thenReturn(Optional.of(testCase));
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateUnitTests(1L, Set.of(31L))).thenReturn(new UnitTestResponseDto(List.of(
                generated(31L, "package demo; record Fixture(String value) {} "
                        + "class ServiceTest { @org.junit.jupiter.api.Test void case31(){ "
                        + "Object value = new Fixture(\"ok\"); String result = switch(value) { "
                        + "case Fixture(String text) when !text.isBlank() -> text; default -> \"\"; }; } }"))));
        when(units.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generateSupplemental(1L, List.of(31L));

        assertThat(result).singleElement().satisfies(test ->
                assertThat(test.sourceCode()).contains("case Fixture(String text) when"));
        verify(ai).generateUnitTests(1L, Set.of(31L));
    }

    @Test
    void generationRetriesInitialSingleCaseOnce() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        TestCase testCase = new TestCase();
        testCase.setId(31L);
        testCase.setTestPlanId(20L);
        testCase.setStatus(ReviewStatus.APPROVED);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(cases.findById(31L)).thenReturn(Optional.of(testCase));
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateUnitTests(1L, Set.of(31L)))
                .thenReturn(new UnitTestResponseDto(List.of(
                                generated(31L, "package demo; class ServiceTest { void broken("))),
                        new UnitTestResponseDto(List.of(
                                generated(31L, validSource("ServiceTest", "case31")))));
        when(units.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generateSupplemental(1L, List.of(31L));

        assertThat(result).hasSize(1);
        verify(ai, org.mockito.Mockito.times(2)).generateUnitTests(1L, Set.of(31L));
        verify(generationProgress).log(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.UNIT_TEST),
                org.mockito.ArgumentMatchers.contains("[31]"));
    }

    @Test
    void generationStopsWhenRecoveryCallBudgetIsExhausted() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        List<TestCase> approved = LongStream.rangeClosed(1, 5).mapToObj(id -> {
            TestCase testCase = new TestCase();
            testCase.setId(id);
            testCase.setTestPlanId(20L);
            testCase.setStatus(ReviewStatus.APPROVED);
            org.mockito.Mockito.lenient().when(cases.findById(id)).thenReturn(Optional.of(testCase));
            return testCase;
        }).toList();
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(cases.findAll()).thenReturn(approved);
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateUnitTests(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.<Set<Long>>any()))
                .thenAnswer(invocation -> {
                    Set<Long> ids = invocation.getArgument(1);
                    if (ids.equals(Set.of(1L, 2L)) || ids.equals(Set.of(3L))) {
                        return new UnitTestResponseDto(ids.stream()
                                .map(id -> generated(id, validSource("ServiceTest", "case" + id)))
                                .toList());
                    }
                    return new UnitTestResponseDto(List.of());
                });

        assertThatThrownBy(() -> service.generate(1L))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("giới hạn 4")
                .hasMessageContaining("4");
        verify(ai, org.mockito.Mockito.times(5)).generateUnitTests(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.<Set<Long>>any());
        verify(units, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void generationDoesNotDeleteOldTestsWhenALateBatchFails() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        List<TestCase> approved = LongStream.rangeClosed(1, 6).mapToObj(id -> {
            TestCase testCase = new TestCase();
            testCase.setId(id);
            testCase.setTestPlanId(20L);
            testCase.setStatus(ReviewStatus.APPROVED);
            org.mockito.Mockito.lenient().when(cases.findById(id)).thenReturn(Optional.of(testCase));
            return testCase;
        }).toList();
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(cases.findAll()).thenReturn(approved);
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateUnitTests(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.<Set<Long>>any()))
                .thenAnswer(invocation -> {
                    Set<Long> ids = invocation.getArgument(1);
                    if (ids.contains(6L)) throw new LlmResponseException("timeout");
                    return new UnitTestResponseDto(ids.stream()
                            .map(id -> new GeneratedUnitTestDto(id, "ServiceTest", "case" + id,
                                    "demo", "NEW_TEST", validSource("ServiceTest", "case" + id)))
                            .toList());
                });

        assertThatThrownBy(() -> service.generate(1L)).isInstanceOf(LlmResponseException.class);
        verify(units, never()).delete(org.mockito.ArgumentMatchers.any());
        verify(units, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void generationRenamesDuplicateMethodNamesAcrossBatches() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        List<TestCase> approved = LongStream.rangeClosed(1, 6).mapToObj(id -> {
            TestCase testCase = new TestCase();
            testCase.setId(id);
            testCase.setTestPlanId(20L);
            testCase.setStatus(ReviewStatus.APPROVED);
            when(cases.findById(id)).thenReturn(Optional.of(testCase));
            return testCase;
        }).toList();
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(cases.findAll()).thenReturn(approved);
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateUnitTests(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.<Set<Long>>any()))
                .thenAnswer(invocation -> {
                    Set<Long> ids = invocation.getArgument(1);
                    return new UnitTestResponseDto(ids.stream()
                            .map(id -> {
                                String methodName = id == 6L ? "case1" : "case" + id;
                                return new GeneratedUnitTestDto(id, "ServiceTest", methodName,
                                        "demo", "NEW_TEST", validSource("ServiceTest", methodName));
                            })
                            .toList());
                });
        when(units.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generate(1L);

        assertThat(result).extracting("testMethodName")
                .contains("case1", "case1Case6");
        assertThat(result).filteredOn(test -> test.testCaseId().equals(6L)).singleElement()
                .satisfies(test -> assertThat(test.sourceCode()).contains("void case1Case6()"));
    }

    @Test
    void supplementalRenamesOnlyMetadataTestMethodWhenHelpersShareName() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        TestCase oldCase = new TestCase();
        oldCase.setId(30L);
        oldCase.setTestPlanId(20L);
        oldCase.setStatus(ReviewStatus.APPROVED);
        TestCase newCase = new TestCase();
        newCase.setId(31L);
        newCase.setTestPlanId(20L);
        newCase.setStatus(ReviewStatus.APPROVED);
        UnitTest oldUnit = new UnitTest();
        oldUnit.setTestCaseId(30L);
        oldUnit.setPackageName("demo");
        oldUnit.setTestClassName("ServiceTest");
        oldUnit.setTestMethodName("sameScenario");
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(cases.findById(31L)).thenReturn(Optional.of(newCase));
        when(cases.findAll()).thenReturn(List.of(oldCase, newCase));
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(units.findByTestCaseId(30L)).thenReturn(oldUnit);
        when(ai.generateUnitTests(1L, Set.of(31L))).thenReturn(new UnitTestResponseDto(List.of(
                new GeneratedUnitTestDto(31L, "ServiceTest", "sameScenario",
                        "demo", "NEW_TEST", "package demo; "
                                + "class OtherTest { void sameScenario(){} } "
                                + "class ServiceTest { void sameScenario(String value){} "
                                + "@org.junit.jupiter.api.Test void sameScenario(){} "
                                + "class Nested { void sameScenario(){} } }"))));
        when(units.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generateSupplemental(1L, List.of(31L));

        assertThat(result).singleElement()
                .satisfies(test -> {
                    assertThat(test.testMethodName()).isEqualTo("sameScenarioCase31");
                    assertThat(test.sourceCode())
                            .contains("@org.junit.jupiter.api.Test", "void sameScenarioCase31()")
                            .contains("void sameScenario(String value)")
                            .contains("class Nested")
                            .doesNotContain("void sameScenarioCase31(String value)");
                });
    }

    @Test
    void rejectsUnsafeAiGeneratedJavaNames() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        TestCase testCase = new TestCase();
        testCase.setId(31L);
        testCase.setTestPlanId(20L);
        testCase.setStatus(ReviewStatus.APPROVED);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(cases.findById(31L)).thenReturn(Optional.of(testCase));
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateUnitTests(1L, java.util.Set.of(31L))).thenReturn(new UnitTestResponseDto(List.of(
                new GeneratedUnitTestDto(31L, "../../Evil", "writesOutsideZip",
                        "demo", "NEW_TEST", "class Evil {}"))));

        assertThatThrownBy(() -> service.generateSupplemental(1L, List.of(31L)))
                .isInstanceOf(LlmResponseException.class);
        verify(units, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    private GeneratedUnitTestDto generated(Long caseId, String sourceCode) {
        return new GeneratedUnitTestDto(caseId, "ServiceTest", "case" + caseId,
                "demo", "NEW_TEST", sourceCode);
    }

    private String validSource(String className, String methodName) {
        return "package demo; class " + className
                + " { @org.junit.jupiter.api.Test void " + methodName + "(){} }";
    }
}
