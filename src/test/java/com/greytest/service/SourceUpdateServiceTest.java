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
import com.greytest.entity.MethodParam;
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
import com.greytest.repository.TestPlanCoveredRuleRepository;
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
    @Mock private TestPlanCoveredRuleRepository testPlanCoveredRuleRepository;
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
                objectMapper, testPlanCoveredRuleRepository
        );
    }

    @Test
    void candidateSourceLookupKeepsExistingRecordsAndArtifacts(@org.junit.jupiter.api.io.TempDir Path snapshot) {
        SourceUpdate update = new SourceUpdate();
        update.setProjectId(10L);
        update.setCandidateRevisionId(300L);
        SourceRevision revision = new SourceRevision();
        revision.setProjectId(10L);
        revision.setStoragePath(snapshot.toString());
        when(sourceRevisionRepository.findById(300L)).thenReturn(Optional.of(revision));

        assertThat(service.getCandidateSourcePath(update)).isEqualTo(snapshot);

        verify(sourceRevisionRepository, never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(projectRepository, sourceUpdateRepository, sourceUpdateItemRepository,
                javaClassRepository, javaMethodRepository, businessRuleRepository, testPlanRepository,
                testCaseRepository, unitTestRepository, storageService);
    }

    @Test
    void candidateSourceLookupRejectsRevisionFromAnotherProject() {
        SourceUpdate update = new SourceUpdate();
        update.setProjectId(10L);
        update.setCandidateRevisionId(300L);
        SourceRevision revision = new SourceRevision();
        revision.setProjectId(99L);
        revision.setStoragePath("another-project");
        when(sourceRevisionRepository.findById(300L)).thenReturn(Optional.of(revision));

        assertThatThrownBy(() -> service.getCandidateSourcePath(update))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("project");
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
        assertThat(item.getUserRevision()).isEqualTo(1L);
    }

    @Test
    void rejectsStaleReviewWithoutChangingUserDecision() {
        AuthUser user = user(1L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project(10L, 1L)));
        SourceUpdate update = new SourceUpdate(); update.setId(200L); update.setProjectId(10L);
        update.setStatus(SourceUpdateStatus.ANALYZED);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));
        SourceUpdateItem item = new SourceUpdateItem(); item.setId(50L); item.setSourceUpdateId(200L);
        item.setVersion(3L); item.setUserRevision(2L); item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
        item.setAfterData("user content");
        when(sourceUpdateItemRepository.findById(50L)).thenReturn(Optional.of(item));
        var request = new SourceUpdateItemPatchRequest(null, SourceUpdateReviewStatus.REJECTED, "late edit", null,
                2L, 2L, null, null, null);

        assertThatThrownBy(() -> service.patchItem(10L, 200L, 50L, request, user))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(item.getAfterData()).isEqualTo("user content");
        assertThat(item.getReviewStatus()).isEqualTo(SourceUpdateReviewStatus.ACCEPTED);
        verify(sourceUpdateItemRepository, never()).save(any());
    }

    @Test
    void repeatedAnalysisKeepsGeneratedAndReviewedProposals() {
        AuthUser user = user(1L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project(10L, 1L)));
        SourceUpdate update = new SourceUpdate(); update.setId(200L); update.setProjectId(10L);
        update.setStatus(SourceUpdateStatus.ANALYZED); update.setImpactSummary("{}");
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));
        SourceUpdateItem item = new SourceUpdateItem(); item.setId(50L); item.setSourceUpdateId(200L);
        item.setTargetType(SourceUpdateTargetType.BUSINESS_RULE); item.setAfterData("user content");
        item.setReviewStatus(SourceUpdateReviewStatus.MODIFIED); item.setUserRevision(1L);
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L)).thenReturn(List.of(item));

        SourceUpdateDto result = service.analyzeUpdate(10L, 200L, user);

        assertThat(result.items()).singleElement().satisfies(dto -> assertThat(dto.afterData()).isEqualTo("user content"));
        verify(sourceUpdateItemRepository, never()).deleteBySourceUpdateId(any());
        org.mockito.Mockito.verifyNoInteractions(sourceDiffService, impactAnalysisService);
    }

    @Test
    void reviewsFailedDraftWithoutDiscardingItsSuccessfulOutputs() {
        AuthUser user = user(1L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project(10L, 1L)));
        SourceUpdate update = new SourceUpdate(); update.setId(200L); update.setProjectId(10L);
        update.setStatus(SourceUpdateStatus.FAILED);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));
        SourceUpdateItem item = new SourceUpdateItem(); item.setId(50L); item.setSourceUpdateId(200L);
        item.setTargetType(SourceUpdateTargetType.BUSINESS_RULE); item.setAfterData("preserved output");
        when(sourceUpdateItemRepository.findById(50L)).thenReturn(Optional.of(item));
        when(sourceUpdateItemRepository.save(any(SourceUpdateItem.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.patchItem(10L, 200L, 50L,
                new SourceUpdateItemPatchRequest(null, SourceUpdateReviewStatus.ACCEPTED, null, null), user);

        assertThat(result.afterData()).isEqualTo("preserved output");
        assertThat(result.userRevision()).isEqualTo(1L);
    }

    @Test
    void keepingAnOriginallyCreatedProposalRetainsOwnershipAndExplicitlyDiscardsIt() {
        AuthUser user = user(1L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project(10L, 1L)));
        SourceUpdate update = new SourceUpdate(); update.setId(200L); update.setProjectId(10L);
        update.setStatus(SourceUpdateStatus.ANALYZED);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));
        SourceUpdateItem item = new SourceUpdateItem(); item.setId(50L); item.setSourceUpdateId(200L);
        item.setTargetType(SourceUpdateTargetType.BUSINESS_RULE); item.setTargetId(301L);
        item.setAction(SourceUpdateAction.CREATE); item.setAfterData("temporary proposal");
        when(sourceUpdateItemRepository.findById(50L)).thenReturn(Optional.of(item));
        when(sourceUpdateItemRepository.save(any(SourceUpdateItem.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.patchItem(10L, 200L, 50L,
                new SourceUpdateItemPatchRequest(SourceUpdateAction.KEEP, SourceUpdateReviewStatus.ACCEPTED, null, null), user);

        assertThat(result.action()).isEqualTo(SourceUpdateAction.CREATE);
        assertThat(result.reviewStatus()).isEqualTo(SourceUpdateReviewStatus.REJECTED);
        assertThat(result.targetId()).isEqualTo(301L);
        assertThat(result.afterData()).isEqualTo("temporary proposal");
    }

    @Test
    void keepingChangedMethodBlocksWholeSnapshotPromotion() {
        AuthUser user = user(1L);
        Project project = project(10L, 1L); project.setStoragePath("active-source");
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));
        SourceUpdate update = new SourceUpdate(); update.setId(200L); update.setProjectId(10L);
        update.setCandidateRevisionId(102L); update.setStatus(SourceUpdateStatus.ANALYZED);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));
        SourceRevision candidate = new SourceRevision(); candidate.setStoragePath("candidate-source");
        when(sourceRevisionRepository.findById(102L)).thenReturn(Optional.of(candidate));
        SourceUpdateItem item = new SourceUpdateItem(); item.setTargetType(SourceUpdateTargetType.METHOD);
        item.setAction(SourceUpdateAction.KEEP); item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L)).thenReturn(List.of(item));

        assertThatThrownBy(() -> service.applyUpdate(10L, 200L, user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("KEEP");
        assertThat(project.getStoragePath()).isEqualTo("active-source");
        verify(projectRepository, never()).save(any());
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
        pending.setTargetType(SourceUpdateTargetType.BUSINESS_RULE);
        pending.setTargetId(201L);
        pending.setAfterData("{\"method_id\":101,\"description\":\"Allow balance > amount\",\"category\":\"BUSINESS_LOGIC\"}");
        pending.setAction(SourceUpdateAction.UPDATE);
        pending.setReviewStatus(SourceUpdateReviewStatus.PENDING);
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L))
                .thenReturn(List.of(pending));

        assertThatThrownBy(() -> service.applyUpdate(10L, 200L, user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PENDING");
        verify(projectRepository, never()).save(any());
        verify(businessRuleRepository, never()).save(any());
    }

    @Test
    void rejectedOracleUpdatesLeaveApprovedRuleAndCaseUnchanged() {
        applyOracleProposal(SourceUpdateReviewStatus.REJECTED);
    }

    @Test
    void explicitlyAcceptedOracleUpdatesApplyNewRuleAndCase() {
        applyOracleProposal(SourceUpdateReviewStatus.ACCEPTED);
    }

    @Test
    void unchangedAcceptedBusinessRuleDoesNotInvalidateDescendants() {
        applyOracleProposal(SourceUpdateReviewStatus.ACCEPTED, false);
    }

    @Test
    void acceptedPlanAddingSecondaryCoverageInSameApplyIsInvalidatedWithoutPersistedLink() {
        applyOracleProposal(SourceUpdateReviewStatus.ACCEPTED, true, true);
    }

    private void applyOracleProposal(SourceUpdateReviewStatus decision) {
        applyOracleProposal(decision, true);
    }

    private void applyOracleProposal(SourceUpdateReviewStatus decision, boolean ruleChanges) {
        applyOracleProposal(decision, ruleChanges, false);
    }

    private void applyOracleProposal(SourceUpdateReviewStatus decision, boolean ruleChanges, boolean newSecondaryLink) {
        AuthUser user = user(1L);
        Project currentProject = project(10L, 1L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(currentProject));
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
        com.greytest.entity.BusinessRule rule = new com.greytest.entity.BusinessRule();
        rule.setId(201L);
        rule.setDescription("Allow balance >= amount");
        rule.setSource(com.greytest.entity.enums.RuleSource.USER_MODIFIED);
        rule.setStatus(com.greytest.entity.enums.ReviewStatus.APPROVED);
        when(businessRuleRepository.findByProjectId(10L)).thenReturn(List.of(rule));
        SourceUpdateItem ruleItem = new SourceUpdateItem();
        ruleItem.setId(601L);
        ruleItem.setTargetType(SourceUpdateTargetType.BUSINESS_RULE);
        ruleItem.setTargetId(201L);
        ruleItem.setAction(SourceUpdateAction.UPDATE);
        ruleItem.setReviewStatus(decision);
        ruleItem.setAfterData("{\"method_id\":101,\"description\":\"Allow balance > amount\",\"category\":\"BUSINESS_LOGIC\"}");
        if (!ruleChanges) ruleItem.setAfterData(ruleItem.getAfterData().replace("balance > amount", "balance >= amount"));
        TestCase testCase = new TestCase();
        testCase.setId(401L);
        testCase.setExpectedResult("Transfer succeeds");
        testCase.setStatus(com.greytest.entity.enums.ReviewStatus.APPROVED);
        TestPlan anchorPlan = new TestPlan();
        anchorPlan.setId(301L);
        anchorPlan.setBusinessRuleId(201L);
        anchorPlan.setStatus(com.greytest.entity.enums.ReviewStatus.APPROVED);
        TestPlan coveredPlan = new TestPlan();
        coveredPlan.setId(302L);
        coveredPlan.setBusinessRuleId(202L);
        coveredPlan.setStatus(com.greytest.entity.enums.ReviewStatus.APPROVED);
        TestCase coveredCase = new TestCase();
        coveredCase.setId(402L);
        coveredCase.setExpectedResult("Old covered oracle");
        coveredCase.setStatus(com.greytest.entity.enums.ReviewStatus.APPROVED);
        TestPlan unrelatedPlan = new TestPlan();
        unrelatedPlan.setId(303L);
        unrelatedPlan.setBusinessRuleId(203L);
        unrelatedPlan.setStatus(com.greytest.entity.enums.ReviewStatus.APPROVED);
        SourceUpdateItem planItem = new SourceUpdateItem();
        planItem.setId(603L);
        planItem.setTargetType(SourceUpdateTargetType.TEST_PLAN);
        planItem.setTargetId(301L);
        planItem.setAction(SourceUpdateAction.UPDATE);
        planItem.setReviewStatus(decision);
        planItem.setAfterData("""
                {"method_id":101,"rule_id":201,"covered_rule_ids":[201],"title":"Accepted plan",
                 "description":"Accepted plan description","test_type":"BOUNDARY"}
                """);
        SourceUpdateItem secondaryItem = new SourceUpdateItem();
        secondaryItem.setId(604L);
        secondaryItem.setTargetType(SourceUpdateTargetType.TEST_PLAN);
        secondaryItem.setTargetId(302L);
        secondaryItem.setAction(SourceUpdateAction.UPDATE);
        secondaryItem.setReviewStatus(decision);
        secondaryItem.setAfterData("""
                {"method_id":101,"rule_id":202,"covered_rule_ids":[201,202],"title":"New secondary coverage",
                 "description":"New coverage link","test_type":"BOUNDARY"}
                """);
        SourceUpdateItem caseItem = new SourceUpdateItem();
        caseItem.setId(602L);
        caseItem.setTargetType(SourceUpdateTargetType.TEST_CASE);
        caseItem.setTargetId(401L);
        caseItem.setAction(SourceUpdateAction.UPDATE);
        caseItem.setReviewStatus(decision);
        caseItem.setAfterData("""
                {"plan_id":301,"test_type":"BOUNDARY","description":"Equality","preconditions":"Valid account",
                 "test_data":{"balance":100,"amount":100},"expected_result":"Transfer rejected",
                 "priority":"HIGH","trace_source":"BR-001 -> TP-001"}
                """);
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L))
                .thenReturn(newSecondaryLink ? List.of(ruleItem, planItem, secondaryItem, caseItem) : List.of(ruleItem, planItem, caseItem));
        if (decision == SourceUpdateReviewStatus.ACCEPTED) {
            when(businessRuleRepository.findById(201L)).thenReturn(Optional.of(rule));
            when(testCaseRepository.findById(401L)).thenReturn(Optional.of(testCase));
            when(testPlanRepository.findById(301L)).thenReturn(Optional.of(anchorPlan));
            if (newSecondaryLink) when(testPlanRepository.findById(302L)).thenReturn(Optional.of(coveredPlan));
            when(testPlanRepository.findByProjectId(10L)).thenReturn(List.of(anchorPlan, coveredPlan, unrelatedPlan));
            if (ruleChanges) {
                var link = new com.greytest.entity.TestPlanCoveredRule();
                link.setBusinessRuleId(201L);
                link.setTestPlanId(302L);
                when(testPlanCoveredRuleRepository.findByBusinessRuleId(201L)).thenReturn(newSecondaryLink ? List.of() : List.of(link));
                when(testCaseRepository.findByTestPlanId(301L)).thenReturn(List.of(testCase));
                when(testCaseRepository.findByTestPlanId(302L)).thenReturn(List.of(coveredCase));
            }
        }

        service.applyUpdate(10L, 200L, user);

        assertThat(rule.getDescription()).isEqualTo(decision == SourceUpdateReviewStatus.ACCEPTED && ruleChanges
                ? "Allow balance > amount" : "Allow balance >= amount");
        assertThat(testCase.getExpectedResult()).isEqualTo(decision == SourceUpdateReviewStatus.ACCEPTED
                ? "Transfer rejected" : "Transfer succeeds");
        if (decision == SourceUpdateReviewStatus.REJECTED) {
            verify(businessRuleRepository, never()).save(any());
            verify(testCaseRepository, never()).save(any());
        } else {
            var expectedStatus = ruleChanges ? com.greytest.entity.enums.ReviewStatus.PENDING_REVIEW
                    : com.greytest.entity.enums.ReviewStatus.APPROVED;
            assertThat(anchorPlan.getStatus()).isEqualTo(expectedStatus);
            assertThat(coveredPlan.getStatus()).isEqualTo(expectedStatus);
            assertThat(testCase.getStatus()).isEqualTo(expectedStatus);
            assertThat(coveredCase.getStatus()).isEqualTo(expectedStatus);
            assertThat(coveredCase.getExpectedResult()).isEqualTo("Old covered oracle");
            if (ruleChanges) assertThat(currentProject.getStatus()).isEqualTo(com.greytest.entity.enums.ProjectStatus.PLAN_PENDING_REVIEW);
        }
        assertThat(unrelatedPlan.getStatus()).isEqualTo(com.greytest.entity.enums.ReviewStatus.APPROVED);
        verify(unitTestRepository, never()).delete(any());
        verify(testCaseRepository, never()).deleteById(any());
        verify(testPlanRepository, never()).deleteById(any());
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void appliesMethodDiffsSuccessfully(boolean moved, @org.junit.jupiter.api.io.TempDir Path snapshot) throws Exception {
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
        candidate.setStoragePath(snapshot.toString());
        when(sourceRevisionRepository.findById(102L)).thenReturn(Optional.of(candidate));

        JavaClass serviceClass = new JavaClass();
        serviceClass.setId(50L);
        serviceClass.setClassName("OrderService");
        serviceClass.setQualifiedName("com.example.OrderService");
        serviceClass.setClassType(ClassType.SERVICE);
        serviceClass.setFilePath("src/main/java/com/example/OrderService.java");
        serviceClass.setSourceCode("package com.example; class OrderService { int calculateDiscount() { return 0; } }");
        String candidatePath = moved ? "module/src/main/java/com/example/OrderService.java" : serviceClass.getFilePath();
        Path source = snapshot.resolve(candidatePath);
        java.nio.file.Files.createDirectories(source.getParent());
        java.nio.file.Files.writeString(source,
                "package com.example;\nclass OrderService {\nint calculateDiscount() { return 10; }\nvoid newMethod() {}\n}\n");
        JavaClass unrelatedClass = new JavaClass();
        unrelatedClass.setId(51L);
        unrelatedClass.setQualifiedName("com.example.ShippingService");
        unrelatedClass.setSourceCode("class ShippingService { int fee() { return 5; } }");
        when(javaClassRepository.findByProjectId(10L)).thenReturn(List.of(serviceClass, unrelatedClass));

        JavaMethod existingMethod = new JavaMethod();
        existingMethod.setId(301L);
        existingMethod.setClassId(50L);
        existingMethod.setMethodName("calculateDiscount");
        existingMethod.setSourceCode("int calculateDiscount() { return 0; }");

        JavaMethod deletedMethod = new JavaMethod();
        deletedMethod.setId(302L);
        deletedMethod.setClassId(50L);
        deletedMethod.setMethodName("oldMethod");

        java.util.List<JavaMethod> storedMethods = new java.util.ArrayList<>(List.of(existingMethod, deletedMethod));
        when(javaMethodRepository.findByClassId(50L)).thenAnswer(call -> new java.util.ArrayList<>(storedMethods));
        org.mockito.Mockito.doAnswer(call -> { storedMethods.remove(call.getArgument(0)); return null; })
                .when(javaMethodRepository).delete(any());
        when(javaMethodRepository.save(any())).thenAnswer(call -> {
            JavaMethod method = call.getArgument(0);
            if (method.getId() == null) { method.setId(303L); storedMethods.add(method); }
            return method;
        });

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

        verify(javaMethodRepository, org.mockito.Mockito.atLeastOnce()).save(argThat(m -> "calculateDiscount".equals(m.getMethodName())
                && "int calculateDiscount() { return 10; }".equals(m.getSourceCode())));
        verify(javaMethodRepository).delete(deletedMethod);
        verify(javaMethodRepository, org.mockito.Mockito.atLeastOnce()).save(argThat(m -> "newMethod".equals(m.getMethodName())
                && "void newMethod() {}".equals(m.getSourceCode())));
        assertThat(serviceClass.getSourceCode()).contains("return 10;", "void newMethod()")
                .doesNotContain("return 0;", "oldMethod");
        assertThat(serviceClass.getId()).isEqualTo(50L);
        assertThat(serviceClass.getFilePath()).isEqualTo(candidatePath);
        verify(javaClassRepository).save(serviceClass);
        verify(javaClassRepository, never()).save(unrelatedClass);
        assertThat(unrelatedClass.getSourceCode()).isEqualTo("class ShippingService { int fee() { return 5; } }");
        assertThat(existingMethod.getLineStart()).isEqualTo(3);
        assertThat(existingMethod.getLineEnd()).isEqualTo(3);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = SourceUpdateReviewStatus.class,
            names = {"ACCEPTED", "MODIFIED", "REJECTED"})
    void keepsClassAndMethodSourcesConsistentWithReviewedPayload(SourceUpdateReviewStatus decision,
            @org.junit.jupiter.api.io.TempDir Path snapshot) throws Exception {
        MethodSourceFixture fixture = methodSourceFixture(snapshot, decision,
                "int calculateDiscount() { return 20; }");

        if (decision == SourceUpdateReviewStatus.REJECTED) {
            assertThatThrownBy(() -> service.applyUpdate(10L, 200L, user(1L)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Snapshot");
            assertThat(fixture.method().getSourceCode()).contains("return 0;");
            verify(javaMethodRepository, never()).save(any());
            verify(javaClassRepository, never()).save(any());
            return;
        }
        Path source = snapshot.resolve(fixture.javaClass().getFilePath());
        java.nio.file.Files.writeString(source, java.nio.file.Files.readString(source).replace("return 10;", "return 20;"));
        service.applyUpdate(10L, 200L, user(1L));

        assertThat(fixture.overload().getSourceCode()).contains("return 30;");
        verify(javaMethodRepository, never()).save(fixture.overload());
        assertThat(fixture.method().getSourceCode()).contains("return 20;");
        assertThat(fixture.javaClass().getSourceCode()).contains("return 20;", "return 30;", "return 40;",
                "package com.example;", "import java.util.List;", "class Outer", "class Nested", "List.of(\"keep\")")
                .doesNotContain("return 10;");
        verify(javaMethodRepository).save(fixture.method());
        verify(javaClassRepository).save(fixture.javaClass());
        assertThat(fixture.update().getStatus()).isEqualTo(SourceUpdateStatus.APPLIED);
    }

    @Test
    void refreshesUnchangedSiblingRangesAfterAcceptedMethodGrows(@org.junit.jupiter.api.io.TempDir Path snapshot) throws Exception {
        MethodSourceFixture fixture = methodSourceFixture(snapshot, SourceUpdateReviewStatus.ACCEPTED,
                "int calculateDiscount() { int discount = 10; return discount; }");
        fixture.overload().setLineStart(6);
        fixture.overload().setLineEnd(6);
        Path source = snapshot.resolve(fixture.javaClass().getFilePath());
        java.nio.file.Files.writeString(source, java.nio.file.Files.readString(source).replace(
                "int calculateDiscount() { return 10; }",
                "int calculateDiscount() {\n            int discount = 10;\n            return discount;\n        }"));

        service.applyUpdate(10L, 200L, user(1L));

        assertThat(fixture.overload().getLineStart()).isEqualTo(9);
        assertThat(fixture.overload().getLineEnd()).isEqualTo(9);
        assertThat(fixture.overload().getId()).isEqualTo(302L);
        assertThat(fixture.overload().getSourceCode()).isEqualTo("int calculateDiscount(String value) { return 30; }");
        verify(javaMethodRepository, never()).save(fixture.overload());
    }

    @Test
    void rejectsActionOnlyRemovalWhenImmutableCandidateStillContainsMethod(@org.junit.jupiter.api.io.TempDir Path snapshot) throws Exception {
        MethodSourceFixture fixture = methodSourceFixture(snapshot, SourceUpdateReviewStatus.ACCEPTED,
                "int calculateDiscount() { return 10; }");
        fixture.item().setAction(SourceUpdateAction.REMOVE);

        assertThatThrownBy(() -> service.applyUpdate(10L, 200L, user(1L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("source method");

        assertThat(fixture.update().getStatus()).isNotEqualTo(SourceUpdateStatus.APPLIED);
        verify(projectRepository, never()).save(any());
        verify(javaMethodRepository, never()).delete(any());
        verify(javaClassRepository, never()).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "int calculateDiscount() { return ; broken }", "int calculateDiscount(String value) { return 20; }",
            "int calculateDiscount() { return 20; }"})
    void rejectsInvalidOrMismatchedReviewedMethodBeforeMarkingApplied(String afterData,
            @org.junit.jupiter.api.io.TempDir Path snapshot) throws Exception {
        MethodSourceFixture fixture = methodSourceFixture(snapshot, SourceUpdateReviewStatus.MODIFIED, afterData);

        assertThatThrownBy(() -> service.applyUpdate(10L, 200L, user(1L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("đồng bộ source method");

        assertThat(fixture.update().getStatus()).isNotEqualTo(SourceUpdateStatus.APPLIED);
        verify(projectRepository, never()).save(any());
        verify(javaClassRepository, never()).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = SourceUpdateAction.class, names = {"UPDATE", "KEEP"})
    void keepsRejectedMethodSourceWhenAnotherMethodInSameClassIsAccepted(
            SourceUpdateAction action, @org.junit.jupiter.api.io.TempDir Path snapshot) throws Exception {
        MethodSourceFixture fixture = methodSourceFixture(snapshot, SourceUpdateReviewStatus.ACCEPTED,
                "int calculateDiscount() { return 20; }");
        JavaMethod rejectedMethod = new JavaMethod();
        rejectedMethod.setClassId(50L);
        rejectedMethod.setMethodName("shippingFee");
        rejectedMethod.setSourceCode("int shippingFee() { return 5; }");
        org.mockito.Mockito.lenient().when(javaMethodRepository.findByClassId(50L)).thenReturn(List.of(fixture.method(), fixture.overload(), rejectedMethod));
        SourceUpdateItem rejected = new SourceUpdateItem();
        rejected.setTargetType(SourceUpdateTargetType.METHOD);
        rejected.setTargetKey("com.example.Outer.OrderService#shippingFee()");
        rejected.setAction(action);
        rejected.setReviewStatus(action == SourceUpdateAction.KEEP
                ? SourceUpdateReviewStatus.ACCEPTED : SourceUpdateReviewStatus.REJECTED);
        rejected.setBeforeData(rejectedMethod.getSourceCode());
        rejected.setAfterData("int shippingFee() { return 50; }");
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L))
                .thenReturn(List.of(fixture.item(), rejected));

        assertThatThrownBy(() -> service.applyUpdate(10L, 200L, user(1L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Snapshot");
        assertThat(fixture.method().getSourceCode()).contains("return 0;");
        assertThat(rejectedMethod.getSourceCode()).contains("return 5;");
        verify(javaMethodRepository, never()).save(any());
        verify(javaClassRepository, never()).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "CREATE, REJECTED", "CREATE, ACCEPTED", "CREATE, MODIFIED", "REMOVE, REJECTED", "REMOVE, ACCEPTED"})
    void appliesMixedMethodActionDecisionsToClassAndMetadata(SourceUpdateAction action,
            SourceUpdateReviewStatus decision, @org.junit.jupiter.api.io.TempDir Path snapshot) throws Exception {
        MethodSourceFixture fixture = methodSourceFixture(snapshot, SourceUpdateReviewStatus.ACCEPTED,
                "int calculateDiscount() { return 20; }");
        Path source = snapshot.resolve(fixture.javaClass().getFilePath());
        String candidate = java.nio.file.Files.readString(source).replace("return 10;", "return 20;");
        String addedSource = "int adjustment(int value) { return 70; }\n"
                + (action == SourceUpdateAction.CREATE ? "int adjustment(String value) { return 60; }\n" : "");
        java.nio.file.Files.writeString(source, candidate.replace("List<String> sentinel()", addedSource + "List<String> sentinel()"));
        JavaMethod overload = new JavaMethod();
        overload.setClassId(50L);
        overload.setMethodName("adjustment");
        overload.setParameters(List.of(new MethodParam("value", "int")));
        overload.setSourceCode("int adjustment(int value) { return 70; }");
        JavaMethod existing = new JavaMethod();
        existing.setClassId(50L);
        existing.setMethodName("adjustment");
        existing.setParameters(List.of(new MethodParam("value", "String")));
        existing.setSourceCode("int adjustment(String value) { return 5; }");
        org.mockito.Mockito.lenient().when(javaMethodRepository.findByClassId(50L)).thenReturn(action == SourceUpdateAction.CREATE
                ? List.of(fixture.method(), fixture.overload(), overload)
                : List.of(fixture.method(), fixture.overload(), overload, existing));
        SourceUpdateItem item = new SourceUpdateItem();
        item.setTargetType(SourceUpdateTargetType.METHOD);
        item.setTargetKey("com.example.Outer.OrderService#adjustment(String)");
        item.setAction(action);
        item.setReviewStatus(decision);
        item.setBeforeData(action == SourceUpdateAction.REMOVE ? existing.getSourceCode() : null);
        item.setAfterData("int adjustment(String value) { return 60; }");
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L))
                .thenReturn(List.of(fixture.item(), item));

        if (decision == SourceUpdateReviewStatus.REJECTED) {
            assertThatThrownBy(() -> service.applyUpdate(10L, 200L, user(1L)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Snapshot");
            verify(javaMethodRepository, never()).save(any());
            verify(javaMethodRepository, never()).delete(any());
            verify(javaClassRepository, never()).save(any());
            return;
        }
        service.applyUpdate(10L, 200L, user(1L));

        String classSource = fixture.javaClass().getSourceCode();
        assertThat(classSource).contains("return 20;", "return 70;").doesNotContain("return 81;");
        verify(javaMethodRepository, never()).delete(overload);
        verify(javaMethodRepository, never()).save(overload);
        if (action == SourceUpdateAction.CREATE && decision != SourceUpdateReviewStatus.REJECTED) {
            assertThat(classSource).contains("int adjustment(String value)", "return 60;");
            verify(javaMethodRepository).save(argThat(method -> "adjustment".equals(method.getMethodName())
                    && "int".equals(method.getReturnType())
                    && List.of(new MethodParam("value", "String")).equals(method.getParameters())
                    && method.getSourceCode().contains("return 60;")));
        } else if (action == SourceUpdateAction.REMOVE && decision == SourceUpdateReviewStatus.REJECTED) {
            assertThat(classSource).contains("int adjustment(String value)", "return 5;");
            verify(javaMethodRepository, never()).delete(existing);
        } else {
            assertThat(classSource).doesNotContain("int adjustment(String value)");
            if (action == SourceUpdateAction.REMOVE) verify(javaMethodRepository).delete(existing);
            else verify(javaMethodRepository, never()).save(argThat(method -> "adjustment".equals(method.getMethodName())));
        }
    }

    private MethodSourceFixture methodSourceFixture(Path snapshot, SourceUpdateReviewStatus decision,
            String afterData) throws Exception {
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project(10L, 1L)));
        SourceUpdate update = new SourceUpdate();
        update.setId(200L);
        update.setProjectId(10L);
        update.setCandidateRevisionId(102L);
        update.setStatus(SourceUpdateStatus.READY_TO_APPLY);
        when(sourceUpdateRepository.findByIdAndProjectId(200L, 10L)).thenReturn(Optional.of(update));
        SourceRevision candidate = new SourceRevision();
        candidate.setId(102L);
        candidate.setStoragePath(snapshot.toString());
        when(sourceRevisionRepository.findById(102L)).thenReturn(Optional.of(candidate));
        String candidateSource = """
                package com.example;
                import java.util.List;
                class Outer {
                    static class OrderService {
                        int calculateDiscount() { return 10; }
                        int calculateDiscount(String value) { return 30; }
                        int shippingFee() { return 50; }
                        List<String> sentinel() { return List.of("keep"); }
                        class Nested { int calculateDiscount() { return 40; } }
                    }
                }
                """;
        Path source = snapshot.resolve("src/main/java/com/example/Outer.java");
        java.nio.file.Files.createDirectories(source.getParent());
        java.nio.file.Files.writeString(source, candidateSource);
        JavaClass javaClass = new JavaClass();
        javaClass.setId(50L);
        javaClass.setQualifiedName("com.example.Outer.OrderService");
        javaClass.setClassType(ClassType.SERVICE);
        javaClass.setFilePath("src/main/java/com/example/Outer.java");
        javaClass.setSourceCode(candidateSource.replace("return 10;", "return 0;").replace("return 50;", "return 5;"));
        JavaMethod method = new JavaMethod();
        method.setId(301L);
        method.setClassId(50L);
        method.setMethodName("calculateDiscount");
        method.setSourceCode("int calculateDiscount() { return 0; }");
        JavaMethod overload = new JavaMethod();
        overload.setId(302L);
        overload.setClassId(50L);
        overload.setMethodName("calculateDiscount");
        overload.setParameters(List.of(new MethodParam("value", "String")));
        overload.setSourceCode("int calculateDiscount(String value) { return 30; }");
        if (decision != SourceUpdateReviewStatus.REJECTED) {
            org.mockito.Mockito.lenient().when(javaClassRepository.findByProjectId(10L)).thenReturn(List.of(javaClass));
            org.mockito.Mockito.lenient().when(javaMethodRepository.findByClassId(50L)).thenReturn(List.of(method, overload));
        }
        SourceUpdateItem item = new SourceUpdateItem();
        item.setTargetType(SourceUpdateTargetType.METHOD);
        item.setTargetKey("com.example.Outer.OrderService#calculateDiscount()");
        item.setAction(SourceUpdateAction.UPDATE);
        item.setReviewStatus(decision);
        item.setAfterData(afterData);
        when(sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(200L)).thenReturn(List.of(item));
        return new MethodSourceFixture(update, javaClass, method, overload, item);
    }

    private record MethodSourceFixture(SourceUpdate update, JavaClass javaClass, JavaMethod method,
            JavaMethod overload, SourceUpdateItem item) {}

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
