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
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextDto;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.TestPlanCoveredRule;
import com.greytest.entity.enums.Priority;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.TestType;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanCoveredRuleRepository;
import com.greytest.repository.TestPlanRepository;
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
    private final GenerationContextBuilder builder = new GenerationContextBuilder(
            analysisService,
            manifestService,
            existingTestService,
            businessRuleRepository,
            testPlanRepository,
            testPlanCoveredRuleRepository,
            testCaseRepository);
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
        assertThat(context.existingTests()).hasSize(1);
        assertThat(context.classes()).extracting("qualifiedName").containsExactly("demo.UserService");
        assertThat(context.classes().get(0).methods()).extracting("methodName").containsExactly("updateUser");
    }

    @Test
    void batchesBusinessRuleMethodsAcrossRequests() {
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
                .containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 5).boxed().toList());
        assertThat(second.classes().get(0).methods()).extracting("id")
                .containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(6, 10).boxed().toList());
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
        when(businessRuleRepository.findByProjectIdAndStatus(1L, ReviewStatus.APPROVED))
                .thenReturn(List.of(approved));

        TestPlanContextDto context = builder.buildTestPlanContext(1L);

        assertThat(context.approvedBusinessRules()).extracting("ruleCode").containsExactly("BR-001");
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
    void unitTestContextIncludesApprovedArtifactsAndExistingTestSource() {
        mockCommonInputs();
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
        when(testPlanRepository.findByProjectId(1L)).thenReturn(List.of(plan));
        when(testPlanCoveredRuleRepository.findByTestPlanIdIn(List.of(20L)))
                .thenReturn(List.of(coveredRule(20L, 7L), coveredRule(20L, 8L)));
        when(testCaseRepository.findByTestPlanId(20L)).thenReturn(List.of(testCase));

        var context = builder.buildUnitTestContext(1L);

        assertThat(context.approvedTestPlans()).extracting("planCode").containsExactly("TP-001");
        assertThat(context.approvedTestPlans().get(0).coveredRuleIds()).containsExactly(7L, 8L);
        assertThat(context.approvedTestCases()).extracting("caseCode").containsExactly("TC-001");
        assertThat(context.existingTests()).extracting("sourceCode").containsExactly("class UserServiceTest {}");
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
                List.of(),
                List.of(serviceMethod, new JavaMethodDto(
                        12L,
                        "updateUser",
                        "User",
                        List.of(),
                        List.of(),
                        "PUBLIC",
                        "User updateUser() { return null; }",
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
                "src/main/java/demo/BatchService.java", List.of(), methods);
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

    private TestPlanCoveredRule coveredRule(Long planId, Long ruleId) {
        TestPlanCoveredRule link = new TestPlanCoveredRule();
        link.setTestPlanId(planId);
        link.setBusinessRuleId(ruleId);
        return link;
    }
}
