package com.greytest.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.greytest.dto.BusinessRuleDto;
import com.greytest.dto.BusinessRuleReviewDto;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.SourceBranchDto;
import com.greytest.dto.CreateBusinessRuleRequest;
import com.greytest.dto.ReviewedBusinessRuleDto;
import com.greytest.dto.UpdateBusinessRuleRequest;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleReviewResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedBusinessRuleDto;
import com.greytest.dto.agent.GenerationResponseDtos.ReviewedBusinessRuleSuggestionDto;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.Project;
import com.greytest.entity.enums.ClassType;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.exception.UsageQuotaExceededException;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.GenerationContextBuilder;
import com.greytest.service.agent.LlmResponseException;
import com.greytest.service.analysis.MethodBranchAnalyzer;

/** Quản lý Business Rule, điều phối sinh AI theo gói và lưu an toàn từng batch hợp lệ. */
@Service
public class BusinessRuleService {

    // ponytail: tam luu suggestion trong review_note; tach cot rieng khi can lich su review.
    private static final String SUGGESTION_MARKER = "\nAI_SUGGESTION:";
    private static final String SOURCE_BRANCH_MARKER = "SOURCE_BRANCH:";
    private static final int MAX_SEMANTIC_ATTEMPTS = 3;

    private final BusinessRuleRepository businessRuleRepository;
    private final ProjectRepository projectRepository;
    private final JavaClassRepository javaClassRepository;
    private final JavaMethodRepository javaMethodRepository;
    private final AIAgentService aiAgentService;
    private final GenerationProgressService generationProgressService;
    private final TransactionTemplate transactions;
    private ServiceScopeResolver scopeResolver;
    private ServicePipelineStatusService scopedStatuses;
    private LlmBatchExecutor batchExecutor;

