package com.greytest.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.greytest.dto.SourceRevisionDto;
import com.greytest.dto.SourceUpdateDto;
import com.greytest.dto.SourceUpdateItemDto;
import com.greytest.dto.SourceUpdateItemPatchRequest;
import com.greytest.entity.AuthUser;
import com.greytest.entity.Project;
import com.greytest.entity.SourceRevision;
import com.greytest.entity.SourceUpdate;
import com.greytest.entity.SourceUpdateItem;
import com.greytest.entity.enums.SourceType;
import com.greytest.entity.enums.SourceUpdateStatus;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.exception.GenerationInProgressException;
import com.greytest.mapper.SourceUpdateMapper;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.SourceRevisionRepository;
import com.greytest.repository.SourceUpdateItemRepository;
import com.greytest.repository.SourceUpdateRepository;
import com.greytest.service.storage.SourceRevisionStorageService;
import com.greytest.service.storage.SourceRevisionStorageService.SnapshotResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.greytest.service.analysis.JavaParserHelper;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedBusinessRuleDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestPlanDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.diff.ImpactSummaryDto;
import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.ClassType;
import com.greytest.entity.enums.Priority;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.SourceUpdateAction;
import com.greytest.entity.enums.SourceUpdateReviewStatus;
import com.greytest.entity.enums.SourceUpdateTargetType;
import com.greytest.entity.enums.TestType;
import com.greytest.entity.enums.Visibility;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.TestPlanCoveredRuleRepository;
import com.greytest.entity.TestPlanCoveredRule;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.diff.ImpactAnalysisService;
import com.greytest.service.diff.SourceDiffService;
import com.greytest.entity.MethodParam;
import com.greytest.service.generation.IncrementalCheckpointCoordinator;

import lombok.extern.slf4j.Slf4j;

/**
 * Service quản lý luồng cập nhật source code tăng dần (Source Update Draft) cho GreyTest.
 */
@Slf4j
@Service
public class SourceUpdateService {

    private static final List<SourceUpdateStatus> TERMINAL_STATUSES = List.of(
            SourceUpdateStatus.APPLIED,
            SourceUpdateStatus.CANCELLED
    );

    private final ProjectRepository projectRepository;
    private final SourceRevisionRepository sourceRevisionRepository;
    private final SourceUpdateRepository sourceUpdateRepository;
    private final SourceUpdateItemRepository sourceUpdateItemRepository;
    private final SourceRevisionStorageService storageService;
    private final SourceUpdateMapper sourceUpdateMapper;
    private final SourceDiffService sourceDiffService;
    private final ImpactAnalysisService impactAnalysisService;
    private final BusinessRuleRepository businessRuleRepository;
    private final TestPlanRepository testPlanRepository;
    private final TestPlanCoveredRuleRepository testPlanCoveredRuleRepository;
    private final TestCaseRepository testCaseRepository;
    private final UnitTestRepository unitTestRepository;
    private final JavaClassRepository javaClassRepository;
    private final JavaMethodRepository javaMethodRepository;
    private final ObjectMapper objectMapper;
    private JdbcTemplate generationJdbc;
    private IncrementalCheckpointCoordinator generationCoordinator;

    public SourceUpdateService(
            ProjectRepository projectRepository,
            SourceRevisionRepository sourceRevisionRepository,
            SourceUpdateRepository sourceUpdateRepository,
            SourceUpdateItemRepository sourceUpdateItemRepository,
            SourceRevisionStorageService storageService,
            SourceUpdateMapper sourceUpdateMapper,
            SourceDiffService sourceDiffService,
            ImpactAnalysisService impactAnalysisService,
            BusinessRuleRepository businessRuleRepository,
            TestPlanRepository testPlanRepository,
            TestCaseRepository testCaseRepository,
            UnitTestRepository unitTestRepository,
            JavaClassRepository javaClassRepository,
            JavaMethodRepository javaMethodRepository,
            ObjectMapper objectMapper, TestPlanCoveredRuleRepository testPlanCoveredRuleRepository) {
        this.projectRepository = projectRepository;
        this.sourceRevisionRepository = sourceRevisionRepository;
        this.sourceUpdateRepository = sourceUpdateRepository;
        this.sourceUpdateItemRepository = sourceUpdateItemRepository;
        this.storageService = storageService;
        this.sourceUpdateMapper = sourceUpdateMapper;
        this.sourceDiffService = sourceDiffService;
        this.impactAnalysisService = impactAnalysisService;
        this.businessRuleRepository = businessRuleRepository;
        this.testPlanRepository = testPlanRepository;
        this.testPlanCoveredRuleRepository = testPlanCoveredRuleRepository;
        this.testCaseRepository = testCaseRepository;
        this.unitTestRepository = unitTestRepository;
        this.javaClassRepository = javaClassRepository;
        this.javaMethodRepository = javaMethodRepository;
        this.objectMapper = objectMapper;
    }

    @Autowired
    public SourceUpdateService(ProjectRepository projects, SourceRevisionRepository revisions, SourceUpdateRepository updates,
            SourceUpdateItemRepository items, SourceRevisionStorageService storage, SourceUpdateMapper mapper,
            SourceDiffService diffs, ImpactAnalysisService impacts, BusinessRuleRepository rules,
            TestPlanRepository plans, TestCaseRepository cases, UnitTestRepository tests,
            JavaClassRepository classes, JavaMethodRepository methods, ObjectMapper json,
            TestPlanCoveredRuleRepository coverage, JdbcTemplate jdbc, IncrementalCheckpointCoordinator coordinator) {
        this(projects, revisions, updates, items, storage, mapper, diffs, impacts, rules, plans, cases, tests,
                classes, methods, json, coverage);
        this.generationJdbc = jdbc;
        this.generationCoordinator = coordinator;
    }

    /**
     * Tạo bản nháp cập nhật source từ file ZIP tải lên.
     */
    @Transactional
    public SourceUpdateDto createZipUpdate(Long projectId, MultipartFile file, AuthUser user) {
        Project project = getProjectAndCheckAccess(projectId, user);
        checkNoActiveDraft(projectId);

        SourceRevision baseRevision = ensureBaselineRevision(project);

        SnapshotResult snapshot = storageService.storeZipSnapshot(file);
        registerSnapshotCleanupOnRollback(snapshot.storageDir());

        SourceRevision candidateRevision = new SourceRevision();
        candidateRevision.setProjectId(projectId);
        candidateRevision.setSourceType(SourceType.ZIP);
        candidateRevision.setStoragePath(snapshot.storageDir().toString());
        candidateRevision.setLogicalRoot(snapshot.logicalRoot().toString());
        candidateRevision.setContentHash(snapshot.contentHash());
        candidateRevision = sourceRevisionRepository.save(candidateRevision);

        SourceUpdate update = new SourceUpdate();
        update.setProjectId(projectId);
        update.setBaseRevisionId(baseRevision != null ? baseRevision.getId() : null);
        update.setCandidateRevisionId(candidateRevision.getId());
        update.setStatus(SourceUpdateStatus.DRAFT);
        update.setTotalChangedMethods(0);
        update = sourceUpdateRepository.save(update);

        project.setActiveSourceUpdateId(update.getId());
        projectRepository.save(project);

        log.info("Đã tạo bản nháp cập nhật ZIP (updateId={}) cho project {}", update.getId(), projectId);
        return sourceUpdateMapper.toUpdateDto(update, List.of());
    }

    /**
     * Tạo bản nháp cập nhật source từ nhánh GitHub.
     */
    @Transactional
    public SourceUpdateDto createGithubUpdate(Long projectId, String branch, AuthUser user) {
        Project project = getProjectAndCheckAccess(projectId, user);
        checkNoActiveDraft(projectId);

        if (project.getSourceUrl() == null || project.getSourceUrl().isBlank()) {
            throw new IllegalArgumentException("Project không có source URL GitHub liên kết");
        }

        SourceRevision baseRevision = ensureBaselineRevision(project);

        SnapshotResult snapshot = storageService.storeGithubSnapshot(project.getSourceUrl(), branch);
        registerSnapshotCleanupOnRollback(snapshot.storageDir());

        SourceRevision candidateRevision = new SourceRevision();
        candidateRevision.setProjectId(projectId);
        candidateRevision.setSourceType(SourceType.GITHUB);
        candidateRevision.setSourceUrl(project.getSourceUrl());
        candidateRevision.setBranch(snapshot.branch());
        candidateRevision.setCommitSha(snapshot.commitSha());
        candidateRevision.setStoragePath(snapshot.storageDir().toString());
        candidateRevision.setLogicalRoot(snapshot.logicalRoot().toString());
        candidateRevision.setContentHash(snapshot.contentHash());
        candidateRevision = sourceRevisionRepository.save(candidateRevision);

        SourceUpdate update = new SourceUpdate();
        update.setProjectId(projectId);
        update.setBaseRevisionId(baseRevision != null ? baseRevision.getId() : null);
        update.setCandidateRevisionId(candidateRevision.getId());
        update.setStatus(SourceUpdateStatus.DRAFT);
        update.setTotalChangedMethods(0);
        update = sourceUpdateRepository.save(update);

        project.setActiveSourceUpdateId(update.getId());
        projectRepository.save(project);

        log.info("Đã tạo bản nháp cập nhật GitHub branch={} (updateId={}) cho project {}", branch, update.getId(), projectId);
        return sourceUpdateMapper.toUpdateDto(update, List.of());
    }

    @Transactional
    public SourceUpdateDto analyzeUpdate(Long projectId, Long updateId, AuthUser user) {
        return analyzeUpdate(projectId, updateId, null, user);
    }

