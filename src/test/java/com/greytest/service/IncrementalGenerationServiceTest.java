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
