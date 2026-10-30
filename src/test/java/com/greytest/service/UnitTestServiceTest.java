package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"repaired", "blocked", "failed"})
    void resumeRevalidatesStoredUnitsAndPreservesValidOnesAndEditedOracle(String outcome) {
        boolean blocked = outcome.equals("blocked"); boolean failed = outcome.equals("failed");
        Project project = new Project(); project.setId(1L); project.setStatus(ProjectStatus.TEST_GENERATED);
        TestPlan plan = new TestPlan(); plan.setId(20L); plan.setProjectId(1L); plan.setStatus(ReviewStatus.APPROVED);
        var first = approvedCase(1L, 20L); var second = approvedCase(2L, 20L); second.setIsModified(true);
        second.setExpectedResult("Human approved expected value");
        var good = new UnitTest(); good.setTestCaseId(1L); good.setSourceCode(validSource("ServiceTest", "case1"));
        var bad = new UnitTest(); bad.setTestCaseId(2L); bad.setSourceCode("old invalid fixture");
        Map<Long, UnitTest> stored = new java.util.HashMap<>(); stored.put(1L, good); stored.put(2L, bad);
        when(projects.findById(1L)).thenReturn(Optional.of(project)); when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(cases.findAll()).thenReturn(List.of(first, second)); when(plans.findById(20L)).thenReturn(Optional.of(plan));
        if (!blocked && !failed) when(cases.findById(2L)).thenReturn(Optional.of(second));
        when(units.findByTestCaseId(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> stored.get(call.<Long>getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> {
            if (call.getArgument(1) == bad) throw new LlmResponseException("Missing collection stub");
            return null;
        }).when(ai).validateExistingUnitTest(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any(UnitTest.class), org.mockito.ArgumentMatchers.isNull());
        var output = blocked ? new UnitTestResponseDto(List.of(), List.of(new com.greytest.dto.agent.GenerationResponseDtos.BlockedUnitTestDto(2L, "TC-2", "not feasible")))
                : new UnitTestResponseDto(List.of(generated(2L, validSource("ServiceTest", "case2"))));
        if (failed) when(ai.generateUnitTests(1L, Set.of(2L))).thenThrow(new LlmResponseException("provider failed"));
        else when(ai.generateUnitTests(1L, Set.of(2L))).thenReturn(output);
        if (!blocked && !failed) when(units.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            List<UnitTest> saved = call.getArgument(0); saved.forEach(unit -> stored.put(unit.getTestCaseId(), unit)); return saved;
        });
        if (failed) assertThatThrownBy(() -> service.generate(1L, (String) null, true)).hasMessageContaining("provider failed");
        else service.generate(1L, (String) null, true);
        verify(ai).validateExistingUnitTest(1L, good, null);
        verify(ai).generateUnitTests(1L, Set.of(2L));
        verify(ai, never()).generateUnitTests(1L, Set.of(1L, 2L));
        assertThat(stored.get(1L)).isSameAs(good);
        assertThat(second.getIsModified()).isTrue(); assertThat(second.getExpectedResult()).isEqualTo("Human approved expected value");
        if (blocked || failed) {
            assertThat(project.getStatus()).isEqualTo(ProjectStatus.CASE_APPROVED);
            verify(generationProgress, never()).completeAfterCommit(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ProjectStatus.class, names = {"CASE_APPROVED", "TEST_GENERATED"})
    void keepsValidTestsAndRecoversOnlyMissingNonBlockedCases(ProjectStatus initialStatus) {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(initialStatus);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        plan.setBusinessRuleId(10L);
        plan.setStatus(ReviewStatus.APPROVED);
        List<TestCase> approved = List.of(approvedCase(1L, 20L), approvedCase(2L, 20L), approvedCase(3L, 20L));
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(cases.findAll()).thenReturn(approved);
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(cases.findById(1L)).thenReturn(Optional.of(approved.get(0)));
        when(cases.findById(3L)).thenReturn(Optional.of(approved.get(2)));
        when(ai.generateUnitTests(1L, Set.of(1L, 2L, 3L))).thenReturn(new UnitTestResponseDto(
                List.of(generated(1L, validSource("ServiceTest", "case1"))),
                List.of(new com.greytest.dto.agent.GenerationResponseDtos.BlockedUnitTestDto(2L, "TC-002", "Math.random branch cannot be controlled"))));
        when(ai.generateUnitTests(1L, Set.of(3L))).thenReturn(new UnitTestResponseDto(
                List.of(generated(3L, validSource("ServiceTest", "case3")))));
        when(units.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.generate(1L));

        assertThat(result).extracting("testCaseId").containsExactly(1L, 3L);
        verify(ai).validateUnitTestPrerequisites(1L, Set.of(1L, 2L, 3L));
        verify(ai, org.mockito.Mockito.times(2)).generateUnitTests(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anySet());
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.CASE_APPROVED);
        verify(generationProgress).partialAfterCommit(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.UNIT_TEST),
                org.mockito.ArgumentMatchers.contains("TC-002"));
        verify(generationProgress, never()).completeAfterCommit(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void allBlockedSupplementalCasesFinishPartialWithoutRetryOrProjectPromotion() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        plan.setBusinessRuleId(10L);
        plan.setStatus(ReviewStatus.APPROVED);
        TestCase blocked = approvedCase(31L, 20L);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(cases.findById(31L)).thenReturn(Optional.of(blocked));
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(ai.generateUnitTests(1L, Set.of(31L))).thenReturn(new UnitTestResponseDto(List.of(),
                List.of(new com.greytest.dto.agent.GenerationResponseDtos.BlockedUnitTestDto(31L, "TC-031", "Uncontrollable random branch"))));
        when(units.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.generateSupplemental(1L, List.of(31L)))).isEmpty();

        verify(ai).generateUnitTests(1L, Set.of(31L));
        verify(ai).validateUnitTestPrerequisites(1L, Set.of(31L));
        verify(projects, never()).save(org.mockito.ArgumentMatchers.any());
        verify(generationProgress).partialAfterCommit(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.UNIT_TEST), org.mockito.ArgumentMatchers.contains("TC-031"));
    }

    @Test
    void continuesWithIndependentBatchesWhenAnEntireBatchIsBlocked() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        plan.setBusinessRuleId(10L);
        plan.setStatus(ReviewStatus.APPROVED);
        List<TestCase> approved = LongStream.rangeClosed(1, 5).mapToObj(id -> approvedCase(id, 20L)).toList();
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(cases.findAll()).thenReturn(approved);
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(cases.findById(5L)).thenReturn(Optional.of(approved.get(4)));
        when(ai.generateUnitTests(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anySet()))
                .thenAnswer(invocation -> {
                    Set<Long> ids = invocation.getArgument(1);
                    if(ids.contains(1L)) return new UnitTestResponseDto(List.of(), ids.stream()
                            .map(id -> new com.greytest.dto.agent.GenerationResponseDtos.BlockedUnitTestDto(id, "TC-" + id, "Uncontrollable random branch"))
                            .toList());
                    return new UnitTestResponseDto(List.of(generated(5L, validSource("ServiceTest", "case5"))));
                });
        when(units.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.generate(1L)).singleElement().extracting("testCaseId").isEqualTo(5L);

        verify(ai).validateUnitTestPrerequisites(1L, LongStream.rangeClosed(1, 5).boxed().collect(java.util.stream.Collectors.toSet()));
        verify(ai, org.mockito.Mockito.times(2)).generateUnitTests(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anySet());
        verify(generationProgress).partialAfterCommit(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.UNIT_TEST), org.mockito.ArgumentMatchers.contains("4 Test Case"));
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.CASE_APPROVED);
    }

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
    void scopedPendingCaseIsRejectedBeforeDeletingExistingUnitTests() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_PENDING_REVIEW);
        var resolver = org.mockito.Mockito.mock(ServiceScopeResolver.class);
        var artifacts = org.mockito.Mockito.mock(ServicePipelineStatusService.class);
        var scope = new ServiceScopeResolver.ServiceScope("service-a", Set.of(), Set.of(11L));
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(resolver.resolve(1L, "service-a")).thenReturn(scope);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        plan.setStatus(ReviewStatus.APPROVED);
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        TestCase pending = approvedCase(31L, 20L);
        pending.setStatus(ReviewStatus.PENDING_REVIEW);
        when(artifacts.cases(1L, scope)).thenReturn(List.of(pending));
        var scopedService = new UnitTestService(units, cases, plans, projects, ai, files,
                transactionManager, generationProgress, resolver, artifacts);

        assertThatThrownBy(() -> scopedService.generate(1L, "service-a"))
                .isInstanceOf(LlmResponseException.class);
        org.mockito.Mockito.verifyNoInteractions(ai, units);
    }

    @Test
    void oraclePreflightFailurePreservesExistingUnitTests() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        plan.setStatus(ReviewStatus.APPROVED);
        TestCase testCase = approvedCase(31L, 20L);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(cases.findAll()).thenReturn(List.of(testCase));
        org.mockito.Mockito.doThrow(new com.greytest.exception.InvalidProjectStatusException("BR chưa APPROVED"))
                .when(ai).validateUnitTestPrerequisites(1L, Set.of(31L));

        assertThatThrownBy(() -> service.generate(1L)).isInstanceOf(com.greytest.exception.InvalidProjectStatusException.class);
        verify(ai, never()).generateUnitTests(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anySet());
        org.mockito.Mockito.verifyNoInteractions(units);
    }

    @Test
    void pendingCaseIsRejectedBeforeDeletingExistingUnitTestsOrCallingAi() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        plan.setStatus(ReviewStatus.APPROVED);
        TestCase pending = new TestCase();
        pending.setId(31L);
        pending.setTestPlanId(20L);
        pending.setStatus(ReviewStatus.PENDING_REVIEW);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(cases.findAll()).thenReturn(List.of(pending));

        assertThatThrownBy(() -> service.generate(1L)).isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("approve");
        org.mockito.Mockito.verifyNoInteractions(ai, units);
        assertThat(pending.getStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);
    }

    @Test
    void refinementGeneratesOnlyNewCasesAndPreservesOldUnitTests() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setStatus(ReviewStatus.APPROVED);
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
        plan.setStatus(ReviewStatus.APPROVED);
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
        TestCase pendingSibling = approvedCase(32L, 20L);
        pendingSibling.setStatus(ReviewStatus.PENDING_REVIEW);
        when(cases.findAll()).thenReturn(List.of(testCase, pendingSibling));
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
        plan.setStatus(ReviewStatus.APPROVED);
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
        assertThat(batches.getAllValues()).extracting(Set::size).containsExactly(4, 4, 3);
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
        plan.setStatus(ReviewStatus.APPROVED);
        plan.setId(20L);
        plan.setProjectId(1L);
        List<TestCase> approved = LongStream.rangeClosed(1, 4).mapToObj(id -> {
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
                    if (ids.size() == 4) {
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

        assertThat(result).extracting("testCaseId").containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        ArgumentCaptor<Set<Long>> requests = ArgumentCaptor.forClass(Set.class);
        verify(ai, org.mockito.Mockito.times(3)).generateUnitTests(
                org.mockito.ArgumentMatchers.eq(1L), requests.capture());
        assertThat(requests.getAllValues()).containsExactly(
                Set.of(1L, 2L, 3L, 4L), Set.of(2L), Set.of(3L, 4L));
        verify(generationProgress).log(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.UNIT_TEST),
                org.mockito.ArgumentMatchers.contains("[2, 3, 4]"));
    }

    @Test
    void generationRejectsParseableJavaWithoutDeclaredTestMethod() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setStatus(ReviewStatus.APPROVED);
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
        plan.setStatus(ReviewStatus.APPROVED);
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
    void generationStoresApprovedEqualityAssertionWithoutExecutingProduction() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setStatus(ReviewStatus.APPROVED);
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
        testCase.setExpectedResult("Transfer succeeds when balance equals amount");
        testCase.setTestData(Map.of("balance", 100, "amount", 100));
        when(ai.generateUnitTests(1L, Set.of(31L))).thenReturn(new UnitTestResponseDto(List.of(
                generated(31L, "package demo; class ServiceTest { "
                        + "@org.junit.jupiter.api.Test void case31(){ "
                        + "TransferService service = new TransferService(); "
                        + "org.junit.jupiter.api.Assertions.assertTrue(service.transfer(100, 100)); } }"))));
        when(units.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generateSupplemental(1L, List.of(31L));

        assertThat(result).singleElement().satisfies(test ->
                assertThat(test.sourceCode()).contains("assertTrue(service.transfer(100, 100))"));
        verify(ai).generateUnitTests(1L, Set.of(31L));
    }

    @Test
    void generationRetriesInitialSingleCaseOnce() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setStatus(ReviewStatus.APPROVED);
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
        plan.setStatus(ReviewStatus.APPROVED);
        plan.setId(20L);
        plan.setProjectId(1L);
        List<TestCase> approved = LongStream.rangeClosed(1, 4).mapToObj(id -> {
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
                    if (ids.equals(Set.of(1L)) || ids.equals(Set.of(2L))) {
                        return new UnitTestResponseDto(ids.stream()
                                .map(id -> generated(id, validSource("ServiceTest", "case" + id)))
                                .toList());
                    }
                    return new UnitTestResponseDto(List.of());
                });

        assertThatThrownBy(() -> service.generate(1L))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("giới hạn 4")
                .hasMessageContaining("3");
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
        plan.setStatus(ReviewStatus.APPROVED);
        plan.setId(20L);
        plan.setProjectId(1L);
        List<TestCase> approved = LongStream.rangeClosed(1, 3).mapToObj(id -> {
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
                    if (ids.contains(3L)) throw new LlmResponseException("timeout");
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
        plan.setStatus(ReviewStatus.APPROVED);
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
        plan.setStatus(ReviewStatus.APPROVED);
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
        plan.setStatus(ReviewStatus.APPROVED);
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

    @Test
    void generationResumeOnlyGeneratesMissingUnitTests() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        TestPlan plan = new TestPlan();
        plan.setStatus(ReviewStatus.APPROVED);
        plan.setId(20L);
        plan.setProjectId(1L);

        TestCase case1 = approvedCase(1L, 20L);
        TestCase case2 = approvedCase(2L, 20L);

        UnitTest existingUnit1 = new UnitTest();
        existingUnit1.setId(101L);
        existingUnit1.setTestCaseId(1L);

        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.findByIdForUpdate(1L)).thenReturn(Optional.of(project));
        when(cases.findAll()).thenReturn(List.of(case1, case2));
        when(cases.findById(2L)).thenReturn(Optional.of(case2));
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(units.findByTestCaseId(1L)).thenReturn(existingUnit1);
        when(units.findByTestCaseId(2L)).thenReturn(null);

        when(ai.generateUnitTests(1L, Set.of(2L))).thenReturn(new UnitTestResponseDto(List.of(
                new GeneratedUnitTestDto(2L, "ServiceTest", "case2",
                        "demo", "NEW_TEST", validSource("ServiceTest", "case2")))));
        when(units.saveAll(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.generate(1L, (String) null, true);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).testCaseId()).isEqualTo(2L);
        verify(ai).generateUnitTests(1L, Set.of(2L));
        verify(ai, never()).generateUnitTests(1L, Set.of(1L));
        verify(units, never()).delete(existingUnit1);
    }

    @Test
    void resumeKeepsOriginalBatchNumbersInProgress() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        int batchSize = com.greytest.service.agent.GenerationContextBuilder.MAX_UNIT_TEST_CASES;
        List<TestCase> approved = LongStream.rangeClosed(1, batchSize + 1)
                .mapToObj(id -> approvedCase(id, 20L))
                .toList();
        TestPlan plan = new TestPlan();
        plan.setStatus(ReviewStatus.APPROVED);
        plan.setId(20L);
        plan.setProjectId(1L);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(cases.findAll()).thenReturn(approved);
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        for (long id = 1; id <= batchSize; id++) {
            UnitTest existing = new UnitTest();
            existing.setTestCaseId(id);
            when(units.findByTestCaseId(id)).thenReturn(existing);
        }
        when(units.findByTestCaseId((long) batchSize + 1)).thenReturn(null);
        when(ai.generateUnitTests(1L, Set.of((long) batchSize + 1)))
                .thenThrow(new LlmResponseException("stop after progress setup"));

        assertThatThrownBy(() -> service.generate(1L, (String) null, true))
                .isInstanceOf(LlmResponseException.class);

        ArgumentCaptor<List<String>> labels = ArgumentCaptor.forClass(List.class);
        verify(generationProgress).resume(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.UNIT_TEST),
                labels.capture(),
                org.mockito.ArgumentMatchers.eq(Set.of(0)),
                org.mockito.ArgumentMatchers.contains("batch 2/2"));
        assertThat(labels.getValue()).contains(
                "Sinh Unit Test - batch 1/2: TC-1, TC-2, TC-3, TC-4",
                "Sinh Unit Test - batch 2/2: TC-5");
    }

    @Test
    void resumeKeepsProgressSnapshotWhenNoBatchIsFullyPersistedYet() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.CASE_APPROVED);
        int batchSize = com.greytest.service.agent.GenerationContextBuilder.MAX_UNIT_TEST_CASES;
        List<TestCase> approved = LongStream.rangeClosed(1, batchSize + 1)
                .mapToObj(id -> approvedCase(id, 20L))
                .toList();
        TestPlan plan = new TestPlan();
        plan.setStatus(ReviewStatus.APPROVED);
        plan.setId(20L);
        plan.setProjectId(1L);
        UnitTest partialUnit = new UnitTest();
        partialUnit.setTestCaseId(1L);

        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(cases.findAll()).thenReturn(approved);
        when(plans.findById(20L)).thenReturn(Optional.of(plan));
        when(units.findByTestCaseId(1L)).thenReturn(partialUnit);
        when(units.findByTestCaseId(org.mockito.ArgumentMatchers.longThat(id -> id > 1L))).thenReturn(null);
        when(ai.generateUnitTests(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.<Long>anySet()))
                .thenThrow(new LlmResponseException("stop after resume setup"));

        assertThatThrownBy(() -> service.generate(1L, (String) null, true))
                .isInstanceOf(LlmResponseException.class);

        verify(generationProgress).resume(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.UNIT_TEST),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(Set.of()),
                org.mockito.ArgumentMatchers.contains("batch 1/2, batch 2/2"));
        verify(generationProgress, never()).start(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(com.greytest.dto.GenerationProgressStage.UNIT_TEST),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private static TestCase approvedCase(Long id, Long planId) {
        TestCase testCase = new TestCase();
        testCase.setId(id);
        testCase.setTestPlanId(planId);
        testCase.setCaseCode("TC-" + id);
        testCase.setTraceSource("BR-1 -> TP-1");
        testCase.setStatus(ReviewStatus.APPROVED);
        return testCase;
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
