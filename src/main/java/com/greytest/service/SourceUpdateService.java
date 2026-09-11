package com.greytest.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

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
import com.greytest.mapper.SourceUpdateMapper;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.SourceRevisionRepository;
import com.greytest.repository.SourceUpdateItemRepository;
import com.greytest.repository.SourceUpdateRepository;
import com.greytest.service.storage.SourceRevisionStorageService;
import com.greytest.service.storage.SourceRevisionStorageService.SnapshotResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedBusinessRuleDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestPlanDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.diff.ImpactSummaryDto;
import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.Priority;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.SourceUpdateAction;
import com.greytest.entity.enums.SourceUpdateReviewStatus;
import com.greytest.entity.enums.SourceUpdateTargetType;
import com.greytest.entity.enums.TestType;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.service.diff.ImpactAnalysisService;
import com.greytest.service.diff.SourceDiffService;

import lombok.extern.slf4j.Slf4j;

/**
 * Service quản lý luồng cập nhật source code tăng dần (Source Update Draft) cho GreyTest.
 */
@Slf4j
@Service
public class SourceUpdateService {

    private static final List<SourceUpdateStatus> TERMINAL_STATUSES = List.of(
            SourceUpdateStatus.APPLIED,
            SourceUpdateStatus.CANCELLED,
            SourceUpdateStatus.FAILED
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
    private final TestCaseRepository testCaseRepository;
    private final UnitTestRepository unitTestRepository;
    private final ObjectMapper objectMapper;

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
            ObjectMapper objectMapper) {
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
        this.testCaseRepository = testCaseRepository;
        this.unitTestRepository = unitTestRepository;
        this.objectMapper = objectMapper;
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

    /**
     * Thực hiện phân tích AST diff và tầm ảnh hưởng (Impact Analysis) cho bản nháp cập nhật.
     */
    @Transactional
    public SourceUpdateDto analyzeUpdate(Long projectId, Long updateId, AuthUser user) {
        getProjectAndCheckAccess(projectId, user);
        SourceUpdate update = sourceUpdateRepository.findByIdAndProjectId(updateId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bản cập nhật " + updateId));

        if (TERMINAL_STATUSES.contains(update.getStatus())) {
            throw new IllegalArgumentException("Không thể phân tích bản cập nhật đã kết thúc: " + update.getStatus());
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

        // 2. Phân tích ảnh hưởng lên các artifact trong hệ thống
        ImpactSummaryDto impact = impactAnalysisService.analyzeImpact(projectId, diffItems);

        try {
            update.setImpactSummary(objectMapper.writeValueAsString(impact));
        } catch (JsonProcessingException e) {
            update.setImpactSummary("{}");
        }
        update.setTotalChangedMethods(impact.totalChangedMethods());

        // 3. Xóa các đề xuất cũ và tạo mới các SourceUpdateItem
        sourceUpdateItemRepository.deleteBySourceUpdateId(updateId);

        for (MethodDiffItem changed : impact.changedMethods()) {
            SourceUpdateItem item = new SourceUpdateItem();
            item.setSourceUpdateId(updateId);
            item.setTargetType(SourceUpdateTargetType.METHOD);
            item.setTargetKey(changed.methodKey());
            item.setAction(toAction(changed.diffType()));
            item.setReason(changed.reason());
            item.setBeforeData(changed.beforeSource());
            item.setAfterData(changed.afterSource());
            item.setReviewStatus(SourceUpdateReviewStatus.PENDING);
            sourceUpdateItemRepository.save(item);
        }

        for (Long ruleId : impact.affectedBusinessRuleIds()) {
            SourceUpdateItem item = new SourceUpdateItem();
            item.setSourceUpdateId(updateId);
            item.setTargetType(SourceUpdateTargetType.BUSINESS_RULE);
            item.setTargetId(ruleId);
            item.setAction(SourceUpdateAction.UPDATE);
            item.setReason("Quy tắc nghiệp vụ thuộc method bị thay đổi");
            item.setReviewStatus(SourceUpdateReviewStatus.PENDING);
            sourceUpdateItemRepository.save(item);
        }

        update.setStatus(SourceUpdateStatus.ANALYZED);
        update = sourceUpdateRepository.save(update);

        log.info("Đã hoàn tất phân tích AST diff & impact cho bản cập nhật {} của project {}: {} changed methods",
                updateId, projectId, impact.totalChangedMethods());

        List<SourceUpdateItem> items = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
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
        SourceUpdate update = sourceUpdateRepository.findByIdAndProjectId(updateId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bản cập nhật " + updateId));

        if (TERMINAL_STATUSES.contains(update.getStatus())) {
            throw new IllegalArgumentException("Không thể chỉnh sửa bản cập nhật đã kết thúc: " + update.getStatus());
        }

        SourceUpdateItem item = sourceUpdateItemRepository.findById(itemId)
                .filter(i -> i.getSourceUpdateId().equals(updateId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đề xuất " + itemId));

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

        item = sourceUpdateItemRepository.save(item);
        return sourceUpdateMapper.toItemDto(item);
    }

    /**
     * Áp dụng toàn bộ các thay đổi trong bản nháp cập nhật vào project chính thức (Atomic Transaction).
     */
    @Transactional
    public SourceUpdateDto applyUpdate(Long projectId, Long updateId, AuthUser user) {
        Project project = getProjectAndCheckAccess(projectId, user);
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
        ensureItemsReviewed(items);

        // 1. Cập nhật con trỏ source và revision active của project
        project.setStoragePath(candidate.getStoragePath());
        project.setActiveSourceRevisionId(candidate.getId());
        project.setActiveSourceUpdateId(null);
        projectRepository.save(project);

        // 2. Duyệt các item đề xuất và áp dụng vào DB
        int brCount = businessRuleRepository.findByProjectId(projectId).size();
        int nextTestCaseNumber = nextTestCaseNumber(projectId);

        for (SourceUpdateItem item : items) {
            if (item.getReviewStatus() == SourceUpdateReviewStatus.REJECTED) {
                continue;
            }

            if (item.getTargetType() == SourceUpdateTargetType.BUSINESS_RULE) {
                if (item.getAction() == SourceUpdateAction.REMOVE && item.getTargetId() != null) {
                    if (item.getReviewStatus() == SourceUpdateReviewStatus.ACCEPTED || item.getReviewStatus() == SourceUpdateReviewStatus.MODIFIED) {
                        businessRuleRepository.deleteById(item.getTargetId());
                    }
                } else if (item.getAfterData() != null && (item.getAction() == SourceUpdateAction.CREATE || item.getAction() == SourceUpdateAction.UPDATE)) {
                    try {
                        GeneratedBusinessRuleDto dto = objectMapper.readValue(item.getAfterData(), GeneratedBusinessRuleDto.class);
                        if (item.getTargetId() != null) {
                            businessRuleRepository.findById(item.getTargetId()).ifPresent(rule -> {
                                rule.setDescription(dto.description());
                                rule.setStatus(ReviewStatus.APPROVED);
                                businessRuleRepository.save(rule);
                            });
                        } else {
                            BusinessRule rule = new BusinessRule();
                            rule.setProjectId(projectId);
                            rule.setMethodId(dto.methodId());
                            rule.setRuleCode(String.format("BR-%03d", ++brCount));
                            rule.setDescription(dto.description());
                            rule.setSource(RuleSource.AI_GENERATED);
                            rule.setStatus(ReviewStatus.APPROVED);
                            rule.setIsModified(false);
                            businessRuleRepository.save(rule);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("Không thể áp dụng BusinessRule item " + item.getId(), e);
                    }
                }
            } else if (item.getTargetType() == SourceUpdateTargetType.TEST_PLAN) {
                if (item.getAction() == SourceUpdateAction.REMOVE && item.getTargetId() != null) {
                    if (item.getReviewStatus() == SourceUpdateReviewStatus.ACCEPTED || item.getReviewStatus() == SourceUpdateReviewStatus.MODIFIED) {
                        testPlanRepository.deleteById(item.getTargetId());
                    }
                } else if (item.getAfterData() != null && (item.getAction() == SourceUpdateAction.CREATE || item.getAction() == SourceUpdateAction.UPDATE)) {
                    try {
                        GeneratedTestPlanDto dto = objectMapper.readValue(item.getAfterData(), GeneratedTestPlanDto.class);
                        if (item.getTargetId() != null) {
                            testPlanRepository.findById(item.getTargetId()).ifPresent(plan -> {
                                plan.setTitle(dto.title());
                                plan.setDescription(dto.description());
                                plan.setStatus(ReviewStatus.APPROVED);
                                testPlanRepository.save(plan);
                            });
                        } else {
                            TestPlan plan = new TestPlan();
                            plan.setProjectId(projectId);
                            plan.setBusinessRuleId(dto.ruleId());
                            plan.setPlanCode(String.format("TP-%03d", testPlanRepository.findByProjectId(projectId).size() + 1));
                            plan.setTitle(dto.title());
                            plan.setDescription(dto.description());
                            plan.setTestType(TestType.valueOf(dto.testType()));
                            plan.setStatus(ReviewStatus.APPROVED);
                            plan.setIsModified(false);
                            testPlanRepository.save(plan);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("Không thể áp dụng TestPlan item " + item.getId(), e);
                    }
                }
            } else if (item.getTargetType() == SourceUpdateTargetType.TEST_CASE) {
                if (item.getAction() == SourceUpdateAction.REMOVE && item.getTargetId() != null) {
                    if (item.getReviewStatus() == SourceUpdateReviewStatus.ACCEPTED || item.getReviewStatus() == SourceUpdateReviewStatus.MODIFIED) {
                        testCaseRepository.deleteById(item.getTargetId());
                    }
                } else if (item.getAfterData() != null && (item.getAction() == SourceUpdateAction.CREATE || item.getAction() == SourceUpdateAction.UPDATE)) {
                    try {
                        GeneratedTestCaseDto dto = objectMapper.readValue(item.getAfterData(), GeneratedTestCaseDto.class);
                        if (item.getTargetId() != null) {
                            testCaseRepository.findById(item.getTargetId()).ifPresent(tc -> {
                                tc.setDescription(dto.description());
                                tc.setPreconditions(dto.preconditions());
                                tc.setTestData(dto.testData());
                                tc.setExpectedResult(dto.expectedResult());
                                tc.setPriority(Priority.valueOf(dto.priority()));
                                tc.setStatus(ReviewStatus.APPROVED);
                                testCaseRepository.save(tc);
                            });
                        } else {
                            TestCase tc = new TestCase();
                            tc.setTestPlanId(dto.planId());
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
                            testCaseRepository.save(tc);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("Không thể áp dụng TestCase item " + item.getId(), e);
                    }
                }
            } else if (item.getTargetType() == SourceUpdateTargetType.UNIT_TEST) {
                if (item.getAction() == SourceUpdateAction.REMOVE && item.getTargetId() != null) {
                    if (item.getReviewStatus() == SourceUpdateReviewStatus.ACCEPTED || item.getReviewStatus() == SourceUpdateReviewStatus.MODIFIED) {
                        unitTestRepository.deleteById(item.getTargetId());
                    }
                } else if (item.getAfterData() != null && (item.getAction() == SourceUpdateAction.CREATE || item.getAction() == SourceUpdateAction.UPDATE)) {
                    try {
                        GeneratedUnitTestDto dto = objectMapper.readValue(item.getAfterData(), GeneratedUnitTestDto.class);
                        ensureTestCaseBelongsToProject(dto.caseId(), projectId);
                        if (item.getTargetId() != null) {
                            UnitTest ut = unitTestRepository.findById(item.getTargetId())
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "Không tìm thấy Unit Test " + item.getTargetId()));
                            if (!Objects.equals(ut.getTestCaseId(), dto.caseId())) {
                                throw new IllegalArgumentException("Unit Test không thuộc Test Case được cập nhật");
                            }
                            ut.setSourceCode(normalizeUnitTestSource(dto));
                            ut.setTestClassName(dto.testClassName());
                            ut.setTestMethodName(dto.testMethodName());
                            ut.setPackageName(dto.packageName());
                            ut.setGenerationType(dto.generationType());
                            ut.setFilePath(unitTestFilePath(dto.packageName(), dto.testClassName()));
                            unitTestRepository.save(ut);
                        } else {
                            UnitTest ut = new UnitTest();
                            ut.setTestCaseId(dto.caseId());
                            ut.setTestClassName(dto.testClassName());
                            ut.setTestMethodName(dto.testMethodName());
                            ut.setPackageName(dto.packageName());
                            ut.setGenerationType(dto.generationType());
                            ut.setSourceCode(normalizeUnitTestSource(dto));
                            ut.setFilePath(unitTestFilePath(dto.packageName(), dto.testClassName()));
                            unitTestRepository.save(ut);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("Không thể áp dụng UnitTest item " + item.getId(), e);
                    }
                }
            }
        }

        update.setStatus(SourceUpdateStatus.APPLIED);
        update = sourceUpdateRepository.save(update);

        log.info("Đã áp dụng thành công bản cập nhật {} cho project {}", updateId, projectId);
        return sourceUpdateMapper.toUpdateDto(update, items);
    }

    /**
     * Hủy bản nháp cập nhật và dọn dẹp thư mục snapshot ứng viên.
     */
    @Transactional
    public void cancelUpdate(Long projectId, Long updateId, AuthUser user) {
        Project project = getProjectAndCheckAccess(projectId, user);
        SourceUpdate update = sourceUpdateRepository.findByIdAndProjectId(updateId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bản cập nhật " + updateId));

        if (update.getStatus() == SourceUpdateStatus.APPLIED) {
            throw new IllegalArgumentException("Không thể hủy bản cập nhật đã áp dụng");
        }

        update.setStatus(SourceUpdateStatus.CANCELLED);
        sourceUpdateRepository.save(update);

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
}
