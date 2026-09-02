package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.AnalysisManifestDto;
import com.greytest.dto.AnalysisResultDto;
import com.greytest.dto.ControllerServiceRelationDto;
import com.greytest.dto.EndpointDto;
import com.greytest.dto.ExistingTestDto;
import com.greytest.dto.JavaClassDto;
import com.greytest.dto.JavaMethodDto;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.RelevantAnnotationDto;
import com.greytest.dto.ServiceRelationDto;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleGenerationContextDto;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleReviewContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextDto;
import com.greytest.dto.CoverageGapDto;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.TestPlanCoveredRule;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.Priority;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.TestType;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanCoveredRuleRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.analysis.AnalysisManifestService;
import com.greytest.service.analysis.AnalysisService;
import com.greytest.service.analysis.ExistingTestService;

class GenerationContextBuilderTest {

    private final AnalysisService analysisService = mock(AnalysisService.class);
    private final AnalysisManifestService manifestService = mock(AnalysisManifestService.class);
    private final ExistingTestService existingTestService = mock(ExistingTestService.class);
    private final BusinessRuleRepository businessRuleRepository = mock(BusinessRuleRepository.class);
    private final TestPlanRepository testPlanRepository = mock(TestPlanRepository.class);
    private final TestPlanCoveredRuleRepository testPlanCoveredRuleRepository = mock(TestPlanCoveredRuleRepository.class);
    private final TestCaseRepository testCaseRepository = mock(TestCaseRepository.class);
    private final UnitTestRepository unitTestRepository = mock(UnitTestRepository.class);
    private final GenerationContextBuilder builder = new GenerationContextBuilder(
            analysisService,
            manifestService,
            existingTestService,
            businessRuleRepository,
            testPlanRepository,
            testPlanCoveredRuleRepository,
            testCaseRepository,
            unitTestRepository);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsDeterministicBusinessRuleGenerationContext() throws Exception {
        mockCommonInputs();

        BusinessRuleGenerationContextDto context = builder.buildBusinessRuleGenerationContext(1L);
        String first = objectMapper.writeValueAsString(context);
        String second = objectMapper.writeValueAsString(builder.buildBusinessRuleGenerationContext(1L));

        assertThat(second).isEqualTo(first);
        assertThat(first.length()).isLessThan(10_000);
        assertThat(context.analysis().totalClasses()).isEqualTo(2);
        assertThat(context.existingTests()).isEmpty();
        assertThat(context.serviceRepositoryRelations()).extracting("serviceQualifiedName")
                .containsExactly("demo.UserService");
        assertThat(context.controllerServiceRelations()).isEmpty();
        assertThat(context.dependencyCalls()).singleElement().satisfies(call -> {
            assertThat(call.collaboratorName()).isEqualTo("repository");
            assertThat(call.collaboratorType()).isEqualTo("UserRepository");
            assertThat(call.calleeMethodName()).isEqualTo("save");
        });
        assertThat(context.classes()).extracting("qualifiedName").containsExactly("demo.UserService");
        assertThat(context.classes().get(0).methods()).extracting("methodName").containsExactly("updateUser");
    }

