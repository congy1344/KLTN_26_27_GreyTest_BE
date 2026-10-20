package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.SourceUpdateDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedBusinessRuleDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.Project;
import com.greytest.entity.SourceUpdate;
import com.greytest.entity.SourceUpdateItem;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.SourceType;
import com.greytest.entity.enums.SourceUpdateStatus;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.mapper.SourceUpdateMapper;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.SourceUpdateItemRepository;
import com.greytest.repository.SourceUpdateRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.agent.AIAgentService;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.TestCase;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.SourceUpdateAction;
import com.greytest.entity.enums.SourceUpdateReviewStatus;
import com.greytest.entity.enums.SourceUpdateTargetType;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class IncrementalGenerationServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SourceUpdateRepository sourceUpdateRepository;
    @Mock
    private SourceUpdateItemRepository sourceUpdateItemRepository;
    @Mock
    private JavaClassRepository javaClassRepository;
    @Mock
    private JavaMethodRepository javaMethodRepository;
    @Mock
    private UnitTestRepository unitTestRepository;
    @Mock
    private SourceUpdateService sourceUpdateService;
    @Mock
    private AIAgentService aiAgentService;
    @Mock private BusinessRuleRepository businessRuleRepository;
    @Mock private TestPlanRepository testPlanRepository;
    @Mock private TestCaseRepository testCaseRepository;

    private IncrementalGenerationService service;
    private final SourceUpdateMapper mapper = new SourceUpdateMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new IncrementalGenerationService(
                projectRepository,
                sourceUpdateRepository,
                sourceUpdateItemRepository,
                javaClassRepository,
                javaMethodRepository,
                unitTestRepository,
                sourceUpdateService,
                aiAgentService,
                mapper,
                objectMapper
        );
    }

    @Test
    void generatesBusinessRulesIncrementallyForTargetMethods() {
        AuthUser user = new AuthUser();
        user.setId(1L);
        user.setRole(UserRole.USER);

        Project project = new Project();
        project.setId(10L);
        project.setOwnerUserId(1L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        String impactJson = """
                {
                    "totalChangedMethods": 1,
                    "changedMethods": [
                        {
                            "className": "OrderService",
                            "qualifiedClassName": "com.example.OrderService",
                            "methodName": "calculateDiscount",
                            "signature": "calculateDiscount(int)",
                            "methodKey": "com.example.OrderService#calculateDiscount(int)",
                            "diffType": "MODIFIED",
                            "reason": "Logic changed",
                            "isServiceMethod": true
                        }
                    ],
                    "affectedServiceMethods": ["com.example.OrderService#calculateDiscount(int)"],
                    "affectedBusinessRuleIds": [],
                    "affectedTestPlanIds": [],
                    "affectedTestCaseIds": [],
                    "affectedUnitTestIds": []
                }
                """;

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setStatus(SourceUpdateStatus.ANALYZED);
        update.setImpactSummary(impactJson);
        when(sourceUpdateRepository.findByIdAndProjectIdForUpdate(200L, 10L)).thenReturn(Optional.of(update));
        when(sourceUpdateRepository.save(any(SourceUpdate.class))).thenAnswer(inv -> inv.getArgument(0));

        JavaClass jc = new JavaClass();
        jc.setId(50L);
        jc.setClassName("OrderService");
        jc.setQualifiedName("com.example.OrderService");
        when(javaClassRepository.findByProjectId(10L)).thenReturn(List.of(jc));

        JavaMethod jm = new JavaMethod();
        jm.setId(101L);
        jm.setMethodName("calculateDiscount");
        jm.setParameters(List.of(new com.greytest.entity.MethodParam("amount", "int")));
        when(javaMethodRepository.findByClassId(50L)).thenReturn(List.of(jm));

        GeneratedBusinessRuleDto ruleDto = new GeneratedBusinessRuleDto(
                101L, "Rule for discount", "BUSINESS_LOGIC"
        );
        BusinessRuleResponseDto ruleResponse = new BusinessRuleResponseDto(List.of(ruleDto));
        when(aiAgentService.generateBusinessRules(eq(10L), eq(Set.of(101L)))).thenReturn(ruleResponse);

        SourceUpdateDto result = service.generateIncrementalStage(10L, 200L, GenerationProgressStage.BUSINESS_RULE, user);

        assertThat(result.status()).isEqualTo(SourceUpdateStatus.READY_TO_APPLY);
        verify(aiAgentService).generateBusinessRules(10L, Set.of(101L));
        verify(sourceUpdateItemRepository).save(any(SourceUpdateItem.class));
    }

    @Test
    void allowsNextGenerationStageAfterFirstStage() {
        AuthUser user = new AuthUser();
        user.setId(1L);
        user.setRole(UserRole.USER);

        Project project = new Project();
        project.setId(10L);
        project.setOwnerUserId(1L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setStatus(SourceUpdateStatus.READY_TO_APPLY);
        update.setImpactSummary("{\"affectedBusinessRuleIds\":[]}");
        when(sourceUpdateRepository.findByIdAndProjectIdForUpdate(200L, 10L)).thenReturn(Optional.of(update));
        when(sourceUpdateRepository.save(any(SourceUpdate.class))).thenAnswer(inv -> inv.getArgument(0));

        SourceUpdateDto result = service.generateIncrementalStage(
                10L, 200L, GenerationProgressStage.TEST_PLAN, user);

        assertThat(result.status()).isEqualTo(SourceUpdateStatus.READY_TO_APPLY);
    }

    @Test
    void marksExistingUnitTestAsUpdateInsteadOfCreatingDuplicate() {
        AuthUser user = new AuthUser();
        user.setId(1L);
        user.setRole(UserRole.USER);

        Project project = new Project();
        project.setId(10L);
        project.setOwnerUserId(1L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setStatus(SourceUpdateStatus.ANALYZED);
        update.setImpactSummary("{\"affectedTestCaseIds\":[401]}");
        when(sourceUpdateRepository.findByIdAndProjectIdForUpdate(200L, 10L)).thenReturn(Optional.of(update));
        when(sourceUpdateRepository.save(any(SourceUpdate.class))).thenAnswer(inv -> inv.getArgument(0));

        UnitTest existing = new UnitTest();
        existing.setId(501L);
        existing.setTestCaseId(401L);
        when(unitTestRepository.findByTestCaseId(401L)).thenReturn(existing);

        GeneratedUnitTestDto generated = new GeneratedUnitTestDto(
                401L, "OrderServiceTest", "calculatesDiscount", "com.example",
                "IMPROVE_EXISTING_TEST", "package com.example; class OrderServiceTest {}");
        java.nio.file.Path candidate = java.nio.file.Path.of("candidate-snapshot");
        when(sourceUpdateService.getCandidateSourcePath(update)).thenReturn(candidate);
        when(aiAgentService.generateSnapshotUnitTests(10L, Set.of(401L), candidate))
                .thenReturn(new UnitTestResponseDto(List.of(generated)));

        service.generateIncrementalStage(10L, 200L, GenerationProgressStage.UNIT_TEST, user);

        verify(aiAgentService).generateSnapshotUnitTests(10L, Set.of(401L), candidate);
        var itemCaptor = org.mockito.ArgumentCaptor.forClass(SourceUpdateItem.class);
        verify(sourceUpdateItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getAction()).isEqualTo(
                com.greytest.entity.enums.SourceUpdateAction.UPDATE);
        assertThat(itemCaptor.getValue().getTargetId()).isEqualTo(501L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = RuleSource.class, names = {"USER_ADDED", "USER_MODIFIED", "AI_GENERATED"})
    void generatedBusinessRuleChangeRemainsPendingAndReusesAnalysisProposal(RuleSource source) {
        useArtifactRepositories();
        AuthUser user = prepareUpdate("""
                {"changedMethods":[{"className":"OrderService","qualifiedClassName":"com.example.OrderService",
                "methodName":"transfer","signature":"transfer()","diffType":"MODIFIED"}]}
                """);
        JavaClass jc = new JavaClass();
        jc.setId(50L);
        jc.setQualifiedName("com.example.OrderService");
        JavaMethod method = new JavaMethod();
        method.setId(101L);
        method.setMethodName("transfer");
        when(javaClassRepository.findByProjectId(10L)).thenReturn(List.of(jc));
        when(javaMethodRepository.findByClassId(50L)).thenReturn(List.of(method));
        BusinessRule rule = new BusinessRule();
        rule.setId(201L);
        rule.setMethodId(101L);
        rule.setDescription("Allow balance >= amount");
        rule.setSource(source);
        rule.setStatus(ReviewStatus.APPROVED);
        when(businessRuleRepository.findByProjectId(10L)).thenReturn(List.of(rule));
        SourceUpdateItem pending = proposal(SourceUpdateTargetType.BUSINESS_RULE, 201L);
        pending.setBeforeData("original snapshot");
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L)).thenReturn(List.of(pending));
        when(aiAgentService.generateBusinessRules(10L, Set.of(101L)))
                .thenReturn(new BusinessRuleResponseDto(List.of(new GeneratedBusinessRuleDto(101L, "Allow balance > amount", "BUSINESS_LOGIC"))));

        service.generateIncrementalStage(10L, 200L, GenerationProgressStage.BUSINESS_RULE, user);

        verify(sourceUpdateItemRepository).save(pending);
        assertThat(pending.getReviewStatus()).isEqualTo(SourceUpdateReviewStatus.PENDING);
        assertThat(pending.getAfterData()).contains("Allow balance > amount");
        assertThat(pending.getBeforeData()).isEqualTo("original snapshot");
        assertThat(rule.getDescription()).isEqualTo("Allow balance >= amount");
        org.mockito.Mockito.verify(businessRuleRepository, org.mockito.Mockito.never()).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void eachDecisionKeepsItsOwnProposalWithoutOverwritingUnanchoredUserRule(boolean anchored) {
        useArtifactRepositories();
        AuthUser user = prepareUpdate("""
                {"changedMethods":[{"className":"OrderService","qualifiedClassName":"com.example.OrderService",
                "methodName":"transfer","signature":"transfer()","diffType":"MODIFIED"}]}
                """);
        JavaClass jc = new JavaClass();
        jc.setId(50L);
        jc.setQualifiedName("com.example.OrderService");
        JavaMethod method = new JavaMethod();
        method.setId(101L);
        method.setMethodName("transfer");
        when(javaClassRepository.findByProjectId(10L)).thenReturn(List.of(jc));
        when(javaMethodRepository.findByClassId(50L)).thenReturn(List.of(method));
        BusinessRule first = new BusinessRule();
        first.setId(201L);
        first.setMethodId(101L);
        first.setStatus(ReviewStatus.APPROVED);
        first.setSource(RuleSource.USER_ADDED);
        first.setDescription("User oracle");
        if (anchored) first.setReviewNote("SOURCE_BRANCH:IF-1\nOriginal note");
        BusinessRule second = new BusinessRule();
        second.setId(202L);
        second.setMethodId(101L);
        second.setStatus(ReviewStatus.APPROVED);
        second.setReviewNote("SOURCE_BRANCH:IF-2");
        when(businessRuleRepository.findByProjectId(10L)).thenReturn(anchored ? List.of(first, second) : List.of(first));
        SourceUpdateItem firstProposal = proposal(SourceUpdateTargetType.BUSINESS_RULE, 201L);
        SourceUpdateItem secondProposal = proposal(SourceUpdateTargetType.BUSINESS_RULE, 202L);
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L))
                .thenReturn(List.of(firstProposal, secondProposal));
        when(aiAgentService.generateBusinessRules(10L, Set.of(101L)))
                .thenReturn(new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(101L, "First decision", "BUSINESS_LOGIC", "IF-1::TRUE"),
                        new GeneratedBusinessRuleDto(101L, "Second decision", "BUSINESS_LOGIC", "IF-2"))));
        if (!anchored) when(businessRuleRepository.save(any())).thenAnswer(inv -> {
            BusinessRule created = inv.getArgument(0);
            created.setId("First decision".equals(created.getDescription()) ? 203L : 204L);
            return created;
        });

        service.generateIncrementalStage(10L, 200L, GenerationProgressStage.BUSINESS_RULE, user);

        if (anchored) {
            verify(sourceUpdateItemRepository).save(firstProposal);
            verify(sourceUpdateItemRepository).save(secondProposal);
            assertThat(firstProposal.getAfterData()).contains("First decision").doesNotContain("Second decision");
            assertThat(secondProposal.getAfterData()).contains("Second decision");
            org.mockito.Mockito.verify(businessRuleRepository, org.mockito.Mockito.never()).save(any());
        } else {
            var captor = org.mockito.ArgumentCaptor.forClass(SourceUpdateItem.class);
            verify(sourceUpdateItemRepository, org.mockito.Mockito.times(2)).save(captor.capture());
            assertThat(captor.getAllValues()).extracting(SourceUpdateItem::getTargetId).containsExactly(203L, 204L);
            assertThat(captor.getAllValues()).allSatisfy(item -> {
                assertThat(item.getAction()).isEqualTo(SourceUpdateAction.CREATE);
                assertThat(item.getReviewStatus()).isEqualTo(SourceUpdateReviewStatus.ACCEPTED);
            });
            assertThat(first.getDescription()).isEqualTo("User oracle");
            assertThat(firstProposal.getAfterData()).isNull();
            assertThat(firstProposal.getReviewStatus()).isEqualTo(SourceUpdateReviewStatus.PENDING);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = SourceUpdateReviewStatus.class, names = {"MODIFIED", "REJECTED"})
    void regenerationPreservesUserDecisionAndCoalescesStaleDuplicate(SourceUpdateReviewStatus decision) {
        useArtifactRepositories();
        AuthUser user = prepareUpdate("{\"affectedTestPlanIds\":[301]}");
        TestCase original = new TestCase();
        original.setId(401L);
        original.setStatus(ReviewStatus.APPROVED);
        original.setExpectedResult("Transfer succeeds");
        when(testCaseRepository.findByTestPlanId(301L)).thenReturn(List.of(original));
        SourceUpdateItem userProposal = proposal(SourceUpdateTargetType.TEST_CASE, 401L);
        userProposal.setReviewStatus(decision);
        userProposal.setAfterData("User-approved edited content");
        SourceUpdateItem staleDuplicate = proposal(SourceUpdateTargetType.TEST_CASE, 401L);
        staleDuplicate.setId(602L);
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L))
                .thenReturn(List.of(staleDuplicate, userProposal));
        when(aiAgentService.generateTestCases(10L, Set.of(301L))).thenReturn(new TestCaseResponseDto(List.of(
                new GeneratedTestCaseDto(301L, "BOUNDARY", "Equality", "Valid account", Map.of(),
                        "Transfer rejected", "HIGH", "BR-001 -> TP-001"))));

        service.generateIncrementalStage(10L, 200L, GenerationProgressStage.TEST_CASE, user);

        assertThat(userProposal.getReviewStatus()).isEqualTo(decision);
        assertThat(userProposal.getAfterData()).isEqualTo("User-approved edited content");
        assertThat(original.getExpectedResult()).isEqualTo("Transfer succeeds");
        verify(sourceUpdateItemRepository).delete(staleDuplicate);
        org.mockito.Mockito.verify(sourceUpdateItemRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void generatedCaseOracleChangeRemainsPendingWithoutChangingPersistedInputOrExpectedResult() {
        useArtifactRepositories();
        AuthUser user = prepareUpdate("{\"affectedTestPlanIds\":[301]}");
        TestCase original = new TestCase();
        original.setId(401L);
        original.setTestPlanId(301L);
        original.setStatus(ReviewStatus.APPROVED);
        original.setPreconditions("Valid account");
        original.setTestData(Map.of("balance", 100, "amount", 100));
        original.setExpectedResult("Transfer succeeds");
        when(testCaseRepository.findByTestPlanId(301L)).thenReturn(List.of(original));
        SourceUpdateItem pending = proposal(SourceUpdateTargetType.TEST_CASE, 401L);
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L)).thenReturn(List.of(pending));
        GeneratedTestCaseDto generated = new GeneratedTestCaseDto(301L, "BOUNDARY", "Equality", "Valid account",
                Map.of("balance", 100, "amount", 100), "Transfer rejected", "HIGH", "BR-001 -> TP-001");
        when(aiAgentService.generateTestCases(10L, Set.of(301L))).thenReturn(new TestCaseResponseDto(List.of(generated)));

        service.generateIncrementalStage(10L, 200L, GenerationProgressStage.TEST_CASE, user);

        verify(sourceUpdateItemRepository).save(pending);
        assertThat(pending.getReviewStatus()).isEqualTo(SourceUpdateReviewStatus.PENDING);
        assertThat(original.getExpectedResult()).isEqualTo("Transfer succeeds");
        assertThat(original.getTestData()).containsEntry("balance", 100).containsEntry("amount", 100);
        org.mockito.Mockito.verify(testCaseRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verify(sourceUpdateItemRepository, org.mockito.Mockito.never()).delete(any());
    }

    @Test
    void approvedCaseRemovalAlsoRequiresReviewAndCoalescesAnalysisItem() {
        useArtifactRepositories();
        AuthUser user = prepareUpdate("{\"affectedTestPlanIds\":[301]}");
        TestCase original = new TestCase();
        original.setId(401L);
        original.setStatus(ReviewStatus.APPROVED);
        when(testCaseRepository.findByTestPlanId(301L)).thenReturn(List.of(original));
        SourceUpdateItem pending = proposal(SourceUpdateTargetType.TEST_CASE, 401L);
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L)).thenReturn(List.of(pending));
        when(aiAgentService.generateTestCases(10L, Set.of(301L))).thenReturn(new TestCaseResponseDto(List.of()));

        service.generateIncrementalStage(10L, 200L, GenerationProgressStage.TEST_CASE, user);

        verify(sourceUpdateItemRepository).save(pending);
        assertThat(pending.getAction()).isEqualTo(SourceUpdateAction.REMOVE);
        assertThat(pending.getReviewStatus()).isEqualTo(SourceUpdateReviewStatus.PENDING);
    }

    private void useArtifactRepositories() {
        service = new IncrementalGenerationService(projectRepository, sourceUpdateRepository, sourceUpdateItemRepository,
                javaClassRepository, javaMethodRepository, unitTestRepository, businessRuleRepository, testPlanRepository,
                testCaseRepository, sourceUpdateService, aiAgentService, mapper, objectMapper);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"TEST_PLAN,ACCEPTED", "TEST_CASE,ACCEPTED", "UNIT_TEST,ACCEPTED",
            "TEST_PLAN,MODIFIED", "TEST_CASE,MODIFIED", "UNIT_TEST,MODIFIED"})
    void blocksDownstreamUntilAcceptedRequirementChangeIsApplied(GenerationProgressStage stage, SourceUpdateReviewStatus decision) {
        useArtifactRepositories();
        AuthUser user = prepareUpdate("{}");
        BusinessRule current = new BusinessRule();
        current.setId(201L);
        current.setDescription("Old approved oracle");
        when(businessRuleRepository.findByProjectId(10L)).thenReturn(List.of(current));
        SourceUpdateItem item = proposal(SourceUpdateTargetType.BUSINESS_RULE, 201L);
        item.setReviewStatus(decision);
        item.setAfterData("{\"method_id\":101,\"description\":\"New approved oracle\",\"category\":\"BUSINESS_LOGIC\"}");
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L)).thenReturn(List.of(item));
        // Guard chạy trước khi GENERATING, nên không lưu trạng thái hay gọi AI.
        org.mockito.Mockito.lenient().when(sourceUpdateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.generateIncrementalStage(10L, 200L, stage, user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("áp dụng");

        org.mockito.Mockito.verify(sourceUpdateRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(aiAgentService);
        assertThat(current.getDescription()).isEqualTo("Old approved oracle");
    }

    private AuthUser prepareUpdate(String impact) {
        AuthUser user = new AuthUser();
        user.setId(1L);
        Project project = new Project();
        project.setId(10L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));
        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setStatus(SourceUpdateStatus.ANALYZED);
        update.setImpactSummary(impact);
        when(sourceUpdateRepository.findByIdAndProjectIdForUpdate(200L, 10L)).thenReturn(Optional.of(update));
        when(sourceUpdateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return user;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = SourceUpdateReviewStatus.class, names = {"PENDING", "REJECTED"})
    void unacceptedBusinessRuleChangesKeepDownstreamOnPersistedOracle(SourceUpdateReviewStatus decision) {
        useArtifactRepositories();
        AuthUser user = prepareUpdate("{\"affectedBusinessRuleIds\":[201]}");
        BusinessRule current = new BusinessRule();
        current.setId(201L);
        current.setDescription("Old approved oracle");
        when(businessRuleRepository.findByProjectId(10L)).thenReturn(List.of(current));
        SourceUpdateItem item = proposal(SourceUpdateTargetType.BUSINESS_RULE, 201L);
        item.setReviewStatus(decision);
        item.setAfterData("{\"method_id\":101,\"description\":\"New proposed oracle\",\"category\":\"BUSINESS_LOGIC\"}");
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L)).thenReturn(List.of(item));
        when(aiAgentService.generateTestPlan(10L, Set.of(201L)))
                .thenAnswer(inv -> {
                    assertThat(current.getDescription()).isEqualTo("Old approved oracle");
                    return new com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto(List.of());
                });

        service.generateIncrementalStage(10L, 200L, GenerationProgressStage.TEST_PLAN, user);

        verify(aiAgentService).generateTestPlan(10L, Set.of(201L));
        assertThat(current.getDescription()).isEqualTo("Old approved oracle");
    }

    private SourceUpdateItem proposal(SourceUpdateTargetType type, Long targetId) {
        SourceUpdateItem pending = new SourceUpdateItem();
        pending.setId(601L);
        pending.setSourceUpdateId(200L);
        pending.setTargetType(type);
        pending.setTargetId(targetId);
        pending.setAction(SourceUpdateAction.UPDATE);
        pending.setReviewStatus(SourceUpdateReviewStatus.PENDING);
        return pending;
    }

    @Test
    void rejectsGenerationForNonOwner() {
        AuthUser user = new AuthUser();
        user.setId(2L);
        user.setRole(UserRole.USER);
        Project project = new Project();
        project.setId(10L);
        project.setOwnerUserId(1L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));
        doThrow(new AuthException("FORBIDDEN", "forbidden", org.springframework.http.HttpStatus.FORBIDDEN))
                .when(sourceUpdateService).checkProjectAccess(project, user);

        assertThatThrownBy(() -> service.generateIncrementalStage(
                10L, 200L, GenerationProgressStage.UNIT_TEST, user))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void resolvesOnlyChangedOverload() {
        AuthUser user = new AuthUser();
        user.setId(1L);
        user.setRole(UserRole.USER);
        Project project = new Project();
        project.setId(10L);
        project.setOwnerUserId(1L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setStatus(SourceUpdateStatus.ANALYZED);
        update.setImpactSummary("""
                {"changedMethods":[{
                    "className":"OrderService","qualifiedClassName":"com.example.OrderService",
                    "methodName":"calculateDiscount","signature":"calculateDiscount(int)",
                    "methodKey":"com.example.OrderService#calculateDiscount(int)","diffType":"MODIFIED"
                }]}
                """);
        when(sourceUpdateRepository.findByIdAndProjectIdForUpdate(200L, 10L)).thenReturn(Optional.of(update));
        when(sourceUpdateRepository.save(any(SourceUpdate.class))).thenAnswer(inv -> inv.getArgument(0));

        JavaClass serviceClass = new JavaClass();
        serviceClass.setId(50L);
        serviceClass.setClassName("OrderService");
        serviceClass.setQualifiedName("com.example.OrderService");
        when(javaClassRepository.findByProjectId(10L)).thenReturn(List.of(serviceClass));

        JavaMethod stringOverload = new JavaMethod();
        stringOverload.setId(101L);
        stringOverload.setMethodName("calculateDiscount");
        stringOverload.setParameters(List.of(new com.greytest.entity.MethodParam("value", "String")));
        JavaMethod intOverload = new JavaMethod();
        intOverload.setId(102L);
        intOverload.setMethodName("calculateDiscount");
        intOverload.setParameters(List.of(new com.greytest.entity.MethodParam("value", "int")));
        when(javaMethodRepository.findByClassId(50L)).thenReturn(List.of(stringOverload, intOverload));
        when(aiAgentService.generateBusinessRules(eq(10L), eq(Set.of(102L))))
                .thenReturn(new BusinessRuleResponseDto(List.of()));

        service.generateIncrementalStage(10L, 200L, GenerationProgressStage.BUSINESS_RULE, user);

        verify(aiAgentService).generateBusinessRules(10L, Set.of(102L));
    }

    @Test
    void addedMethodKeepsSignatureFromCandidateSource() {
        AuthUser user = new AuthUser();
        user.setId(1L);
        user.setRole(UserRole.USER);
        Project project = new Project();
        project.setId(10L);
        project.setOwnerUserId(1L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setStatus(SourceUpdateStatus.ANALYZED);
        update.setImpactSummary("""
                {"changedMethods":[{
                    "className":"OrderService","qualifiedClassName":"com.example.OrderService",
                    "methodName":"createUser","signature":"createUser(String)",
                    "methodKey":"com.example.OrderService#createUser(String)","diffType":"ADDED",
                    "afterSource":"public User createUser(String email) throws IOException { return null; }"
                }]}
                """);
        when(sourceUpdateRepository.findByIdAndProjectIdForUpdate(200L, 10L)).thenReturn(Optional.of(update));
        when(sourceUpdateRepository.save(any(SourceUpdate.class))).thenAnswer(inv -> inv.getArgument(0));

        JavaClass serviceClass = new JavaClass();
        serviceClass.setId(50L);
        serviceClass.setClassName("OrderService");
        serviceClass.setQualifiedName("com.example.OrderService");
        when(javaClassRepository.findByProjectId(10L)).thenReturn(List.of(serviceClass));
        JavaMethod oldSignature = new JavaMethod();
        oldSignature.setId(101L);
        oldSignature.setMethodName("createUser");
        oldSignature.setParameters(List.of(new com.greytest.entity.MethodParam("id", "Long")));
        when(javaMethodRepository.findByClassId(50L)).thenReturn(List.of(oldSignature));
        when(javaMethodRepository.save(any(JavaMethod.class))).thenAnswer(inv -> {
            JavaMethod method = inv.getArgument(0);
            method.setId(102L);
            return method;
        });
        when(aiAgentService.generateBusinessRules(eq(10L), eq(Set.of(102L))))
                .thenReturn(new BusinessRuleResponseDto(List.of()));

        service.generateIncrementalStage(10L, 200L, GenerationProgressStage.BUSINESS_RULE, user);

        var captor = org.mockito.ArgumentCaptor.forClass(JavaMethod.class);
        verify(javaMethodRepository).save(captor.capture());
        assertThat(captor.getValue().getReturnType()).isEqualTo("User");
        assertThat(captor.getValue().getParameters())
                .containsExactly(new com.greytest.entity.MethodParam("email", "String"));
        assertThat(captor.getValue().getThrowsList()).containsExactly("IOException");
        assertThat(captor.getValue().getVisibility()).isEqualTo(com.greytest.entity.enums.Visibility.PUBLIC);
        assertThat(oldSignature.getParameters())
                .containsExactly(new com.greytest.entity.MethodParam("id", "Long"));
    }
}
