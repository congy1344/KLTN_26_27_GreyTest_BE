package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.greytest.dto.SourceUpdateDto;
import com.greytest.dto.SourceUpdateItemDto;
import com.greytest.dto.SourceUpdateItemPatchRequest;
import com.greytest.entity.AuthUser;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.Project;
import com.greytest.entity.SourceRevision;
import com.greytest.entity.SourceUpdate;
import com.greytest.entity.SourceUpdateItem;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.enums.ClassType;
import com.greytest.entity.enums.SourceType;
import com.greytest.entity.enums.SourceUpdateAction;
import com.greytest.entity.enums.SourceUpdateReviewStatus;
import com.greytest.entity.enums.SourceUpdateStatus;
import com.greytest.entity.enums.SourceUpdateTargetType;
import com.greytest.entity.enums.UserRole;
import com.greytest.mapper.SourceUpdateMapper;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.SourceRevisionRepository;
import com.greytest.repository.SourceUpdateItemRepository;
import com.greytest.repository.SourceUpdateRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.storage.SourceRevisionStorageService;
import com.greytest.service.storage.SourceRevisionStorageService.SnapshotResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.diff.ImpactSummaryDto;
import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.service.diff.ImpactAnalysisService;
import com.greytest.service.diff.SourceDiffService;