    public BusinessRuleService(
            BusinessRuleRepository businessRuleRepository,
            ProjectRepository projectRepository,
            JavaClassRepository javaClassRepository,
            JavaMethodRepository javaMethodRepository,
            AIAgentService aiAgentService,
            GenerationProgressService generationProgressService,
            PlatformTransactionManager transactionManager) {
        this.businessRuleRepository = businessRuleRepository;
        this.projectRepository = projectRepository;
        this.javaClassRepository = javaClassRepository;
        this.javaMethodRepository = javaMethodRepository;
        this.aiAgentService = aiAgentService;
        this.generationProgressService = generationProgressService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public BusinessRuleService(
            BusinessRuleRepository businessRuleRepository,
            ProjectRepository projectRepository,
            JavaClassRepository javaClassRepository,
            JavaMethodRepository javaMethodRepository,
            AIAgentService aiAgentService,
            GenerationProgressService generationProgressService,
            PlatformTransactionManager transactionManager,
            ServiceScopeResolver scopeResolver,
            ServicePipelineStatusService scopedStatuses) {
        this(businessRuleRepository, projectRepository, javaClassRepository, javaMethodRepository,
                aiAgentService, generationProgressService, transactionManager);
        this.scopeResolver = scopeResolver;
        this.scopedStatuses = scopedStatuses;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BusinessRuleService(
            BusinessRuleRepository businessRuleRepository,
            ProjectRepository projectRepository,
            JavaClassRepository javaClassRepository,
            JavaMethodRepository javaMethodRepository,
            AIAgentService aiAgentService,
            GenerationProgressService generationProgressService,
            PlatformTransactionManager transactionManager,
            ServiceScopeResolver scopeResolver,
            ServicePipelineStatusService scopedStatuses,
            LlmBatchExecutor batchExecutor) {
        this(businessRuleRepository, projectRepository, javaClassRepository, javaMethodRepository,
                aiAgentService, generationProgressService, transactionManager, scopeResolver, scopedStatuses);
        this.batchExecutor = batchExecutor;
    }

    @Transactional(readOnly = true)
    public List<BusinessRuleDto> list(Long projectId) {
        ensureProjectExists(projectId);
        return businessRuleRepository.findByProjectId(projectId).stream()
                .sorted(Comparator.comparingInt((BusinessRule rule) -> ruleNumber(rule.getRuleCode()))
                        .thenComparing(BusinessRule::getRuleCode, Comparator.nullsLast(String::compareTo)))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BusinessRuleDto> list(Long projectId, String servicePath) {
        Set<Long> methodIds = scopeResolver.resolve(projectId, servicePath).methodIds();
        return businessRuleRepository.findByProjectId(projectId).stream()
                .filter(rule -> rule.getMethodId() != null && methodIds.contains(rule.getMethodId()))
                .sorted(Comparator.comparingInt((BusinessRule rule) -> ruleNumber(rule.getRuleCode()))
                        .thenComparing(BusinessRule::getRuleCode, Comparator.nullsLast(String::compareTo)))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Long projectIdForRule(Long ruleId) {
        return businessRuleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy business rule " + ruleId))
                .getProjectId();
    }


    @Transactional
    public BusinessRuleDto create(Long projectId, String servicePath, CreateBusinessRuleRequest request) {
        Set<Long> methodIds = scopeResolver.resolve(projectId, servicePath).methodIds();
        if (!methodIds.contains(request.methodId())) {
            throw new IllegalArgumentException("Service method không thuộc servicePath đã chọn.");
        }
        return create(projectId, request);
    }

    @Transactional
    public BusinessRuleDto create(Long projectId, CreateBusinessRuleRequest request) {
        Project project = ensureProjectExists(projectId);
        ensureBusinessRuleEditable(project);
        String description = request.description().trim();
        List<BusinessRule> existingRules = businessRuleRepository.findByProjectId(projectId);
        ensureUniqueDescription(existingRules.stream()
                .filter(rule -> request.methodId().equals(rule.getMethodId()))
                .toList(), null, description);

        Long validMethodId = requireProjectServiceMethod(projectId, request.methodId());
        String sourceBranchId = requireSourceBranch(validMethodId, request.sourceBranchId());
        ensureDecisionAvailable(existingRules, null, validMethodId, sourceBranchId);

        BusinessRule rule = new BusinessRule();
        rule.setProjectId(projectId);
        rule.setMethodId(validMethodId);
        rule.setRuleCode(nextRuleCode(nextRuleNumber(existingRules)));
        rule.setDescription(description);
        rule.setSource(RuleSource.USER_ADDED);
        rule.setStatus(ReviewStatus.PENDING_REVIEW);
        rule.setIsModified(true);
        rule.setReviewNote(withSourceBranch(
                sourceBranchId,
                "AI review là tùy chọn để kiểm tra độ rõ ràng."));
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
        return toDto(businessRuleRepository.save(rule));
    }

    @Transactional
    public BusinessRuleDto update(Long ruleId, UpdateBusinessRuleRequest request) {
        BusinessRule rule = businessRuleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy business rule " + ruleId));
        Project project = ensureProjectExists(rule.getProjectId());
        ensureBusinessRuleEditable(project);
        String description = request.description().trim();
        ensureUniqueDescription(businessRuleRepository.findByProjectId(rule.getProjectId()).stream()
                .filter(existing -> request.methodId().equals(existing.getMethodId()))
                .toList(), ruleId, description);

        String sourceBranchId = request.methodId().equals(rule.getMethodId())
                ? sourceBranchId(rule.getReviewNote())
                : null;
        rule.setMethodId(requireProjectServiceMethod(rule.getProjectId(), request.methodId()));
        rule.setDescription(description);
        rule.setSource(RuleSource.USER_MODIFIED);
        rule.setStatus(ReviewStatus.PENDING_REVIEW);
        rule.setIsModified(true);
        rule.setReviewNote(withSourceBranch(
                sourceBranchId,
                "Đã sửa thủ công. AI review lại là tùy chọn."));
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
        return toDto(businessRuleRepository.save(rule));
    }

    @Transactional
    public void delete(Long ruleId) {
        BusinessRule rule = businessRuleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy business rule " + ruleId));
        Project project = ensureProjectExists(rule.getProjectId());
        ensureBusinessRuleEditable(project);
        // Cascade DB xóa Plan/Case/Unit Test gắn với rule này; đưa project về chờ review lại
        businessRuleRepository.delete(rule);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
    }

    public List<BusinessRuleDto> generate(Long projectId) {
        return generate(projectId, true);
    }

    public List<BusinessRuleDto> generate(Long projectId, boolean resume) {
        return generate(projectId, null, null, resume);
    }

    public List<BusinessRuleDto> generate(Long projectId, String servicePath) {
        return generate(projectId, servicePath, true);
    }

    public List<BusinessRuleDto> generate(Long projectId, String servicePath, boolean resume) {
        var scope = scopeResolver.resolve(projectId, servicePath);
        return generate(projectId, scope.methodIds(), scopedStatuses.status(projectId, scope), resume);
    }

    private List<BusinessRuleDto> generate(
            Long projectId,
            Set<Long> scopedMethodIds,
            ProjectStatus scopedStatus, boolean resume) {
        Project project = ensureProjectExists(projectId);
        ensureBusinessRuleEditable(scopedStatus == null ? project.getStatus() : scopedStatus);

        List<JavaMethod> serviceMethods = serviceMethods(projectId).stream()
                .filter(method -> scopedMethodIds == null || scopedMethodIds.contains(method.getId()))
                .toList();
        Set<Long> validMethodIds = serviceMethods.stream()
                .map(JavaMethod::getId)
                .collect(Collectors.toSet());
        if (validMethodIds.isEmpty()) {
            throw new InvalidProjectStatusException(
                    "Project chưa có service method nào để AI sinh Business Rule. Hãy kiểm tra kết quả Analysis.");
        }

        List<BusinessRule> existingRules = businessRuleRepository.findByProjectId(projectId).stream()
                .filter(rule -> scopedMethodIds == null || (rule.getMethodId() != null && scopedMethodIds.contains(rule.getMethodId())))
                .toList();
        ProjectStatus effectiveStatus = scopedStatus == null ? project.getStatus() : scopedStatus;
        // Chỉ thao tác Sinh lại mới được cập nhật BR AI chưa sửa; Tiếp tục giữ kết quả đã lưu.
        boolean backfillAiRules = !resume && effectiveStatus == ProjectStatus.BR_PENDING_REVIEW
                && existingRules.stream().anyMatch(rule -> rule.getSource() == RuleSource.AI_GENERATED
                        && !Boolean.TRUE.equals(rule.getIsModified()));
        // Tiếp tục chỉ sinh decision còn thiếu; Sinh lại là lựa chọn rõ ràng cho BR AI chưa sửa.
        Set<Long> uncoveredMethodIds = serviceMethods.stream()
                .filter(method -> !resume || needsGeneration(method, existingRules))
                .map(JavaMethod::getId)
                .collect(Collectors.toCollection(HashSet::new));
        if (uncoveredMethodIds.isEmpty()) {
            // Nếu lần chạy trước đã lưu đủ BR nhưng chưa kịp cập nhật project status,
            // retry chỉ cần hoàn tất bước chuyển trạng thái, không gọi LLM lại.
            if (project.getStatus() == ProjectStatus.ANALYZED && !existingRules.isEmpty()) {
                project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
                projectRepository.save(project);
            }
            return List.of();
        }

        Map<Long, String> methodNameMap = serviceMethods.stream()
                .collect(Collectors.toMap(JavaMethod::getId, JavaMethod::getMethodName, (a, b) -> a));
        List<Set<Long>> allBatches = serviceMethodBatches(projectId, validMethodIds);
        int totalBatches = allBatches.size();
        List<String> stepLabels = new ArrayList<>();
        for (int i = 0; i < totalBatches; i++) {
            String summary = formatMethodBatchSummary(allBatches.get(i), methodNameMap);
            stepLabels.add("Sinh Business Rule - batch " + (i + 1) + "/" + totalBatches + ": " + summary);
        }
        stepLabels.add("Kiểm tra và lưu Business Rule vào CSDL");

        Set<Integer> completedBatchIndices = new LinkedHashSet<>();
        List<BusinessRuleBatch> pendingBatches = new ArrayList<>();
        for (int i = 0; i < totalBatches; i++) {
            Set<Long> activeIds = allBatches.get(i).stream().filter(uncoveredMethodIds::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (activeIds.isEmpty()) completedBatchIndices.add(i);
            else pendingBatches.add(new BusinessRuleBatch(i + 1, activeIds));
        }
        int firstPendingBatchIndex = pendingBatches.get(0).number() - 1;
        if (!completedBatchIndices.isEmpty()) {
            generationProgressService.resume(projectId, GenerationProgressStage.BUSINESS_RULE,
                    stepLabels, completedBatchIndices,
                    "Tiếp tục sinh các batch còn thiếu (" + uncoveredMethodIds.size() + " Service method còn lại).");
        } else {
            generationProgressService.start(projectId, GenerationProgressStage.BUSINESS_RULE, stepLabels,
                    "Đã xác định " + serviceMethods.size() + " Service method trong " + totalBatches + " batch.");
        }

        List<BusinessRuleDto> created = new ArrayList<>();
        Set<String> existingRuleKeys = ruleKeys(existingRules);
        int firstRuleNumber = nextRuleNumber(businessRuleRepository.findByProjectId(projectId));
        List<BusinessRuleBatch> batchesToRun = pendingBatches;
        AtomicInteger failedSaveBatch = new AtomicInteger();
        int lastAttemptedBatchNumber = firstPendingBatchIndex + 1;
        try {
        LlmBatchExecutor.mapOrSequential(
                batchExecutor,
                batchesToRun,
                () -> failedSaveBatch.get() > 0
                        || generationProgressService.isPaused(projectId, GenerationProgressStage.BUSINESS_RULE),
                batch -> {
                    String summary = formatMethodBatchSummary(batch.methodIds(), methodNameMap);
                    generationProgressService.setStepStatus(projectId, GenerationProgressStage.BUSINESS_RULE,
                            batch.number() - 1, com.greytest.dto.GenerationProgressStepStatus.RUNNING);
                    generationProgressService.log(projectId, GenerationProgressStage.BUSINESS_RULE,
                            "Đang gọi AI phân tích batch " + batch.number() + "/" + totalBatches
                                    + " (" + summary + ")...");
                    return generateValidatedBusinessRules(projectId, batch.methodIds());
                },
                (completedRunBatch, response) -> {
                    if (failedSaveBatch.get() > 0) return;
                    BusinessRuleBatch current = batchesToRun.get(completedRunBatch - 1);
                    try {
                        // Callback đã được executor chạy tuần tự; lưu ngay để lỗi batch khác không làm mất kết quả hợp lệ.
                        List<BusinessRuleDto> saved = saveGeneratedRulesInTransaction(
                                projectId, orderedBatchRules(current.methodIds(), response), RuleSource.AI_GENERATED,
                                current.methodIds(), Set.of(), existingRuleKeys,
                                firstRuleNumber + created.size(), backfillAiRules);
                        created.addAll(saved);
                        generationProgressService.advanceStep(projectId, GenerationProgressStage.BUSINESS_RULE,
                                current.number() - 1,
                                "Batch " + current.number() + "/" + totalBatches + ": đã sinh "
                                        + saved.size() + " Business Rule cho "
                                        + formatMethodBatchSummary(current.methodIds(), methodNameMap) + ".");
                    } catch (RuntimeException exception) {
                        failedSaveBatch.set(current.number());
                        throw exception;
                    }
                });

        if (generationProgressService.isPaused(projectId, GenerationProgressStage.BUSINESS_RULE)) {
            generationProgressService.log(projectId, GenerationProgressStage.BUSINESS_RULE,
                    "Tác vụ đã tạm dừng. Các Business Rule đã sinh được lưu an toàn vào CSDL. Nhấn 'Tiếp tục sinh' khi bạn sẵn sàng.");
            return created;
        }

        lastAttemptedBatchNumber = totalBatches + 1;
        generationProgressService.log(projectId, GenerationProgressStage.BUSINESS_RULE,
                "Đang kiểm tra tính nhất quán và hoàn tất cập nhật trạng thái...");
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
        generationProgressService.completeAfterCommit(projectId, GenerationProgressStage.BUSINESS_RULE,
                "Hoàn tất: đã lưu " + created.size() + " Business Rule cho " + uncoveredMethodIds.size() + " service method.");
        return created;
        } catch (RuntimeException exception) {
            if (generationProgressService.isPaused(projectId, GenerationProgressStage.BUSINESS_RULE)) {
                return created;
            }
            int failedRunBatch = LlmBatchExecutor.failedBatch(exception, 0);
            if (failedSaveBatch.get() > 0) {
                lastAttemptedBatchNumber = failedSaveBatch.get();
            } else if (failedRunBatch > 0 && failedRunBatch <= batchesToRun.size()) {
                lastAttemptedBatchNumber = batchesToRun.get(failedRunBatch - 1).number();
            }
            if (lastAttemptedBatchNumber <= totalBatches) {
                generationProgressService.failStep(projectId, GenerationProgressStage.BUSINESS_RULE,
                        lastAttemptedBatchNumber - 1, "Sinh Business Rule thất bại.");
            }
            boolean isQuota = isUsageQuotaExceeded(exception);
            String failureLocation = lastAttemptedBatchNumber <= totalBatches
                    ? "Dừng ở batch " + lastAttemptedBatchNumber + "/" + totalBatches + "."
                    : "Dừng ở bước kiểm tra và lưu Business Rule.";
            String persistedSummary = created.isEmpty()
                    ? "Chưa lưu thêm Business Rule nào trong phiên này."
                    : "Đã lưu " + created.size() + " Business Rule trong phiên này.";
            String detailMsg = isQuota
                    ? " Bạn đã sử dụng hết quota LLM trong tháng này. Vui lòng nâng cấp lên gói PRO để tiếp tục sinh."
                    : " Sinh Business Rule thất bại; xem thông báo lỗi để biết chi tiết.";
            generationProgressService.fail(projectId, GenerationProgressStage.BUSINESS_RULE,
                    failureLocation + " " + persistedSummary + detailMsg);
            throw LlmBatchExecutor.originalFailure(exception);
        }
    }

    private record BusinessRuleBatch(int number, Set<Long> methodIds) { }

    private List<GeneratedBusinessRuleDto> orderedBatchRules(
            Set<Long> methodIds, BusinessRuleResponseDto response) {
        List<GeneratedBusinessRuleDto> orderedRules = new ArrayList<>();
        for (Long methodId : methodIds) {
            response.rules().stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(rule -> methodId.equals(rule.methodId()))
                    .sorted(Comparator.comparingInt(rule -> branchOrder(methodId, rule.branchId())))
                    .forEach(orderedRules::add);
        }
        return orderedRules;
    }

    private boolean isUsageQuotaExceeded(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof UsageQuotaExceededException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String formatMethodBatchSummary(Set<Long> methodIds, Map<Long, String> methodNameMap) {
        if (methodIds == null || methodIds.isEmpty()) return "0 method";
        List<String> names = methodIds.stream()
                .map(id -> methodNameMap.getOrDefault(id, "Method#" + id))
                .toList();
        return String.join(", ", names);
    }

    @Transactional
    public BusinessRuleReviewDto review(Long projectId, String servicePath) {
        var scope = scopeResolver.resolve(projectId, servicePath);
        return review(projectId, scope.methodIds(), scopedStatuses.status(projectId, scope));
    }

    @Transactional
    public BusinessRuleReviewDto review(Long projectId) {
        return review(projectId, null, null);
    }

    private BusinessRuleReviewDto review(
            Long projectId,
            Set<Long> scopedMethodIds,
            ProjectStatus scopedStatus) {
        Project project = ensureProjectExists(projectId);
        ensureBusinessRuleEditable(scopedStatus == null ? project.getStatus() : scopedStatus);

        List<BusinessRule> existingRules = businessRuleRepository.findByProjectId(projectId).stream()
                .filter(rule -> scopedMethodIds == null || (rule.getMethodId() != null && scopedMethodIds.contains(rule.getMethodId())))
                .toList();
        List<BusinessRule> dirtyRules = existingRules.stream()
                .filter(rule -> Boolean.TRUE.equals(rule.getIsModified()))
                .toList();
        if (dirtyRules.isEmpty()) {
            throw new InvalidProjectStatusException(
                    "Không có Business Rule do người dùng thêm hoặc sửa cần AI review.");
        }

        Set<Long> pendingRuleIds = dirtyRules.stream().map(BusinessRule::getId).collect(Collectors.toSet());
        List<ReviewedBusinessRuleDto> reviewed = new ArrayList<>();
        while (!pendingRuleIds.isEmpty()) {
            List<BusinessRule> activeRules = dirtyRules.stream()
                    .filter(rule -> pendingRuleIds.contains(rule.getId()))
                    .sorted(Comparator.comparing(BusinessRule::getRuleCode, Comparator.nullsLast(String::compareTo)))
                    .limit(GenerationContextBuilder.MAX_REVIEW_RULES)
                    .toList();
            BusinessRuleReviewResponseDto response = scopedMethodIds == null
                    ? aiAgentService.reviewBusinessRules(projectId)
                    : aiAgentService.reviewBusinessRules(projectId,
                            activeRules.stream().map(BusinessRule::getId).collect(Collectors.toSet()));
            List<ReviewedBusinessRuleDto> reviewedBatch = applyReviewSuggestions(
                    response.reviewedRules(),
                    activeRules);
            if (reviewedBatch.isEmpty()) {
                throw new LlmResponseException("AI không review Business Rule nào trong danh sách cần review.");
            }
            reviewedBatch.stream().map(ReviewedBusinessRuleDto::ruleId).forEach(pendingRuleIds::remove);
            reviewed.addAll(reviewedBatch);
        }
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
        return new BusinessRuleReviewDto(reviewed, List.of());
    }

    @Transactional
    public BusinessRuleDto acceptSuggestion(Long ruleId) {
        BusinessRule rule = businessRuleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Business Rule " + ruleId));
        Project project = ensureProjectExists(rule.getProjectId());
        ensureBusinessRuleEditable(project);
        String suggestion = suggestedDescription(rule.getReviewNote());
        if (suggestion == null) {
            throw new IllegalArgumentException("Business Rule không có gợi ý AI để áp dụng.");
        }
        ensureUniqueDescription(businessRuleRepository.findByProjectId(rule.getProjectId()).stream()
                .filter(existing -> rule.getMethodId().equals(existing.getMethodId()))
                .toList(), ruleId, suggestion);
        rule.setDescription(suggestion);
        rule.setSource(RuleSource.USER_MODIFIED);
        rule.setStatus(ReviewStatus.PENDING_REVIEW);
        rule.setIsModified(false);
        rule.setReviewNote(withSourceBranch(
                sourceBranchId(rule.getReviewNote()),
                "Đã áp dụng gợi ý AI."));
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
        return toDto(businessRuleRepository.save(rule));
    }

    @Transactional
    public List<BusinessRuleDto> approve(Long projectId, String servicePath) {
        return approve(projectId, scopeResolver.resolve(projectId, servicePath).methodIds());
    }

    @Transactional
    public List<BusinessRuleDto> approve(Long projectId) {
        return approve(projectId, (Set<Long>) null);
    }

    private List<BusinessRuleDto> approve(Long projectId, Set<Long> scopedMethodIds) {
        Project project = ensureProjectExists(projectId);
        List<BusinessRule> rules = businessRuleRepository.findByProjectId(projectId).stream()
                .filter(rule -> scopedMethodIds == null || (rule.getMethodId() != null && scopedMethodIds.contains(rule.getMethodId())))
                .toList();
        if (rules.isEmpty()) {
            throw new InvalidProjectStatusException("Cần có ít nhất một Business Rule trước khi approve.");
        }
        ensureNoDuplicateDecisions(rules);
        List<String> missingBranches = serviceMethods(projectId).stream()
                .filter(method -> scopedMethodIds == null || scopedMethodIds.contains(method.getId()))
                .flatMap(method -> {
                    Set<String> covered = rules.stream()
                            .filter(rule -> method.getId().equals(rule.getMethodId()))
                            .map(rule -> sourceBranchId(rule.getReviewNote()))
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toSet());
                    return decisionIds(method).stream()
                            .filter(branchId -> !covered.contains(branchId))
                            .map(branchId -> method.getMethodName() + ":" + branchId);
                })
                .toList();
        if (!missingBranches.isEmpty()) {
            throw new InvalidProjectStatusException(
                    "Chưa thể approve vì còn quyết định source chưa có Business Rule: " + missingBranches);
        }
        for (BusinessRule rule : rules) {
            rule.setStatus(ReviewStatus.APPROVED);
            rule.setIsModified(false);
            if (rule.getReviewNote() == null || rule.getReviewNote().isBlank()) {
                rule.setReviewNote("Approved by user.");
            }
            businessRuleRepository.save(rule);
        }
        project.setStatus(ProjectStatus.BR_APPROVED);
        projectRepository.save(project);
        return rules.stream().map(this::toDto).toList();
    }

    private List<ReviewedBusinessRuleDto> applyReviewSuggestions(
            List<ReviewedBusinessRuleSuggestionDto> suggestions,
            List<BusinessRule> existingRules) {
        Map<Long, BusinessRule> rulesById = existingRules.stream()
                .filter(rule -> rule.getId() != null)
                .collect(Collectors.toMap(BusinessRule::getId, rule -> rule, (first, second) -> first));
        List<ReviewedBusinessRuleDto> reviewed = new ArrayList<>();
        for (ReviewedBusinessRuleSuggestionDto suggestion : suggestions) {
            BusinessRule rule = rulesById.get(suggestion.ruleId());
            if (rule == null) continue;
            // Bất đồng với source không phải đề xuất thay yêu cầu của người dùng.
            if ("SOURCE_MISMATCH".equals(suggestion.verdict())) {
                suggestion = new ReviewedBusinessRuleSuggestionDto(
                        suggestion.ruleId(), suggestion.verdict(), null, suggestion.reason());
            }
            rule.setReviewNote(withSourceBranch(
                    sourceBranchId(rule.getReviewNote()),
                    reviewNote(suggestion)));
            rule.setIsModified(false);
            businessRuleRepository.save(rule);
            reviewed.add(new ReviewedBusinessRuleDto(
                    rule.getId(),
                    suggestion.verdict(),
                    suggestion.suggestedDescription(),
                    suggestion.reason()));
        }
        return reviewed;
    }

    private List<BusinessRuleDto> saveGeneratedRulesInTransaction(
            Long projectId,
            List<GeneratedBusinessRuleDto> generatedRules,
            RuleSource source,
            Set<Long> validMethodIds,
            Set<Long> blockedMethodIds,
            Set<String> existingRuleKeys,
            int firstRuleNumber,
            boolean replaceAiGenerated) {
        return transactions.execute(status -> saveGeneratedRules(
                projectId,
                generatedRules,
                source,
                validMethodIds,
                blockedMethodIds,
                existingRuleKeys,
                firstRuleNumber,
                replaceAiGenerated));
    }
    private List<BusinessRuleDto> saveGeneratedRules(
            Long projectId,
            List<GeneratedBusinessRuleDto> generatedRules,
            RuleSource source,
            Set<Long> validMethodIds,
            Set<Long> blockedMethodIds,
            Set<String> existingRuleKeys,
            int firstRuleNumber,
            boolean replaceAiGenerated) {
        List<BusinessRuleDto> created = new ArrayList<>();
        int ruleNumber = firstRuleNumber;
        Map<String, BusinessRule> existingByKey = businessRuleRepository.findByProjectId(projectId).stream()
                .filter(rule -> rule.getMethodId() != null)
                .collect(Collectors.toMap(
                        rule -> sourceRuleKey(rule.getMethodId(), sourceBranchId(rule.getReviewNote()), rule.getDescription()),
                        rule -> rule,
                        (first, ignored) -> first));
        for (GeneratedBusinessRuleDto generatedRule : generatedRules) {
            if (!isUsableGeneratedRule(generatedRule, validMethodIds, blockedMethodIds)) continue;
            String key = generatedRuleKey(generatedRule);
            BusinessRule existing = existingByKey.get(key);
            String cleanDescription = com.greytest.util.TextSanitizer.cleanAiText(generatedRule.description().trim());
            if (replaceAiGenerated && existing != null
                    && existing.getSource() == RuleSource.AI_GENERATED
                    && existing.getStatus() != ReviewStatus.APPROVED
                    && !Boolean.TRUE.equals(existing.getIsModified())) {
                existing.setDescription(cleanDescription);
                existing.setStatus(ReviewStatus.PENDING_REVIEW);
                existing.setIsModified(false);
                existing.setReviewNote(withSourceBranch(
                        generatedRule.branchId(),
                        "AI category: " + generatedRule.category() + ". User review/chỉnh sửa trước khi approve."));
                existingRuleKeys.add(key);
                created.add(toDto(businessRuleRepository.save(existing)));
                continue;
            }
            if (!existingRuleKeys.add(key)) continue;

            BusinessRule rule = new BusinessRule();
            rule.setProjectId(projectId);
            rule.setMethodId(generatedRule.methodId());
            rule.setRuleCode(nextRuleCode(ruleNumber++));
            rule.setDescription(cleanDescription);
            rule.setSource(source);
            rule.setStatus(ReviewStatus.PENDING_REVIEW);
            rule.setIsModified(false);
            rule.setReviewNote(withSourceBranch(
                    generatedRule.branchId(),
                    "AI category: " + generatedRule.category() + ". User review/chỉnh sửa trước khi approve."));
            created.add(toDto(businessRuleRepository.save(rule)));
        }
        return created;
    }

    private boolean isUsableGeneratedRule(
            GeneratedBusinessRuleDto generatedRule,
            Set<Long> validMethodIds,
            Set<Long> blockedMethodIds) {
        return generatedRule != null
                && generatedRule.methodId() != null
                && generatedRule.description() != null
                && !generatedRule.description().isBlank()
                && validMethodIds.contains(generatedRule.methodId())
                && !blockedMethodIds.contains(generatedRule.methodId());
    }

    private String reviewNote(ReviewedBusinessRuleSuggestionDto suggestion) {
        String note = suggestion.verdict() + ": " + suggestion.reason();
        if (suggestion.suggestedDescription() == null || suggestion.suggestedDescription().isBlank()) {
            return note + SUGGESTION_MARKER;
        }
        String encodedSuggestion = Base64.getEncoder().encodeToString(
                suggestion.suggestedDescription().trim().getBytes(StandardCharsets.UTF_8));
        return note + SUGGESTION_MARKER + encodedSuggestion;
    }

    private String suggestedDescription(String reviewNote) {
        if (reviewNote == null) return null;
        int marker = reviewNote.lastIndexOf(SUGGESTION_MARKER);
        if (marker < 0) return null;
        String encodedSuggestion = reviewNote.substring(marker + SUGGESTION_MARKER.length()).trim();
        if (encodedSuggestion.isBlank()) return null;
        try {
            return new String(Base64.getDecoder().decode(encodedSuggestion), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String visibleReviewNote(String reviewNote) {
        String note = withoutSourceBranch(reviewNote);
        if (note == null) return null;
        int marker = note.lastIndexOf(SUGGESTION_MARKER);
        return marker < 0 ? note : note.substring(0, marker);
    }

    private List<JavaMethod> serviceMethods(Long projectId) {
        List<JavaMethod> methods = new ArrayList<>();
        for (JavaClass javaClass : serviceClasses(projectId)) {
            methods.addAll(javaMethodRepository.findByClassIdIn(List.of(javaClass.getId())).stream()
                    .sorted(Comparator.comparing(JavaMethod::getLineStart, Comparator.nullsLast(Integer::compareTo))
                            .thenComparing(JavaMethod::getId))
                    .toList());
        }
        return methods;
    }

    private boolean needsGeneration(JavaMethod method, List<BusinessRule> existingRules) {
        Set<String> expectedDecisions = decisionIds(method);
        List<BusinessRule> methodRules = existingRules.stream()
                .filter(rule -> method.getId().equals(rule.getMethodId()))
                .toList();
        if (expectedDecisions.isEmpty()) return methodRules.isEmpty();
        Set<String> coveredBranches = methodRules.stream()
                .map(rule -> sourceBranchId(rule.getReviewNote()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return !coveredBranches.containsAll(expectedDecisions);
    }

    /**
     * Sinh va kiem tra Business Rule, cho phep AI tu sua mot lan khi JSON hop le
     * nhung noi dung thieu, trung, hoac gan sai decision cua source.
     */
    private BusinessRuleResponseDto generateValidatedBusinessRules(
            Long projectId,
            Set<Long> activeMethodIds) {
        LlmResponseException lastValidationError = null;
        for (int attempt = 1; attempt <= MAX_SEMANTIC_ATTEMPTS; attempt++) {
            BusinessRuleResponseDto response = lastValidationError == null
                    ? aiAgentService.generateBusinessRules(projectId, activeMethodIds)
                    : aiAgentService.generateBusinessRules(
                            projectId,
                            activeMethodIds,
                            semanticCorrection(activeMethodIds, lastValidationError.getMessage()));
            try {
                validateGeneratedBusinessRules(activeMethodIds, response);
                return response;
            } catch (LlmResponseException exception) {
                lastValidationError = exception;
                if (attempt == MAX_SEMANTIC_ATTEMPTS) {
                    throw exception;
                }
                GenerationJobContext.log(
                        "AI chưa bao phủ đúng control-flow. Đang tự sinh lại batch hiện tại (lần "
                                + (attempt + 1) + "/" + MAX_SEMANTIC_ATTEMPTS + ").");
            }
        }
        throw lastValidationError;
    }

    private String semanticCorrection(Set<Long> activeMethodIds, String validationError) {
        String branchChecklist = activeMethodIds.stream()
                .sorted()
                .map(methodId -> {
                    Set<String> expected = requiredDecisionIds(methodId);
                    Set<String> anchors = sourceAnchorIds(methodId);
                    return expected.isEmpty()
                            ? "method_id " + methodId + ": no decision; source anchor values " + anchors
                            : "method_id " + methodId + ": required branch_id values " + expected
                                    + "; optional source anchor values " + anchors;
                })
                .collect(Collectors.joining("; "));
        return validationError
                + "\nExpected branch checklist: " + branchChecklist
                + "\nEvery required branch_id must appear exactly once. "
                + "Use the matching STMT-* source anchor for independently testable behavior outside every decision.";
    }

    private void validateGeneratedBusinessRules(
            Set<Long> activeMethodIds,
            BusinessRuleResponseDto response) {
        if (response == null || response.rules() == null) {
            throw new LlmResponseException("AI không trả về danh sách Business Rule hợp lệ.");
        }
        if (response.rules().stream()
                .filter(java.util.Objects::nonNull)
                .map(GeneratedBusinessRuleDto::methodId)
                .anyMatch(methodId -> methodId == null || !activeMethodIds.contains(methodId))) {
            throw new LlmResponseException(
                    "AI trả về Business Rule nằm ngoài Service method đang phân tích: " + activeMethodIds + ".");
        }
        ensureBranchCoverage(activeMethodIds, response.rules());
    }

    private void ensureBranchCoverage(
            Set<Long> activeMethodIds,
            List<GeneratedBusinessRuleDto> generatedRules) {
        Map<Long, Set<String>> returnedByMethod =
                ensureValidBranchAssignments(activeMethodIds, generatedRules);
        for (Long methodId : activeMethodIds) {
            Set<String> expected = requiredDecisionIds(methodId);
            Set<String> returned = returnedByMethod.getOrDefault(methodId, Set.of());
            if (!expected.isEmpty() && !expected.equals(returned)) {
                Set<String> missing = new LinkedHashSet<>(expected);
                missing.removeAll(returned);
                throw new LlmResponseException(
                        "AI chưa bao phủ đủ quyết định control-flow của method " + methodId + ": " + missing);
            }
        }
    }

    private Map<Long, Set<String>> ensureValidBranchAssignments(
            Set<Long> activeMethodIds,
            List<GeneratedBusinessRuleDto> generatedRules) {
        if (generatedRules == null) {
            throw new LlmResponseException("AI không trả về danh sách Business Rule hợp lệ.");
        }
        Map<Long, Set<String>> returnedByMethod = new java.util.HashMap<>();
        Set<String> uniqueDecisionAssignments = new HashSet<>();
        Map<Long, Set<String>> descriptionsByMethod = new java.util.HashMap<>();
        for (GeneratedBusinessRuleDto rule : generatedRules) {
            if (rule == null || rule.methodId() == null
                    || rule.description() == null || rule.description().isBlank()
                    || !activeMethodIds.contains(rule.methodId())) {
                throw new LlmResponseException(
                        "AI trả về Business Rule nằm ngoài Service method đang phân tích.");
            }
            String normalizedDescription = descriptionKey(rule.description());
            Set<String> methodDescriptions = descriptionsByMethod.computeIfAbsent(
                    rule.methodId(), ignored -> new HashSet<>());
            if (!methodDescriptions.add(normalizedDescription)) {
                throw new LlmResponseException(
                        "AI sinh trùng Business Rule cho method " + rule.methodId()
                                + ": mô tả trùng lặp giữa các decision.");
            }
            Set<String> expected = requiredDecisionIds(rule.methodId());
            String branchId = decisionId(rule.branchId());
            Set<String> sourceAnchors = sourceAnchorIds(rule.methodId());
            if (expected.isEmpty()) {
                if (branchId != null && !sourceAnchors.contains(branchId)) {
                    throw new LlmResponseException(
                            "AI trả về source anchor không thuộc method " + rule.methodId() + ": " + rule.branchId());
                }
            } else if (branchId != null
                    && !expected.contains(branchId)
                    && !sourceAnchors.contains(branchId)) {
                throw new LlmResponseException(
                        "AI trả về source anchor không thuộc method " + rule.methodId() + ": " + rule.branchId());
            }
            if (branchId == null && !sourceAnchors.isEmpty()) {
                throw new LlmResponseException(
                        "AI chưa gắn source anchor cho Business Rule của method " + rule.methodId() + ".");
            }
            String assignment = rule.methodId() + "\n" + branchId;
            if (branchId != null && expected.contains(branchId)
                    && !uniqueDecisionAssignments.add(assignment)) {
                throw new LlmResponseException(
                        "AI sinh trùng Business Rule cho method " + rule.methodId()
                                + ", quyết định " + branchId + ".");
            }
            if (branchId != null) {
                if (expected.contains(branchId)) {
                    returnedByMethod.computeIfAbsent(rule.methodId(), ignored -> new LinkedHashSet<>()).add(branchId);
                }
            }
        }
        return returnedByMethod;
    }

    private Set<String> requiredDecisionIds(Long methodId) {
        JavaMethod method = javaMethodRepository.findById(methodId)
                .orElseThrow(() -> new LlmResponseException(
                        "Không tìm thấy Service method " + methodId + " để xác minh Business Rule."));
        return decisionIds(method);
    }

    private Set<String> decisionIds(JavaMethod method) {
        return sourceBranches(method).stream()
                .filter(branch -> !"STATEMENT".equals(branch.kind()))
                .map(SourceBranchDto::branchId)
                .map(this::decisionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> sourceAnchorIds(Long methodId) {
        JavaMethod method = javaMethodRepository.findById(methodId)
                .orElseThrow(() -> new LlmResponseException(
                        "Không tìm thấy Service method " + methodId + " để xác minh source anchor."));
        return sourceBranches(method).stream()
                .filter(branch -> "STATEMENT".equals(branch.kind()))
                .map(SourceBranchDto::branchId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<SourceBranchDto> sourceBranches(JavaMethod method) {
        try {
            List<SourceBranchDto> branches = MethodBranchAnalyzer.analyze(
                    method.getSourceCode(), method.getLineStart());
            validateSourceRanges(method, branches);
            return branches;
        } catch (IllegalStateException exception) {
            throw new InvalidProjectStatusException(
                    "Không thể xác minh source anchor của method " + method.getMethodName()
                            + ". Hãy phân tích lại project.");
        }
    }

    /** Xác minh mọi anchor AI dùng đều nằm trong phạm vi method đã phân tích. */
    private void validateSourceRanges(JavaMethod method, List<SourceBranchDto> branches) {
        Integer methodStart = method.getLineStart();
        Integer methodEnd = method.getLineEnd();
        if (methodStart == null || methodEnd == null || methodStart <= 0 || methodEnd < methodStart) return;
        boolean invalidRange = branches.stream().anyMatch(branch -> branch.lineStart() == null
                || branch.lineEnd() == null
                || branch.lineStart() < methodStart
                || branch.lineEnd() > methodEnd
                || branch.lineEnd() < branch.lineStart());
        if (invalidRange) {
            throw new InvalidProjectStatusException(
                    "Range source của source anchor nằm ngoài method " + method.getMethodName()
                            + ". Hãy phân tích lại project.");
        }
    }

    private int branchOrder(Long methodId, String branchId) {
        JavaMethod method = javaMethodRepository.findById(methodId).orElse(null);
        if (method == null || branchId == null) return Integer.MAX_VALUE;
        List<String> branchIds = sourceBranches(method).stream()
                .map(SourceBranchDto::branchId)
                .map(this::decisionId)
                .distinct()
                .toList();
        int index = branchIds.indexOf(decisionId(branchId));
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private List<Set<Long>> serviceMethodBatches(Long projectId, Set<Long> selectedMethodIds) {
        List<Set<Long>> batches = new ArrayList<>();
        for (JavaClass javaClass : serviceClasses(projectId)) {
            List<Long> methodIds = javaMethodRepository.findByClassIdIn(List.of(javaClass.getId())).stream()
                    .filter(method -> selectedMethodIds.contains(method.getId()))
                    .sorted(Comparator.comparing(JavaMethod::getLineStart, Comparator.nullsLast(Integer::compareTo))
                            .thenComparing(JavaMethod::getId))
                    .map(JavaMethod::getId)
                    .toList();
            for (int start = 0; start < methodIds.size(); start += GenerationContextBuilder.MAX_GENERATION_METHODS) {
                batches.add(new LinkedHashSet<>(methodIds.subList(
                        start,
                        Math.min(start + GenerationContextBuilder.MAX_GENERATION_METHODS, methodIds.size()))));
            }
        }
        return batches;
    }

    private List<JavaClass> serviceClasses(Long projectId) {
        return javaClassRepository.findByProjectIdAndClassType(projectId, ClassType.SERVICE).stream()
                .sorted(Comparator.comparing(JavaClass::getFilePath, Comparator.nullsLast(String::compareTo))
                        .thenComparing(JavaClass::getQualifiedName, Comparator.nullsLast(String::compareTo))
                        .thenComparing(JavaClass::getId))
                .toList();
    }

    private Set<Long> serviceMethodIds(Long projectId) {
        return serviceMethods(projectId).stream()
                .map(JavaMethod::getId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private Set<Long> methodIds(List<BusinessRule> rules) {
        Set<Long> methodIds = new HashSet<>();
        for (BusinessRule rule : rules) {
            if (rule.getMethodId() != null) {
                methodIds.add(rule.getMethodId());
            }
        }
        return methodIds;
    }

    private Set<String> ruleKeys(List<BusinessRule> rules) {
        Set<String> keys = new HashSet<>();
        for (BusinessRule rule : rules) {
            String branchId = sourceBranchId(rule.getReviewNote());
            if (rule.getMethodId() != null) {
                keys.add(sourceRuleKey(rule.getMethodId(), branchId, rule.getDescription()));
            }
        }
        return keys;
    }

    private void ensureUniqueDescription(List<BusinessRule> rules, Long currentRuleId, String description) {
        String newKey = descriptionKey(description);
        for (BusinessRule rule : rules) {
            if (currentRuleId != null && currentRuleId.equals(rule.getId())) continue;
            if (rule.getDescription() != null && descriptionKey(rule.getDescription()).equals(newKey)) {
                throw new IllegalArgumentException("Business Rule đã tồn tại trong project: " + rule.getRuleCode());
            }
        }
    }

    private void ensureDecisionAvailable(
            List<BusinessRule> rules,
            Long currentRuleId,
            Long methodId,
            String branchId) {
        if (branchId == null || isStatementAnchor(branchId)) return;
        for (BusinessRule rule : rules) {
            if (currentRuleId != null && currentRuleId.equals(rule.getId())) continue;
            if (methodId.equals(rule.getMethodId())
                    && branchId.equals(sourceBranchId(rule.getReviewNote()))) {
                throw new IllegalArgumentException(
                        "Quyết định source đã có Business Rule: " + branchId);
            }
        }
    }

    private void ensureNoDuplicateDecisions(List<BusinessRule> rules) {
        var branchesByDecision = new java.util.LinkedHashMap<String, List<String>>();
        for (BusinessRule rule : rules) {
            if (rule.getMethodId() == null) continue;
            String rawBranchId = rawSourceBranchId(rule.getReviewNote());
            String branchId = decisionId(rawBranchId);
            if (branchId != null && !isStatementAnchor(branchId)) {
                branchesByDecision.computeIfAbsent(
                        branchKey(rule.getMethodId(), branchId), ignored -> new ArrayList<>())
                        .add(rawBranchId);
            }
        }
        List<String> duplicates = branchesByDecision.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .filter(entry -> !isLegacyOutcomePair(entry.getValue()))
                .map(java.util.Map.Entry::getKey)
                .toList();
        if (!duplicates.isEmpty()) {
            throw new InvalidProjectStatusException(
                    "Chưa thể approve vì một quyết định source đang có nhiều Business Rule: " + duplicates);
        }
    }

    private boolean isLegacyOutcomePair(List<String> branchIds) {
        if (branchIds.size() != 2) return false;
        String decision = decisionId(branchIds.get(0));
        return decision != null && new HashSet<>(branchIds)
                .equals(Set.of(decision + "-TRUE", decision + "-FALSE"));
    }

    private String descriptionKey(String description) {
        return description.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String generatedRuleKey(GeneratedBusinessRuleDto rule) {
        return sourceRuleKey(rule.methodId(), decisionId(rule.branchId()), rule.description());
    }

    private String sourceRuleKey(Long methodId, String branchId, String description) {
        return branchId == null || isStatementAnchor(branchId)
                ? methodRuleKey(methodId, description)
                : branchKey(methodId, branchId);
    }

    private boolean isStatementAnchor(String branchId) {
        return branchId != null && branchId.startsWith("STMT-");
    }

    private String methodRuleKey(Long methodId, String description) {
        return "method:" + methodId + ":" + descriptionKey(description);
    }

    private String branchKey(Long methodId, String branchId) {
        return "branch:" + methodId + ":" + branchId;
    }

    private String normalizedBranchId(String branchId) {
        return branchId == null || branchId.isBlank() ? null : branchId.trim();
    }

    private String decisionId(String branchId) {
        String normalized = normalizedBranchId(branchId);
        if (normalized == null) return null;
        int outcomeSeparator = normalized.indexOf("::");
        return outcomeSeparator < 0
                ? normalized.replaceFirst("-(TRUE|FALSE)$", "")
                : normalized.substring(0, outcomeSeparator);
    }

    private String requireSourceBranch(Long methodId, String requestedBranchId) {
        String branchId = decisionId(requestedBranchId);
        Set<String> validBranchIds = requiredDecisionIds(methodId);
        if (validBranchIds.isEmpty()) {
            if (branchId != null) {
                throw new IllegalArgumentException("Method không có quyết định source để gắn Business Rule.");
            }
            return null;
        }
        if (branchId == null || !validBranchIds.contains(branchId)) {
            throw new IllegalArgumentException(
                    "Hãy chọn một quyết định source hợp lệ cho Business Rule: " + validBranchIds);
        }
        return branchId;
    }

    private String withSourceBranch(String branchId, String note) {
        String normalized = decisionId(branchId);
        return normalized == null ? note : SOURCE_BRANCH_MARKER + normalized + "\n" + note;
    }

    private String sourceBranchId(String reviewNote) {
        return decisionId(rawSourceBranchId(reviewNote));
    }

    private String rawSourceBranchId(String reviewNote) {
        if (reviewNote == null || !reviewNote.startsWith(SOURCE_BRANCH_MARKER)) return null;
        int lineEnd = reviewNote.indexOf('\n');
        return normalizedBranchId(reviewNote.substring(
                SOURCE_BRANCH_MARKER.length(),
                lineEnd < 0 ? reviewNote.length() : lineEnd));
    }

    private String withoutSourceBranch(String reviewNote) {
        if (sourceBranchId(reviewNote) == null) return reviewNote;
        int lineEnd = reviewNote.indexOf('\n');
        return lineEnd < 0 ? "" : reviewNote.substring(lineEnd + 1);
    }

    private int nextRuleNumber(List<BusinessRule> rules) {
        return rules.stream()
                .map(BusinessRule::getRuleCode)
                .mapToInt(this::ruleNumber)
                .max()
                .orElse(0) + 1;
    }

    private int ruleNumber(String code) {
        try {
            return code != null && code.startsWith("BR-") ? Integer.parseInt(code.substring(3)) : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String nextRuleCode(int next) {
        return "BR-" + String.format("%03d", next);
    }

    private Project ensureProjectExists(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private Long requireProjectServiceMethod(Long projectId, Long methodId) {
        if (methodId == null) {
            throw new IllegalArgumentException("Business Rule phải liên kết với một Service method.");
        }
        JavaMethod method = javaMethodRepository.findById(methodId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Service method " + methodId));
        JavaClass javaClass = javaClassRepository.findById(method.getClassId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy class của method " + methodId));
        if (!projectId.equals(javaClass.getProjectId()) || javaClass.getClassType() != ClassType.SERVICE) {
            throw new IllegalArgumentException("Method phải là Service method của project hiện tại.");
        }
        return methodId;
    }

    // Cho phép quay lại sửa BR ở mọi pha sau (regenerate); dữ liệu pha sau bị ảnh hưởng
    // sẽ được DB cascade dọn và status rollback về BR_PENDING_REVIEW
    private void ensureBusinessRuleEditable(Project project) {
        ensureBusinessRuleEditable(project.getStatus());
    }

    private void ensureBusinessRuleEditable(ProjectStatus status) {
        if (status == ProjectStatus.UPLOADED || status == ProjectStatus.FAILED) {
            throw new InvalidProjectStatusException(
                    "Chỉ có thể thao tác Business Rule sau khi project đã ANALYZED.");
        }
    }

    private BusinessRuleDto toDto(BusinessRule rule) {
        return new BusinessRuleDto(
                rule.getId(),
                rule.getProjectId(),
                rule.getMethodId(),
                rule.getRuleCode(),
                com.greytest.util.TextSanitizer.cleanAiText(rule.getDescription()),
                visibleReviewNote(rule.getReviewNote()),
                suggestedDescription(rule.getReviewNote()),
                rule.getSource(),
                rule.getStatus(),
                rule.getIsModified(),
                rule.getCreatedAt(),
                rule.getUpdatedAt(),
                sourceBranchId(rule.getReviewNote()));
    }
}