    @Test
    void sendsUpToThreeBusinessRuleMethodsPerRequest() {
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysisWithServiceMethods(21));
        when(manifestService.exportManifest(1L)).thenReturn(manifest());
        when(existingTestService.list(1L)).thenReturn(List.of());
        List<BusinessRule> coveredRules = LongStream.rangeClosed(1, 5)
                .mapToObj(id -> ruleEntity(id, id, ReviewStatus.APPROVED))
                .toList();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(), coveredRules);

        BusinessRuleGenerationContextDto first = builder.buildBusinessRuleGenerationContext(1L);
        BusinessRuleGenerationContextDto second = builder.buildBusinessRuleGenerationContext(1L);

        assertThat(first.classes().get(0).methods()).extracting("id")
                .containsExactly(1L, 2L, 3L);
        assertThat(second.classes().get(0).methods()).extracting("id")
                .containsExactly(6L, 7L, 8L);
    }
    @Test
    void resolvesClientCallAndEndpointForSelectedMethod() {
        mockCommonInputs();
        AnalysisResultDto base = analysis();
        JavaMethodDto updateMethod = new JavaMethodDto(
                12L, "updateUser", "User", List.of(), List.of(), "PUBLIC",
                "void updateUser() { this.statisticsClient.updateStatistics(); }",
                14, 16, List.of(), List.of());
        JavaClassDto service = new JavaClassDto(
                10L, "demo", "UserService", "demo.UserService", "SERVICE",
                "src/main/java/demo/UserService.java",
                "class UserService { private StatisticsServiceClient statisticsClient; }",
                List.of(), List.of(updateMethod));
        JavaMethodDto clientMethod = new JavaMethodDto(
                80L, "updateStatistics", "void", List.of(), List.of(), "PUBLIC",
                "void updateStatistics() {}",
                4, 5, List.of(),
                List.of(new EndpointDto(81L, "PUT", "/statistics/{accountName}", null, null, "updateStatistics")));
        JavaClassDto client = new JavaClassDto(
                80L, "demo.client", "StatisticsServiceClient",
                "demo.client.StatisticsServiceClient", "OTHER",
                "src/main/java/demo/client/StatisticsServiceClient.java",
                "interface StatisticsServiceClient { void updateStatistics(); }",
                List.of(), List.of(clientMethod));
        AnalysisResultDto enriched = new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(),
                base.totalClasses() + 1, base.totalMethods() + 1, base.totalEndpoints() + 1,
                base.totalRelations(), base.totalControllerServiceRelations(),
                base.existingTestFiles(), base.totalProductionFiles(), base.parsedProductionFiles(),
                base.failedParseFiles(), base.failedParseFilePaths(),
                List.of(base.classes().get(0), service, client),
                base.relations(), base.controllerServiceRelations());
        when(analysisService.getAnalysisResult(1L)).thenReturn(enriched);

        BusinessRuleGenerationContextDto context = builder.buildBusinessRuleGenerationContext(1L);

        assertThat(context.dependencyCalls()).singleElement().satisfies(call -> {
            assertThat(call.calleeQualifiedName()).isEqualTo("demo.client.StatisticsServiceClient");
            assertThat(call.calleeMethodName()).isEqualTo("updateStatistics");
            assertThat(call.httpMethod()).isEqualTo("PUT");
            assertThat(call.endpointPath()).isEqualTo("/statistics/{accountName}");
            assertThat(call.calleeServiceSourceCode()).isNull();
        });
    }

    @Test
    void includesResolvedServiceSourceForCrossServiceInvariant() {
        AnalysisResultDto base = analysis();
        JavaMethodDto orderMethod = new JavaMethodDto(
                90L, "placeOrder", "void", List.of(), List.of(), "PUBLIC",
                "void placeOrder() { try { paymentService.charge(); } catch (PaymentFailedException exception) { rollbackInventory(); throw exception; } }",
                10, 12, List.of(), List.of());
        JavaClassDto orderService = new JavaClassDto(
                90L, "demo.order", "OrderService", "demo.order.OrderService", "SERVICE",
                "order-service/src/main/java/demo/order/OrderService.java",
                "class OrderService { private PaymentService paymentService; }",
                List.of(), List.of(orderMethod));
        JavaMethodDto paymentMethod = new JavaMethodDto(
                91L, "charge", "void", List.of(), List.of("PaymentFailedException"), "PUBLIC",
                "void charge() { if (gateway.isRejected()) throw new PaymentFailedException(); }",
                8, 10, List.of(), List.of());
        JavaClassDto paymentService = new JavaClassDto(
                91L, "demo.payment", "PaymentService", "demo.payment.PaymentService", "SERVICE",
                "payment-service/src/main/java/demo/payment/PaymentService.java",
                "class PaymentService { void charge() {} }",
                List.of(), List.of(paymentMethod));
        AnalysisResultDto enriched = new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(),
                base.totalClasses() + 2, base.totalMethods() + 2, base.totalEndpoints(),
                base.totalRelations(), base.totalControllerServiceRelations(),
                base.existingTestFiles(), base.totalProductionFiles() + 2, base.parsedProductionFiles() + 2,
                base.failedParseFiles(), base.failedParseFilePaths(),
                List.of(base.classes().get(0), orderService, paymentService),
                base.relations(), base.controllerServiceRelations());
        when(analysisService.getAnalysisResult(1L)).thenReturn(enriched);

        BusinessRuleGenerationContextDto context =
                builder.buildBusinessRuleGenerationContext(1L, Set.of(90L));

        assertThat(context.dependencyCalls()).singleElement().satisfies(call -> {
            assertThat(call.calleeQualifiedName()).isEqualTo("demo.payment.PaymentService");
            assertThat(call.calleeMethodName()).isEqualTo("charge");
            assertThat(call.calleeServiceSourceCode()).contains("gateway.isRejected()")
                    .contains("PaymentFailedException");
        });

        JavaMethodDto longPaymentMethod = new JavaMethodDto(
                92L, "charge", "void", List.of(), List.of(), "PUBLIC",
                "void charge() { String payload = \"" + "x".repeat(4_100) + "\"; }",
                8, 10, List.of(), List.of());
        JavaClassDto longPaymentService = new JavaClassDto(
                92L, "demo.payment", "PaymentService", "demo.payment.PaymentService", "SERVICE",
                "payment-service/src/main/java/demo/payment/PaymentService.java",
                "class PaymentService { void charge() {} }",
                List.of(), List.of(longPaymentMethod));
        AnalysisResultDto withLongCalleeSource = new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(),
                base.totalClasses() + 2, base.totalMethods() + 2, base.totalEndpoints(),
                base.totalRelations(), base.totalControllerServiceRelations(),
                base.existingTestFiles(), base.totalProductionFiles() + 2, base.parsedProductionFiles() + 2,
                base.failedParseFiles(), base.failedParseFilePaths(),
                List.of(base.classes().get(0), orderService, longPaymentService),
                base.relations(), base.controllerServiceRelations());
        when(analysisService.getAnalysisResult(1L)).thenReturn(withLongCalleeSource);

        BusinessRuleGenerationContextDto longSourceContext =
                builder.buildBusinessRuleGenerationContext(1L, Set.of(90L));

        assertThat(longSourceContext.dependencyCalls()).singleElement()
                .satisfies(call -> assertThat(call.calleeServiceSourceCode()).isNull());

        JavaClassDto duplicatePaymentService = new JavaClassDto(
                93L, "demo.legacy", "PaymentService", "demo.legacy.PaymentService", "SERVICE",
                "legacy-service/src/main/java/demo/legacy/PaymentService.java",
                "class PaymentService { void charge() {} }",
                List.of(), List.of(paymentMethod));
        AnalysisResultDto withAmbiguousCallee = new AnalysisResultDto(
                base.projectId(), base.projectName(), base.status(),
                base.totalClasses() + 3, base.totalMethods() + 3, base.totalEndpoints(),
                base.totalRelations(), base.totalControllerServiceRelations(),
                base.existingTestFiles(), base.totalProductionFiles() + 3, base.parsedProductionFiles() + 3,
                base.failedParseFiles(), base.failedParseFilePaths(),
                List.of(base.classes().get(0), orderService, paymentService, duplicatePaymentService),
                base.relations(), base.controllerServiceRelations());
        when(analysisService.getAnalysisResult(1L)).thenReturn(withAmbiguousCallee);

        BusinessRuleGenerationContextDto ambiguousContext =
                builder.buildBusinessRuleGenerationContext(1L, Set.of(90L));

        assertThat(ambiguousContext.dependencyCalls()).singleElement().satisfies(call -> {
            assertThat(call.calleeQualifiedName()).isNull();
            assertThat(call.calleeServiceSourceCode()).isNull();
        });
    }

    @Test
    void reviewContextContainsOnlyDirtyRulesAndSameMethodReferences() {
        mockCommonInputs();
        BusinessRule dirty = ruleEntity(7L, 11L, ReviewStatus.PENDING_REVIEW);
        dirty.setIsModified(true);
        BusinessRule related = ruleEntity(8L, 11L, ReviewStatus.PENDING_REVIEW);
        BusinessRule unrelated = ruleEntity(9L, 12L, ReviewStatus.PENDING_REVIEW);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(dirty, related, unrelated));

        BusinessRuleReviewContextDto context = builder.buildBusinessRuleReviewContext(1L);

        assertThat(context.businessRules()).extracting("id").containsExactly(7L);
        assertThat(context.relatedBusinessRules()).extracting("id").containsExactly(8L);
        assertThat(context.classes().get(0).methods()).extracting("id").containsExactly(11L);
    }

    @Test
    void testPlanContextUsesOnlyApprovedRuleMethods() {
        mockCommonInputs();
        BusinessRule approved = ruleEntity(7L, 11L, ReviewStatus.APPROVED);
        approved.setReviewNote("SOURCE_BRANCH:IF-1-FALSE\nLegacy data.");
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED))
                .thenReturn(List.of(approved));

        TestPlanContextDto context = builder.buildTestPlanContext(1L);

        assertThat(context.approvedBusinessRules()).extracting("ruleCode").containsExactly("BR-001");
        assertThat(context.approvedBusinessRules().get(0).sourceBranchId()).isEqualTo("IF-1");
        assertThat(context.classes()).hasSize(1);
        assertThat(context.classes().get(0).methods()).extracting("id").containsExactly(11L);
    }

    @Test
    void testPlanContextUsesOnlyRequestedRules() {
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysisWithServiceMethods(7));
        when(manifestService.exportManifest(1L)).thenReturn(manifest());
        when(existingTestService.list(1L)).thenReturn(List.of());
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED))
                .thenReturn(LongStream.rangeClosed(1, 7)
                        .mapToObj(id -> ruleEntity(id, id, ReviewStatus.APPROVED))
                        .toList());

        TestPlanContextDto context = builder.buildTestPlanContext(1L, Set.of(1L, 2L, 3L, 4L, 5L, 6L));

        assertThat(context.approvedBusinessRules()).extracting("id")
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(context.classes().get(0).methods()).extracting("id")
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void testCaseContextUsesOnlyRequestedPlansAndRelevantExistingTests() {
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysis());
        BusinessRule firstRule = ruleEntity(7L, 11L, ReviewStatus.APPROVED);
        BusinessRule secondRule = ruleEntity(8L, 12L, ReviewStatus.APPROVED);
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED))
                .thenReturn(List.of(firstRule, secondRule));
        TestPlan firstPlan = testPlan(20L, 7L, "TP-001");
        TestPlan secondPlan = testPlan(21L, 8L, "TP-002");
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(firstPlan, secondPlan));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L, 21L)))
                .thenReturn(List.of(coveredRule(20L, 7L), coveredRule(21L, 8L)));
        ExistingTestDto unrelatedTest = new ExistingTestDto(
                51L, 1L, "src/test/java/demo/UserControllerTest.java", "demo", "UserControllerTest",
                30L, null, List.of(), List.of(), null, null);
        when(existingTestService.list(1L)).thenReturn(List.of(existingTest(), unrelatedTest));

        TestCaseContextDto context = builder.buildTestCaseContext(1L, Set.of(20L));

        assertThat(context.approvedTestPlans()).extracting("id").containsExactly(20L);
        assertThat(context.approvedBusinessRules()).extracting("id").containsExactly(7L);
        assertThat(context.classes()).extracting("className").containsExactly("UserService");
        assertThat(context.classes().get(0).methods()).extracting("id").containsExactly(11L);
        assertThat(context.existingTests()).extracting("testClassName").containsExactly("UserServiceTest");
    }

    @Test
    void unitTestContextIncludesApprovedArtifactsAndExistingTestSource() {
        mockCommonInputs();
        ExistingTestDto unrelatedTest = new ExistingTestDto(
                51L, 1L, "src/test/java/demo/UserControllerTest.java", "demo", "UserControllerTest",
                30L, null, List.of(), List.of(), "class UserControllerTest {}", null);
        when(existingTestService.list(1L)).thenReturn(List.of(existingTest(), unrelatedTest));
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED))
                .thenReturn(List.of(ruleEntity(7L, 11L, ReviewStatus.APPROVED)));
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        plan.setBusinessRuleId(7L);
        plan.setPlanCode("TP-001");
        plan.setTitle("Happy path");
        plan.setDescription("Input hop le");
        plan.setTestType(TestType.HAPPY_PATH);
        plan.setStatus(ReviewStatus.APPROVED);
        TestCase testCase = new TestCase();
        testCase.setId(30L);
        testCase.setTestPlanId(20L);
        testCase.setCaseCode("TC-001");
        testCase.setPriority(Priority.HIGH);
        testCase.setStatus(ReviewStatus.APPROVED);
        TestCase previousCase = new TestCase();
        previousCase.setId(29L);
        previousCase.setTestPlanId(20L);
        previousCase.setCaseCode("TC-000");
        previousCase.setPriority(Priority.HIGH);
        previousCase.setStatus(ReviewStatus.APPROVED);
        UnitTest previousUnit = new UnitTest();
        previousUnit.setTestCaseId(29L);
        previousUnit.setTestClassName("UserServiceTest");
        previousUnit.setTestMethodName("createUser_existing");
        previousUnit.setPackageName("demo");
        previousUnit.setFilePath("src/test/java/demo/UserServiceTest.java");
        previousUnit.setSourceCode("class UserServiceTest { void createUser_existing() {} }");
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(plan));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L)))
                .thenReturn(List.of(coveredRule(20L, 7L), coveredRule(20L, 8L)));
        when(testCaseRepository.findByTestPlanId(20L)).thenReturn(List.of(previousCase, testCase));
        when(unitTestRepository.findByTestCaseId(29L)).thenReturn(previousUnit);

        var context = builder.buildUnitTestContext(1L);

        assertThat(context.approvedTestPlans()).extracting("planCode").containsExactly("TP-001");
        assertThat(context.approvedTestPlans().get(0).coveredRuleIds()).containsExactly(7L, 8L);
        assertThat(context.approvedTestCases()).extracting("caseCode").containsExactly("TC-000", "TC-001");
        assertThat(context.existingTests()).extracting("sourceCode").containsExactly("class UserServiceTest {}");
        assertThat(context.classes().get(0).sourceCode()).contains("private UserRepository repository");

        var refinement = builder.buildUnitTestContext(1L, Set.of(30L));
        assertThat(refinement.approvedTestCases()).extracting("caseCode").containsExactly("TC-001");
        assertThat(refinement.existingApprovedTestCases()).extracting("caseCode").containsExactly("TC-000");
        assertThat(refinement.previousGeneratedUnitTests()).extracting("testMethodName")
                .containsExactly("createUser_existing");
    }

    @Test
    void unitTestBatchContextStaysBoundedForOneLargePlan() {
        mockCommonInputs();
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED))
                .thenReturn(List.of(ruleEntity(7L, 11L, ReviewStatus.APPROVED)));
        TestPlan plan = testPlan(20L, 7L, "TP-001");
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(plan));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L)))
                .thenReturn(List.of(coveredRule(20L, 7L)));
        List<TestCase> testCases = LongStream.rangeClosed(1, 31).mapToObj(id -> {
            TestCase testCase = new TestCase();
            testCase.setId(id);
            testCase.setTestPlanId(20L);
            testCase.setCaseCode("TC-" + id);
            testCase.setPriority(Priority.HIGH);
            testCase.setStatus(ReviewStatus.APPROVED);
            return testCase;
        }).toList();
        when(testCaseRepository.findByTestPlanId(20L)).thenReturn(testCases);
        ExistingTestDto largeExistingTest = new ExistingTestDto(
                50L, 1L, "src/test/java/demo/UserServiceTest.java", "demo", "UserServiceTest",
                10L, null, List.of(), List.of(), "x".repeat(10_000), null);
        when(existingTestService.list(1L)).thenReturn(List.of(largeExistingTest));

        var context = builder.buildUnitTestContext(1L, Set.of(31L));

        assertThat(context.approvedTestCases()).extracting("id").containsExactly(31L);
        assertThat(context.existingApprovedTestCases()).hasSize(10);
        assertThat(context.existingTests()).singleElement()
                .extracting("sourceCode").asString().hasSizeLessThan(5_000);
    }

    @Test
    void refinementContextContainsOnlyGapMethodsAndTheirPlans() {
        mockCommonInputs();
        BusinessRule approved = ruleEntity(7L, 11L, ReviewStatus.APPROVED);
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED))
                .thenReturn(List.of(approved));
        TestPlan plan = new TestPlan();
        plan.setId(20L);
        plan.setProjectId(1L);
        plan.setBusinessRuleId(7L);
        plan.setPlanCode("TP-001");
        plan.setStatus(ReviewStatus.APPROVED);
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(plan));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L)))
                .thenReturn(List.of(coveredRule(20L, 7L)));

        var gap = new CoverageGapDto(11L, "UserService", "createUser",
                java.math.BigDecimal.valueOf(40), java.math.BigDecimal.valueOf(50),
                List.of(11), List.of(11), "HIGH", "cover branch", true);
        var context = builder.buildCoverageRefinementContext(1L, 2, List.of(gap));

        assertThat(context.round()).isEqualTo(2);
        assertThat(context.coverageGaps()).containsExactly(gap);
        assertThat(context.classes().get(0).methods()).extracting("id").containsExactly(11L);
        assertThat(context.approvedTestPlans()).extracting("id").containsExactly(20L);
    }

    private void mockCommonInputs() {
        when(analysisService.getAnalysisResult(1L)).thenReturn(analysis());
        when(manifestService.exportManifest(1L)).thenReturn(manifest());
        when(existingTestService.list(1L)).thenReturn(List.of(existingTest()));
        when(businessRuleRepository.findByProjectId(1L))
                .thenReturn(List.of(ruleEntity(7L, 11L, ReviewStatus.APPROVED)));
    }

    private AnalysisResultDto analysis() {
        JavaMethodDto serviceMethod = new JavaMethodDto(
                11L,
                "createUser",
                "User",
                List.of(new MethodParamDto("email", "String")),
                List.of(),
                "PUBLIC",
                "User createUser(String email) { return null; }",
                10,
                12,
                List.of(new RelevantAnnotationDto(20L, "METHOD", "TRANSACTION", "Transactional", "@Transactional")),
                List.of());
        JavaClassDto service = new JavaClassDto(
                10L,
                "demo",
                "UserService",
                "demo.UserService",
                "SERVICE",
                "src/main/java/demo/UserService.java",
                "class UserService { private UserRepository repository; }",
                List.of(),
                List.of(serviceMethod, new JavaMethodDto(
                        12L,
                        "updateUser",
                        "User",
                        List.of(),
                        List.of(),
                        "PUBLIC",
                        "User updateUser() { String note = \"repository.fake(\"; /* repository.bad( */ return repository.save(new User()); }",
                        14,
                        16,
                        List.of(),
                        List.of())));
        JavaMethodDto controllerMethod = new JavaMethodDto(
                31L,
                "create",
                "User",
                List.of(),
                List.of(),
                "PUBLIC",
                "User create() { return null; }",
                3,
                5,
                List.of(),
                List.of(new EndpointDto(40L, "POST", "/users", null, null, "create")));
        JavaClassDto controller = new JavaClassDto(
                30L,
                "demo",
                "UserController",
                "demo.UserController",
                "CONTROLLER",
                "src/main/java/demo/UserController.java",
                null,
                List.of(),
                List.of(controllerMethod));
        return new AnalysisResultDto(
                1L,
                "demo",
                "ANALYZED",
                2,
                2,
                1,
                1,
                1,
                1,
                2,
                2,
                0,
                List.of(),
                List.of(controller, service),
                List.of(new ServiceRelationDto(
                        60L,
                        "UserService",
                        "demo.UserService",
                        "UserRepository",
                        "demo.UserRepository")),
                List.of(new ControllerServiceRelationDto(
                        70L,
                        "UserController",
                        "demo.UserController",
                        "create",
                        "UserService",
                        "demo.UserService",
                        "createUser",
                        "userService",
                        "UserService")));
    }

    private AnalysisResultDto analysisWithServiceMethods(int count) {
        List<JavaMethodDto> methods = LongStream.rangeClosed(1, count)
                .mapToObj(id -> new JavaMethodDto(
                        id, "method" + id, "void", List.of(), List.of(), "PUBLIC", "void method() {}",
                        1, 1, List.of(), List.of()))
                .toList();
        JavaClassDto service = new JavaClassDto(
                1L, "demo", "BatchService", "demo.BatchService", "SERVICE",
                "src/main/java/demo/BatchService.java", null, List.of(), methods);
        return new AnalysisResultDto(
                1L, "demo", "ANALYZED", 1, count, 0, 0, 0, 0, 1, 1, 0,
                List.of(), List.of(service), List.of(), List.of());
    }

    private AnalysisManifestDto manifest() {
        return new AnalysisManifestDto(
                1L,
                "demo",
                "1.1",
                List.of("demo.UserController", "demo.UserService"),
                List.of("demo.UserService#createUser(String):User"),
                List.of("POST /users -> demo.UserController#create():User"),
                List.of(),
                List.of("demo.UserService -> demo.UserRepository"),
                List.of("demo.UserController#create -> demo.UserService#createUser via userService"));
    }

    private ExistingTestDto existingTest() {
        return new ExistingTestDto(
                50L,
                1L,
                "src/test/java/demo/UserServiceTest.java",
                "demo",
                "UserServiceTest",
                10L,
                null,
                List.of(Map.of("name", "createUser_success", "assertions", List.of("assertEquals"))),
                List.of("org.junit.jupiter.api.Test"),
                "class UserServiceTest {}",
                null);
    }

    private BusinessRule ruleEntity(Long id, Long methodId, ReviewStatus status) {
        BusinessRule rule = new BusinessRule();
        rule.setId(id);
        rule.setProjectId(1L);
        rule.setMethodId(methodId);
        rule.setRuleCode("BR-001");
        rule.setDescription("Email phai hop le truoc khi tao user.");
        rule.setReviewNote("OK");
        rule.setSource(RuleSource.USER_ADDED);
        rule.setStatus(status);
        rule.setIsModified(false);
        return rule;
    }

    private TestPlan testPlan(Long id, Long ruleId, String code) {
        TestPlan plan = new TestPlan();
        plan.setId(id);
        plan.setProjectId(1L);
        plan.setBusinessRuleId(ruleId);
        plan.setPlanCode(code);
        plan.setTitle("Plan " + code);
        plan.setDescription("Description " + code);
        plan.setTestType(TestType.HAPPY_PATH);
        plan.setStatus(ReviewStatus.APPROVED);
        return plan;
    }

    private TestPlanCoveredRule coveredRule(Long planId, Long ruleId) {
        TestPlanCoveredRule link = new TestPlanCoveredRule();
        link.setTestPlanId(planId);
        link.setBusinessRuleId(ruleId);
        return link;
    }
}
