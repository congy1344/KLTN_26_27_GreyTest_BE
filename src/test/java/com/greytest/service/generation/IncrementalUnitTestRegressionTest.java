package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.dto.diff.ImpactSummaryDto;
import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.MethodParam;
import com.greytest.entity.SourceUpdate;
import com.greytest.entity.SourceUpdateItem;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.SourceUpdateTargetType;
import com.greytest.mapper.SourceUpdateMapper;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.SourceUpdateItemRepository;
import com.greytest.repository.SourceUpdateRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.GenerationJobService;
import com.greytest.service.IncrementalGenerationService;
import com.greytest.service.SourceUpdateService;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.GenerationSourceFingerprint;
import com.greytest.service.agent.LlmStageTokenLimits;
import com.greytest.service.agent.PromptManager;

/**
 * Kiểm tra hồi quy 7 kịch bản cho tối ưu sinh Unit Test gia tăng (Incremental Unit Test Generation).
 */
class IncrementalUnitTestRegressionTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private PromptManager prompts;
    private MockEnvironment environment;
    private GenerationFingerprintService fingerprintService;

    @BeforeEach
    void setup() {
        prompts = mock(PromptManager.class);
        when(prompts.load("business-rule")).thenReturn("rules-v1");
        when(prompts.load("unit-test")).thenReturn("tests-v1");
        when(prompts.load("source-contract")).thenReturn("contract-v1");
        environment = new MockEnvironment().withProperty("llm.provider", "mock");
        fingerprintService = new GenerationFingerprintService(mapper, prompts, LlmStageTokenLimits.defaults(), environment);
    }

    private String sourceHeader(String relativePath, String hash) {
        String encoded = Base64.getEncoder().encodeToString(relativePath.getBytes(StandardCharsets.UTF_8));
        return GenerationSourceFingerprint.PREFIX + hash + " " + encoded + "\n";
    }

    private UnitTestContextDto singleClassContext(String packageName, String className, String relativePath, String methodName) {
        MethodContextDto methodDto = new MethodContextDto(1L,
                packageName.isEmpty() ? className : packageName + "." + className,
                methodName, "void", List.of(), List.of(), "public", "", 1, 2, List.of(), List.of(), List.of());
        ClassContextDto classDto = new ClassContextDto(1L, packageName, className,
                packageName.isEmpty() ? className : packageName + "." + className,
                "SERVICE", relativePath, "", List.of(), List.of(methodDto));
        return new UnitTestContextDto(null, null, List.of(classDto), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Kịch bản 1: Class có 25 UT cũ + 1 method mới (13 case mới).
     * Chỉ 13 UT mới được sinh, 0 lượt gọi LLM cho 25 UT cũ dù toàn bộ file SUT thay đổi hash SHA-256.
     */
    @Test
    @DisplayName("Scenario 1: Class có 25 UT cũ + 1 method mới -> chỉ sinh 13 UT mới, 0 LLM call cho UT cũ")
    void scenario1_classWith25ExistingTestsAnd1NewMethod_generatesOnly13NewTestsAnd0CallsForOldTests() throws Exception {
        Path srcDir = tempDir.resolve("src/main/java/com/hospital/appointment/service");
        Files.createDirectories(srcDir);
        Path sutFile = srcDir.resolve("AppointmentService.java");
        Files.writeString(sutFile, """
            package com.hospital.appointment.service;
            public class AppointmentService {
                public String getAppointmentById(Long id) {
                    return "Appointment-" + id;
                }
                public String updateAppointment(Long id, String status) {
                    return "Updated-" + id + "-" + status;
                }
            }
            """);

        SourceUpdateRepository sourceUpdateRepo = mock(SourceUpdateRepository.class);
        SourceUpdateItemRepository itemRepo = mock(SourceUpdateItemRepository.class);
        BusinessRuleRepository ruleRepo = mock(BusinessRuleRepository.class);
        TestPlanRepository testPlanRepo = mock(TestPlanRepository.class);
        TestCaseRepository testCaseRepo = mock(TestCaseRepository.class);
        UnitTestRepository unitTestRepo = mock(UnitTestRepository.class);
        JavaMethodRepository methodRepo = mock(JavaMethodRepository.class);
        JavaClassRepository classRepo = mock(JavaClassRepository.class);
        ProjectRepository projectRepo = mock(ProjectRepository.class);
        SourceUpdateService sourceUpdateService = mock(SourceUpdateService.class);
        AIAgentService aiAgentService = mock(AIAgentService.class);
        IncrementalCheckpointCoordinator coordinator = mock(IncrementalCheckpointCoordinator.class);

        UnitTestContextDto contextDto = singleClassContext("com.hospital.appointment.service",
                "AppointmentService", "src/main/java/com/hospital/appointment/service/AppointmentService.java", "getAppointmentById");
        when(aiAgentService.snapshotContext(any(), anyLong(), anySet(), any())).thenReturn(contextDto);

        IncrementalGenerationService service = new IncrementalGenerationService(
                projectRepo, sourceUpdateRepo, itemRepo, classRepo, methodRepo,
                unitTestRepo, ruleRepo, testPlanRepo, testCaseRepo,
                sourceUpdateService, aiAgentService, new SourceUpdateMapper(), mapper, null);

        org.springframework.test.util.ReflectionTestUtils.setField(service, "checkpoints", coordinator);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "fingerprints", fingerprintService);

        Long projectId = 909L;
        Long updateId = 263L;
        Long classId = 101L;
        Long planId = 100L;
        Long ruleId = 501L;
        Long oldMethodId = 21559L;
        Long newMethodId = 21570L;

        JavaClass sutClass = new JavaClass();
        sutClass.setId(classId);
        sutClass.setQualifiedName("com.hospital.appointment.service.AppointmentService");
        when(classRepo.findByProjectId(projectId)).thenReturn(List.of(sutClass));

        JavaMethod oldMethod = new JavaMethod();
        oldMethod.setId(oldMethodId);
        oldMethod.setClassId(classId);
        oldMethod.setMethodName("getAppointmentById");
        oldMethod.setParameters(List.of(new MethodParam("id", "Long")));

        JavaMethod newMethod = new JavaMethod();
        newMethod.setId(newMethodId);
        newMethod.setClassId(classId);
        newMethod.setMethodName("updateAppointment");
        newMethod.setParameters(List.of(new MethodParam("id", "Long"), new MethodParam("status", "String")));

        when(methodRepo.findByClassId(classId)).thenReturn(List.of(oldMethod, newMethod));

        BusinessRule oldRule = new BusinessRule();
        oldRule.setId(ruleId);
        oldRule.setMethodId(oldMethodId);
        when(ruleRepo.findById(ruleId)).thenReturn(Optional.of(oldRule));

        TestPlan approvedPlan = new TestPlan();
        approvedPlan.setId(planId);
        approvedPlan.setBusinessRuleId(ruleId);
        approvedPlan.setStatus(ReviewStatus.APPROVED);
        when(testPlanRepo.findByProjectId(projectId)).thenReturn(List.of(approvedPlan));
        when(testPlanRepo.findById(planId)).thenReturn(Optional.of(approvedPlan));

        String header = sourceHeader("src/main/java/com/hospital/appointment/service/AppointmentService.java", "0".repeat(64));

        // 25 test cases cũ cho oldMethod (IDs 3851..3875)
        List<TestCase> oldCases = new ArrayList<>();
        List<UnitTest> oldUnits = new ArrayList<>();
        for (long id = 3851L; id <= 3875L; id++) {
            TestCase tc = new TestCase();
            tc.setId(id);
            tc.setTestPlanId(planId);
            tc.setStatus(ReviewStatus.APPROVED);
            oldCases.add(tc);

            UnitTest ut = new UnitTest();
            ut.setId(id + 1000L);
            ut.setTestCaseId(id);
            ut.setTestClassName("AppointmentServiceTest");
            ut.setTestMethodName("testGet");
            ut.setPackageName("com.hospital.appointment.service");
            ut.setGenerationType("NEW_TEST");
            ut.setSourceCode(header + """
                package com.hospital.appointment.service;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                class AppointmentServiceTest {
                    @Test void testGet() {
                        assertNotNull(new AppointmentService().getAppointmentById(1L));
                    }
                }
                """);
            oldUnits.add(ut);
            when(unitTestRepo.findByTestCaseId(id)).thenReturn(ut);
            when(testCaseRepo.findById(id)).thenReturn(Optional.of(tc));
        }

        // 13 test cases mới cho newMethod (IDs 3876..3888), chưa có approved UT
        List<TestCase> newCases = new ArrayList<>();
        for (long id = 3876L; id <= 3888L; id++) {
            TestCase tc = new TestCase();
            tc.setId(id);
            tc.setTestPlanId(planId);
            tc.setStatus(ReviewStatus.APPROVED);
            newCases.add(tc);
            when(unitTestRepo.findByTestCaseId(id)).thenReturn(null);
            when(testCaseRepo.findById(id)).thenReturn(Optional.of(tc));
        }

        List<TestCase> allCases = new ArrayList<>(oldCases);
        allCases.addAll(newCases);
        when(testCaseRepo.findByProjectId(projectId)).thenReturn(allCases);
        when(unitTestRepo.findByTestCaseIdIn(any())).thenReturn(oldUnits);

        // Impact summary: Chỉ method 21570 (updateAppointment) được thêm mới
        MethodDiffItem diffItem = new MethodDiffItem(
                "AppointmentService", "com.hospital.appointment.service.AppointmentService",
                "updateAppointment", "updateAppointment(Long,String)", "key",
                MethodDiffType.ADDED, "added", null, "...", List.of(), true);

        ImpactSummaryDto impact = new ImpactSummaryDto(
                1, 1, 0, 0, List.of(diffItem), List.of(), List.of(), List.of(),
                newCases.stream().map(TestCase::getId).toList(), List.of());

        SourceUpdate update = new SourceUpdate();
        update.setId(updateId);
        update.setProjectId(projectId);
        update.setImpactSummary(mapper.writeValueAsString(impact));
        when(sourceUpdateService.getCandidateSourcePath(update)).thenReturn(tempDir);

        java.lang.reflect.Method staleMethod = IncrementalGenerationService.class.getDeclaredMethod(
                "staleSourceCaseIds", Long.class, SourceUpdate.class, ImpactSummaryDto.class, Path.class);
        staleMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<Long> staleIds = (Set<Long>) staleMethod.invoke(service, projectId, update, impact, tempDir);

        // Khẳng định: 25 test cases cũ KHÔNG bị coi là stale
        assertThat(staleIds).doesNotContainAnyElementsOf(oldCases.stream().map(TestCase::getId).toList());
        // Verify coordinator đã lưu retained checkpoint cho 25 test cases cũ mà không gọi LLM
        verify(coordinator, times(25)).recordRetainedUnitTest(eq(projectId), eq(updateId), anyLong(), any(UnitTest.class), eq(tempDir));
    }

    /**
     * Kịch bản 2: Sửa signature của 1 method cũ -> chỉ các UT liên quan đến method đó được chọn để sinh lại.
     */
    @Test
    @DisplayName("Scenario 2: Sửa signature method cũ -> chỉ các UT liên quan bị stale")
    void scenario2_modifyingMethodSignature_invalidatesOnlyRelatedUnitTests() throws Exception {
        SourceUpdateRepository sourceUpdateRepo = mock(SourceUpdateRepository.class);
        SourceUpdateItemRepository itemRepo = mock(SourceUpdateItemRepository.class);
        BusinessRuleRepository ruleRepo = mock(BusinessRuleRepository.class);
        TestPlanRepository testPlanRepo = mock(TestPlanRepository.class);
        TestCaseRepository testCaseRepo = mock(TestCaseRepository.class);
        UnitTestRepository unitTestRepo = mock(UnitTestRepository.class);
        JavaMethodRepository methodRepo = mock(JavaMethodRepository.class);
        JavaClassRepository classRepo = mock(JavaClassRepository.class);
        ProjectRepository projectRepo = mock(ProjectRepository.class);
        SourceUpdateService sourceUpdateService = mock(SourceUpdateService.class);
        AIAgentService aiAgentService = mock(AIAgentService.class);
        IncrementalCheckpointCoordinator coordinator = mock(IncrementalCheckpointCoordinator.class);

        UnitTestContextDto contextDto = singleClassContext("example", "Subject", "src/main/java/example/Subject.java", "checkStatus");
        when(aiAgentService.snapshotContext(any(), anyLong(), anySet(), any())).thenReturn(contextDto);

        IncrementalGenerationService service = new IncrementalGenerationService(
                projectRepo, sourceUpdateRepo, itemRepo, classRepo, methodRepo,
                unitTestRepo, ruleRepo, testPlanRepo, testCaseRepo,
                sourceUpdateService, aiAgentService, new SourceUpdateMapper(), mapper, null);

        org.springframework.test.util.ReflectionTestUtils.setField(service, "checkpoints", coordinator);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "fingerprints", fingerprintService);

        Long projectId = 1L;
        Long classId = 10L;
        Long plan1Id = 101L;
        Long plan2Id = 102L;
        Long rule1Id = 201L;
        Long rule2Id = 202L;
        Long modifiedMethodId = 301L;
        Long untouchedMethodId = 302L;

        JavaClass subjectClass = new JavaClass();
        subjectClass.setId(classId);
        subjectClass.setQualifiedName("example.Subject");
        when(classRepo.findByProjectId(projectId)).thenReturn(List.of(subjectClass));

        JavaMethod m1 = new JavaMethod();
        m1.setId(modifiedMethodId);
        m1.setClassId(classId);
        m1.setMethodName("calculateFee");
        m1.setParameters(List.of(new MethodParam("amount", "int"), new MethodParam("discount", "boolean")));

        JavaMethod m2 = new JavaMethod();
        m2.setId(untouchedMethodId);
        m2.setClassId(classId);
        m2.setMethodName("checkStatus");
        m2.setParameters(List.of());

        when(methodRepo.findByClassId(classId)).thenReturn(List.of(m1, m2));

        BusinessRule r1 = new BusinessRule(); r1.setId(rule1Id); r1.setMethodId(modifiedMethodId);
        BusinessRule r2 = new BusinessRule(); r2.setId(rule2Id); r2.setMethodId(untouchedMethodId);
        when(ruleRepo.findById(rule1Id)).thenReturn(Optional.of(r1));
        when(ruleRepo.findById(rule2Id)).thenReturn(Optional.of(r2));

        TestPlan p1 = new TestPlan(); p1.setId(plan1Id); p1.setBusinessRuleId(rule1Id); p1.setStatus(ReviewStatus.APPROVED);
        TestPlan p2 = new TestPlan(); p2.setId(plan2Id); p2.setBusinessRuleId(rule2Id); p2.setStatus(ReviewStatus.APPROVED);
        when(testPlanRepo.findByProjectId(projectId)).thenReturn(List.of(p1, p2));
        when(testPlanRepo.findById(plan1Id)).thenReturn(Optional.of(p1));
        when(testPlanRepo.findById(plan2Id)).thenReturn(Optional.of(p2));

        TestCase tc1 = new TestCase(); tc1.setId(1001L); tc1.setTestPlanId(plan1Id); tc1.setStatus(ReviewStatus.APPROVED);
        TestCase tc2 = new TestCase(); tc2.setId(1002L); tc2.setTestPlanId(plan2Id); tc2.setStatus(ReviewStatus.APPROVED);
        when(testCaseRepo.findByProjectId(projectId)).thenReturn(List.of(tc1, tc2));
        when(testCaseRepo.findById(1001L)).thenReturn(Optional.of(tc1));
        when(testCaseRepo.findById(1002L)).thenReturn(Optional.of(tc2));

        // Viết source cho Untouched Subject
        Path subjectDir = tempDir.resolve("src/main/java/example");
        Files.createDirectories(subjectDir);
        Files.writeString(subjectDir.resolve("Subject.java"), """
            package example;
            public class Subject {
                public void calculateFee(int amount, boolean discount) {}
                public void checkStatus() {}
            }
            """);

        String header = sourceHeader("src/main/java/example/Subject.java", "0".repeat(64));
        UnitTest ut1 = new UnitTest(); ut1.setId(2001L); ut1.setTestCaseId(1001L);
        ut1.setTestClassName("SubjectTest"); ut1.setTestMethodName("test1"); ut1.setPackageName("example"); ut1.setGenerationType("NEW_TEST");
        ut1.setSourceCode(header + "class Test1 {}");

        UnitTest ut2 = new UnitTest(); ut2.setId(2002L); ut2.setTestCaseId(1002L);
        ut2.setTestClassName("SubjectTest"); ut2.setTestMethodName("test2"); ut2.setPackageName("example"); ut2.setGenerationType("NEW_TEST");
        ut2.setSourceCode(header + """
            package example;
            class Test2 {
                void test() { new Subject().checkStatus(); }
            }
            """);
        when(unitTestRepo.findByTestCaseId(1001L)).thenReturn(ut1);
        when(unitTestRepo.findByTestCaseId(1002L)).thenReturn(ut2);
        when(unitTestRepo.findByTestCaseIdIn(any())).thenReturn(List.of(ut1, ut2));

        MethodDiffItem diffItem = new MethodDiffItem(
                "Subject", "example.Subject", "calculateFee", "calculateFee(int,boolean)",
                "key", MethodDiffType.MODIFIED, "modified", "...", "...", List.of(), true);

        ImpactSummaryDto impact = new ImpactSummaryDto(
                1, 0, 1, 0, List.of(diffItem), List.of(), List.of(), List.of(),
                List.of(1001L), List.of());

        SourceUpdate update = new SourceUpdate();
        update.setId(50L);
        update.setProjectId(projectId);

        java.lang.reflect.Method staleMethod = IncrementalGenerationService.class.getDeclaredMethod(
                "staleSourceCaseIds", Long.class, SourceUpdate.class, ImpactSummaryDto.class, Path.class);
        staleMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<Long> staleIds = (Set<Long>) staleMethod.invoke(service, projectId, update, impact, tempDir);

        // TC liên quan đến modified method bị stale, TC của untouched method được giữ lại
        assertThat(staleIds).contains(1001L);
        assertThat(staleIds).doesNotContain(1002L);
    }

    /**
     * Kịch bản 3: Thay đổi 1 shared dependency -> UT phụ thuộc bị đánh dấu affected.
     */
    @Test
    @DisplayName("Scenario 3: Thay đổi shared dependency -> UT phụ thuộc bị stale")
    void scenario3_modifyingSharedDependency_invalidatesDependentUnitTests() throws Exception {
        SourceUpdateRepository sourceUpdateRepo = mock(SourceUpdateRepository.class);
        SourceUpdateItemRepository itemRepo = mock(SourceUpdateItemRepository.class);
        BusinessRuleRepository ruleRepo = mock(BusinessRuleRepository.class);
        TestPlanRepository testPlanRepo = mock(TestPlanRepository.class);
        TestCaseRepository testCaseRepo = mock(TestCaseRepository.class);
        UnitTestRepository unitTestRepo = mock(UnitTestRepository.class);
        JavaMethodRepository methodRepo = mock(JavaMethodRepository.class);
        JavaClassRepository classRepo = mock(JavaClassRepository.class);
        ProjectRepository projectRepo = mock(ProjectRepository.class);
        SourceUpdateService sourceUpdateService = mock(SourceUpdateService.class);
        AIAgentService aiAgentService = mock(AIAgentService.class);
        IncrementalCheckpointCoordinator coordinator = mock(IncrementalCheckpointCoordinator.class);

        UnitTestContextDto contextDto = singleClassContext("example", "OrderService", "src/main/java/example/OrderService.java", "ping");
        when(aiAgentService.snapshotContext(any(), anyLong(), anySet(), any())).thenReturn(contextDto);

        IncrementalGenerationService service = new IncrementalGenerationService(
                projectRepo, sourceUpdateRepo, itemRepo, classRepo, methodRepo,
                unitTestRepo, ruleRepo, testPlanRepo, testCaseRepo,
                sourceUpdateService, aiAgentService, new SourceUpdateMapper(), mapper, null);

        org.springframework.test.util.ReflectionTestUtils.setField(service, "checkpoints", coordinator);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "fingerprints", fingerprintService);

        Long projectId = 1L;
        Long classId = 11L;
        Long plan1Id = 201L;
        Long plan2Id = 202L;
        Long rule1Id = 301L;
        Long rule2Id = 302L;
        Long dependentMethodId = 401L;
        Long independentMethodId = 402L;

        JavaClass orderClass = new JavaClass();
        orderClass.setId(classId);
        orderClass.setQualifiedName("example.OrderService");
        when(classRepo.findByProjectId(projectId)).thenReturn(List.of(orderClass));

        JavaMethod m1 = new JavaMethod(); m1.setId(dependentMethodId); m1.setClassId(classId); m1.setMethodName("processOrder"); m1.setParameters(List.of());
        JavaMethod m2 = new JavaMethod(); m2.setId(independentMethodId); m2.setClassId(classId); m2.setMethodName("ping"); m2.setParameters(List.of());
        when(methodRepo.findByClassId(classId)).thenReturn(List.of(m1, m2));

        BusinessRule r1 = new BusinessRule(); r1.setId(rule1Id); r1.setMethodId(dependentMethodId);
        BusinessRule r2 = new BusinessRule(); r2.setId(rule2Id); r2.setMethodId(independentMethodId);
        when(ruleRepo.findById(rule1Id)).thenReturn(Optional.of(r1));
        when(ruleRepo.findById(rule2Id)).thenReturn(Optional.of(r2));

        TestPlan p1 = new TestPlan(); p1.setId(plan1Id); p1.setBusinessRuleId(rule1Id); p1.setStatus(ReviewStatus.APPROVED);
        TestPlan p2 = new TestPlan(); p2.setId(plan2Id); p2.setBusinessRuleId(rule2Id); p2.setStatus(ReviewStatus.APPROVED);
        when(testPlanRepo.findByProjectId(projectId)).thenReturn(List.of(p1, p2));
        when(testPlanRepo.findById(plan1Id)).thenReturn(Optional.of(p1));
        when(testPlanRepo.findById(plan2Id)).thenReturn(Optional.of(p2));

        TestCase tc1 = new TestCase(); tc1.setId(5001L); tc1.setTestPlanId(plan1Id); tc1.setStatus(ReviewStatus.APPROVED);
        TestCase tc2 = new TestCase(); tc2.setId(5002L); tc2.setTestPlanId(plan2Id); tc2.setStatus(ReviewStatus.APPROVED);
        when(testCaseRepo.findByProjectId(projectId)).thenReturn(List.of(tc1, tc2));
        when(testCaseRepo.findById(5001L)).thenReturn(Optional.of(tc1));
        when(testCaseRepo.findById(5002L)).thenReturn(Optional.of(tc2));

        Path src = tempDir.resolve("src/main/java/example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("OrderService.java"), """
            package example;
            public class OrderService {
                public void processOrder() {}
                public void ping() {}
            }
            """);

        String header = sourceHeader("src/main/java/example/OrderService.java", "0".repeat(64));
        UnitTest ut1 = new UnitTest(); ut1.setId(6001L); ut1.setTestCaseId(5001L);
        ut1.setTestClassName("OrderServiceTest"); ut1.setTestMethodName("testProcess"); ut1.setPackageName("example"); ut1.setGenerationType("NEW_TEST");
        ut1.setSourceCode(header + "class Test1 {}");

        UnitTest ut2 = new UnitTest(); ut2.setId(6002L); ut2.setTestCaseId(5002L);
        ut2.setTestClassName("OrderServiceTest"); ut2.setTestMethodName("testPing"); ut2.setPackageName("example"); ut2.setGenerationType("NEW_TEST");
        ut2.setSourceCode(header + """
            package example;
            class TestPing {
                void test() { new OrderService().ping(); }
            }
            """);
        when(unitTestRepo.findByTestCaseId(5001L)).thenReturn(ut1);
        when(unitTestRepo.findByTestCaseId(5002L)).thenReturn(ut2);
        when(unitTestRepo.findByTestCaseIdIn(any())).thenReturn(List.of(ut1, ut2));

        // affectedTestCaseIds chứa 5001L do dependency của processOrder bị thay đổi
        ImpactSummaryDto impact = new ImpactSummaryDto(
                0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(),
                List.of(5001L), List.of());

        SourceUpdate update = new SourceUpdate();
        update.setId(51L);
        update.setProjectId(projectId);

        java.lang.reflect.Method staleMethod = IncrementalGenerationService.class.getDeclaredMethod(
                "staleSourceCaseIds", Long.class, SourceUpdate.class, ImpactSummaryDto.class, Path.class);
        staleMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<Long> staleIds = (Set<Long>) staleMethod.invoke(service, projectId, update, impact, tempDir);

        assertThat(staleIds).contains(5001L);
        assertThat(staleIds).doesNotContain(5002L);
    }

    /**
     * Kịch bản 4: Project dùng WebClient / FeignClient không có analysis classpath.
     * Mock collaborator pass output dependency verification mà không bị coi là unresolved call.
     */
    @Test
    @DisplayName("Scenario 4: Mocked collaborator dùng WebClient không có classpath -> reusable = true")
    void scenario4_webClientOrFeignMockWithoutClasspath_passesOutputDependencyVerification() throws Exception {
        Path mainDir = tempDir.resolve("src/main/java/com/hospital/appointment/service");
        Files.createDirectories(mainDir);

        // Production classes
        Files.writeString(mainDir.resolve("DoctorServiceClient.java"), """
            package com.hospital.appointment.service;
            import org.springframework.web.reactive.function.client.WebClient;
            public class DoctorServiceClient {
                private WebClient webClient;
                public String getDoctor(Long id) {
                    return webClient.get().uri("/doctors/{id}", id).retrieve().bodyToMono(String.class).block();
                }
            }
            """);

        Files.writeString(mainDir.resolve("AppointmentService.java"), """
            package com.hospital.appointment.service;
            public class AppointmentService {
                private DoctorServiceClient doctorServiceClient;
                public String schedule(Long doctorId) {
                    return doctorServiceClient.getDoctor(doctorId);
                }
            }
            """);

        // Unit Test file sử dụng Mockito mock DoctorServiceClient
        String testSource = """
            package com.hospital.appointment.service;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.extension.ExtendWith;
            import org.mockito.InjectMocks;
            import org.mockito.Mock;
            import org.mockito.junit.jupiter.MockitoExtension;
            import static org.mockito.Mockito.when;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            @ExtendWith(MockitoExtension.class)
            class AppointmentServiceTest {
                @Mock
                private DoctorServiceClient doctorServiceClient;
                @InjectMocks
                private AppointmentService appointmentService;

                @Test
                void testSchedule() {
                    when(doctorServiceClient.getDoctor(1L)).thenReturn("Dr. Strange");
                    String result = appointmentService.schedule(1L);
                    assertEquals("Dr. Strange", result);
                }
            }
            """;

        var response = Map.of("unit_tests", List.of(Map.of("source_code", testSource)));
        try (var scope = GenerationJobContext.openScope(null)) {
            var outputFp = fingerprintService.outputFingerprint(response, tempDir);
            assertThat(outputFp.reusable())
                    .as("Mock collaborator calling WebClient internally must be reusable: %s", outputFp.provenance())
                    .isTrue();
        }
    }

    /**
     * Kịch bản 5: Unit test gọi method không tồn tại ở production.
     * Output fingerprint not reusable, checkpoint được đánh dấu BLOCKED và không được ghi SUCCEEDED.
     */
    @Test
    @DisplayName("Scenario 5: Unit test gọi method không tồn tại -> BLOCKED và không SUCCEEDED")
    void scenario5_unitTestCallingNonExistentProductionMethod_isBlocked() throws Exception {
        Path mainDir = tempDir.resolve("src/main/java/com/hospital/appointment/service");
        Files.createDirectories(mainDir);
        Files.writeString(mainDir.resolve("AppointmentService.java"), """
            package com.hospital.appointment.service;
            public class AppointmentService {
                public String validMethod() { return "ok"; }
            }
            """);

        String invalidTestSource = """
            package com.hospital.appointment.service;
            import org.junit.jupiter.api.Test;
            class AppointmentServiceTest {
                @Test
                void testInvalid() {
                    AppointmentService service = new AppointmentService();
                    service.thisMethodDoesNotExistOnProduction(123);
                }
            }
            """;

        var response = Map.of("unit_tests", List.of(Map.of("source_code", invalidTestSource)));
        try (var scope = GenerationJobContext.openScope(null)) {
            var outputFp = fingerprintService.outputFingerprint(response, tempDir);
            assertThat(outputFp.reusable()).isFalse();
            assertThat(outputFp.provenance()).contains("thisMethodDoesNotExistOnProduction");
        }
    }

    /**
     * Kịch bản 6: Resume sau khi BLOCKED.
     * Xác minh lại draft trong DB mà không gọi LLM nếu output hiện tại hợp lệ.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"same", "resolution-upgrade", "changed-oracle"})
    @DisplayName("Scenario 6: Resume draft khi input không đổi; oracle đổi phải sinh lại")
    void scenario6_resumeAfterBlocked_revalidatesDraftFromDbWithoutLlmCalls(String mode) throws Exception {
        GenerationCheckpointStore store = mock(GenerationCheckpointStore.class);
        GenerationFingerprintService mockFingerprints = mock(GenerationFingerprintService.class);
        SourceUpdateItemRepository items = mock(SourceUpdateItemRepository.class);
        AIAgentService agent = mock(AIAgentService.class);
        GenerationJobService jobs = mock(GenerationJobService.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);

        when(jobs.executeMutation(eq(1L), any(Supplier.class))).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        when(store.claim(eq(1L), eq(2L), any(Duration.class))).thenReturn(new GenerationCheckpointStore.Lease(1L, 2L, "owner", 1L));
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(agent.snapshotContext(any(), anyLong(), anySet(), any())).thenReturn(Map.of("source", "current"));

        String oldProvenance = mapper.writeValueAsString(Map.of("inputs", Map.of("expected", "original"),
                "sourceDependencies", Map.of("Subject#value()", "body-hash", "resolved-call:old", "external-or-source"),
                "problems", List.of("unknown library"), "reusable", false));
        String newProvenance = mapper.writeValueAsString(Map.of("inputs", Map.of("expected", mode.equals("changed-oracle") ? "changed" : "original"),
                "sourceDependencies", Map.of("Subject#value()", "body-hash", "resolved-call:new", "external-or-source"),
                "problems", List.of(), "reusable", true));
        when(mockFingerprints.fingerprint(anyString(), any(), any()))
                .thenReturn(new GenerationFingerprintService.Fingerprint("fp-123", newProvenance, true));
        when(mockFingerprints.outputFingerprint(any(), any(), any()))
                .thenReturn(new GenerationFingerprintService.Fingerprint("out-fp-456", "{}", true));

        Long updateId = 2L;
        Long targetCaseId = 3851L;

        Path mainDir = tempDir.resolve("src/main/java/example");
        Files.createDirectories(mainDir);
        Files.writeString(mainDir.resolve("Subject.java"), """
            package example;
            public class Subject {
                public int getValue() { return 42; }
            }
            """);

        String validTestSource = """
            package example;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class SubjectTest {
                @Test void testValue() { assertEquals(42, new Subject().getValue()); }
            }
            """;

        GeneratedUnitTestDto unitDto = new GeneratedUnitTestDto(
                targetCaseId, "SubjectTest", "testValue", "example", "NEW_TEST", validTestSource);
        UnitTestResponseDto responseDto = new UnitTestResponseDto(List.of(unitDto));

        when(agent.parseSnapshotOutput(eq(GenerationProgressStage.UNIT_TEST), anyString())).thenReturn(responseDto);

        IncrementalCheckpointCoordinator coordinator = new IncrementalCheckpointCoordinator(
                store, mockFingerprints, items, agent, mapper, tx, jobs);

        SourceUpdateItem draftItem = new SourceUpdateItem();
        draftItem.setId(99L);
        draftItem.setSourceUpdateId(updateId);
        draftItem.setTargetType(SourceUpdateTargetType.UNIT_TEST);
        draftItem.setTargetId(targetCaseId);
        draftItem.setUserRevision(1L);
        draftItem.setAfterData(mapper.writeValueAsString(unitDto));

        when(items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId)).thenReturn(List.of(draftItem));

        // Checkpoint ban đầu là BLOCKED
        String blockedEnvelope = mapper.writeValueAsString(Map.of(
                "itemIds", List.of(99L),
                "revisions", Map.of("99", 0L),
                "response", responseDto,
                "outputFingerprint", "old-unresolved"
        ));
        GenerationCheckpointStore.Checkpoint blockedCheckpoint = new GenerationCheckpointStore.Checkpoint(
                "UNIT_TEST", targetCaseId.toString(), mode.equals("same") ? "fp-123" : "old-fp", oldProvenance, blockedEnvelope, "some-digest", "BLOCKED");

        when(store.find(updateId, "UNIT_TEST", targetCaseId.toString())).thenReturn(Optional.of(blockedCheckpoint));

        AtomicInteger llmCalls = new AtomicInteger(0);
        Function<Set<Long>, UnitTestResponseDto> generator = ids -> {
            llmCalls.incrementAndGet();
            return responseDto;
        };
        BiConsumer<Set<Long>, UnitTestResponseDto> persistence = (ids, res) -> {};

        // Chạy executeBatch trong lease run
        coordinator.withRun(1L, updateId, 1L, () -> {
            coordinator.executeBatch(1L, updateId, GenerationProgressStage.UNIT_TEST,
                    Set.of(targetCaseId), tempDir, UnitTestResponseDto.class, generator, persistence);
            return null;
        });

        // Khẳng định: LLM KHÔNG được gọi (0 calls)
        assertThat(llmCalls.get()).isEqualTo(mode.equals("changed-oracle") ? 1 : 0);
        // Checkpoint được hoàn tất thành công (SUCCEEDED)
        verify(store).complete(any(), eq("UNIT_TEST"), eq(targetCaseId.toString()), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * Kịch bản 7: Apply/Export -> provenance mới khớp với source và giữ nguyên audit trail.
     */
    @Test
    @DisplayName("Scenario 7: Apply/Export -> provenance mới khớp với source và giữ nguyên audit trail")
    void scenario7_exportAndApply_preservesProvenanceAndAuditTrail() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AIAgentService agent = mock(AIAgentService.class);
        UnitTestExportProvenanceService service = new UnitTestExportProvenanceService(
                jdbc, agent, fingerprintService, mapper);

        Path source = tempDir.resolve("Service.java");
        Files.writeString(source, "class Service { int value() { return 100; } }");
        String currentHash = GenerationSourceFingerprint.hash(Files.readString(source));

        // Unit test có metadata khớp với hash hiện tại
        String header = sourceHeader("Service.java", currentHash);
        UnitTest unit = new UnitTest();
        unit.setId(10L);
        unit.setTestCaseId(20L);
        unit.setTestClassName("ServiceTest");
        unit.setTestMethodName("testValue");
        unit.setSourceCode(header + "class ServiceTest { void testValue() {} }");

        try (var scope = GenerationJobContext.openScope(1L)) {
            GenerationJobContext.setSourceUpdateId(88L);
            List<UnitTest> prepared = service.prepare(1L, List.of(unit), tempDir);

            // Xuất ra vẫn giữ nguyên unit test entity và source metadata
            assertThat(prepared).containsExactly(unit);
            assertThat(unit.getSourceCode()).contains(currentHash);
            assertThat(unit.getSourceCode()).contains(GenerationSourceFingerprint.PREFIX);
        }
    }
}