    /**
     * Thực hiện phân tích AST diff và tầm ảnh hưởng (Impact Analysis) cho bản nháp cập nhật.
     * Nếu có servicePath, chỉ phân tích và đề xuất cho riêng service đó.
     */
    @Transactional
    public SourceUpdateDto analyzeUpdate(Long projectId, Long updateId, String servicePath, AuthUser user) {
        getProjectAndCheckAccess(projectId, user);
        guardMutation(projectId, updateId);
        SourceUpdate update = sourceUpdateRepository.findByIdAndProjectId(updateId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bản cập nhật " + updateId));

        if (TERMINAL_STATUSES.contains(update.getStatus())) {
            throw new IllegalArgumentException("Không thể phân tích bản cập nhật đã kết thúc: " + update.getStatus());
        }

        List<SourceUpdateItem> existing = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
        if (update.getImpactSummary() != null && !existing.isEmpty()) {
            // Snapshot của một draft là bất biến; phân tích lại không được xóa review hoặc output đã sinh.
            if (!sameAnalysisScope(updateId, servicePath)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Bản nháp đã phân tích; hãy tạo bản nháp mới để đổi phạm vi service");
            }
            verifySnapshotUnchanged(update);
            return sourceUpdateMapper.toUpdateDto(update, existing);
        }
        if (existing.stream().anyMatch(i -> hasUserDecision(i)
                || (i.getTargetType() != SourceUpdateTargetType.METHOD && i.getAfterData() != null))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bản nháp có quyết định review cần được giữ nguyên");
        }

        update.setStatus(SourceUpdateStatus.ANALYZING);
        sourceUpdateRepository.save(update);

        SourceRevision baseRev = update.getBaseRevisionId() != null
                ? sourceRevisionRepository.findById(update.getBaseRevisionId()).orElse(null)
                : null;
        SourceRevision candRev = sourceRevisionRepository.findById(update.getCandidateRevisionId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy snapshot ứng viên"));

        Path basePath = baseRev != null && baseRev.getStoragePath() != null ? Path.of(baseRev.getStoragePath()) : null;
        Path candPath = Path.of(candRev.getStoragePath());

        // 1. So sánh AST các method
        List<MethodDiffItem> diffItems = sourceDiffService.compareSnapshots(basePath, candPath);

        // 2. Phân tích ảnh hưởng lên các artifact trong hệ thống (lọc theo servicePath nếu có)
        ImpactSummaryDto impact = servicePath != null
                ? impactAnalysisService.analyzeImpact(projectId, diffItems, servicePath)
                : impactAnalysisService.analyzeImpact(projectId, diffItems);

        try {
            update.setImpactSummary(objectMapper.writeValueAsString(impact));
        } catch (JsonProcessingException e) {
            update.setImpactSummary("{}");
        }
        update.setTotalChangedMethods(impact.totalChangedMethods());

        // 3. Xóa các đề xuất cũ và tạo mới các SourceUpdateItem
        sourceUpdateItemRepository.deleteBySourceUpdateId(updateId);

        Set<Long> deletedMethodIds = new HashSet<>();
        for (MethodDiffItem changed : impact.changedMethods()) {
            if (!changed.isServiceMethod()) {
                continue;
            }
            SourceUpdateItem item = new SourceUpdateItem();
            item.setSourceUpdateId(updateId);
            item.setTargetType(SourceUpdateTargetType.METHOD);
            item.setTargetKey(changed.methodKey());
            item.setAction(toAction(changed.diffType()));
            if (changed.diffType() != MethodDiffType.ADDED) {
                item.setTargetId(resolveChangedMethodId(projectId, changed));
                if (changed.diffType() == MethodDiffType.DELETED && item.getTargetId() != null) {
                    deletedMethodIds.add(item.getTargetId());
                }
            }
            item.setReason(changed.reason());
            item.setBeforeData(changed.beforeSource());
            item.setAfterData(changed.afterSource());
            item.setReviewStatus(SourceUpdateReviewStatus.PENDING);
            sourceUpdateItemRepository.save(item);
        }

        Set<Long> deletedRuleIds = new HashSet<>();
        for (Long ruleId : impact.affectedBusinessRuleIds()) {
            BusinessRule rule = businessRuleRepository.findById(ruleId).orElse(null);
            boolean isDeleted = rule != null && deletedMethodIds.contains(rule.getMethodId());
            if (isDeleted) {
                deletedRuleIds.add(ruleId);
            }
            SourceUpdateItem item = new SourceUpdateItem();
            item.setSourceUpdateId(updateId);
            item.setTargetType(SourceUpdateTargetType.BUSINESS_RULE);
            item.setTargetId(ruleId);
            item.setAction(isDeleted ? SourceUpdateAction.REMOVE : SourceUpdateAction.UPDATE);
            item.setReason(isDeleted ? "Quy tắc nghiệp vụ thuộc method đã bị xóa khỏi source code" : "Quy tắc nghiệp vụ thuộc method bị thay đổi");
            item.setReviewStatus(SourceUpdateReviewStatus.PENDING);
            sourceUpdateItemRepository.save(item);
        }

        Set<Long> deletedPlanIds = new HashSet<>();
        for (Long planId : impact.affectedTestPlanIds()) {
            TestPlan tp = testPlanRepository.findById(planId).orElse(null);
            boolean isDeleted = tp != null && tp.getBusinessRuleId() != null && deletedRuleIds.contains(tp.getBusinessRuleId());
            if (isDeleted) {
                deletedPlanIds.add(planId);
            }
            SourceUpdateItem item = new SourceUpdateItem();
            item.setSourceUpdateId(updateId);
            item.setTargetType(SourceUpdateTargetType.TEST_PLAN);
            item.setTargetId(planId);
            item.setAction(isDeleted ? SourceUpdateAction.REMOVE : SourceUpdateAction.UPDATE);
            item.setReason(isDeleted ? "Test Plan thuộc Business Rule đã bị xóa" : "Test Plan thuộc Business Rule bị thay đổi");
            item.setReviewStatus(SourceUpdateReviewStatus.PENDING);
            sourceUpdateItemRepository.save(item);
        }

        Set<Long> deletedCaseIds = new HashSet<>();
        for (Long caseId : impact.affectedTestCaseIds()) {
            TestCase tc = testCaseRepository.findById(caseId).orElse(null);
            boolean isDeleted = tc != null && tc.getTestPlanId() != null && deletedPlanIds.contains(tc.getTestPlanId());
            if (isDeleted) {
                deletedCaseIds.add(caseId);
            }
            SourceUpdateItem item = new SourceUpdateItem();
            item.setSourceUpdateId(updateId);
            item.setTargetType(SourceUpdateTargetType.TEST_CASE);
            item.setTargetId(caseId);
            item.setAction(isDeleted ? SourceUpdateAction.REMOVE : SourceUpdateAction.UPDATE);
            item.setReason(isDeleted ? "Test Case thuộc Test Plan đã bị xóa" : "Test Case thuộc Test Plan bị thay đổi");
            item.setReviewStatus(SourceUpdateReviewStatus.PENDING);
            sourceUpdateItemRepository.save(item);
        }

        for (Long unitTestId : impact.affectedUnitTestIds()) {
            UnitTest ut = unitTestRepository.findById(unitTestId).orElse(null);
            boolean isDeleted = ut != null && ut.getTestCaseId() != null && deletedCaseIds.contains(ut.getTestCaseId());
            SourceUpdateItem item = new SourceUpdateItem();
            item.setSourceUpdateId(updateId);
            item.setTargetType(SourceUpdateTargetType.UNIT_TEST);
            item.setTargetId(unitTestId);
            item.setAction(isDeleted ? SourceUpdateAction.REMOVE : SourceUpdateAction.UPDATE);
            item.setReason(isDeleted ? "Unit Test thuộc Test Case đã bị xóa" : "Unit Test thuộc Test Case bị thay đổi");
            item.setReviewStatus(SourceUpdateReviewStatus.PENDING);
            sourceUpdateItemRepository.save(item);
        }

        update.setStatus(SourceUpdateStatus.ANALYZED);
        update = sourceUpdateRepository.save(update);
        rememberAnalysisScope(updateId, servicePath);

        log.info("Đã hoàn tất phân tích AST diff & impact cho bản cập nhật {} của project {}: {} changed methods",
                updateId, projectId, impact.totalChangedMethods());

        List<SourceUpdateItem> items = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
        captureActiveBaselines(items);
        sourceUpdateItemRepository.flush();
        return sourceUpdateMapper.toUpdateDto(update, items);
    }

    private SourceUpdateAction toAction(MethodDiffType diffType) {
        return switch (diffType) {
            case ADDED -> SourceUpdateAction.CREATE;
            case DELETED -> SourceUpdateAction.REMOVE;
            case MODIFIED -> SourceUpdateAction.UPDATE;
            case UNCHANGED -> SourceUpdateAction.KEEP;
        };
    }

    private Long resolveChangedMethodId(Long projectId, MethodDiffItem changed) {
        List<Long> matches = new java.util.ArrayList<>();
        for (JavaClass owner : javaClassRepository.findByProjectId(projectId)) {
            if (!Objects.equals(owner.getQualifiedName(), changed.qualifiedClassName())) continue;
            if (changed.servicePath() != null && !Objects.equals(ServiceScopeResolver.modulePath(owner.getFilePath()),
                    ServiceScopeResolver.normalizeServicePath(changed.servicePath()))) continue;
            for (JavaMethod method : javaMethodRepository.findByClassId(owner.getId())) {
                if (Objects.equals(methodSignature(method), changed.signature()) && method.getId() != null) matches.add(method.getId());
            }
        }
        if (matches.size() > 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Method trùng identity giữa các module; cần phân tích theo servicePath");
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Đọc đường dẫn snapshot ứng viên của bản nháp đã được kiểm tra quyền truy cập.
     */
    @Transactional(readOnly = true)
    public Path getCandidateSourcePath(SourceUpdate update) {
        if (update.getCandidateRevisionId() == null) {
            throw new IllegalArgumentException("Bản cập nhật không có snapshot ứng viên");
        }
        SourceRevision candidate = sourceRevisionRepository.findById(update.getCandidateRevisionId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy snapshot ứng viên"));
        if (!Objects.equals(candidate.getProjectId(), update.getProjectId())) {
            throw new IllegalArgumentException("Snapshot ứng viên không thuộc project của bản cập nhật");
        }
        if (candidate.getStoragePath() == null || candidate.getStoragePath().isBlank()
                || !Files.isDirectory(Path.of(candidate.getStoragePath()))) {
            throw new IllegalArgumentException("Snapshot source cập nhật không còn tồn tại. Vui lòng tải lại source.");
        }
        return Path.of(candidate.getStoragePath());
    }

    /**
     * Lấy thông tin chi tiết một bản nháp cập nhật.
     */
    @Transactional(readOnly = true)
    public SourceUpdateDto getUpdate(Long projectId, Long updateId, AuthUser user) {
        getProjectAndCheckAccess(projectId, user);
        SourceUpdate update = sourceUpdateRepository.findByIdAndProjectId(updateId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bản cập nhật " + updateId));

        List<SourceUpdateItem> items = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
        return sourceUpdateMapper.toUpdateDto(update, items);
    }

    /**
     * Lấy danh sách toàn bộ các bản cập nhật của project.
     */
    @Transactional(readOnly = true)
    public List<SourceUpdateDto> getUpdatesByProject(Long projectId, AuthUser user) {
        getProjectAndCheckAccess(projectId, user);
        return sourceUpdateRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(u -> {
                    List<SourceUpdateItem> items = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(u.getId());
                    return sourceUpdateMapper.toUpdateDto(u, items);
                })
                .toList();
    }

    /**
     * Người dùng cập nhật quyết định duyệt hoặc chỉnh sửa cho 1 item đề xuất trong bản nháp.
     */
    @Transactional
    public SourceUpdateItemDto patchItem(Long projectId, Long updateId, Long itemId, SourceUpdateItemPatchRequest request, AuthUser user) {
        getProjectAndCheckAccess(projectId, user);
        guardMutation(projectId, updateId);
        SourceUpdate update = sourceUpdateRepository.findByIdAndProjectId(updateId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bản cập nhật " + updateId));

        if (TERMINAL_STATUSES.contains(update.getStatus())) {
            throw new IllegalArgumentException("Không thể chỉnh sửa bản cập nhật đã kết thúc: " + update.getStatus());
        }

        SourceUpdateItem item = sourceUpdateItemRepository.findById(itemId)
                .filter(i -> i.getSourceUpdateId().equals(updateId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đề xuất " + itemId));
        lockArtifactRows(List.of(item));

        if (request.action() == null && request.reviewStatus() == null && request.modifiedAfterData() == null
                && request.reason() == null && request.resolution() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần cung cấp quyết định review hoặc nội dung chỉnh sửa");
        }
        if (hasUserDecision(item) && request.expectedVersion() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đề xuất đã có quyết định người dùng; cần version hiện tại");
        }
        if ((request.expectedVersion() != null && !Objects.equals(request.expectedVersion(), item.getVersion()))
                || (request.expectedUserRevision() != null && !Objects.equals(request.expectedUserRevision(), item.getUserRevision()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đề xuất đã thay đổi; hãy tải lại trước khi review");
        }
        boolean originallyCreated = item.getAction() == SourceUpdateAction.CREATE;
        boolean wasRejected = item.getReviewStatus() == SourceUpdateReviewStatus.REJECTED;
        if (request.resolution() != null) {
            resolveConflict(updateId, item, request);
        }

        if (request.action() != null) {
            item.setAction(request.action());
        }
        if (request.reviewStatus() != null) {
            item.setReviewStatus(request.reviewStatus());
        }
        if (request.modifiedAfterData() != null) {
            item.setAfterData(request.modifiedAfterData());
        }
        if (request.reason() != null) {
            item.setReason(request.reason());
        }
        boolean adoptedPhysicalRow = originallyCreated && "KEEP_USER".equals(request.resolution())
                && item.getAction() == SourceUpdateAction.KEEP;
        if (originallyCreated && !adoptedPhysicalRow) {
            if (item.getAction() == SourceUpdateAction.KEEP || item.getAction() == SourceUpdateAction.REMOVE) {
                item.setReviewStatus(SourceUpdateReviewStatus.REJECTED);
            }
            // KEEP cho một artifact mới nghĩa là bỏ đề xuất; vẫn giữ CREATE để chứng minh quyền sở hữu bản ghi tạm.
            item.setAction(SourceUpdateAction.CREATE);
        }
        captureActiveBaselines(List.of(item));

        item.setUserRevision((item.getUserRevision() == null ? 0L : item.getUserRevision()) + 1L);
        if (request.modifiedAfterData() != null || request.action() != null || request.resolution() != null
                || wasRejected != (item.getReviewStatus() == SourceUpdateReviewStatus.REJECTED)) {
            invalidateDependentStages(updateId, item.getTargetType());
        }

        item = sourceUpdateItemRepository.save(item);
        sourceUpdateItemRepository.flush();
        return sourceUpdateMapper.toItemDto(item);
    }

    private void guardMutation(Long projectId, Long updateId) {
        if (generationJdbc == null) return;
        generationJdbc.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE", projectId);
        Integer running = generationJdbc.queryForObject("""
                SELECT COUNT(*) FROM generation_checkpoint WHERE project_id=? AND stage='PIPELINE'
                    AND target_key='__run__' AND state='RUNNING' AND lease_until > clock_timestamp()
                """, Integer.class, projectId);
        if (running != null && running > 0) {
            throw new GenerationInProgressException("Project đang sinh artifact; vui lòng chờ trước khi thay đổi bản nháp");
        }
        generationJdbc.update("""
                INSERT INTO generation_checkpoint(project_id,source_update_id,stage,target_key,state)
                SELECT project_id,id,'PIPELINE','__run__','BLOCKED' FROM source_update WHERE id=? AND project_id=?
                ON CONFLICT (source_update_id,stage,target_key) DO NOTHING
                """, updateId, projectId);
        generationJdbc.queryForList("""
                SELECT id FROM generation_checkpoint WHERE project_id=? AND stage='PIPELINE'
                    AND target_key='__run__' FOR UPDATE
                """, projectId);
    }

    private boolean hasUserDecision(SourceUpdateItem item) {
        return (item.getUserRevision() != null && item.getUserRevision() > 0)
                || (item.getReviewStatus() != null && item.getReviewStatus() != SourceUpdateReviewStatus.PENDING);
    }

    private boolean sameAnalysisScope(Long updateId, String servicePath) {
        String requested = servicePath == null ? "" : servicePath.trim();
        if (generationJdbc == null) return requested.isEmpty();
        var scopes = generationJdbc.queryForList("""
                SELECT provenance ->> 'analysisScope' FROM generation_checkpoint WHERE source_update_id=?
                    AND stage='PIPELINE' AND target_key='__run__'
                """, String.class, updateId);
        return !scopes.isEmpty() && (Objects.equals(scopes.get(0), requested)
                || (scopes.get(0) == null && requested.isEmpty()));
    }

    private void verifySnapshotUnchanged(SourceUpdate update) {
        if (generationJdbc == null) return;
        var revision = sourceRevisionRepository.findById(update.getCandidateRevisionId()).orElseThrow();
        if (revision.getContentHash() == null || revision.getContentHash().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Snapshot cũ thiếu digest; cần bản nháp mới để xác minh source");
        }
        Path root = Path.of(revision.getLogicalRoot() == null ? revision.getStoragePath() : revision.getLogicalRoot());
        if (!Files.isDirectory(root) || !revision.getContentHash().equals(storageService.computeContentHash(root))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Snapshot đã thay đổi; giữ nguyên review và tạo bản nháp source mới");
        }
    }

    private void rememberAnalysisScope(Long updateId, String servicePath) {
        if (generationJdbc == null) return;
        generationJdbc.update("""
                UPDATE generation_checkpoint SET provenance=jsonb_build_object('formatVersion',1,'analysisScope',CAST(? AS text))
                WHERE source_update_id=? AND stage='PIPELINE' AND target_key='__run__'
                """, servicePath == null ? "" : servicePath.trim(), updateId);
    }

    private void resolveConflict(Long updateId, SourceUpdateItem item, SourceUpdateItemPatchRequest request) {
        if (generationJdbc == null || request.expectedVersion() == null || request.expectedUserRevision() == null
                || request.conflictStage() == null || request.conflictTargetKey() == null
                || !Set.of("KEEP_USER", "USE_PROPOSAL").contains(request.resolution())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Giải quyết xung đột cần version và revision hiện tại");
        }
        var proposals = generationJdbc.queryForList("""
                SELECT output_json FROM generation_checkpoint WHERE source_update_id=? AND stage=? AND target_key=?
                    AND state='CONFLICT' FOR UPDATE
                """, String.class, updateId, request.conflictStage(), request.conflictTargetKey());
        if (proposals.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Xung đột đã được thay đổi; hãy tải lại");
        }
        try {
            var parsed = objectMapper.readTree(proposals.get(0));
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("Invalid conflict envelope");
            var proposal = (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
            if (proposal.path("itemId").asLong(-1) != item.getId()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Xung đột không thuộc đề xuất này");
            }
            if (proposal.has("activeSnapshot")) {
                String current = generationCoordinator == null ? null : generationCoordinator.activeSnapshot(item);
                if (current == null || !Objects.equals(current, proposal.path("activeSnapshot").asText(null))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Artifact đã đổi sau khi ghi nhận xung đột; hãy tiếp tục sinh để tải đề xuất mới");
                }
                if ("KEEP_USER".equals(request.resolution())) {
                    item.setAction(SourceUpdateAction.KEEP);
                    item.setReviewStatus(SourceUpdateReviewStatus.MODIFIED);
                } else {
                    // Chỉ lựa chọn rõ ràng của người dùng mới được thay baseline đã ghi lúc tạo artifact tạm.
                    item.setBeforeData(current);
                }
            }
            if ("USE_PROPOSAL".equals(request.resolution())) {
                item.setAction(SourceUpdateAction.valueOf(proposal.path("proposedAction").asText()));
                item.setAfterData(proposal.path("proposedAfterData").asText(null));
                item.setReviewStatus(SourceUpdateReviewStatus.MODIFIED);
            }
            proposal.put("resolution", request.resolution());
            generationJdbc.update("""
                    UPDATE generation_checkpoint SET state='BLOCKED',output_json=?,output_digest=NULL,
                        error='User resolution requires current-context validation',updated_at=clock_timestamp()
                    WHERE source_update_id=? AND stage=? AND target_key=? AND state='CONFLICT'
                    """, objectMapper.writeValueAsString(proposal), updateId, request.conflictStage(), request.conflictTargetKey());
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đề xuất cạnh tranh không hợp lệ", invalid);
        }
    }

    private void invalidateDependentStages(Long updateId, SourceUpdateTargetType targetType) {
        if (generationJdbc == null) return;
        List<String> stages = List.of("METHOD", "BUSINESS_RULE", "TEST_PLAN", "TEST_CASE", "UNIT_TEST");
        int changed = stages.indexOf(targetType.name());
        for (int index = Math.max(0, changed); index < stages.size(); index++) {
            generationJdbc.update("""
                    UPDATE generation_checkpoint SET state='BLOCKED',error='Draft input changed',updated_at=clock_timestamp()
                    WHERE source_update_id=? AND stage=? AND target_key='__stage__'
                    """, updateId, stages.get(index));
        }
    }

    private void ensureProtectedArtifactsReviewed(List<SourceUpdateItem> items) {
        for (SourceUpdateItem item : items) {
            if (item.getTargetId() == null || item.getAction() == SourceUpdateAction.CREATE
                    || item.getAction() == SourceUpdateAction.KEEP || (item.getUserRevision() != null && item.getUserRevision() > 0)
                    || item.getReviewStatus() == SourceUpdateReviewStatus.REJECTED) continue;
            boolean protectedArtifact = switch (item.getTargetType()) {
                case BUSINESS_RULE -> businessRuleRepository.findById(item.getTargetId())
                        .map(r -> r.getStatus() == ReviewStatus.APPROVED || Boolean.TRUE.equals(r.getIsModified())).orElse(false);
                case TEST_PLAN -> testPlanRepository.findById(item.getTargetId())
                        .map(p -> p.getStatus() == ReviewStatus.APPROVED || Boolean.TRUE.equals(p.getIsModified())).orElse(false);
                case TEST_CASE -> testCaseRepository.findById(item.getTargetId())
                        .map(c -> c.getStatus() == ReviewStatus.APPROVED || Boolean.TRUE.equals(c.getIsModified())).orElse(false);
                case UNIT_TEST -> true;
                default -> false;
            };
            if (protectedArtifact) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Artifact đã duyệt/chỉnh sửa cần quyết định review rõ ràng cho đề xuất " + item.getId());
            }
        }
    }

    private void captureActiveBaselines(List<SourceUpdateItem> items) {
        if (generationCoordinator == null) return;
        for (SourceUpdateItem item : items) {
            // CREATE chỉ được ghi baseline tại lần tạo đầu tiên; review/retry không tự nhận một bản main đã sửa.
            if (item.getTargetType() == SourceUpdateTargetType.METHOD || item.getTargetId() == null
                    || item.getAction() == SourceUpdateAction.CREATE || item.getBeforeData() != null) continue;
            String baseline = generationCoordinator.activeSnapshot(item);
            if (baseline != null) {
                item.setBeforeData(baseline);
                sourceUpdateItemRepository.save(item);
            }
        }
    }

    private void assertActiveBaselines(List<SourceUpdateItem> items) {
        for (SourceUpdateItem item : items) {
            if (item.getTargetType() == SourceUpdateTargetType.METHOD || item.getTargetId() == null
                    || item.getAction() == SourceUpdateAction.KEEP
                    || item.getReviewStatus() == SourceUpdateReviewStatus.REJECTED) continue;
            if (item.getBeforeData() == null && item.getAction() != SourceUpdateAction.CREATE) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Đề xuất cũ chưa có snapshot artifact; cần review lại trước Apply: " + item.getId());
            }
            generationCoordinator.assertActiveArtifactUnchanged(item);
        }
    }

    /**
     * Áp dụng toàn bộ các thay đổi trong bản nháp cập nhật vào project chính thức (Atomic Transaction).
     */
    @Transactional
    public SourceUpdateDto applyUpdate(Long projectId, Long updateId, AuthUser user) {
        Project project = getProjectAndCheckAccess(projectId, user);
        guardMutation(projectId, updateId);
        SourceUpdate update = sourceUpdateRepository.findByIdAndProjectId(updateId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bản cập nhật " + updateId));

        if (update.getStatus() == SourceUpdateStatus.APPLIED) {
            List<SourceUpdateItem> items = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
            return sourceUpdateMapper.toUpdateDto(update, items);
        }

        if (update.getStatus() != SourceUpdateStatus.ANALYZED
                && update.getStatus() != SourceUpdateStatus.READY_TO_APPLY) {
            throw new IllegalArgumentException(
                    "Chỉ có thể áp dụng bản cập nhật sau khi phân tích hoàn tất: " + update.getStatus());
        }

        SourceRevision candidate = sourceRevisionRepository.findById(update.getCandidateRevisionId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy revision ứng viên"));
        List<SourceUpdateItem> items = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
        lockArtifactRows(items);
        ensureItemsReviewed(items);
        if (generationCoordinator != null) {
            generationCoordinator.assertReadyForApply(updateId);
            ensureProtectedArtifactsReviewed(items);
            assertActiveBaselines(items);
        }
        // Snapshot được quảng bá nguyên khối; KEEP một method đã đổi cũng cần source ứng viên mới.
        if (items.stream().anyMatch(i -> i.getTargetType() == SourceUpdateTargetType.METHOD
                && (i.getReviewStatus() == SourceUpdateReviewStatus.REJECTED || i.getAction() == SourceUpdateAction.KEEP))) {
            throw new IllegalArgumentException("Snapshot còn method bị từ chối hoặc KEEP. Vui lòng tải source đã loại bỏ thay đổi đó trước khi Apply.");
        }
        var reviewedSources = validateReviewedMethodSources(projectId, candidate, items);
        ensureNoProtectedChildren(items.stream().filter(i -> i.getTargetId() != null
                && ((i.getAction() == SourceUpdateAction.REMOVE && i.getReviewStatus() != SourceUpdateReviewStatus.REJECTED)
                || (i.getAction() == SourceUpdateAction.CREATE && i.getReviewStatus() == SourceUpdateReviewStatus.REJECTED))).toList());
        cleanupDiscardedCreates(items);

        // 1. Cập nhật con trỏ source và revision active của project
        project.setStoragePath(candidate.getStoragePath());
        project.setActiveSourceRevisionId(candidate.getId());
        project.setActiveSourceUpdateId(null);
        projectRepository.save(project);

        int nextBrNumber = nextBusinessRuleNumber(projectId);
        int nextTpNumber = nextTestPlanNumber(projectId);
        int nextTestCaseNumber = nextTestCaseNumber(projectId);

        // Phase 1: Xóa các mục REMOVE theo thứ tự từ dưới lên (UNIT_TEST -> TEST_CASE -> TEST_PLAN -> BUSINESS_RULE -> METHOD)
        // để tránh vi phạm foreign key constraints của PostgreSQL.
        for (SourceUpdateItem item : items) {
            if (item.getAction() != SourceUpdateAction.REMOVE
                    || (item.getReviewStatus() != SourceUpdateReviewStatus.ACCEPTED && item.getReviewStatus() != SourceUpdateReviewStatus.MODIFIED)) {
                continue;
            }
            if (item.getTargetType() == SourceUpdateTargetType.UNIT_TEST && item.getTargetId() != null) {
                unitTestRepository.deleteById(item.getTargetId());
            }
        }

        for (SourceUpdateItem item : items) {
            if (item.getAction() != SourceUpdateAction.REMOVE
                    || (item.getReviewStatus() != SourceUpdateReviewStatus.ACCEPTED && item.getReviewStatus() != SourceUpdateReviewStatus.MODIFIED)) {
                continue;
            }
            if (item.getTargetType() == SourceUpdateTargetType.TEST_CASE && item.getTargetId() != null) {
                UnitTest ut = unitTestRepository.findByTestCaseId(item.getTargetId());
                if (ut != null) {
                    unitTestRepository.delete(ut);
                }
                testCaseRepository.deleteById(item.getTargetId());
            }
        }

        for (SourceUpdateItem item : items) {
            if (item.getAction() != SourceUpdateAction.REMOVE
                    || (item.getReviewStatus() != SourceUpdateReviewStatus.ACCEPTED && item.getReviewStatus() != SourceUpdateReviewStatus.MODIFIED)) {
                continue;
            }
            if (item.getTargetType() == SourceUpdateTargetType.TEST_PLAN && item.getTargetId() != null) {
                testPlanRepository.deleteById(item.getTargetId());
            }
        }

        for (SourceUpdateItem item : items) {
            if (item.getAction() != SourceUpdateAction.REMOVE
                    || (item.getReviewStatus() != SourceUpdateReviewStatus.ACCEPTED && item.getReviewStatus() != SourceUpdateReviewStatus.MODIFIED)) {
                continue;
            }
            if (item.getTargetType() == SourceUpdateTargetType.BUSINESS_RULE && item.getTargetId() != null) {
                businessRuleRepository.deleteById(item.getTargetId());
            }
        }

        for (SourceUpdateItem item : items) {
            if (item.getAction() == SourceUpdateAction.REMOVE && item.getTargetType() == SourceUpdateTargetType.METHOD) {
                if (item.getReviewStatus() == SourceUpdateReviewStatus.REJECTED || item.getAction() == SourceUpdateAction.KEEP) continue;
                applyMethodUpdate(projectId, item);
            }
        }

        // Phase 2: Áp dụng CREATE và UPDATE theo thứ tự từ trên xuống (METHOD -> BR -> TP -> TC -> UT)
        // và ánh xạ ID thật giữa các thực thể cha - con mới được tạo.
        Map<Long, Long> ruleIdMap = new HashMap<>();
        Map<Long, Long> planIdMap = new HashMap<>();
        Map<Long, Long> caseIdMap = new HashMap<>();
        Set<Long> changedOracleRuleIds = new HashSet<>();
        Set<Long> appliedPlansCoveringChangedRules = new HashSet<>();

        for (SourceUpdateItem item : items) {
            if (item.getAction() != SourceUpdateAction.REMOVE && item.getTargetType() == SourceUpdateTargetType.METHOD) {
                if (item.getReviewStatus() == SourceUpdateReviewStatus.REJECTED || item.getAction() == SourceUpdateAction.KEEP) continue;
                applyMethodUpdate(projectId, item);
            }
        }
        synchronizeChangedSource(projectId, items, reviewedSources);

        for (SourceUpdateItem item : items) {
            if (item.getReviewStatus() == SourceUpdateReviewStatus.REJECTED || item.getAction() == SourceUpdateAction.REMOVE
                    || item.getAction() == SourceUpdateAction.KEEP) {
                continue;
            }
            if (item.getTargetType() == SourceUpdateTargetType.BUSINESS_RULE) {
                if (item.getAfterData() != null) {
                    try {
                        GeneratedBusinessRuleDto dto = objectMapper.readValue(item.getAfterData(), GeneratedBusinessRuleDto.class);
                        if (item.getTargetId() != null) {
                            BusinessRule rule = businessRuleRepository.findById(item.getTargetId())
                                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy BR đã sinh " + item.getTargetId()));
                            if (!Objects.equals(rule.getDescription(), dto.description())) changedOracleRuleIds.add(rule.getId());
                                rule.setDescription(dto.description());
                                rule.setStatus(ReviewStatus.APPROVED);
                                rule.setIsModified(false);
                                rule.setReviewNote(SourceBranchReference.write(dto.branchId(), rule.getReviewNote()));
                                businessRuleRepository.save(rule);
                                ruleIdMap.put(item.getTargetId(), rule.getId());
                        } else {
                            BusinessRule rule = new BusinessRule();
                            rule.setProjectId(projectId);
                            rule.setMethodId(dto.methodId());
                            rule.setRuleCode(String.format("BR-%03d", nextBrNumber++));
                            rule.setDescription(dto.description());
                            rule.setSource(RuleSource.AI_GENERATED);
                            rule.setStatus(ReviewStatus.APPROVED);
                            rule.setIsModified(false);
                            rule.setReviewNote(SourceBranchReference.write(dto.branchId(), null));
                            rule = businessRuleRepository.save(rule);
                            if (item.getTargetId() != null) {
                                ruleIdMap.put(item.getTargetId(), rule.getId());
                            }
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("Không thể áp dụng BusinessRule item " + item.getId(), e);
                    }
                } else if (item.getTargetId() != null && item.getAction() == SourceUpdateAction.UPDATE) {
                    businessRuleRepository.findById(item.getTargetId()).ifPresent(rule -> {
                        rule.setIsModified(false);
                        businessRuleRepository.save(rule);
                    });
                }
            }
        }

        for (SourceUpdateItem item : items) {
            if (item.getReviewStatus() == SourceUpdateReviewStatus.REJECTED || item.getAction() == SourceUpdateAction.REMOVE
                    || item.getAction() == SourceUpdateAction.KEEP) {
                continue;
            }
            if (item.getTargetType() == SourceUpdateTargetType.TEST_PLAN) {
                if (item.getAfterData() != null) {
                    try {
                        GeneratedTestPlanDto dto = objectMapper.readValue(item.getAfterData(), GeneratedTestPlanDto.class);
                        Long targetRuleId = ruleIdMap.getOrDefault(dto.ruleId(), dto.ruleId());
                        if (item.getTargetId() != null) {
                            TestPlan plan = testPlanRepository.findById(item.getTargetId())
                                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Test Plan đã sinh " + item.getTargetId()));
                                plan.setTitle(dto.title());
                                plan.setDescription(dto.description());
                                plan.setBusinessRuleId(targetRuleId);
                                plan.setStatus(ReviewStatus.APPROVED);
                                plan.setIsModified(false);
                                testPlanRepository.save(plan);
                                savePlanCoverage(plan.getId(), dto.coveredRuleIds(), ruleIdMap);
                                planIdMap.put(item.getTargetId(), plan.getId());
                                if (coversChangedOracle(dto, changedOracleRuleIds, ruleIdMap)) appliedPlansCoveringChangedRules.add(plan.getId());
                        } else {
                            TestPlan plan = new TestPlan();
                            plan.setProjectId(projectId);
                            plan.setBusinessRuleId(targetRuleId);
                            plan.setPlanCode(String.format("TP-%03d", nextTpNumber++));
                            plan.setTitle(dto.title());
                            plan.setDescription(dto.description());
                            plan.setTestType(TestType.valueOf(dto.testType()));
                            plan.setStatus(ReviewStatus.APPROVED);
                            plan.setIsModified(false);
                            plan = testPlanRepository.save(plan);
                            savePlanCoverage(plan.getId(), dto.coveredRuleIds(), ruleIdMap);
                            if (coversChangedOracle(dto, changedOracleRuleIds, ruleIdMap)) appliedPlansCoveringChangedRules.add(plan.getId());
                            if (item.getTargetId() != null) {
                                planIdMap.put(item.getTargetId(), plan.getId());
                            }
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("Không thể áp dụng TestPlan item " + item.getId(), e);
                    }
                } else if (item.getTargetId() != null && item.getAction() == SourceUpdateAction.UPDATE) {
                    testPlanRepository.findById(item.getTargetId()).ifPresent(plan -> {
                        plan.setIsModified(false);
                        testPlanRepository.save(plan);
                    });
                }
            }
        }

        for (SourceUpdateItem item : items) {
            if (item.getReviewStatus() == SourceUpdateReviewStatus.REJECTED || item.getAction() == SourceUpdateAction.REMOVE
                    || item.getAction() == SourceUpdateAction.KEEP) {
                continue;
            }
            if (item.getTargetType() == SourceUpdateTargetType.TEST_CASE) {
                if (item.getAfterData() != null) {
                    try {
                        GeneratedTestCaseDto dto = objectMapper.readValue(item.getAfterData(), GeneratedTestCaseDto.class);
                        Long targetPlanId = planIdMap.getOrDefault(dto.planId(), dto.planId());
                        if (item.getTargetId() != null) {
                            TestCase tc = testCaseRepository.findById(item.getTargetId())
                                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Test Case đã sinh " + item.getTargetId()));
                                tc.setDescription(dto.description());
                                tc.setPreconditions(dto.preconditions());
                                tc.setTestData(dto.testData());
                                tc.setExpectedResult(dto.expectedResult());
                                tc.setPriority(Priority.valueOf(dto.priority()));
                                tc.setTestPlanId(targetPlanId);
                                tc.setStatus(ReviewStatus.APPROVED);
                                tc.setIsModified(false);
                                testCaseRepository.save(tc);
                                caseIdMap.put(item.getTargetId(), tc.getId());
                        } else {
                            TestCase tc = new TestCase();
                            tc.setTestPlanId(targetPlanId);
                            tc.setCaseCode(String.format("TC-%03d", nextTestCaseNumber++));
                            tc.setTestType(TestType.valueOf(dto.testType()));
                            tc.setDescription(dto.description());
                            tc.setPreconditions(dto.preconditions());
                            tc.setTestData(dto.testData());
                            tc.setExpectedResult(dto.expectedResult());
                            tc.setPriority(Priority.valueOf(dto.priority()));
                            tc.setTraceSource(dto.traceSource());
                            tc.setStatus(ReviewStatus.APPROVED);
                            tc.setIsModified(false);
                            tc = testCaseRepository.save(tc);
                            if (item.getTargetId() != null) {
                                caseIdMap.put(item.getTargetId(), tc.getId());
                            }
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("Không thể áp dụng TestCase item " + item.getId(), e);
                    }
                } else if (item.getTargetId() != null && item.getAction() == SourceUpdateAction.UPDATE) {
                    testCaseRepository.findById(item.getTargetId()).ifPresent(tc -> {
                        tc.setIsModified(false);
                        testCaseRepository.save(tc);
                    });
                }
            }
        }

        for (SourceUpdateItem item : items) {
            if (item.getReviewStatus() == SourceUpdateReviewStatus.REJECTED || item.getAction() == SourceUpdateAction.REMOVE
                    || item.getAction() == SourceUpdateAction.KEEP) {
                continue;
            }
            if (item.getTargetType() == SourceUpdateTargetType.UNIT_TEST) {
                if (item.getAfterData() != null) {
                    try {
                        GeneratedUnitTestDto dto = objectMapper.readValue(item.getAfterData(), GeneratedUnitTestDto.class);
                        Long targetCaseId = caseIdMap.getOrDefault(dto.caseId(), dto.caseId());
                        ensureTestCaseBelongsToProject(targetCaseId, projectId);
                        String genType = (dto.generationType() != null && !dto.generationType().isBlank())
                                ? dto.generationType()
                                : "INCREMENTAL";
                        if (item.getTargetId() != null) {
                            UnitTest ut = unitTestRepository.findById(item.getTargetId())
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "Không tìm thấy Unit Test " + item.getTargetId()));
                            ut.setTestCaseId(targetCaseId);
                            ut.setSourceCode(normalizeUnitTestSource(dto));
                            ut.setTestClassName(dto.testClassName());
                            ut.setTestMethodName(dto.testMethodName());
                            ut.setPackageName(dto.packageName());
                            ut.setGenerationType(genType);
                            ut.setFilePath(unitTestFilePath(dto.packageName(), dto.testClassName()));
                            unitTestRepository.save(ut);
                        } else {
                            UnitTest ut = new UnitTest();
                            ut.setTestCaseId(targetCaseId);
                            ut.setTestClassName(dto.testClassName());
                            ut.setTestMethodName(dto.testMethodName());
                            ut.setPackageName(dto.packageName());
                            ut.setGenerationType(genType);
                            ut.setSourceCode(normalizeUnitTestSource(dto));
                            ut.setFilePath(unitTestFilePath(dto.packageName(), dto.testClassName()));
                            unitTestRepository.save(ut);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("Không thể áp dụng UnitTest item " + item.getId(), e);
                    }
                } else if (item.getTargetId() != null && item.getAction() == SourceUpdateAction.UPDATE) {
                    unitTestRepository.findById(item.getTargetId()).ifPresent(ut -> {
                        ut.setGenerationType("INCREMENTAL");
                        unitTestRepository.save(ut);
                    });
                }
            }
        }

        // 3. Ghi nhận log cập nhật thành công
        List<JavaClass> currentClasses = javaClassRepository.findByProjectId(projectId);
        List<Long> classIds = currentClasses.stream().map(JavaClass::getId).toList();
        int totalMethods = classIds.isEmpty() ? 0 : javaMethodRepository.findByClassIdIn(classIds).size();

        update.setStatus(SourceUpdateStatus.APPLIED);
        update = sourceUpdateRepository.save(update);

        boolean invalidatedPlans = invalidateChangedOracleDescendants(projectId, changedOracleRuleIds, appliedPlansCoveringChangedRules);
        boolean hasUnitTests = items.stream().anyMatch(i -> i.getTargetType() == SourceUpdateTargetType.UNIT_TEST);
        if (invalidatedPlans) {
            project.setStatus(ProjectStatus.PLAN_PENDING_REVIEW);
            projectRepository.save(project);
        } else if (hasUnitTests) {
            project.setStatus(ProjectStatus.TEST_GENERATED);
            projectRepository.save(project);
        }

        log.info("Đã áp dụng thành công bản cập nhật {} cho project {}: {} classes, {} methods",
                updateId, projectId, currentClasses.size(), totalMethods);
        return sourceUpdateMapper.toUpdateDto(update, items);
    }

    /** Giữ output cũ để đối chiếu, nhưng không gọi chúng là đã duyệt theo yêu cầu vừa thay đổi. */
    private boolean invalidateChangedOracleDescendants(Long projectId, Set<Long> changedRuleIds, Set<Long> appliedPlanIds) {
        if (changedRuleIds.isEmpty()) return false;
        Set<Long> planIds = new HashSet<>(appliedPlanIds);
        for (Long ruleId : changedRuleIds) {
            testPlanCoveredRuleRepository.findByBusinessRuleId(ruleId).forEach(link -> planIds.add(link.getTestPlanId()));
        }
        List<TestPlan> affectedPlans = testPlanRepository.findByProjectId(projectId).stream()
                .filter(plan -> changedRuleIds.contains(plan.getBusinessRuleId()) || planIds.contains(plan.getId())).toList();
        for (TestPlan plan : affectedPlans) {
            plan.setStatus(ReviewStatus.PENDING_REVIEW);
            plan.setIsModified(true);
            testPlanRepository.save(plan);
            for (TestCase testCase : testCaseRepository.findByTestPlanId(plan.getId())) {
                testCase.setStatus(ReviewStatus.PENDING_REVIEW);
                testCase.setIsModified(true);
                testCaseRepository.save(testCase);
            }
        }
        return !affectedPlans.isEmpty();
    }

    /** Bao gồm liên kết mới trong payload đã áp dụng, kể cả chưa có persisted coverage link. */
    private boolean coversChangedOracle(GeneratedTestPlanDto dto, Set<Long> changedRuleIds, Map<Long, Long> ruleIdMap) {
        return dto.coveredRuleIds() != null && dto.coveredRuleIds().stream()
                .map(ruleId -> ruleIdMap.getOrDefault(ruleId, ruleId)).anyMatch(changedRuleIds::contains);
    }

    private void savePlanCoverage(Long planId, List<Long> ruleIds, Map<Long, Long> ruleIdMap) {
        Set<Long> desired = ruleIds.stream().map(id -> ruleIdMap.getOrDefault(id, id)).collect(Collectors.toSet());
        Set<Long> existing = testPlanCoveredRuleRepository.findByTestPlanId(planId).stream()
                .map(TestPlanCoveredRule::getBusinessRuleId).collect(Collectors.toSet());
        if (desired.equals(existing)) return;
        testPlanCoveredRuleRepository.deleteByTestPlanId(planId);
        testPlanCoveredRuleRepository.flush();
        for (Long ruleId : desired) {
            TestPlanCoveredRule covered = new TestPlanCoveredRule();
            covered.setTestPlanId(planId);
            covered.setBusinessRuleId(ruleId);
            testPlanCoveredRuleRepository.save(covered);
        }
    }

    /** Source đã duyệt phải khớp snapshot bất biến trước khi ghi metadata. */
    private JavaParserHelper.SourceScanResult validateReviewedMethodSources(Long projectId, SourceRevision candidate,
            List<SourceUpdateItem> items) {
        var changed = items.stream().filter(i -> i.getTargetType() == SourceUpdateTargetType.METHOD
                && (i.getAction() == SourceUpdateAction.CREATE || i.getAction() == SourceUpdateAction.UPDATE
                    || i.getAction() == SourceUpdateAction.REMOVE)
                && (i.getReviewStatus() == SourceUpdateReviewStatus.ACCEPTED
                    || i.getReviewStatus() == SourceUpdateReviewStatus.MODIFIED)).toList();
        if (changed.isEmpty()) return null;
        JavaParserHelper parser = new JavaParserHelper();
        var scanned = parser.scanProject(Path.of(candidate.getStoragePath()));
        if (!scanned.failedParseFiles().isEmpty()) throw new IllegalStateException("Snapshot còn source không parse được");
        List<JavaClass> stored = javaClassRepository.findByProjectId(projectId);
        for (SourceUpdateItem item : changed) {
            try {
                String[] key = item.getTargetKey().split("#", 2);
                JavaClass owner = methodOwner(item, stored);
                if (item.getAction() == SourceUpdateAction.REMOVE) {
                    // REMOVE chỉ hợp lệ khi snapshot không còn method trong đúng module.
                    var remainingFiles = scanned.productionFiles().stream().filter(file -> parser.findTypes(file.compilationUnit()).stream()
                            .anyMatch(type -> type.getFullyQualifiedName().filter(key[0]::equals).isPresent())).toList();
                    if (owner != null && stored.stream().filter(c -> Objects.equals(c.getQualifiedName(), key[0])).count() > 1) {
                        remainingFiles = remainingFiles.stream().filter(file -> ServiceScopeResolver.modulePath(file.relativePath())
                                .equals(ServiceScopeResolver.modulePath(owner.getFilePath()))).toList();
                    }
                    if (remainingFiles.isEmpty()) continue;
                    var file = methodSourceFile(parser, scanned, key[0], owner, stored);
                    boolean methodRemains = parser.findTypes(file.compilationUnit()).stream()
                            .filter(type -> type.getFullyQualifiedName().filter(key[0]::equals).isPresent())
                            .flatMap(type -> type.getMethods().stream()).anyMatch(method -> key[1].equals(parser.methodKey(method)));
                    if (methodRemains) throw new IllegalArgumentException("METHOD REMOVE vẫn còn trong snapshot; cần tải source ứng viên mới");
                    continue;
                }
                MethodDeclaration reviewed = parser.parseMethodDeclaration(item.getAfterData());
                if (!key[1].equals(parser.methodKey(reviewed))) throw new IllegalArgumentException("Signature không khớp target key");
                var file = methodSourceFile(parser, scanned, key[0], owner, stored);
                var type = parser.findTypes(file.compilationUnit()).stream()
                        .filter(t -> t.getFullyQualifiedName().filter(key[0]::equals).isPresent()).findFirst().orElseThrow();
                var methods = type.getMethods().stream().filter(m -> key[1].equals(parser.methodKey(m))).toList();
                if (methods.size() != 1 || !methods.get(0).toString().equals(reviewed.toString())) {
                    throw new IllegalArgumentException("METHOD đã duyệt khác snapshot; cần tải source ứng viên mới");
                }
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("Không thể đồng bộ source method " + item.getTargetKey(), invalid);
            }
        }
        return scanned;
    }

    private List<SourceUpdateItem> appliedMethodItems(List<SourceUpdateItem> items) {
        return items.stream().filter(i -> i.getTargetType() == SourceUpdateTargetType.METHOD
                && (i.getAction() == SourceUpdateAction.CREATE || i.getAction() == SourceUpdateAction.UPDATE)
                && (i.getReviewStatus() == SourceUpdateReviewStatus.ACCEPTED || i.getReviewStatus() == SourceUpdateReviewStatus.MODIFIED)).toList();
    }

    private JavaClass methodOwner(SourceUpdateItem item, List<JavaClass> stored) {
        if (item.getTargetId() != null) {
            JavaMethod method = javaMethodRepository.findById(item.getTargetId()).orElseThrow();
            return stored.stream().filter(c -> Objects.equals(c.getId(), method.getClassId())).findFirst().orElseThrow();
        }
        String owner = item.getTargetKey().split("#", 2)[0];
        var matches = stored.stream().filter(c -> Objects.equals(c.getQualifiedName(), owner)).toList();
        if (matches.size() > 1) throw new IllegalArgumentException("Method thiếu ID và trùng qualified name giữa các module");
        return matches.isEmpty() ? null : matches.get(0);
    }

    private JavaParserHelper.ParsedFile methodSourceFile(JavaParserHelper parser, JavaParserHelper.SourceScanResult scanned,
            String qualifiedName, JavaClass owner, List<JavaClass> stored) {
        var candidates = scanned.productionFiles().stream().filter(file -> parser.findTypes(file.compilationUnit()).stream()
                .anyMatch(type -> type.getFullyQualifiedName().filter(qualifiedName::equals).isPresent())).toList();
        if (owner != null) {
            var samePath = candidates.stream().filter(file -> Objects.equals(file.relativePath(),
                    owner.getFilePath() == null ? null : owner.getFilePath().replace('\\', '/'))).toList();
            if (samePath.size() == 1) return samePath.get(0);
            var sameModule = candidates.stream().filter(file -> ServiceScopeResolver.modulePath(file.relativePath())
                    .equals(ServiceScopeResolver.modulePath(owner.getFilePath()))).toList();
            if (sameModule.size() == 1) return sameModule.get(0);
            if (stored.stream().filter(c -> Objects.equals(c.getQualifiedName(), qualifiedName)).count() > 1)
                throw new IllegalArgumentException("Không xác định duy nhất module source của " + qualifiedName);
        }
        if (candidates.size() != 1) throw new IllegalArgumentException("Không xác định duy nhất source cập nhật của " + qualifiedName);
        return candidates.get(0);
    }

    /** Dùng cùng AST đã xác minh, giữ ID/module và range của source thực tế. */
    private void synchronizeChangedSource(Long projectId, List<SourceUpdateItem> items, JavaParserHelper.SourceScanResult scanned) {
        var changed = appliedMethodItems(items);
        if (changed.isEmpty()) return;
        JavaParserHelper parser = new JavaParserHelper();
        List<JavaClass> stored = javaClassRepository.findByProjectId(projectId);
        Set<Long> affected = affectedClassIds(changed, stored);
        for (JavaClass owner : stored) {
            if (!affected.contains(owner.getId())) continue;
            var file = methodSourceFile(parser, scanned, owner.getQualifiedName(), owner, stored);
            var unit = file.compilationUnit().clone();
            TypeDeclaration<?> type = parser.findTypes(unit).stream().filter(t -> t.getFullyQualifiedName()
                    .filter(owner.getQualifiedName()::equals).isPresent()).findFirst().orElseThrow();
            // Method không đổi nội dung vẫn có thể dịch dòng khi method đứng trước dài hơn.
            for (JavaMethod method : javaMethodRepository.findByClassId(owner.getId())) {
                type.getMethods().stream().filter(declaration -> Objects.equals(methodSignature(method), parser.methodKey(declaration)))
                        .findFirst().ifPresent(declaration -> {
                            method.setLineStart(declaration.getBegin().map(p -> p.line).orElse(1));
                            method.setLineEnd(declaration.getEnd().map(p -> p.line).orElse(method.getLineStart()));
                        });
            }
            while (type.getParentNode().orElse(null) instanceof TypeDeclaration<?> enclosing) type = enclosing;
            unit.setTypes(new NodeList<>(type));
            owner.setSourceCode(unit.toString()); owner.setFilePath(file.relativePath());
            owner.setPackageName(parser.getPackageName(unit));
            javaClassRepository.save(owner);
        }
    }

    private Set<Long> affectedClassIds(List<SourceUpdateItem> changed, List<JavaClass> stored) {
        Set<Long> ids = new HashSet<>();
        for (SourceUpdateItem item : changed) {
            if (item.getTargetId() != null) {
                JavaMethod method = javaMethodRepository.findById(item.getTargetId()).orElseThrow();
                if (stored.stream().noneMatch(c -> Objects.equals(c.getId(), method.getClassId()))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Method không thuộc project của bản nháp");
                }
                ids.add(method.getClassId());
            } else if (item.getTargetKey() != null && item.getTargetKey().contains("#")) {
                String owner = item.getTargetKey().split("#", 2)[0];
                var matches = stored.stream().filter(c -> Objects.equals(c.getQualifiedName(), owner)).toList();
                if (matches.size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Không xác định duy nhất module của method");
                ids.add(matches.get(0).getId());
            }
        }
        return ids;
    }

    private void applyMethodUpdate(Long projectId, SourceUpdateItem item) {
        if (item.getReviewStatus() != SourceUpdateReviewStatus.ACCEPTED
                && item.getReviewStatus() != SourceUpdateReviewStatus.MODIFIED) return;
        String targetKey = item.getTargetKey();
        if (targetKey == null || !targetKey.contains("#")) return;

        String[] parts = targetKey.split("#", 2);
        String qualifiedClassName = parts[0];
        String signature = parts[1];

        List<JavaClass> classes = javaClassRepository.findByProjectId(projectId);
        JavaMethod pinned = item.getTargetId() == null ? null : javaMethodRepository.findById(item.getTargetId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy method " + item.getTargetId()));
        var owners = classes.stream().filter(c -> Objects.equals(c.getQualifiedName(), qualifiedClassName)).toList();
        if (pinned == null && owners.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Method chưa có ID và trùng class giữa các module");
        }
        JavaClass targetClass = pinned != null ? javaClassRepository.findById(pinned.getClassId()).orElseThrow()
                : owners.isEmpty() ? null : owners.get(0);
        if (pinned != null && (!Objects.equals(targetClass.getProjectId(), projectId)
                || !Objects.equals(targetClass.getQualifiedName(), qualifiedClassName)
                || !Objects.equals(methodSignature(pinned), signature))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Method ID không khớp identity của bản nháp");
        }

        // Chỉ cập nhật method cho các class thuộc tầng Service
        if (targetClass != null && targetClass.getClassType() != null && targetClass.getClassType() != ClassType.SERVICE) {
            log.warn("Bỏ qua cập nhật method {} vì class {} không phải là Service (type={})",
                    targetKey, qualifiedClassName, targetClass.getClassType());
            return;
        }

        if (item.getAction() == SourceUpdateAction.REMOVE) {
            if (targetClass != null) {
                List<JavaMethod> methods = pinned != null ? List.of(pinned) : javaMethodRepository.findByClassId(targetClass.getId());
                for (JavaMethod m : methods) {
                    if (Objects.equals(methodSignature(m), signature)) {
                        javaMethodRepository.delete(m);
                    }
                }
            }
        } else if (item.getAction() == SourceUpdateAction.UPDATE) {
            if (targetClass != null && item.getAfterData() != null) {
                List<JavaMethod> methods = pinned != null ? List.of(pinned) : javaMethodRepository.findByClassId(targetClass.getId());
                for (JavaMethod m : methods) {
                    if (Objects.equals(methodSignature(m), signature)) {
                        applyReviewedMethodSource(m, item, signature);
                        javaMethodRepository.save(m);
                    }
                }
            }
        } else if (item.getAction() == SourceUpdateAction.CREATE) {
            if (targetClass == null) {
                targetClass = new JavaClass();
                targetClass.setProjectId(projectId);
                targetClass.setQualifiedName(qualifiedClassName);
                int lastDot = qualifiedClassName.lastIndexOf('.');
                targetClass.setClassName(lastDot > 0 ? qualifiedClassName.substring(lastDot + 1) : qualifiedClassName);
                targetClass.setPackageName(lastDot > 0 ? qualifiedClassName.substring(0, lastDot) : "");
                targetClass.setClassType(ClassType.SERVICE);
                String packagePrefix = qualifiedClassName.substring(0, Math.max(0, qualifiedClassName.lastIndexOf('.')));
                String inferredFilePath = classes.stream()
                        .filter(c -> c.getQualifiedName() != null && c.getQualifiedName().startsWith(packagePrefix))
                        .map(JavaClass::getFilePath)
                        .filter(fp -> fp != null && !fp.isBlank())
                        .findFirst()
                        .map(fp -> {
                            int srcIdx = fp.indexOf("src/main/java/");
                            String prefix = srcIdx > 0 ? fp.substring(0, srcIdx) : "";
                            return prefix + "src/main/java/" + qualifiedClassName.replace('.', '/') + ".java";
                        })
                        .orElse("src/main/java/" + qualifiedClassName.replace('.', '/') + ".java");
                targetClass.setFilePath(inferredFilePath);
                targetClass = javaClassRepository.save(targetClass);
            }
            JavaClass finalClass = targetClass;
            boolean exists = javaMethodRepository.findByClassId(targetClass.getId()).stream()
                    .anyMatch(m -> Objects.equals(methodSignature(m), signature));
            if (!exists) {
                JavaMethod newMethod = new JavaMethod();
                newMethod.setClassId(finalClass.getId());
                applyReviewedMethodSource(newMethod, item, signature);
                newMethod.setLineStart(1);
                newMethod.setLineEnd(Math.max(1, (int) item.getAfterData().lines().count()));
                javaMethodRepository.save(newMethod);
            } else if (item.getAfterData() != null) {
                javaMethodRepository.findByClassId(targetClass.getId()).stream()
                        .filter(m -> Objects.equals(methodSignature(m), signature))
                        .findFirst()
                        .ifPresent(m -> {
                            applyReviewedMethodSource(m, item, signature);
                            javaMethodRepository.save(m);
                        });
            }
        }
    }

    private String methodSignature(JavaMethod method) {
        return method.getMethodName() + "(" + (method.getParameters() == null ? "" : method.getParameters().stream()
                .map(MethodParam::type).collect(Collectors.joining(","))) + ")";
    }

    /** Metadata và source phải cùng phản ánh method đã được người dùng duyệt. */
    private void applyReviewedMethodSource(JavaMethod method, SourceUpdateItem item, String signature) {
        try {
            JavaParserHelper parser = new JavaParserHelper();
            MethodDeclaration declaration = parser.parseMethodDeclaration(item.getAfterData());
            if (!signature.equals(parser.methodKey(declaration))) {
                throw new IllegalArgumentException("Signature method đã duyệt không khớp target key");
            }
            method.setMethodName(declaration.getNameAsString());
            method.setReturnType(declaration.getTypeAsString());
            method.setParameters(declaration.getParameters().stream()
                    .map(parameter -> new MethodParam(parameter.getNameAsString(), parameter.getTypeAsString())).toList());
            method.setThrowsList(declaration.getThrownExceptions().stream().map(Object::toString).toList());
            method.setVisibility(declaration.isPublic() ? Visibility.PUBLIC : declaration.isProtected()
                    ? Visibility.PROTECTED : declaration.isPrivate() ? Visibility.PRIVATE : Visibility.PACKAGE_PRIVATE);
            method.setSourceCode(item.getAfterData());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Không thể đồng bộ source method " + item.getTargetKey(), exception);
        }
    }

    /**
     * Hủy bản nháp cập nhật và dọn dẹp thư mục snapshot ứng viên.
     */
    @Transactional
    public void cancelUpdate(Long projectId, Long updateId, AuthUser user) {
        Project project = getProjectAndCheckAccess(projectId, user);
        guardMutation(projectId, updateId);
        SourceUpdate update = sourceUpdateRepository.findByIdAndProjectId(updateId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bản cập nhật " + updateId));

        if (update.getStatus() == SourceUpdateStatus.APPLIED) {
            throw new IllegalArgumentException("Không thể hủy bản cập nhật đã áp dụng");
        }

        List<SourceUpdateItem> items = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
        lockArtifactRows(items.stream().filter(i -> i.getAction() == SourceUpdateAction.CREATE).toList());
        ensureCancelableArtifacts(items);

        update.setStatus(SourceUpdateStatus.CANCELLED);
        sourceUpdateRepository.save(update);

        // Hoàn tác các thay đổi tạm thời đối với UT, TC, TP, BR, JavaMethod/JavaClass nếu có
        for (SourceUpdateItem item : items) {
            if (item.getAction() == SourceUpdateAction.CREATE && item.getTargetId() != null) {
                if (item.getTargetType() == SourceUpdateTargetType.UNIT_TEST) {
                    unitTestRepository.deleteById(item.getTargetId());
                }
            }
        }
        for (SourceUpdateItem item : items) {
            if (item.getAction() == SourceUpdateAction.CREATE && item.getTargetId() != null) {
                if (item.getTargetType() == SourceUpdateTargetType.TEST_CASE) {
                    testCaseRepository.deleteById(item.getTargetId());
                }
            }
        }
        for (SourceUpdateItem item : items) {
            if (item.getAction() == SourceUpdateAction.CREATE && item.getTargetId() != null) {
                if (item.getTargetType() == SourceUpdateTargetType.TEST_PLAN) {
                    testPlanRepository.deleteById(item.getTargetId());
                }
            }
        }
        for (SourceUpdateItem item : items) {
            if (item.getAction() == SourceUpdateAction.CREATE && item.getTargetId() != null) {
                if (item.getTargetType() == SourceUpdateTargetType.BUSINESS_RULE) {
                    businessRuleRepository.deleteById(item.getTargetId());
                }
            }
        }
        for (SourceUpdateItem item : items) {
            if (item.getTargetType() == SourceUpdateTargetType.METHOD) {
                revertMethodUpdate(projectId, item);
            }
        }

        if (update.getCandidateRevisionId() != null) {
            sourceRevisionRepository.findById(update.getCandidateRevisionId()).ifPresent(candidate -> {
                if (candidate.getStoragePath() != null) {
                    registerSnapshotDeletionAfterCommit(Path.of(candidate.getStoragePath()));
                }
            });
        }

        if (updateId.equals(project.getActiveSourceUpdateId())) {
            project.setActiveSourceUpdateId(null);
            projectRepository.save(project);
        }

        log.info("Đã hủy bản cập nhật nháp {} của project {}", updateId, projectId);
    }

    private void revertMethodUpdate(Long projectId, SourceUpdateItem item) {
        if (item.getAction() != SourceUpdateAction.CREATE || item.getTargetId() == null) return;
        String targetKey = item.getTargetKey();
        if (targetKey == null || !targetKey.contains("#")) return;
        String[] parts = targetKey.split("#", 2);
        String qualifiedClassName = parts[0];
        String signature = parts[1];
        javaMethodRepository.findById(item.getTargetId()).ifPresent(method -> {
            JavaClass owner = javaClassRepository.findById(method.getClassId()).orElseThrow();
            if (!Objects.equals(owner.getProjectId(), projectId) || !Objects.equals(owner.getQualifiedName(), qualifiedClassName)
                    || !Objects.equals(methodSignature(method), signature) || !Objects.equals(method.getSourceCode(), item.getAfterData())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Method tạm đã thay đổi; không thể tự động xóa khi hủy bản nháp");
            }
            javaMethodRepository.delete(method);
        });
    }

    private void ensureCancelableArtifacts(List<SourceUpdateItem> items) {
        ensureNoProtectedChildren(items.stream().filter(i -> i.getAction() == SourceUpdateAction.CREATE
                && i.getTargetId() != null && i.getTargetType() != SourceUpdateTargetType.METHOD).toList());
        if (generationCoordinator == null) return;
        for (SourceUpdateItem item : items) {
            if (item.getAction() != SourceUpdateAction.CREATE || item.getTargetId() == null
                    || item.getTargetType() == SourceUpdateTargetType.METHOD) continue;
            generationCoordinator.assertActiveArtifactUnchanged(item);
            boolean modified = switch (item.getTargetType()) {
                case BUSINESS_RULE -> businessRuleRepository.findById(item.getTargetId()).map(r -> Boolean.TRUE.equals(r.getIsModified())).orElse(false);
                case TEST_PLAN -> testPlanRepository.findById(item.getTargetId()).map(p -> Boolean.TRUE.equals(p.getIsModified())).orElse(false);
                case TEST_CASE -> testCaseRepository.findById(item.getTargetId()).map(c -> Boolean.TRUE.equals(c.getIsModified())).orElse(false);
                default -> false;
            };
            if (modified || !generationCoordinator.isRecordedOutput(item)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Artifact có chỉnh sửa hoặc thiếu provenance; giữ nguyên dữ liệu trước khi hủy: " + item.getId());
            }
        }
    }

    private void lockArtifactRows(List<SourceUpdateItem> items) {
        if (generationJdbc == null) return;
        for (SourceUpdateTargetType type : List.of(SourceUpdateTargetType.BUSINESS_RULE, SourceUpdateTargetType.TEST_PLAN,
                SourceUpdateTargetType.TEST_CASE, SourceUpdateTargetType.UNIT_TEST, SourceUpdateTargetType.METHOD)) {
            String sql = switch (type) {
                case BUSINESS_RULE -> "SELECT id FROM business_rule WHERE id=? FOR UPDATE";
                case TEST_PLAN -> "SELECT id FROM test_plan WHERE id=? FOR UPDATE";
                case TEST_CASE -> "SELECT id FROM test_case WHERE id=? FOR UPDATE";
                case UNIT_TEST -> "SELECT id FROM unit_test WHERE id=? FOR UPDATE";
                case METHOD -> "SELECT id FROM java_method WHERE id=? FOR UPDATE";
            };
            for (Long id : items.stream().filter(i -> i.getTargetType() == type).map(SourceUpdateItem::getTargetId)
                    .filter(Objects::nonNull).distinct().sorted().toList()) {
                generationJdbc.queryForList(sql, id);
            }
        }
    }

    private void cleanupDiscardedCreates(List<SourceUpdateItem> items) {
        var discarded = items.stream().filter(i -> i.getAction() == SourceUpdateAction.CREATE
                && i.getReviewStatus() == SourceUpdateReviewStatus.REJECTED && i.getTargetId() != null
                && i.getTargetType() != SourceUpdateTargetType.METHOD).toList();
        ensureCancelableArtifacts(discarded);
        for (SourceUpdateTargetType type : List.of(SourceUpdateTargetType.UNIT_TEST, SourceUpdateTargetType.TEST_CASE,
                SourceUpdateTargetType.TEST_PLAN, SourceUpdateTargetType.BUSINESS_RULE)) {
            for (SourceUpdateItem item : discarded) {
                if (item.getTargetType() != type) continue;
                switch (type) {
                    case UNIT_TEST -> unitTestRepository.deleteById(item.getTargetId());
                    case TEST_CASE -> testCaseRepository.deleteById(item.getTargetId());
                    case TEST_PLAN -> testPlanRepository.deleteById(item.getTargetId());
                    case BUSINESS_RULE -> businessRuleRepository.deleteById(item.getTargetId());
                    default -> { }
                }
            }
        }
    }

    private void ensureNoProtectedChildren(List<SourceUpdateItem> deleting) {
        Map<SourceUpdateTargetType, Set<Long>> ids = new java.util.EnumMap<>(SourceUpdateTargetType.class);
        for (SourceUpdateTargetType type : SourceUpdateTargetType.values()) ids.put(type, new HashSet<>());
        for (SourceUpdateItem item : deleting) ids.get(item.getTargetType()).add(item.getTargetId());
        for (Long caseId : ids.get(SourceUpdateTargetType.TEST_CASE)) {
            for (UnitTest child : unitTestRepository.findByTestCaseIdIn(List.of(caseId))) {
                requireExplicitChildDelete(ids.get(SourceUpdateTargetType.UNIT_TEST), child.getId());
            }
        }
        for (Long planId : ids.get(SourceUpdateTargetType.TEST_PLAN)) {
            for (TestCase child : testCaseRepository.findByTestPlanId(planId)) {
                requireExplicitChildDelete(ids.get(SourceUpdateTargetType.TEST_CASE), child.getId());
            }
        }
        for (Long ruleId : ids.get(SourceUpdateTargetType.BUSINESS_RULE)) {
            for (TestPlan child : testPlanRepository.findByBusinessRuleId(ruleId)) {
                requireExplicitChildDelete(ids.get(SourceUpdateTargetType.TEST_PLAN), child.getId());
            }
            if (testPlanCoveredRuleRepository != null) {
                for (var covered : testPlanCoveredRuleRepository.findByBusinessRuleId(ruleId)) {
                    requireExplicitChildDelete(ids.get(SourceUpdateTargetType.TEST_PLAN), covered.getTestPlanId());
                }
            }
        }
    }

    private void requireExplicitChildDelete(Set<Long> approved, Long childId) {
        if (!approved.contains(childId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Artifact còn bản ghi con được giữ lại; cần quyết định xóa rõ ràng trước khi tiếp tục");
        }
    }

    /**
     * Đảm bảo baseline SourceRevision tồn tại cho project hiện hành.
     */
    @Transactional
    public SourceRevision ensureBaselineRevision(Project project) {
        if (project.getActiveSourceRevisionId() != null) {
            return sourceRevisionRepository.findById(project.getActiveSourceRevisionId()).orElse(null);
        }

        // Tạo baseline từ storagePath hiện tại của project
        if (project.getStoragePath() == null || !Files.isDirectory(Path.of(project.getStoragePath()))) {
            return null;
        }

        Path currentDir = Path.of(project.getStoragePath());
        SnapshotResult baselineSnapshot = storageService.createBaselineFromExistingDir(currentDir);
        registerSnapshotCleanupOnRollback(baselineSnapshot.storageDir());

        SourceRevision baseline = new SourceRevision();
        baseline.setProjectId(project.getId());
        baseline.setSourceType(project.getSourceType());
        baseline.setSourceUrl(project.getSourceUrl());
        baseline.setStoragePath(baselineSnapshot.storageDir().toString());
        baseline.setLogicalRoot(baselineSnapshot.logicalRoot().toString());
        baseline.setContentHash(baselineSnapshot.contentHash());
        baseline = sourceRevisionRepository.save(baseline);

        project.setActiveSourceRevisionId(baseline.getId());
        projectRepository.save(project);

        log.info("Đã khởi tạo baseline SourceRevision (id={}) cho project {}", baseline.getId(), project.getId());
        return baseline;
    }

    private void checkNoActiveDraft(Long projectId) {
        sourceUpdateRepository.findFirstByProjectIdAndStatusNotIn(projectId, TERMINAL_STATUSES)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Project đang có một bản cập nhật nháp chưa hoàn tất (ID: " + existing.getId() + "). Vui lòng hoàn thành hoặc hủy bản nháp trước.");
        });
    }

    private void registerSnapshotCleanupOnRollback(Path snapshotDir) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    storageService.delete(snapshotDir);
                }
            }
        });
    }

    private void registerSnapshotDeletionAfterCommit(Path snapshotDir) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            storageService.delete(snapshotDir);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    storageService.delete(snapshotDir);
                }
            }
        });
    }

    private void ensureItemsReviewed(List<SourceUpdateItem> items) {
        items.stream()
                .filter(item -> item.getReviewStatus() != SourceUpdateReviewStatus.ACCEPTED
                        && item.getReviewStatus() != SourceUpdateReviewStatus.MODIFIED
                        && item.getReviewStatus() != SourceUpdateReviewStatus.REJECTED)
                .findFirst()
                .ifPresent(item -> {
                    throw new IllegalArgumentException(
                            "Không thể áp dụng khi đề xuất " + item.getId() + " còn ở trạng thái "
                                    + item.getReviewStatus());
                });
    }

    private Project getProjectAndCheckAccess(Long projectId, AuthUser user) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        checkProjectAccess(project, user);
        return project;
    }

    void checkProjectAccess(Project project, AuthUser user) {
        if (user != null && user.getRole() != UserRole.ADMIN && !user.getId().equals(project.getOwnerUserId())) {
            throw new AuthException("FORBIDDEN", "Không có quyền truy cập project này", HttpStatus.FORBIDDEN);
        }
    }

    private String unitTestFilePath(String packageName, String testClassName) {
        String packagePath = packageName == null || packageName.isBlank()
                ? ""
                : packageName.replace('.', '/') + "/";
        return "src/test/java/" + packagePath + testClassName + ".java";
    }

    private int nextBusinessRuleNumber(Long projectId) {
        int max = 0;
        for (BusinessRule rule : businessRuleRepository.findByProjectId(projectId)) {
            String code = rule.getRuleCode();
            if (code == null || !code.startsWith("BR-")) continue;
            try {
                max = Math.max(max, Integer.parseInt(code.substring(3)));
            } catch (NumberFormatException ignored) {
            }
        }
        return max + 1;
    }

    private int nextTestPlanNumber(Long projectId) {
        int max = 0;
        for (TestPlan plan : testPlanRepository.findByProjectId(projectId)) {
            String code = plan.getPlanCode();
            if (code == null || !code.startsWith("TP-")) continue;
            try {
                max = Math.max(max, Integer.parseInt(code.substring(3)));
            } catch (NumberFormatException ignored) {
            }
        }
        return max + 1;
    }

    private int nextTestCaseNumber(Long projectId) {
        int max = 0;
        for (TestPlan plan : testPlanRepository.findByProjectId(projectId)) {
            for (TestCase testCase : testCaseRepository.findByTestPlanId(plan.getId())) {
                String code = testCase.getCaseCode();
                if (code == null || !code.startsWith("TC-")) continue;
                try {
                    max = Math.max(max, Integer.parseInt(code.substring(3)));
                } catch (NumberFormatException ignored) {
                    // Bỏ qua mã cũ không theo format TC-xxx.
                }
            }
        }
        return max + 1;
    }

    private void ensureTestCaseBelongsToProject(Long caseId, Long projectId) {
        TestCase testCase = testCaseRepository.findById(caseId).orElse(null);
        TestPlan testPlan = testCase == null ? null : testPlanRepository.findById(testCase.getTestPlanId()).orElse(null);
        if (testPlan == null || !projectId.equals(testPlan.getProjectId())) {
            throw new IllegalArgumentException("Test Case " + caseId + " không thuộc project " + projectId);
        }
    }

    private String normalizeUnitTestSource(GeneratedUnitTestDto dto) {
        String source = dto.sourceCode() == null ? "" : dto.sourceCode();
        source = source.startsWith("\uFEFF") ? source.substring(1) : source;
        source = source.replace("org.mockito.Matchers", "org.mockito.ArgumentMatchers");
        if (source.contains("// GreyTest trace:")) return source;
        TestCase testCase = testCaseRepository.findById(dto.caseId()).orElse(null);
        if (testCase == null) return source;
        return "// GreyTest trace: " + testCase.getCaseCode() + " | " + testCase.getTraceSource() + "\n" + source;
    }

    private ImpactSummaryDto parseImpactSummary(String json) {
        if (json == null || json.isBlank()) {
            return new ImpactSummaryDto(0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        try {
            return objectMapper.readValue(json, ImpactSummaryDto.class);
        } catch (Exception e) {
            log.warn("Không thể parse impactSummary JSON: {}", e.getMessage());
            return new ImpactSummaryDto(0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
}