@ExtendWith(MockitoExtension.class)
class SourceUpdateServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SourceRevisionRepository sourceRevisionRepository;
    @Mock
    private SourceUpdateRepository sourceUpdateRepository;
    @Mock
    private SourceUpdateItemRepository sourceUpdateItemRepository;
    @Mock
    private SourceRevisionStorageService storageService;
    @Mock
    private SourceDiffService sourceDiffService;
    @Mock
    private ImpactAnalysisService impactAnalysisService;
    @Mock
    private BusinessRuleRepository businessRuleRepository;
    @Mock
    private TestPlanRepository testPlanRepository;
    @Mock
    private TestCaseRepository testCaseRepository;
    @Mock
    private UnitTestRepository unitTestRepository;
    @Mock
    private JavaClassRepository javaClassRepository;
    @Mock
    private JavaMethodRepository javaMethodRepository;

    private SourceUpdateService service;
    private final SourceUpdateMapper mapper = new SourceUpdateMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new SourceUpdateService(
                projectRepository,
                sourceRevisionRepository,
                sourceUpdateRepository,
                sourceUpdateItemRepository,
                storageService,
                mapper,
                sourceDiffService,
                impactAnalysisService,
                businessRuleRepository,
                testPlanRepository,
                testCaseRepository,
                unitTestRepository,
                javaClassRepository,
                javaMethodRepository,
                objectMapper
        );
    }

    @Test
    void createsZipUpdateDraftSuccessfully() {
        AuthUser user = user(1L);
        Project project = project(10L, user.getId());
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));
        when(sourceUpdateRepository.findFirstByProjectIdAndStatusNotIn(any(), any())).thenReturn(Optional.empty());

        SnapshotResult snapshot = new SnapshotResult(Path.of("/tmp/snap"), Path.of("/tmp/snap"), "hash123", null, null);
        when(storageService.storeZipSnapshot(any())).thenReturn(snapshot);

        when(sourceRevisionRepository.save(any(SourceRevision.class))).thenAnswer(inv -> {
            SourceRevision r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });

        when(sourceUpdateRepository.save(any(SourceUpdate.class))).thenAnswer(inv -> {
            SourceUpdate u = inv.getArgument(0);
            u.setId(200L);
            return u;
        });

        MockMultipartFile file = new MockMultipartFile("file", "update.zip", "application/zip", new byte[] {1, 2});
        SourceUpdateDto dto = service.createZipUpdate(10L, file, user);

        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(200L);
        assertThat(dto.candidateRevisionId()).isEqualTo(100L);
        assertThat(dto.status()).isEqualTo(SourceUpdateStatus.DRAFT);
        assertThat(project.getActiveSourceUpdateId()).isEqualTo(200L);
    }

    @Test
    void deletesCandidateSnapshotWhenCreateUpdateRollsBack() {
        AuthUser user = user(1L);
        Project project = project(10L, user.getId());
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));
        when(sourceUpdateRepository.findFirstByProjectIdAndStatusNotIn(any(), any())).thenReturn(Optional.empty());

        SnapshotResult snapshot = new SnapshotResult(Path.of("snapshot"), Path.of("snapshot"), "hash123", null, null);
        when(storageService.storeZipSnapshot(any())).thenReturn(snapshot);
        when(sourceRevisionRepository.save(any(SourceRevision.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.createZipUpdate(
                    10L,
                    new MockMultipartFile("file", "update.zip", "application/zip", new byte[] {1, 2}),
                    user))
                    .isInstanceOf(IllegalStateException.class);

            TransactionSynchronization synchronization =
                    TransactionSynchronizationManager.getSynchronizations().get(0);
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(storageService).delete(snapshot.storageDir());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rejectsUpdateWhenDraftAlreadyActive() {
        AuthUser user = user(1L);
        Project project = project(10L, user.getId());
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        SourceUpdate activeUpdate = new SourceUpdate();
        activeUpdate.setId(999L);
        when(sourceUpdateRepository.findFirstByProjectIdAndStatusNotIn(any(), any())).thenReturn(Optional.of(activeUpdate));

        MockMultipartFile file = new MockMultipartFile("file", "update.zip", "application/zip", new byte[] {1, 2});
        assertThatThrownBy(() -> service.createZipUpdate(10L, file, user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Project đang có một bản cập nhật nháp");
    }

    @Test
    void patchesUpdateItem() {
        AuthUser user = user(1L);
        Project project = project(10L, user.getId());
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setStatus(SourceUpdateStatus.DRAFT);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));

        SourceUpdateItem item = new SourceUpdateItem();
        item.setId(50L);
        item.setSourceUpdateId(200L);
        item.setTargetType(SourceUpdateTargetType.METHOD);
        item.setAction(SourceUpdateAction.KEEP);
        item.setReviewStatus(SourceUpdateReviewStatus.PENDING);

        when(sourceUpdateItemRepository.findById(50L)).thenReturn(Optional.of(item));
        when(sourceUpdateItemRepository.save(any(SourceUpdateItem.class))).thenAnswer(inv -> inv.getArgument(0));

        SourceUpdateItemPatchRequest patch = new SourceUpdateItemPatchRequest(
                SourceUpdateAction.UPDATE,
                SourceUpdateReviewStatus.ACCEPTED,
                "{\"name\":\"newMethod\"}",
                "Updated logic"
        );

        SourceUpdateItemDto result = service.patchItem(10L, 200L, 50L, patch, user);

        assertThat(result.action()).isEqualTo(SourceUpdateAction.UPDATE);
        assertThat(result.reviewStatus()).isEqualTo(SourceUpdateReviewStatus.ACCEPTED);
        assertThat(result.afterData()).isEqualTo("{\"name\":\"newMethod\"}");
        assertThat(result.reason()).isEqualTo("Updated logic");
    }

    @Test
    void cancelsUpdateAndCleansStorage() {
        AuthUser user = user(1L);
        Project project = project(10L, user.getId());
        project.setActiveSourceUpdateId(200L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setCandidateRevisionId(100L);
        update.setStatus(SourceUpdateStatus.DRAFT);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));

        SourceRevision candidateRev = new SourceRevision();
        candidateRev.setId(100L);
        candidateRev.setStoragePath("/tmp/storage/candidate");
        when(sourceRevisionRepository.findById(100L)).thenReturn(Optional.of(candidateRev));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.cancelUpdate(10L, 200L, user);

            assertThat(update.getStatus()).isEqualTo(SourceUpdateStatus.CANCELLED);
            assertThat(project.getActiveSourceUpdateId()).isNull();
            verify(storageService, never()).delete(Path.of("/tmp/storage/candidate"));

            TransactionSynchronization synchronization =
                    TransactionSynchronizationManager.getSynchronizations().get(0);
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            verify(storageService).delete(Path.of("/tmp/storage/candidate"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void analyzesUpdateAndPopulatesItems() {
        AuthUser user = user(1L);
        Project project = project(10L, user.getId());
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setBaseRevisionId(101L);
        update.setCandidateRevisionId(102L);
        update.setStatus(SourceUpdateStatus.DRAFT);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));
        when(sourceUpdateRepository.save(any(SourceUpdate.class))).thenAnswer(inv -> inv.getArgument(0));

        SourceRevision baseRev = new SourceRevision();
        baseRev.setId(101L);
        baseRev.setStoragePath("/tmp/base");
        when(sourceRevisionRepository.findById(101L)).thenReturn(Optional.of(baseRev));

        SourceRevision candRev = new SourceRevision();
        candRev.setId(102L);
        candRev.setStoragePath("/tmp/cand");
        when(sourceRevisionRepository.findById(102L)).thenReturn(Optional.of(candRev));

        MethodDiffItem diffItem = new MethodDiffItem(
                "OrderService", "com.example.OrderService", "calculateDiscount",
                "calculateDiscount(int)", "com.example.OrderService#calculateDiscount(int)",
                MethodDiffType.MODIFIED, "Logic changed", "code1", "code2", List.of(), true
        );
        when(sourceDiffService.compareSnapshots(any(), any())).thenReturn(List.of(diffItem));

        ImpactSummaryDto impact = new ImpactSummaryDto(
                1, 0, 1, 0, List.of(diffItem),
                List.of("com.example.OrderService#calculateDiscount(int)"),
                List.of(301L), List.of(), List.of(), List.of()
        );
        when(impactAnalysisService.analyzeImpact(10L, List.of(diffItem))).thenReturn(impact);

        SourceUpdateDto result = service.analyzeUpdate(10L, 200L, user);

        assertThat(result.status()).isEqualTo(SourceUpdateStatus.ANALYZED);
        assertThat(result.totalChangedMethods()).isEqualTo(1);
        verify(sourceUpdateItemRepository).deleteBySourceUpdateId(200L);
        verify(sourceUpdateItemRepository, times(2)).save(any(SourceUpdateItem.class));
    }

    @Test
    void appliesUpdateDraftSuccessfully() {
        AuthUser user = user(1L);
        Project project = project(10L, user.getId());
        project.setActiveSourceUpdateId(200L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setCandidateRevisionId(102L);
        update.setStatus(SourceUpdateStatus.READY_TO_APPLY);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));
        when(sourceUpdateRepository.save(any(SourceUpdate.class))).thenAnswer(inv -> inv.getArgument(0));

        SourceRevision candRev = new SourceRevision();
        candRev.setId(102L);
        candRev.setStoragePath("/tmp/cand_applied");
        when(sourceRevisionRepository.findById(102L)).thenReturn(Optional.of(candRev));

        SourceUpdateItem item1 = new SourceUpdateItem();
        item1.setId(1L);
        item1.setSourceUpdateId(200L);
        item1.setTargetType(SourceUpdateTargetType.BUSINESS_RULE);
        item1.setAction(SourceUpdateAction.CREATE);
        item1.setAfterData("{\"method_id\": 50, \"description\": \"BR updated rule\", \"category\": \"VALIDATION\"}");
        item1.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);

        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L)).thenReturn(List.of(item1));
        when(businessRuleRepository.findByProjectId(10L)).thenReturn(List.of());

        SourceUpdateDto result = service.applyUpdate(10L, 200L, user);

        assertThat(result.status()).isEqualTo(SourceUpdateStatus.APPLIED);
        assertThat(project.getStoragePath()).isEqualTo("/tmp/cand_applied");
        assertThat(project.getActiveSourceRevisionId()).isEqualTo(102L);
        assertThat(project.getActiveSourceUpdateId()).isNull();
        verify(businessRuleRepository).save(any());
        verify(projectRepository).save(project);
    }

    @Test
    void rejectsApplyBeforeAnalysisCompletes() {
        AuthUser user = user(1L);
        Project project = project(10L, user.getId());
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setStatus(SourceUpdateStatus.DRAFT);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));

        assertThatThrownBy(() -> service.applyUpdate(10L, 200L, user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    void updatesAllUnitTestMetadataWhenApplyingRegeneratedTest() {
        AuthUser user = user(1L);
        Project project = project(10L, user.getId());
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setCandidateRevisionId(102L);
        update.setStatus(SourceUpdateStatus.READY_TO_APPLY);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));
        SourceRevision candidate = new SourceRevision();
        candidate.setId(102L);
        candidate.setStoragePath("/tmp/candidate");
        when(sourceRevisionRepository.findById(102L)).thenReturn(Optional.of(candidate));

        SourceUpdateItem item = new SourceUpdateItem();
        item.setId(1L);
        item.setSourceUpdateId(200L);
        item.setTargetType(SourceUpdateTargetType.UNIT_TEST);
        item.setTargetId(501L);
        item.setAction(SourceUpdateAction.UPDATE);
        item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
        item.setAfterData("""
                {"case_id":401,"test_class_name":"NewTest","test_method_name":"newCase",
                 "package_name":"com.changed","generation_type":"IMPROVE_EXISTING_TEST",
                 "source_code":"package com.changed; class NewTest {}"}
                """);
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L))
                .thenReturn(List.of(item));
        when(businessRuleRepository.findByProjectId(10L)).thenReturn(List.of());
        TestCase testCase = new TestCase();
        testCase.setId(401L);
        testCase.setTestPlanId(301L);
        testCase.setCaseCode("TC-401");
        testCase.setTraceSource("BR-001 -> TP-001");
        TestPlan testPlan = new TestPlan();
        testPlan.setId(301L);
        testPlan.setProjectId(10L);
        when(testCaseRepository.findById(401L)).thenReturn(Optional.of(testCase));
        when(testPlanRepository.findById(301L)).thenReturn(Optional.of(testPlan));

        com.greytest.entity.UnitTest existing = new com.greytest.entity.UnitTest();
        existing.setId(501L);
        existing.setTestCaseId(401L);
        when(unitTestRepository.findById(501L)).thenReturn(Optional.of(existing));

        service.applyUpdate(10L, 200L, user);

        assertThat(existing.getTestClassName()).isEqualTo("NewTest");
        assertThat(existing.getTestMethodName()).isEqualTo("newCase");
        assertThat(existing.getPackageName()).isEqualTo("com.changed");
        assertThat(existing.getGenerationType()).isEqualTo("IMPROVE_EXISTING_TEST");
        assertThat(existing.getFilePath()).isEqualTo("src/test/java/com/changed/NewTest.java");
    }

    @Test
    void rejectsApplyWhileAnyItemIsPendingReview() {
        AuthUser user = user(1L);
        Project project = project(10L, user.getId());
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setCandidateRevisionId(102L);
        update.setStatus(SourceUpdateStatus.READY_TO_APPLY);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));

        SourceRevision candidate = new SourceRevision();
        candidate.setId(102L);
        candidate.setStoragePath("/tmp/candidate");
        when(sourceRevisionRepository.findById(102L)).thenReturn(Optional.of(candidate));

        SourceUpdateItem pending = new SourceUpdateItem();
        pending.setSourceUpdateId(200L);
        pending.setTargetType(SourceUpdateTargetType.METHOD);
        pending.setAction(SourceUpdateAction.UPDATE);
        pending.setReviewStatus(SourceUpdateReviewStatus.PENDING);
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L))
                .thenReturn(List.of(pending));

        assertThatThrownBy(() -> service.applyUpdate(10L, 200L, user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void failsApplyInsteadOfMarkingUpdateAppliedWhenItemIsInvalid() {
        AuthUser user = user(1L);
        Project project = project(10L, user.getId());
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setCandidateRevisionId(102L);
        update.setStatus(SourceUpdateStatus.READY_TO_APPLY);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));

        SourceRevision candidate = new SourceRevision();
        candidate.setId(102L);
        candidate.setStoragePath("/tmp/candidate");
        when(sourceRevisionRepository.findById(102L)).thenReturn(Optional.of(candidate));

        SourceUpdateItem invalid = new SourceUpdateItem();
        invalid.setSourceUpdateId(200L);
        invalid.setTargetType(SourceUpdateTargetType.UNIT_TEST);
        invalid.setAction(SourceUpdateAction.UPDATE);
        invalid.setTargetId(501L);
        invalid.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
        invalid.setAfterData("not-json");
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L))
                .thenReturn(List.of(invalid));

        assertThatThrownBy(() -> service.applyUpdate(10L, 200L, user))
                .isInstanceOf(IllegalStateException.class);
        assertThat(update.getStatus()).isNotEqualTo(SourceUpdateStatus.APPLIED);
    }

    @Test
    void appliesMethodDiffsSuccessfully() {
        AuthUser user = user(1L);
        Project p = project(10L, 1L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(p));

        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setCandidateRevisionId(102L);
        update.setStatus(SourceUpdateStatus.READY_TO_APPLY);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));

        SourceRevision candidate = new SourceRevision();
        candidate.setId(102L);
        candidate.setStoragePath("/tmp/candidate");
        when(sourceRevisionRepository.findById(102L)).thenReturn(Optional.of(candidate));

        JavaClass serviceClass = new JavaClass();
        serviceClass.setId(50L);
        serviceClass.setClassName("OrderService");
        serviceClass.setQualifiedName("com.example.OrderService");
        serviceClass.setClassType(ClassType.SERVICE);
        when(javaClassRepository.findByProjectId(10L)).thenReturn(List.of(serviceClass));

        JavaMethod existingMethod = new JavaMethod();
        existingMethod.setId(301L);
        existingMethod.setClassId(50L);
        existingMethod.setMethodName("calculateDiscount");
        existingMethod.setSourceCode("int calculateDiscount() { return 0; }");

        JavaMethod deletedMethod = new JavaMethod();
        deletedMethod.setId(302L);
        deletedMethod.setClassId(50L);
        deletedMethod.setMethodName("oldMethod");

        when(javaMethodRepository.findByClassId(50L)).thenReturn(List.of(existingMethod, deletedMethod));

        // 1. UPDATE item
        SourceUpdateItem updateItem = new SourceUpdateItem();
        updateItem.setId(1L);
        updateItem.setSourceUpdateId(200L);
        updateItem.setTargetType(SourceUpdateTargetType.METHOD);
        updateItem.setTargetKey("com.example.OrderService#calculateDiscount()");
        updateItem.setAction(SourceUpdateAction.UPDATE);
        updateItem.setAfterData("int calculateDiscount() { return 10; }");
        updateItem.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);

        // 2. REMOVE item
        SourceUpdateItem removeItem = new SourceUpdateItem();
        removeItem.setId(2L);
        removeItem.setSourceUpdateId(200L);
        removeItem.setTargetType(SourceUpdateTargetType.METHOD);
        removeItem.setTargetKey("com.example.OrderService#oldMethod()");
        removeItem.setAction(SourceUpdateAction.REMOVE);
        removeItem.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);

        // 3. CREATE item
        SourceUpdateItem createItem = new SourceUpdateItem();
        createItem.setId(3L);
        createItem.setSourceUpdateId(200L);
        createItem.setTargetType(SourceUpdateTargetType.METHOD);
        createItem.setTargetKey("com.example.OrderService#newMethod()");
        createItem.setAction(SourceUpdateAction.CREATE);
        createItem.setAfterData("void newMethod() {}");
        createItem.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);

        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L))
                .thenReturn(List.of(updateItem, removeItem, createItem));

        service.applyUpdate(10L, 200L, user);

        verify(javaMethodRepository).save(argThat(m -> "calculateDiscount".equals(m.getMethodName())
                && "int calculateDiscount() { return 10; }".equals(m.getSourceCode())));
        verify(javaMethodRepository).delete(deletedMethod);
        verify(javaMethodRepository).save(argThat(m -> "newMethod".equals(m.getMethodName())
                && "void newMethod() {}".equals(m.getSourceCode())));
    }

    private AuthUser user(Long id) {
        AuthUser u = new AuthUser();
        u.setId(id);
        u.setRole(UserRole.USER);
        return u;
    }

    private Project project(Long id, Long ownerId) {
        Project p = new Project();
        p.setId(id);
        p.setOwnerUserId(ownerId);
        p.setSourceType(SourceType.ZIP);
        return p;
    }
}
