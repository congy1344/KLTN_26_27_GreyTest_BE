package com.greytest.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.CreateTestPlanRequest;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.TestPlanDto;
import com.greytest.dto.UpdateTestPlanRequest;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestPlanDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.Project;
import com.greytest.entity.TestPlan;
import com.greytest.entity.TestPlanCoveredRule;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.TestType;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestPlanCoveredRuleRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.GenerationContextBuilder;
import com.greytest.service.agent.LlmResponseException;

/**
 * Quản lý vòng đời Test Plan sau khi Business Rule đã được phê duyệt.
 */
@Service
public class TestPlanService {

    private static final int MAX_SEMANTIC_ATTEMPTS = 2;

    private final TestPlanRepository testPlanRepository;
    private final TestPlanCoveredRuleRepository testPlanCoveredRuleRepository;
    private final BusinessRuleRepository businessRuleRepository;
    private final ProjectRepository projectRepository;
    private final AIAgentService aiAgentService;
    private final GenerationProgressService generationProgressService;
    private final TransactionTemplate transactions;
    private ServiceScopeResolver scopeResolver;
    private LlmBatchExecutor batchExecutor;

    public TestPlanService(
            TestPlanRepository testPlanRepository,
            TestPlanCoveredRuleRepository testPlanCoveredRuleRepository,
            BusinessRuleRepository businessRuleRepository,
            ProjectRepository projectRepository,
            AIAgentService aiAgentService,
            GenerationProgressService generationProgressService,
            PlatformTransactionManager transactionManager) {
        this.testPlanRepository = testPlanRepository;
        this.testPlanCoveredRuleRepository = testPlanCoveredRuleRepository;
        this.businessRuleRepository = businessRuleRepository;
        this.projectRepository = projectRepository;
        this.aiAgentService = aiAgentService;
        this.transactions = transactionManager != null
                ? new TransactionTemplate(transactionManager)
                : new TransactionTemplate(new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                    @Override
                    protected Object doGetTransaction() { return new Object(); }
                    @Override
                    protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}
                    @Override
                    protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
                    @Override
                    protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
                });
        this.generationProgressService = generationProgressService;
    }

    public TestPlanService(
            TestPlanRepository testPlanRepository,
            TestPlanCoveredRuleRepository testPlanCoveredRuleRepository,
            BusinessRuleRepository businessRuleRepository,
            ProjectRepository projectRepository,
            AIAgentService aiAgentService,
            GenerationProgressService generationProgressService) {
        this(testPlanRepository, testPlanCoveredRuleRepository, businessRuleRepository,
                projectRepository, aiAgentService, generationProgressService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TestPlanService(
            TestPlanRepository testPlanRepository,
            TestPlanCoveredRuleRepository testPlanCoveredRuleRepository,
            BusinessRuleRepository businessRuleRepository,
            ProjectRepository projectRepository,
            AIAgentService aiAgentService,
            GenerationProgressService generationProgressService,
            PlatformTransactionManager transactionManager,
            ServiceScopeResolver scopeResolver,
            LlmBatchExecutor batchExecutor) {
        this(testPlanRepository, testPlanCoveredRuleRepository, businessRuleRepository,
                projectRepository, aiAgentService, generationProgressService, transactionManager);
        this.scopeResolver = scopeResolver;
        this.batchExecutor = batchExecutor;
    }

    public TestPlanService(
            TestPlanRepository testPlanRepository,
            TestPlanCoveredRuleRepository testPlanCoveredRuleRepository,
            BusinessRuleRepository businessRuleRepository,
            ProjectRepository projectRepository,
            AIAgentService aiAgentService,
            GenerationProgressService generationProgressService,
            PlatformTransactionManager transactionManager,
            ServiceScopeResolver scopeResolver) {
        this(testPlanRepository, testPlanCoveredRuleRepository, businessRuleRepository,
                projectRepository, aiAgentService, generationProgressService, transactionManager,
                scopeResolver, null);
    }

    @Transactional(readOnly = true)
    public List<TestPlanDto> list(Long projectId) {
        ensureProjectExists(projectId);
        return testPlanRepository.findByProjectId(projectId).stream()
                .sorted(Comparator.comparing(TestPlan::getPlanCode, Comparator.nullsLast(String::compareTo)))
                .map(this::toDto)
                .toList();
    }


    @Transactional(readOnly = true)
    public List<TestPlanDto> list(Long projectId, String servicePath) {
        Set<Long> methodIds = scopeResolver.resolve(projectId, servicePath).methodIds();
        Set<Long> ruleIds = businessRuleRepository.findByProjectId(projectId).stream()
                .filter(rule -> rule.getMethodId() != null && methodIds.contains(rule.getMethodId()))
                .map(BusinessRule::getId).collect(Collectors.toSet());
        return testPlanRepository.findByProjectId(projectId).stream()
                .filter(plan -> ruleIds.contains(plan.getBusinessRuleId()))
                .map(this::toDto).toList();
    }
    @Transactional(readOnly = true)
    public Long projectIdForPlan(Long planId) {
        return testPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay Test Plan " + planId))
                .getProjectId();
    }

    @Transactional
    public List<TestPlanDto> generate(Long projectId) {
        return generate(projectId, (String) null, false);
    }

    public List<TestPlanDto> generate(Long projectId, String servicePath) {
        return generate(projectId, servicePath, false);
    }

    public List<TestPlanDto> generate(Long projectId, String servicePath, boolean resume) {
        Set<Long> methodIds = servicePath == null || servicePath.isBlank()
                ? null
                : scopeResolver.resolve(projectId, servicePath).methodIds();
        return generate(projectId, methodIds, resume);
    }

    /**
     * Sinh Test Plan theo batch, mỗi batch được lưu ngay vào CSDL sau khi AI trả kết quả.
     * Nếu bị lỗi giữa chừng, các batch đã hoàn thành vẫn được giữ lại —
     * người dùng bấm "Tiếp tục sinh" (resume=true) để chạy tiếp phần còn thiếu.
     */
    private List<TestPlanDto> generate(Long projectId, Set<Long> scopedMethodIds, boolean resume) {
        Project project = ensureProjectExists(projectId);
        if (scopedMethodIds == null) ensureCanGenerate(project);

        List<BusinessRule> queriedRules = businessRuleRepository.findByProjectId(projectId);
        if (queriedRules.isEmpty()) {
            queriedRules = businessRuleRepository.findByProjectIdAndStatus(projectId, ReviewStatus.APPROVED);
        }
        List<BusinessRule> allApprovedRules = queriedRules.stream()
                .filter(rule -> rule.getStatus() == ReviewStatus.APPROVED || rule.getStatus() == ReviewStatus.PENDING_REVIEW)
                .filter(rule -> scopedMethodIds == null || (rule.getMethodId() != null && scopedMethodIds.contains(rule.getMethodId()))).toList();
        if (allApprovedRules.isEmpty()) {
            throw new InvalidProjectStatusException("Can co it nhat mot Business Rule APPROVED truoc khi sinh Test Plan.");
        }

        List<TestPlan> existingPlans = testPlanRepository.findByProjectId(projectId);
        Set<Long> alreadyCoveredRuleIds = existingPlans.stream()
                .flatMap(plan -> {
                    List<Long> ids = testPlanCoveredRuleRepository.findByTestPlanId(plan.getId()).stream()
                            .map(TestPlanCoveredRule::getBusinessRuleId).toList();
                    if (ids.isEmpty() && plan.getBusinessRuleId() != null) {
                        return java.util.stream.Stream.of(plan.getBusinessRuleId());
                    }
                    return ids.stream();
                })
                .collect(Collectors.toSet());

        List<BusinessRule> targetRules = resume
                ? allApprovedRules.stream().filter(rule -> !alreadyCoveredRuleIds.contains(rule.getId())).toList()
                : allApprovedRules;

        if (targetRules.isEmpty()) {
            generationProgressService.log(projectId, GenerationProgressStage.TEST_PLAN,
                    "Tat ca Business Rule da duoc bao phu boi Test Plan.");
            return existingPlans.stream().map(this::toDto).toList();
        }

        // Nếu sinh lại từ đầu: xóa plan cũ TRƯỚC khi bắt đầu gọi AI,
        // để mỗi batch mới lưu vào DB ngay mà không bị trùng.
        List<TestPlan> oldPlans = new ArrayList<>();
        if (!resume) {
            Set<Long> approvedRuleIds = allApprovedRules.stream().map(BusinessRule::getId).collect(Collectors.toSet());
            oldPlans = scopedMethodIds == null
                    ? existingPlans
                    : existingPlans.stream()
                            .filter(plan -> approvedRuleIds.contains(plan.getBusinessRuleId()))
                            .toList();
            if (!oldPlans.isEmpty()) {
                final List<TestPlan> toDelete = oldPlans;
                transactions.executeWithoutResult(status -> {
                    testPlanRepository.deleteAll(toDelete);
                    testPlanRepository.flush();
                });
            }
        }
        final Set<Long> deletedPlanIds = oldPlans.stream().map(TestPlan::getId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());

        List<List<BusinessRule>> allBatches = methodBatches(allApprovedRules);
        int totalBatches = allBatches.size();
        List<String> stepLabels = new ArrayList<>();
        for (int i = 0; i < totalBatches; i++) {
            stepLabels.add("Sinh Test Plan - batch " + (i + 1) + "/" + totalBatches + ": " + formatRuleBatchSummary(allBatches.get(i)));
        }
        stepLabels.add("Kiểm tra và lưu Test Plan vào CSDL");

        int firstPendingBatchIndex = -1;
        for (int i = 0; i < totalBatches; i++) {
            if (allBatches.get(i).stream().anyMatch(rule -> !alreadyCoveredRuleIds.contains(rule.getId()))) {
                firstPendingBatchIndex = i;
                break;
            }
        }
        if (firstPendingBatchIndex < 0) {
            firstPendingBatchIndex = totalBatches;
        }

        if (resume && firstPendingBatchIndex > 0) {
            generationProgressService.resume(projectId, GenerationProgressStage.TEST_PLAN, stepLabels, firstPendingBatchIndex,
                    "Tiếp tục sinh Test Plan từ batch " + (firstPendingBatchIndex + 1) + "/" + totalBatches + " (" + targetRules.size() + " Business Rule còn thiếu).");
        } else {
            generationProgressService.start(projectId, GenerationProgressStage.TEST_PLAN, stepLabels,
                    "Đã nhóm " + allApprovedRules.size() + " Business Rule thành " + totalBatches + " batch.");
        }

        // Đếm plan number tăng dần qua các batch; dùng AtomicInteger để thread-safe
        List<TestPlan> remainingPlans = existingPlans.stream()
                .filter(p -> p.getId() == null || !deletedPlanIds.contains(p.getId()))
                .toList();
        int baseNumber = resume || scopedMethodIds != null
                ? nextPlanNumber(remainingPlans)
                : 1;
        AtomicInteger planCounter = new AtomicInteger(baseNumber);

        List<List<BusinessRule>> batchesToRun = allBatches.stream()
                .filter(batch -> batch.stream().anyMatch(rule -> !alreadyCoveredRuleIds.contains(rule.getId())))
                .toList();

        List<TestPlan> allSavedPlans = new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
        // Mỗi batch được lưu ngay vào CSDL qua callback onCompleted
        List<List<GeneratedTestPlanDto>> allBatchResults = LlmBatchExecutor.mapOrSequential(
                batchExecutor,
                batchesToRun,
                () -> generationProgressService.isPaused(projectId, GenerationProgressStage.TEST_PLAN),
                batch -> {
                    int batchIdx = allBatches.indexOf(batch) + 1;
                    String summary = formatRuleBatchSummary(batch);
                    generationProgressService.log(projectId, GenerationProgressStage.TEST_PLAN,
                            "Đang gọi AI sinh Test Plan cho batch " + batchIdx + "/" + totalBatches
                                     + " (" + summary + ")...");
                    Set<Long> batchRuleIds = ruleIds(batch);
                    TestPlanResponseDto response = generateValidatedTestPlans(projectId, batchRuleIds, rulesByMethod(batch));
                    return response.plans();
                },
                (completedRunBatch, batchPlans) -> {
                    List<BusinessRule> currentBatch = batchesToRun.get(completedRunBatch - 1);
                    int actualBatchIdx = allBatches.indexOf(currentBatch) + 1;
                    // Lưu ngay batch này vào CSDL để không mất dữ liệu nếu bị ngắt quãng giữa chừng
                    List<TestPlan> saved = transactions.execute(status -> persistBatch(projectId, targetRules, batchPlans, planCounter, deletedPlanIds));
                    if (saved != null) allSavedPlans.addAll(saved);
                    String summary = formatRuleBatchSummary(currentBatch);
                    generationProgressService.advance(projectId, GenerationProgressStage.TEST_PLAN,
                            "Batch " + actualBatchIdx + "/" + totalBatches + ": đã kiểm tra & lưu "
                                    + batchPlans.size() + " Test Plan (" + summary + ").");
                });

        if (generationProgressService.isPaused(projectId, GenerationProgressStage.TEST_PLAN)) {
            generationProgressService.log(projectId, GenerationProgressStage.TEST_PLAN,
                    "Tác vụ đã tạm dừng. Các Test Plan đã sinh được lưu an toàn vào CSDL. Nhấn 'Tiếp tục sinh' khi bạn sẵn sàng.");
            List<TestPlanDto> fromRepo = testPlanRepository.findByProjectId(projectId).stream().map(this::toDto).toList();
            return fromRepo.isEmpty() ? allSavedPlans.stream().map(this::toDto).toList() : fromRepo;
        }

        // Cập nhật trạng thái project sau khi tất cả batch hoàn thành
        transactions.executeWithoutResult(status -> {
            Project p = projectRepository.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
            p.setStatus(ProjectStatus.PLAN_PENDING_REVIEW);
            projectRepository.save(p);
        });

        int totalSaved = allBatchResults.stream().mapToInt(List::size).sum();
        generationProgressService.completeAfterCommit(projectId, GenerationProgressStage.TEST_PLAN,
                "Hoàn tất: đã lưu " + totalSaved + " Test Plan"
                        + (resume ? " mới bổ sung." : " và liên kết với " + allApprovedRules.size() + " Business Rule."));
        List<TestPlanDto> fromRepo = testPlanRepository.findByProjectId(projectId).stream().map(this::toDto).toList();
        return fromRepo.isEmpty() ? allSavedPlans.stream().map(this::toDto).toList() : fromRepo;

        } catch (RuntimeException exception) {
            if (generationProgressService.isPaused(projectId, GenerationProgressStage.TEST_PLAN)) {
                List<TestPlanDto> fromRepo = testPlanRepository.findByProjectId(projectId).stream().map(this::toDto).toList();
                return fromRepo.isEmpty() ? allSavedPlans.stream().map(this::toDto).toList() : fromRepo;
            }
            int failedBatch = LlmBatchExecutor.failedBatch(exception, 0);
            String failureLocation = failedBatch > 0
                    ? "Dừng ở batch " + failedBatch + "."
                    : "Dừng ở bước kiểm tra và lưu Test Plan.";
            generationProgressService.fail(projectId, GenerationProgressStage.TEST_PLAN,
                    failureLocation + " Sinh Test Plan thất bại; các batch đã sinh trước đó đã được lưu an toàn."
                            + " Bạn có thể nhấn 'Tiếp tục sinh' để chạy tiếp.");
            throw LlmBatchExecutor.originalFailure(exception);
        }
    }

    /**
     * Lưu kết quả một batch Test Plan vào CSDL ngay lập tức.
     * Được gọi trong callback onCompleted của LlmBatchExecutor.
     */
    private List<TestPlan> persistBatch(Long projectId, List<BusinessRule> targetRules,
                              List<GeneratedTestPlanDto> batchPlans, AtomicInteger planCounter,
                              Set<Long> deletedPlanIds) {
        List<GeneratedPlanDraft> validDrafts = buildGeneratedPlanDrafts(
                projectId, batchPlans, targetRules, planCounter, deletedPlanIds);
        if (validDrafts.isEmpty()) return List.of();
        List<TestPlan> savedPlans = testPlanRepository.saveAll(
                validDrafts.stream().map(GeneratedPlanDraft::plan).toList());
        testPlanCoveredRuleRepository.saveAll(coveredRuleLinks(savedPlans, validDrafts));
        targetRules.forEach(rule -> {
            if (rule.getStatus() == ReviewStatus.PENDING_REVIEW) {
                rule.setStatus(ReviewStatus.APPROVED);
                businessRuleRepository.save(rule);
            }
        });
        return savedPlans;
    }

    private String formatRuleBatchSummary(List<BusinessRule> batch) {
        if (batch == null || batch.isEmpty()) return "0 rule";
        List<String> codes = batch.stream()
                .map(BusinessRule::getRuleCode)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (codes.isEmpty()) return batch.size() + " rule";
        return String.join(", ", codes);
    }

    /**
     * Sinh va kiem tra coverage cua mot batch Test Plan, tu sua semantic mot lan.
     */
    private TestPlanResponseDto generateValidatedTestPlans(
            Long projectId,
            Set<Long> batchRuleIds,
            Map<Long, Set<Long>> expectedRulesByMethod) {
        LlmResponseException lastValidationError = null;
        for (int attempt = 1; attempt <= MAX_SEMANTIC_ATTEMPTS; attempt++) {
            TestPlanResponseDto response = lastValidationError == null
                    ? aiAgentService.generateTestPlan(projectId, batchRuleIds)
                    : aiAgentService.generateTestPlan(
                            projectId, batchRuleIds, lastValidationError.getMessage());
            try {
                if (response == null || response.plans() == null) {
                    throw new LlmResponseException("AI khong tra ve danh sach Test Plan hop le.");
                }
                ensureBatchMatchesMethods(response.plans(), expectedRulesByMethod);
                return response;
            } catch (LlmResponseException exception) {
                lastValidationError = exception;
                if (attempt == MAX_SEMANTIC_ATTEMPTS) {
                    throw exception;
                }
                GenerationJobContext.log(
                        "AI chua cover du Business Rule cho batch hien tai. Dang tu sinh lai batch (lan 2/2).");
            }
        }
        throw lastValidationError;
    }


    @Transactional
    public TestPlanDto create(Long projectId, String servicePath, CreateTestPlanRequest request) {
        Set<Long> methodIds = scopeResolver.resolve(projectId, servicePath).methodIds();
        BusinessRule rule = businessRuleRepository.findById(request.businessRuleId())
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay Business Rule"));
        if (rule.getMethodId() == null || !methodIds.contains(rule.getMethodId())) {
            throw new IllegalArgumentException("Business Rule khong thuoc servicePath da chon.");
        }
        return create(projectId, request);
    }

    @Transactional
    public TestPlanDto create(Long projectId, CreateTestPlanRequest request) {
        Project project = ensureProjectExists(projectId);
        ensurePlanEditable(project);
        BusinessRule rule = ensureApprovedRule(projectId, request.businessRuleId());

        TestPlan plan = new TestPlan();
        plan.setProjectId(projectId);
        plan.setBusinessRuleId(rule.getId());
        plan.setPlanCode(nextPlanCode(nextPlanNumber(testPlanRepository.findByProjectId(projectId))));
        plan.setTitle(request.title().trim());
        plan.setDescription(request.description().trim());
        plan.setTestType(request.testType());
        plan.setStatus(ReviewStatus.PENDING_REVIEW);
        plan.setIsModified(false);
        project.setStatus(ProjectStatus.PLAN_PENDING_REVIEW);
        projectRepository.save(project);
        TestPlan savedPlan = testPlanRepository.save(plan);
        testPlanCoveredRuleRepository.save(coveredRuleLink(savedPlan.getId(), rule.getId()));
        return toDto(savedPlan);
    }

    @Transactional
    public TestPlanDto update(Long planId, UpdateTestPlanRequest request) {
        TestPlan plan = testPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay Test Plan " + planId));
        Project project = ensureProjectExists(plan.getProjectId());
        ensurePlanEditable(project);
        BusinessRule rule = ensureApprovedRule(plan.getProjectId(), request.businessRuleId());
        boolean anchorChanged = !rule.getId().equals(plan.getBusinessRuleId());

        plan.setBusinessRuleId(rule.getId());
        plan.setTitle(request.title().trim());
        plan.setDescription(request.description().trim());
        plan.setTestType(request.testType());
        plan.setStatus(ReviewStatus.PENDING_REVIEW);
        plan.setIsModified(true);
        project.setStatus(ProjectStatus.PLAN_PENDING_REVIEW);
        projectRepository.save(project);
        TestPlan savedPlan = testPlanRepository.save(plan);
        if (anchorChanged) {
            replaceCoveredRules(savedPlan.getId(), Set.of(rule.getId()));
        }
        return toDto(savedPlan);
    }

    @Transactional
    public void delete(Long planId) {
        TestPlan plan = testPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay Test Plan " + planId));
        Project project = ensureProjectExists(plan.getProjectId());
        ensurePlanEditable(project);
        testPlanRepository.delete(plan);
        project.setStatus(ProjectStatus.PLAN_PENDING_REVIEW);
        projectRepository.save(project);
    }

    @Transactional
    public List<TestPlanDto> approve(Long projectId, String servicePath) {
        return approve(projectId, scopeResolver.resolve(projectId, servicePath).methodIds());
    }

    @Transactional
    public List<TestPlanDto> approve(Long projectId) {
        return approve(projectId, (Set<Long>) null);
    }

    private List<TestPlanDto> approve(Long projectId, Set<Long> scopedMethodIds) {
        Project project = ensureProjectExists(projectId);
        if (scopedMethodIds == null && project.getStatus() != ProjectStatus.PLAN_PENDING_REVIEW) {
            throw new InvalidProjectStatusException("Chi co the approve Test Plan dang cho review.");
        }
        List<TestPlan> projectPlans = testPlanRepository.findByProjectId(projectId);
        List<TestPlan> plans;
        if (scopedMethodIds == null) {
            plans = projectPlans;
        } else {
            Set<Long> ruleIds = businessRuleRepository.findByProjectId(projectId).stream()
                    .filter(rule -> rule.getMethodId() != null && scopedMethodIds.contains(rule.getMethodId()))
                    .map(BusinessRule::getId).collect(Collectors.toSet());
            plans = projectPlans.stream().filter(plan -> ruleIds.contains(plan.getBusinessRuleId())).toList();
        }
        if (plans.isEmpty()) {
            throw new InvalidProjectStatusException("Can co it nhat mot Test Plan truoc khi approve.");
        }
        for (TestPlan plan : plans) {
            plan.setStatus(ReviewStatus.APPROVED);
            testPlanRepository.save(plan);
        }
        project.setStatus(ProjectStatus.PLAN_APPROVED);
        projectRepository.save(project);
        return plans.stream().map(this::toDto).toList();
    }

    private List<GeneratedPlanDraft> buildGeneratedPlanDrafts(
            Long projectId,
            List<GeneratedTestPlanDto> generatedPlans,
            List<BusinessRule> approvedRules,
            AtomicInteger planCounter,
            Set<Long> deletedPlanIds) {
        Set<Long> approvedRuleIds = approvedRules.stream()
                .map(BusinessRule::getId)
                .collect(Collectors.toSet());
        Set<String> existingCodes = testPlanRepository.findByProjectId(projectId).stream()
                .map(TestPlan::getPlanCode)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        List<GeneratedPlanDraft> drafts = new ArrayList<>();
        for (GeneratedTestPlanDto plan : generatedPlans) {
            if (!isUsableGeneratedPlan(plan, approvedRuleIds)) continue;
            int num = planCounter.getAndIncrement();
            while (existingCodes.contains(nextPlanCode(num))) {
                num = planCounter.getAndIncrement();
            }
            existingCodes.add(nextPlanCode(num));
            drafts.add(new GeneratedPlanDraft(
                    generatedPlan(projectId, plan, num),
                    new TreeSet<>(plan.coveredRuleIds())));
        }
        return drafts;
    }

    private List<TestPlanCoveredRule> coveredRuleLinks(List<TestPlan> plans, List<GeneratedPlanDraft> drafts) {
        List<TestPlanCoveredRule> links = new ArrayList<>();
        for (int index = 0; index < plans.size(); index++) {
            Long planId = plans.get(index).getId();
            for (Long ruleId : drafts.get(index).coveredRuleIds()) {
                links.add(coveredRuleLink(planId, ruleId));
            }
        }
        return links;
    }

    private TestPlanCoveredRule coveredRuleLink(Long planId, Long ruleId) {
        TestPlanCoveredRule link = new TestPlanCoveredRule();
        link.setTestPlanId(planId);
        link.setBusinessRuleId(ruleId);
        return link;
    }

    private void replaceCoveredRules(Long planId, Set<Long> ruleIds) {
        Set<Long> existingRuleIds = testPlanCoveredRuleRepository.findByTestPlanId(planId).stream()
                .map(TestPlanCoveredRule::getBusinessRuleId)
                .collect(Collectors.toCollection(TreeSet::new));
        if (existingRuleIds.equals(ruleIds)) return;

        testPlanCoveredRuleRepository.deleteByTestPlanId(planId);
        testPlanCoveredRuleRepository.flush();
        testPlanCoveredRuleRepository.saveAll(ruleIds.stream()
                .map(ruleId -> coveredRuleLink(planId, ruleId))
                .toList());
    }

    private List<List<BusinessRule>> methodBatches(List<BusinessRule> rules) {
        List<List<BusinessRule>> methodGroups = new ArrayList<>(rulesByMethod(rules).values()).stream()
                .map(ruleIds -> rules.stream()
                        .filter(rule -> ruleIds.contains(rule.getId()))
                        .sorted(Comparator.comparing(BusinessRule::getId))
                        .toList())
                .toList();
        List<List<BusinessRule>> batches = new ArrayList<>();
        for (int start = 0; start < methodGroups.size(); start += GenerationContextBuilder.MAX_TEST_PLAN_METHODS) {
            batches.add(methodGroups.subList(start, Math.min(start + GenerationContextBuilder.MAX_TEST_PLAN_METHODS, methodGroups.size()))
                    .stream()
                    .flatMap(List::stream)
                    .toList());
        }
        return batches;
    }

    private Map<Long, Set<Long>> rulesByMethod(List<BusinessRule> rules) {
        List<BusinessRule> sortedRules = rules.stream()
                .sorted(Comparator
                        .comparing(BusinessRule::getMethodId, Comparator.nullsLast(Long::compareTo))
                        .thenComparing(BusinessRule::getId))
                .toList();
        Map<Long, Set<Long>> result = new LinkedHashMap<>();
        for (BusinessRule rule : sortedRules) {
            if (rule.getMethodId() == null) {
                throw new LlmResponseException("Business Rule chua lien ket method: " + rule.getId());
            }
            result.computeIfAbsent(rule.getMethodId(), ignored -> new TreeSet<>()).add(rule.getId());
        }
        return result;
    }

    private Set<Long> ruleIds(List<BusinessRule> rules) {
        return rules.stream()
                .map(BusinessRule::getId)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private void ensureBatchMatchesMethods(List<GeneratedTestPlanDto> plans, Map<Long, Set<Long>> expectedRulesByMethod) {
        Map<Long, Set<Long>> returnedRulesByMethod = new LinkedHashMap<>();
        // Gom mọi lỗi mapping để lần retry sửa đủ batch, không chỉ lỗi đầu tiên.
        List<String> errors = new ArrayList<>();
        for (GeneratedTestPlanDto plan : plans) {
            if (plan == null || plan.methodId() == null || !expectedRulesByMethod.containsKey(plan.methodId())) {
                errors.add("AI tra ve Test Plan nam ngoai batch method: method_id="
                        + (plan == null ? null : plan.methodId()) + "; expected=" + expectedRulesByMethod.keySet());
                continue;
            }
            Set<Long> expectedRuleIds = expectedRulesByMethod.get(plan.methodId());
            if (plan.coveredRuleIds() == null
                    || plan.coveredRuleIds().stream().anyMatch(java.util.Objects::isNull)) {
                errors.add("AI tra ve covered_rule_ids co gia tri null cho method " + plan.methodId() + ".");
                continue;
            }
            Set<Long> coveredRuleIds = new TreeSet<>(plan.coveredRuleIds());
            Set<Long> unexpectedRuleIds = new TreeSet<>(coveredRuleIds);
            unexpectedRuleIds.removeAll(expectedRuleIds);
            if (plan.coveredRuleIds().size() != coveredRuleIds.size()
                    || coveredRuleIds.isEmpty()
                    || !unexpectedRuleIds.isEmpty()) {
                errors.add("AI tra ve covered_rule_ids rong, trung lap hoac nam ngoai method "
                        + plan.methodId() + ": expected=" + expectedRuleIds
                        + "; actual=" + plan.coveredRuleIds() + "; unexpected=" + unexpectedRuleIds);
            }
            if (plan.ruleId() == null || !coveredRuleIds.contains(plan.ruleId())) {
                errors.add("AI tra ve anchor rule_id khong thuoc covered_rule_ids: method_id="
                        + plan.methodId() + "; rule_id=" + plan.ruleId() + "; covered_rule_ids=" + coveredRuleIds);
            }
            returnedRulesByMethod.computeIfAbsent(plan.methodId(), ignored -> new TreeSet<>()).addAll(coveredRuleIds);
        }
        Set<Long> missingMethodIds = new TreeSet<>(expectedRulesByMethod.keySet());
        missingMethodIds.removeAll(returnedRulesByMethod.keySet());
        if (!missingMethodIds.isEmpty()) {
            errors.add("AI chua sinh Test Plan cho method: " + missingMethodIds);
        }
        for (Map.Entry<Long, Set<Long>> entry : expectedRulesByMethod.entrySet()) {
            Set<Long> actualRuleIds = returnedRulesByMethod.getOrDefault(entry.getKey(), Set.of());
            Set<Long> missingRuleIds = new TreeSet<>(entry.getValue());
            missingRuleIds.removeAll(actualRuleIds);
            if (!missingRuleIds.isEmpty()) {
                errors.add("AI chua cover dung Business Rule cho method "
                        + entry.getKey() + ": expected=" + entry.getValue()
                        + "; actual=" + actualRuleIds + "; missing=" + missingRuleIds);
            }
        }
        if (!errors.isEmpty()) {
            throw new LlmResponseException(String.join("\n", errors));
        }
    }

    private boolean isUsableGeneratedPlan(GeneratedTestPlanDto plan, Set<Long> approvedRuleIds) {
        return plan != null
                && plan.methodId() != null
                && approvedRuleIds.contains(plan.ruleId())
                && plan.coveredRuleIds() != null
                && !plan.coveredRuleIds().isEmpty()
                && plan.coveredRuleIds().contains(plan.ruleId())
                && plan.title() != null
                && !plan.title().isBlank()
                && plan.description() != null
                && !plan.description().isBlank()
                && parseTestType(plan.testType()) != null;
    }

    private TestPlan generatedPlan(Long projectId, GeneratedTestPlanDto generatedPlan, int planNumber) {
        TestPlan plan = new TestPlan();
        plan.setProjectId(projectId);
        plan.setBusinessRuleId(generatedPlan.ruleId());
        plan.setPlanCode(nextPlanCode(planNumber));
        plan.setTitle(generatedPlan.title().trim());
        plan.setDescription(generatedPlan.description().trim());
        plan.setTestType(parseTestType(generatedPlan.testType()));
        plan.setStatus(ReviewStatus.PENDING_REVIEW);
        plan.setIsModified(false);
        return plan;
    }

    private TestType parseTestType(String value) {
        try {
            return value == null ? null : TestType.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private BusinessRule ensureApprovedRule(Long projectId, Long ruleId) {
        BusinessRule rule = businessRuleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay Business Rule " + ruleId));
        if (!projectId.equals(rule.getProjectId()) || (rule.getStatus() != ReviewStatus.APPROVED && rule.getStatus() != ReviewStatus.PENDING_REVIEW)) {
            throw new IllegalArgumentException("Business Rule phai thuoc project hien tai.");
        }
        return rule;
    }

    private Project ensureProjectExists(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    // Cho phép regenerate/sửa Test Plan ở mọi pha từ BR_APPROVED trở đi; dữ liệu pha sau
    // (Case/Unit Test) được DB cascade dọn, status rollback về PLAN_PENDING_REVIEW
    private static final Set<ProjectStatus> PLAN_EDITABLE_STATUSES = Set.of(
            ProjectStatus.BR_PENDING_REVIEW, ProjectStatus.BR_APPROVED, ProjectStatus.PLAN_PENDING_REVIEW, ProjectStatus.PLAN_APPROVED,
            ProjectStatus.CASE_PENDING_REVIEW, ProjectStatus.CASE_APPROVED, ProjectStatus.TEST_GENERATED,
            ProjectStatus.COVERAGE_ANALYZED, ProjectStatus.COMPLETED);

    private void ensureCanGenerate(Project project) {
        if (!PLAN_EDITABLE_STATUSES.contains(project.getStatus())) {
            throw new InvalidProjectStatusException(
                    "Chi co the sinh Test Plan sau khi Business Rule da APPROVED.");
        }
    }

    private void ensurePlanEditable(Project project) {
        if (!PLAN_EDITABLE_STATUSES.contains(project.getStatus())) {
            throw new InvalidProjectStatusException(
                    "Chi co the thao tac Test Plan sau khi Business Rule da APPROVED.");
        }
    }

    private int nextPlanNumber(List<TestPlan> plans) {
        return plans.stream()
                .map(TestPlan::getPlanCode)
                .mapToInt(this::planNumber)
                .max()
                .orElse(0) + 1;
    }

    private int planNumber(String code) {
        try {
            return code != null && code.startsWith("TP-") ? Integer.parseInt(code.substring(3)) : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String nextPlanCode(int next) {
        return "TP-" + String.format("%03d", next);
    }

    private TestPlanDto toDto(TestPlan plan) {
        return new TestPlanDto(
                plan.getId(),
                plan.getProjectId(),
                plan.getBusinessRuleId(),
                plan.getPlanCode(),
                com.greytest.util.TextSanitizer.cleanAiText(plan.getTitle()),
                com.greytest.util.TextSanitizer.cleanAiText(plan.getDescription()),
                plan.getTestType(),
                plan.getStatus(),
                plan.getIsModified(),
                plan.getCreatedAt(),
                testPlanCoveredRuleRepository.findByTestPlanId(plan.getId()).stream()
                        .map(TestPlanCoveredRule::getBusinessRuleId)
                        .collect(Collectors.collectingAndThen(
                                Collectors.toCollection(TreeSet::new),
                                ruleIds -> ruleIds.isEmpty()
                                        ? List.of(plan.getBusinessRuleId())
                                        : List.copyOf(ruleIds))));
    }

    private record GeneratedPlanDraft(TestPlan plan, Set<Long> coveredRuleIds) {
    }
}
