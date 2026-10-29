package com.greytest.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.github.javaparser.JavaParser;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.SourceUpdateDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedBusinessRuleDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestPlanResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.dto.agent.GenerationResponseDtos.BlockedUnitTestDto;
import com.greytest.dto.diff.ImpactSummaryDto;
import com.greytest.dto.diff.MethodDiffItem;
import com.greytest.entity.AuthUser;
import com.greytest.entity.JavaClass;
import com.greytest.entity.JavaMethod;
import com.greytest.entity.Project;
import com.greytest.entity.SourceUpdate;
import com.greytest.entity.SourceUpdateItem;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.SourceUpdateAction;
import com.greytest.entity.enums.SourceUpdateReviewStatus;
import com.greytest.entity.enums.SourceUpdateStatus;
import com.greytest.entity.enums.SourceUpdateTargetType;
import com.greytest.dto.diff.MethodDiffType;
import com.greytest.entity.MethodParam;
import com.greytest.entity.enums.ClassType;
import com.greytest.entity.enums.Priority;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.TestType;
import com.greytest.entity.enums.Visibility;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.mapper.SourceUpdateMapper;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.SourceUpdateItemRepository;
import com.greytest.repository.SourceUpdateRepository;
import com.greytest.repository.UnitTestRepository;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.TestCaseRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.repository.TestPlanCoveredRuleRepository;
import com.greytest.entity.TestPlanCoveredRule;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.GenerationContextBuilder;
import com.greytest.service.agent.TestFeasibilityBlockedException;

import lombok.extern.slf4j.Slf4j;

/**
 * Service sinh tăng dần bằng AI Agent chỉ cho các method và artifacts bị ảnh hưởng,
 * tiết kiệm tối đa quota LLM và thời gian xử lý.
 */
@Slf4j
@Service
public class IncrementalGenerationService {

    private final ProjectRepository projectRepository;
    private final SourceUpdateRepository sourceUpdateRepository;
    private final SourceUpdateItemRepository sourceUpdateItemRepository;
    private final JavaClassRepository javaClassRepository;
    private final JavaMethodRepository javaMethodRepository;
    private final UnitTestRepository unitTestRepository;
    private final SourceUpdateService sourceUpdateService;
    private final AIAgentService aiAgentService;
    private final BusinessRuleRepository businessRuleRepository;
    private final TestPlanRepository testPlanRepository;
    private final TestPlanCoveredRuleRepository testPlanCoveredRuleRepository;
    private final TestCaseRepository testCaseRepository;
    private final SourceUpdateMapper sourceUpdateMapper;
    private final ObjectMapper objectMapper;
    private final LlmBatchExecutor batchExecutor;
    @Autowired(required = false)
    private com.greytest.service.generation.IncrementalCheckpointCoordinator checkpoints;

    @Autowired
    public IncrementalGenerationService(
            ProjectRepository projectRepository,
            SourceUpdateRepository sourceUpdateRepository,
            SourceUpdateItemRepository sourceUpdateItemRepository,
            JavaClassRepository javaClassRepository,
            JavaMethodRepository javaMethodRepository,
            UnitTestRepository unitTestRepository,
            BusinessRuleRepository businessRuleRepository,
            TestPlanRepository testPlanRepository,
            TestCaseRepository testCaseRepository,
            SourceUpdateService sourceUpdateService,
            AIAgentService aiAgentService,
            SourceUpdateMapper sourceUpdateMapper,
            ObjectMapper objectMapper, TestPlanCoveredRuleRepository testPlanCoveredRuleRepository,
            LlmBatchExecutor batchExecutor) {
        this.projectRepository = projectRepository;
        this.sourceUpdateRepository = sourceUpdateRepository;
        this.sourceUpdateItemRepository = sourceUpdateItemRepository;
        this.javaClassRepository = javaClassRepository;
        this.javaMethodRepository = javaMethodRepository;
        this.unitTestRepository = unitTestRepository;
        this.businessRuleRepository = businessRuleRepository;
        this.testPlanRepository = testPlanRepository;
        this.testPlanCoveredRuleRepository = testPlanCoveredRuleRepository;
        this.testCaseRepository = testCaseRepository;
        this.sourceUpdateService = sourceUpdateService;
        this.aiAgentService = aiAgentService;
        this.sourceUpdateMapper = sourceUpdateMapper;
        this.objectMapper = objectMapper;
        this.batchExecutor = batchExecutor;
    }

    /** Constructor compatible với các test/service khởi tạo thủ công ngoài Spring. */
    public IncrementalGenerationService(
            ProjectRepository projectRepository,
            SourceUpdateRepository sourceUpdateRepository,
            SourceUpdateItemRepository sourceUpdateItemRepository,
            JavaClassRepository javaClassRepository,
            JavaMethodRepository javaMethodRepository,
            UnitTestRepository unitTestRepository,
            BusinessRuleRepository businessRuleRepository,
            TestPlanRepository testPlanRepository,
            TestCaseRepository testCaseRepository,
            SourceUpdateService sourceUpdateService,
            AIAgentService aiAgentService,
            SourceUpdateMapper sourceUpdateMapper,
            ObjectMapper objectMapper, TestPlanCoveredRuleRepository testPlanCoveredRuleRepository) {
        this(projectRepository, sourceUpdateRepository, sourceUpdateItemRepository, javaClassRepository,
                javaMethodRepository, unitTestRepository, businessRuleRepository, testPlanRepository,
                testCaseRepository, sourceUpdateService, aiAgentService, sourceUpdateMapper, objectMapper,
                testPlanCoveredRuleRepository, null);
    }

    public IncrementalGenerationService(
            ProjectRepository projectRepository,
            SourceUpdateRepository sourceUpdateRepository,
            SourceUpdateItemRepository sourceUpdateItemRepository,
            JavaClassRepository javaClassRepository,
            JavaMethodRepository javaMethodRepository,
            UnitTestRepository unitTestRepository,
            SourceUpdateService sourceUpdateService,
            AIAgentService aiAgentService,
            SourceUpdateMapper sourceUpdateMapper,
            ObjectMapper objectMapper) {
        this(projectRepository, sourceUpdateRepository, sourceUpdateItemRepository, javaClassRepository,
                javaMethodRepository, unitTestRepository, null, null, null, sourceUpdateService, aiAgentService,
                sourceUpdateMapper, objectMapper, null, null);
    }

    /**
     * Sinh tăng dần theo từng stage (BUSINESS_RULE, TEST_PLAN, TEST_CASE, UNIT_TEST)
     * chỉ tập trung vào các mục bị ảnh hưởng được phát hiện trong impactSummary.
     * KHÔNG bọc @Transactional ở đây để tránh giữ kết nối DB và deadlock khóa dòng
     * trong lúc chờ phản hồi từ các cuộc gọi mạng AI Agent (LLM).
     */
    public SourceUpdateDto generateIncrementalStage(
            Long projectId,
            Long updateId,
            GenerationProgressStage stage,
            AuthUser user) {
        if(checkpoints!=null) {
            authorizeGeneration(projectId,updateId,user);
            return checkpoints.withRun(projectId,updateId,user==null ? null : user.getId(),
                () -> generateStage(projectId,updateId,stage,user));
        }
        return GenerationJobContext.withActor(user == null ? null : user.getId(),
                () -> generateStage(projectId,updateId,stage,user));
    }

    /** Một lượt Sinh Tất Cả dùng chung analysis và giữ lease xuyên suốt bốn stage. */
    public SourceUpdateDto generateAll(Long projectId,Long updateId,AuthUser user) {
        if(checkpoints!=null) authorizeGeneration(projectId,updateId,user);
        java.util.function.Supplier<SourceUpdateDto> run=()->{
            SourceUpdateDto result=null;
            for(var stage:GenerationProgressStage.values()) result=generateStage(projectId,updateId,stage,user);
            return result;
        };
        return checkpoints==null ? GenerationJobContext.withActor(user == null ? null : user.getId(), run)
                : checkpoints.withRun(projectId,updateId,user==null ? null : user.getId(),run);
    }

    private void authorizeGeneration(Long projectId,Long updateId,AuthUser user) {
        var project=projectRepository.findById(projectId).orElseThrow(()->new ProjectNotFoundException(projectId));
        sourceUpdateService.checkProjectAccess(project,user);
        sourceUpdateRepository.findByIdAndProjectId(updateId,projectId)
            .orElseThrow(()->new IllegalArgumentException("Không tìm thấy bản cập nhật "+updateId));
    }

    private SourceUpdateDto generateStage(Long projectId,Long updateId,GenerationProgressStage stage,AuthUser user) {

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        sourceUpdateService.checkProjectAccess(project, user);

        SourceUpdate update = sourceUpdateRepository.findByIdAndProjectId(updateId, projectId)
                .or(() -> sourceUpdateRepository.findByIdAndProjectIdForUpdate(updateId, projectId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bản cập nhật " + updateId));

        if (update.getStatus() != SourceUpdateStatus.ANALYZED
                && update.getStatus() != SourceUpdateStatus.READY_TO_APPLY
                && !(checkpoints!=null && (update.getStatus()==SourceUpdateStatus.GENERATING || update.getStatus()==SourceUpdateStatus.FAILED))) {
            throw new IllegalArgumentException("Bản cập nhật phải ở trạng thái ANALYZED trước khi sinh AI: " + update.getStatus());
        }

        // Snapshot downstream đọc overlay đã review; luồng cũ vẫn yêu cầu Apply oracle trước.
        if (checkpoints == null && stage != GenerationProgressStage.BUSINESS_RULE) requireAppliedBusinessRuleChanges(projectId, updateId);
        update.setStatus(SourceUpdateStatus.GENERATING);
        update=checkpoints==null ? sourceUpdateRepository.save(update) : checkpoints.changeStatus(updateId,SourceUpdateStatus.GENERATING);

        long stageStarted=System.nanoTime();
        try {
            ImpactSummaryDto impact = parseImpactSummary(update.getImpactSummary());
            if(checkpoints!=null) checkpoints.beginStage(updateId,stage);
            switch (stage) {
                case BUSINESS_RULE -> generateIncrementalBusinessRules(projectId, updateId, impact);
                case TEST_PLAN -> generateIncrementalTestPlans(projectId, updateId, impact, sourceUpdateService.getCandidateSourcePath(update));
                case TEST_CASE -> generateIncrementalTestCases(projectId, updateId, impact, sourceUpdateService.getCandidateSourcePath(update));
                case UNIT_TEST -> generateIncrementalUnitTests(projectId, update, impact);
            }
            if(checkpoints!=null) checkpoints.completeStage(updateId,stage);

            update.setStatus(SourceUpdateStatus.READY_TO_APPLY);
            update = checkpoints==null ? sourceUpdateRepository.save(update) : checkpoints.changeStatus(updateId,SourceUpdateStatus.READY_TO_APPLY);
        } catch (Exception ex) {
            log.error("Lỗi khi sinh tăng dần stage {}: {}", stage, ex.getMessage(), ex);
            // Batch executor có thể bọc lỗi nghiệp vụ; còn case bị chặn thì tuyệt đối không mở Apply.
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof TestFeasibilityBlockedException blocked) {
                    update.setStatus(SourceUpdateStatus.FAILED);
                    if(checkpoints==null) sourceUpdateRepository.save(update); else checkpoints.changeStatus(updateId,SourceUpdateStatus.FAILED);
                    throw blocked;
                }
            }
            List<SourceUpdateItem> items = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
            update.setStatus(items.isEmpty() ? SourceUpdateStatus.ANALYZED : SourceUpdateStatus.READY_TO_APPLY);
            if(checkpoints==null) sourceUpdateRepository.save(update); else checkpoints.changeStatus(updateId,update.getStatus());
            throw ex;
        } finally {
            GenerationJobContext.recordDuration("stage."+stage.name()+".wall",System.nanoTime()-stageStarted);
        }

        List<SourceUpdateItem> items = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
        return sourceUpdateMapper.toUpdateDto(update, items);
    }

    private void generateIncrementalBusinessRules(Long projectId, Long updateId, ImpactSummaryDto impact) {
        List<Set<Long>> batches = checkpoints==null ? resolveTargetMethodBatches(projectId,updateId,impact)
            : checkpoints.mutate(()->resolveTargetMethodBatches(projectId,updateId,impact));

        if (batches.isEmpty()) {
            log.info("Không có target method nào cần sinh lại BR cho project {}", projectId);
            return;
        }

        AtomicInteger nextBrNumber=new AtomicInteger(nextBusinessRuleNumber(projectId));
        var snapshot=checkpoints==null ? null : sourceUpdateService.getCandidateSourcePath(
                sourceUpdateRepository.findByIdAndProjectId(updateId,projectId).orElseThrow());
        LlmBatchExecutor.mapOrSequential(batchExecutor, batches, methodIds -> {
            log.info("Gọi AI Agent sinh Business Rules cho batch {} methodIds: {}", methodIds.size(), methodIds);
            return executeBatch(projectId,updateId,GenerationProgressStage.BUSINESS_RULE,methodIds,snapshot,
                    BusinessRuleResponseDto.class,
                    ids->snapshot==null ? aiAgentService.generateBusinessRules(projectId,ids)
                        : aiAgentService.generateSnapshotBusinessRules(projectId,ids,snapshot),
                    (ids,response)-> {
                        var selected = response == null || response.rules() == null ? response
                                : new BusinessRuleResponseDto(response.rules().stream()
                                        .filter(rule -> rule != null && ids.contains(rule.methodId())).toList());
                        persistBusinessRules(projectId,updateId,selected,nextBrNumber);
                    });
        });
    }

    private void persistBusinessRules(Long projectId,Long updateId,BusinessRuleResponseDto response,AtomicInteger nextBrNumber) {
        var generatedRules=response==null || response.rules()==null ? List.<GeneratedBusinessRuleDto>of() : response.rules();
        if (!generatedRules.isEmpty()) {
            log.info("Generated business rules: {}", generatedRules);
            log.info("Next business rule number: {}", nextBrNumber);
            for (var br : generatedRules) {
                if (br == null) continue;
                SourceUpdateItem item = new SourceUpdateItem();
                item.setSourceUpdateId(updateId);
                item.setTargetType(SourceUpdateTargetType.BUSINESS_RULE);
                List<BusinessRule> methodRules = businessRuleRepository != null
                        ? businessRuleRepository.findByProjectId(projectId).stream()
                                .filter(r -> Objects.equals(r.getMethodId(), br.methodId())).toList()
                        : List.of();
                BusinessRule existing = methodRules.stream()
                        .filter(rule -> br.branchId() != null && Objects.equals(sourceBranchId(rule.getReviewNote()), decisionId(br.branchId())))
                        .findFirst().orElse(methodRules.size() == 1 && generatedRules.stream()
                                .filter(candidate -> candidate != null && Objects.equals(candidate.methodId(), br.methodId())).count() == 1
                                && (br.branchId() == null || sourceBranchId(methodRules.get(0).getReviewNote()) == null)
                                ? methodRules.get(0) : null);
                if (existing != null) {
                    if (checkpoints == null) {
                        item = existingProposal(updateId, SourceUpdateTargetType.BUSINESS_RULE, existing.getId());
                        if (isUserDecision(item)) continue;
                    }
                    item.setTargetId(existing.getId());
                    item.setAction(SourceUpdateAction.UPDATE);
                    item.setReason("Cập nhật Business Rule theo method bị thay đổi");
                } else if (businessRuleRepository != null) {
                    // Tạo BusinessRule tạm thời với status APPROVED để các stage TP/TC/UT tiếp theo có context đọc được
                    BusinessRule newRule = new BusinessRule();
                    newRule.setProjectId(projectId);
                    newRule.setMethodId(br.methodId());
                    newRule.setRuleCode(String.format("BR-%03d", nextBrNumber.getAndIncrement()));
                    newRule.setDescription(br.description());
                    newRule.setSource(RuleSource.AI_GENERATED);
                    newRule.setStatus(ReviewStatus.APPROVED);
                    newRule.setIsModified(false);
                    newRule.setReviewNote(SourceBranchReference.write(br.branchId(), null));
                    newRule = businessRuleRepository.save(newRule);
                    item.setTargetId(newRule.getId());
                    item.setAction(SourceUpdateAction.CREATE);
                    item.setReason("Thêm mới Business Rule cho method mới");
                }
                item.setAfterData(toJson(br));
                // Sinh lại không phải là đồng ý thay đổi yêu cầu đã duyệt.
                item.setReviewStatus(existing != null && existing.getStatus() == ReviewStatus.APPROVED
                        && !Objects.equals(existing.getDescription(), br.description())
                        ? SourceUpdateReviewStatus.PENDING : SourceUpdateReviewStatus.ACCEPTED);
                saveSourceUpdateItem(item);
            }
        }
    }

    private void generateIncrementalTestPlans(Long projectId, Long updateId, ImpactSummaryDto impact, java.nio.file.Path snapshot) {
        Set<Long> removedRuleIds = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                .filter(i -> i.getTargetType() == SourceUpdateTargetType.BUSINESS_RULE && i.getAction() == SourceUpdateAction.REMOVE)
                .filter(i->i.getReviewStatus()!=SourceUpdateReviewStatus.REJECTED)
                .map(SourceUpdateItem::getTargetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> ruleIds = new HashSet<>(impact.affectedBusinessRuleIds());
        ruleIds.removeAll(removedRuleIds);

        // Gom thêm các Business Rule mới được tạo (CREATE) từ stage trước
        sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                .filter(i -> i.getTargetType() == SourceUpdateTargetType.BUSINESS_RULE && i.getAction() == SourceUpdateAction.CREATE)
                .filter(i->i.getReviewStatus()!=SourceUpdateReviewStatus.REJECTED)
                .map(SourceUpdateItem::getTargetId)
                .filter(Objects::nonNull)
                .forEach(ruleIds::add);

        if (ruleIds.isEmpty()) {
            log.info("Không có Business Rule nào cần sinh Test Plan tăng dần cho project {}", projectId);
            return;
        }

        List<Set<Long>> ruleBatches = partitionRuleIdsByMethod(projectId, ruleIds);
        AtomicInteger nextTpNumber = new AtomicInteger(nextTestPlanNumber(projectId));
        List<TestPlanResponseDto> responses = LlmBatchExecutor.mapOrSequential(
                batchExecutor,
                ruleBatches,
                batch -> {
                    log.info("Gọi AI Agent sinh Test Plan cho {} ruleIds: {}", batch.size(), batch);
                    return executeBatch(projectId,updateId,GenerationProgressStage.TEST_PLAN,batch,snapshot,
                        TestPlanResponseDto.class,ids->snapshot == null ? aiAgentService.generateTestPlan(projectId,ids)
                                : aiAgentService.generateSnapshotTestPlan(projectId,ids,snapshot),
                        (ids,response)->persistTestPlans(projectId,updateId,response,nextTpNumber));
                });

        if (responses.isEmpty()) {
            log.info("AI không trả về Test Plan cho project {}", projectId);
        }
    }

    private void persistTestPlans(Long projectId, Long updateId, TestPlanResponseDto response,
            AtomicInteger nextTpNumber) {
        if (response != null && response.plans() != null) {
            for (var tp : response.plans()) {
                SourceUpdateItem item = new SourceUpdateItem();
                item.setSourceUpdateId(updateId);
                item.setTargetType(SourceUpdateTargetType.TEST_PLAN);
                TestPlan existing = testPlanRepository != null
                        ? testPlanRepository.findByProjectId(projectId).stream()
                                .filter(p -> Objects.equals(p.getBusinessRuleId(), tp.ruleId()))
                                .findFirst().orElse(null)
                        : null;
                if (existing != null) {
                    item.setTargetId(existing.getId());
                    item.setAction(SourceUpdateAction.UPDATE);
                    item.setReason("Cập nhật Test Plan theo Business Rule bị ảnh hưởng");
                } else if (testPlanRepository != null) {
                    TestPlan newPlan = new TestPlan();
                    newPlan.setProjectId(projectId);
                    newPlan.setBusinessRuleId(tp.ruleId());
                    newPlan.setPlanCode(String.format("TP-%03d", nextTpNumber.getAndIncrement()));
                    newPlan.setTitle(tp.title());
                    newPlan.setDescription(tp.description());
                    newPlan.setTestType(TestType.valueOf(tp.testType()));
                    newPlan.setStatus(ReviewStatus.APPROVED);
                    newPlan.setIsModified(false);
                    newPlan = testPlanRepository.save(newPlan);
                    if (testPlanCoveredRuleRepository != null) {
                        for (Long ruleId : tp.coveredRuleIds().stream().distinct().toList()) {
                            TestPlanCoveredRule covered = new TestPlanCoveredRule();
                            covered.setTestPlanId(newPlan.getId());
                            covered.setBusinessRuleId(ruleId);
                            testPlanCoveredRuleRepository.save(covered);
                        }
                    }
                    item.setTargetId(newPlan.getId());
                    item.setAction(SourceUpdateAction.CREATE);
                    item.setReason("Thêm mới Test Plan cho Business Rule mới");
                }
                item.setAfterData(toJson(tp));
                item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
                saveSourceUpdateItem(item);
            }
        }
    }

    private void generateIncrementalTestCases(Long projectId, Long updateId, ImpactSummaryDto impact, java.nio.file.Path snapshot) {
        Set<Long> removedPlanIds = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                .filter(i -> i.getTargetType() == SourceUpdateTargetType.TEST_PLAN && i.getAction() == SourceUpdateAction.REMOVE)
                .filter(i->i.getReviewStatus()!=SourceUpdateReviewStatus.REJECTED)
                .map(SourceUpdateItem::getTargetId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> planIds = new HashSet<>(impact.affectedTestPlanIds());
        planIds.removeAll(removedPlanIds);
        sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                .filter(i -> i.getTargetType() == SourceUpdateTargetType.TEST_PLAN && i.getAction() == SourceUpdateAction.CREATE)
                .filter(i->i.getReviewStatus()!=SourceUpdateReviewStatus.REJECTED)
                .map(SourceUpdateItem::getTargetId).filter(Objects::nonNull).forEach(planIds::add);
        if (planIds.isEmpty()) return;

        List<Set<Long>> planBatches = partitionIds(planIds, GenerationContextBuilder.MAX_TEST_CASE_PLANS);
        AtomicInteger nextCaseNumber = new AtomicInteger(nextTestCaseNumber(projectId));
        List<TestCaseResponseDto> responses = LlmBatchExecutor.mapOrSequential(
                batchExecutor, planBatches,
                batch -> executeBatch(projectId,updateId,GenerationProgressStage.TEST_CASE,batch,snapshot,
                        TestCaseResponseDto.class,ids->snapshot == null ? aiAgentService.generateTestCases(projectId,ids)
                                : aiAgentService.generateSnapshotTestCases(projectId,ids,snapshot),
                        (ids,response)->persistTestCases(projectId,updateId,ids,response,nextCaseNumber)));
        if (responses.isEmpty()) log.info("AI không trả về Test Case cho project {}", projectId);
    }

    private void persistTestCases(Long projectId, Long updateId, Set<Long> planIds,
            TestCaseResponseDto response, AtomicInteger nextCaseNumber) {
        if (response == null || response.cases() == null) return;
        Map<Long, List<GeneratedTestCaseDto>> byPlan = response.cases().stream()
                .filter(Objects::nonNull).filter(dto -> planIds.contains(dto.planId()))
                .collect(Collectors.groupingBy(GeneratedTestCaseDto::planId));
        for (Long planId : planIds) {
            List<GeneratedTestCaseDto> generated = byPlan.getOrDefault(planId, List.of());
            List<TestCase> existing = testCaseRepository == null ? List.of() : testCaseRepository.findByTestPlanId(planId);
            int matched = Math.min(existing.size(), generated.size());
            for (int i = 0; i < matched; i++) {
                TestCase original = existing.get(i);
                GeneratedTestCaseDto proposed = generated.get(i);
                SourceUpdateItem item = checkpoints == null
                        ? existingProposal(updateId, SourceUpdateTargetType.TEST_CASE, original.getId()) : new SourceUpdateItem();
                if (checkpoints == null && isUserDecision(item)) continue;
                item.setSourceUpdateId(updateId); item.setTargetType(SourceUpdateTargetType.TEST_CASE);
                item.setTargetId(original.getId()); item.setAction(SourceUpdateAction.UPDATE);
                item.setReason("Cập nhật Test Case theo Test Plan bị ảnh hưởng"); item.setAfterData(toJson(proposed));
                item.setReviewStatus(original.getStatus() == ReviewStatus.APPROVED
                        && (!Objects.equals(original.getExpectedResult(), proposed.expectedResult())
                            || !Objects.equals(original.getTestData(), proposed.testData())
                            || !Objects.equals(original.getPreconditions(), proposed.preconditions()))
                        ? SourceUpdateReviewStatus.PENDING : SourceUpdateReviewStatus.ACCEPTED);
                saveSourceUpdateItem(item);
            }
            for (int i = matched; i < generated.size(); i++) {
                GeneratedTestCaseDto dto = generated.get(i);
                Long createdId = null;
                if (testCaseRepository != null) {
                    TestCase tc = new TestCase();
                    tc.setTestPlanId(planId);
                    tc.setCaseCode(String.format("TC-%03d", nextCaseNumber.getAndIncrement()));
                    tc.setTestType(TestType.valueOf(dto.testType()));
                    tc.setDescription(dto.description()); tc.setPreconditions(dto.preconditions());
                    tc.setTestData(dto.testData()); tc.setExpectedResult(dto.expectedResult());
                    tc.setPriority(Priority.valueOf(dto.priority())); tc.setTraceSource(dto.traceSource());
                    tc.setStatus(ReviewStatus.APPROVED); tc.setIsModified(false);
                    createdId = testCaseRepository.save(tc).getId();
                }
                saveSourceUpdateItem(updateId, SourceUpdateTargetType.TEST_CASE, createdId,
                        "plan:" + planId + ":case:" + i, SourceUpdateAction.CREATE,
                        "Them moi Test Case theo Test Plan bi anh huong", dto);
            }
            for (int i = matched; i < existing.size(); i++) {
                TestCase stale = existing.get(i);
                SourceUpdateItem draftItem = sourceUpdateItemRepository
                        .findBySourceUpdateIdAndTargetTypeAndTargetId(
                                updateId, SourceUpdateTargetType.TEST_CASE, stale.getId()).orElse(null);
                if (draftItem != null && draftItem.getAction() == SourceUpdateAction.CREATE) {
                    if(checkpoints!=null) checkpoints.assertActiveArtifactUnchanged(draftItem);
                    if(checkpoints!=null && (draftItem.getUserRevision()>0 || !checkpoints.isRecordedOutput(draftItem))) {
                        var proposal=new SourceUpdateItem(); proposal.setId(draftItem.getId()); proposal.setAction(SourceUpdateAction.REMOVE);
                        throw new com.greytest.service.generation.GenerationConflictException(draftItem.getId(),toJson(proposal));
                    }
                    var child=sourceUpdateItemRepository.findBySourceUpdateIdAndTargetKey(updateId,"case:"+stale.getId()).orElse(null);
                    if(checkpoints!=null && child!=null && (child.getUserRevision()==null || child.getUserRevision()>0
                            || child.getReviewStatus()==SourceUpdateReviewStatus.MODIFIED || child.getReviewStatus()==SourceUpdateReviewStatus.REJECTED
                            || !checkpoints.isRecordedOutput(child))) {
                        var proposal=new SourceUpdateItem(); proposal.setAction(SourceUpdateAction.REMOVE); proposal.setAfterData(child.getAfterData());
                        throw new com.greytest.service.generation.GenerationConflictException(child.getId(),toJson(proposal));
                    }
                    if(checkpoints!=null && unitTestRepository.findByTestCaseId(stale.getId())!=null) {
                        var proposal=new SourceUpdateItem(); proposal.setAction(SourceUpdateAction.REMOVE);
                        throw new com.greytest.service.generation.GenerationConflictException(draftItem.getId(),toJson(proposal));
                    }
                    // Đây là artifact tạm do chính draft sinh ra; xóa ngay để Apply/Cancel
                    // không giữ lại bản ghi mồ côi khi lần sinh sau trả ít case hơn.
                    sourceUpdateItemRepository.findBySourceUpdateIdAndTargetKey(
                            updateId, "case:" + stale.getId()).ifPresent(sourceUpdateItemRepository::delete);
                    sourceUpdateItemRepository.delete(draftItem);
                    if (testCaseRepository != null) testCaseRepository.deleteById(stale.getId());
                } else {
                    SourceUpdateItem item = checkpoints == null
                            ? existingProposal(updateId, SourceUpdateTargetType.TEST_CASE, stale.getId()) : new SourceUpdateItem();
                    if (checkpoints == null && isUserDecision(item)) continue;
                    item.setSourceUpdateId(updateId); item.setTargetType(SourceUpdateTargetType.TEST_CASE);
                    item.setTargetId(stale.getId()); item.setAction(SourceUpdateAction.REMOVE);
                    item.setReason("Xóa Test Case cũ không còn phù hợp"); item.setAfterData(null);
                    item.setReviewStatus(stale.getStatus() == ReviewStatus.APPROVED
                            ? SourceUpdateReviewStatus.PENDING : SourceUpdateReviewStatus.ACCEPTED);
                    saveSourceUpdateItem(item);
                    proposeRemovedCaseUnitTests(updateId, stale);
                }
            }
        }
    }

    /** Đề xuất xóa test phụ thuộc, không xóa trực tiếp hoặc ghi đè lựa chọn người dùng. */
    private void proposeRemovedCaseUnitTests(Long updateId, TestCase testCase) {
        for (UnitTest unitTest : unitTestRepository.findByTestCaseIdIn(List.of(testCase.getId()))) {
            SourceUpdateItem item = checkpoints == null
                    ? existingProposal(updateId, SourceUpdateTargetType.UNIT_TEST, unitTest.getId()) : new SourceUpdateItem();
            if (checkpoints == null && isUserDecision(item)) continue;
            item.setSourceUpdateId(updateId);
            item.setTargetType(SourceUpdateTargetType.UNIT_TEST);
            item.setTargetId(unitTest.getId());
            item.setAction(SourceUpdateAction.REMOVE);
            item.setAfterData(null);
            item.setReason("Xóa Unit Test gắn với Test Case không còn phù hợp");
            item.setReviewStatus(testCase.getStatus() == ReviewStatus.APPROVED
                    ? SourceUpdateReviewStatus.PENDING : SourceUpdateReviewStatus.ACCEPTED);
            saveSourceUpdateItem(item);
        }
    }

    private void generateIncrementalUnitTests(Long projectId, SourceUpdate update, ImpactSummaryDto impact) {
        Long updateId = update.getId();
        List<SourceUpdateItem> existingItems = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
        Set<Long> removedCaseIds = existingItems.stream()
                .filter(i -> i.getTargetType() == SourceUpdateTargetType.TEST_CASE && i.getAction() == SourceUpdateAction.REMOVE)
                .filter(i->i.getReviewStatus()!=SourceUpdateReviewStatus.REJECTED)
                .map(SourceUpdateItem::getTargetId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> caseIds = new HashSet<>(impact.affectedTestCaseIds());
        // Hash UT bao phủ cả file: method không đổi vẫn cần xác minh/sinh lại khi file nguồn đổi.
        caseIds.addAll(staleSourceCaseIds(projectId, sourceUpdateService.getCandidateSourcePath(update)));
        Set<Long> removedUnitIds = existingItems.stream()
                .filter(i -> i.getTargetType() == SourceUpdateTargetType.UNIT_TEST && i.getAction() == SourceUpdateAction.REMOVE)
                .filter(i -> i.getReviewStatus() != SourceUpdateReviewStatus.REJECTED)
                .map(SourceUpdateItem::getTargetId).filter(Objects::nonNull).collect(Collectors.toSet());
        existingItems.stream()
                .filter(i -> i.getTargetType() == SourceUpdateTargetType.TEST_CASE && i.getAction() == SourceUpdateAction.CREATE)
                .filter(i->i.getReviewStatus()!=SourceUpdateReviewStatus.REJECTED)
                .map(SourceUpdateItem::getTargetId).filter(Objects::nonNull).forEach(caseIds::add);
        caseIds.removeAll(removedCaseIds);
        for (Long unitId : removedUnitIds) unitTestRepository.findById(unitId)
                .map(UnitTest::getTestCaseId).ifPresent(caseIds::remove);
        if (caseIds.isEmpty()) return;

        List<Set<Long>> caseBatches = partitionIds(caseIds, GenerationContextBuilder.MAX_UNIT_TEST_CASES);
        Map<Long, BlockedUnitTestDto> blockedCases = new java.util.concurrent.ConcurrentHashMap<>();
        List<UnitTestResponseDto> responses;
        try {
            responses = LlmBatchExecutor.mapOrSequential(
                batchExecutor, caseBatches,
                batch -> executeBatch(projectId,updateId,GenerationProgressStage.UNIT_TEST,batch,
                        sourceUpdateService.getCandidateSourcePath(update),UnitTestResponseDto.class,
                        ids->aiAgentService.generateSnapshotUnitTests(projectId,ids,sourceUpdateService.getCandidateSourcePath(update)),
                        (ids,response) -> {
                    if (response != null && response.blockedCases() != null) {
                        for (BlockedUnitTestDto blocked : response.blockedCases()) {
                            if (blocked != null && caseIds.contains(blocked.caseId())) {
                                blockedCases.put(blocked.caseId(), blocked);
                            }
                        }
                    }
                    persistUnitTests(updateId, ids, response);
                }));
        } catch (RuntimeException failure) {
            if (!blockedCases.isEmpty()) throw blockedSnapshotFailure(blockedCases, failure);
            throw failure;
        }
        if (!blockedCases.isEmpty()) throw blockedSnapshotFailure(blockedCases, null);
        if (responses.isEmpty()) log.info("AI không trả về Unit Test cho project {}", projectId);
    }

    private Set<Long> staleSourceCaseIds(Long projectId, java.nio.file.Path candidate) {
        if (candidate == null || testPlanRepository == null || testCaseRepository == null || unitTestRepository == null)
            return Set.of();
        Set<Long> approvedPlans = testPlanRepository.findByProjectId(projectId).stream()
                .filter(plan -> plan.getStatus() == ReviewStatus.APPROVED).map(TestPlan::getId).collect(Collectors.toSet());
        List<Long> approvedCases = testCaseRepository.findByProjectId(projectId).stream()
                .filter(testCase -> testCase.getStatus() == ReviewStatus.APPROVED && approvedPlans.contains(testCase.getTestPlanId()))
                .map(TestCase::getId).toList();
        if (approvedCases.isEmpty()) return Set.of();
        Set<Long> stale = new HashSet<>();
        for (UnitTest test : unitTestRepository.findByTestCaseIdIn(approvedCases)) {
            if (test.getSourceCode() == null) continue;
            String metadata = com.greytest.service.agent.GenerationSourceFingerprint.aggregate(List.of(test.getSourceCode()));
            if (!metadata.contains(com.greytest.service.agent.GenerationSourceFingerprint.PREFIX)) continue;
            try {
                com.greytest.service.agent.GenerationSourceFingerprint.alignToSourceRoot(List.of(test.getSourceCode()), candidate);
            } catch (IllegalArgumentException changed) {
                stale.add(test.getTestCaseId());
            }
        }
        return stale;
    }

    private TestFeasibilityBlockedException blockedSnapshotFailure(
            Map<Long, BlockedUnitTestDto> blockedCases, Throwable cause) {
        String details = blockedCases.values().stream().sorted(Comparator.comparing(BlockedUnitTestDto::caseId))
                .map(blocked -> (blocked.caseCode() == null ? "Test Case" : blocked.caseCode())
                        + " (ID " + blocked.caseId() + "): " + blocked.details())
                .collect(Collectors.joining("; "));
        return new TestFeasibilityBlockedException("Không thể Apply bản cập nhật vì còn Test Case bị chặn: "
                + details + ". Các draft hợp lệ được giữ để xem lại. Hãy xử lý điều kiện bị chặn và tạo source ứng viên mới.", cause);
    }

    private void persistUnitTests(Long updateId, Set<Long> caseIds, UnitTestResponseDto response) {
        if (response == null || response.unitTests() == null) return;
        for (var ut : response.unitTests()) {
            if (ut == null || !caseIds.contains(ut.caseId())) continue;
            UnitTest existing = unitTestRepository == null ? null : unitTestRepository.findByTestCaseId(ut.caseId());
            saveSourceUpdateItem(updateId, SourceUpdateTargetType.UNIT_TEST,
                    existing == null ? null : existing.getId(), "case:" + ut.caseId(),
                    existing == null ? SourceUpdateAction.CREATE : SourceUpdateAction.UPDATE,
                    "Unit Test mới sinh tăng dần từ AI", ut);
        }
    }

    private void saveSourceUpdateItem(SourceUpdateItem item) {
        SourceUpdateItem target = null;
        if (item.getTargetId() != null) {
            target = sourceUpdateItemRepository.findBySourceUpdateIdAndTargetTypeAndTargetId(
                    item.getSourceUpdateId(), item.getTargetType(), item.getTargetId()).orElse(null);
        } else if (item.getTargetKey() != null) {
            target = sourceUpdateItemRepository.findBySourceUpdateIdAndTargetKey(
                    item.getSourceUpdateId(), item.getTargetKey()).orElse(null);
        }
        if(checkpoints!=null) {
            if(target!=null && target.getAction()==SourceUpdateAction.CREATE) checkpoints.assertActiveArtifactUnchanged(target);
            if(item.getBeforeData()==null && (target==null || target.getAction()!=SourceUpdateAction.CREATE)) {
                if(item.getAction()==SourceUpdateAction.CREATE && item.getTargetId()!=null) flushTemporaryArtifact(item);
                item.setBeforeData(checkpoints.activeSnapshot(item));
            }
        }
        if (target != null) {
            if(checkpoints!=null && (target.getUserRevision()==null || target.getUserRevision()>0
                    || target.getReviewStatus()==SourceUpdateReviewStatus.MODIFIED
                    || target.getReviewStatus()==SourceUpdateReviewStatus.REJECTED
                    || (target.getAfterData()!=null && target.getReviewStatus()==SourceUpdateReviewStatus.ACCEPTED
                        && !checkpoints.isRecordedOutput(target)))) {
                if(!Objects.equals(target.getAfterData(),item.getAfterData()) || target.getAction()!=item.getAction())
                    throw new com.greytest.service.generation.GenerationConflictException(target.getId(),toJson(item));
                return;
            }
            // Artifact tạm đã được tạo ở batch trước vẫn phải giữ CREATE để Apply/Cancel
            // xử lý đúng bản ghi draft khi stage được tiếp tục.
            SourceUpdateAction action = target.getAction() == SourceUpdateAction.CREATE
                    && item.getAction() == SourceUpdateAction.UPDATE
                            ? SourceUpdateAction.CREATE : item.getAction();
            target.setTargetId(item.getTargetId());
            // REMOVE có thể chỉ mang targetId; không xóa liên kết case của đề xuất đã lưu.
            if (item.getTargetKey() != null) target.setTargetKey(item.getTargetKey());
            target.setAction(action);
            target.setReason(item.getReason());
            if(target.getBeforeData()==null) target.setBeforeData(item.getBeforeData());
            target.setAfterData(item.getAfterData());
            target.setReviewStatus(item.getReviewStatus());
            item = target;
        }
        if(checkpoints!=null)
            item.setReviewStatus(SourceUpdateReviewStatus.PENDING);
        sourceUpdateItemRepository.save(item);
    }

    private void flushTemporaryArtifact(SourceUpdateItem item) {
        switch(item.getTargetType()) {
            case BUSINESS_RULE -> businessRuleRepository.flush();
            case TEST_PLAN -> { testPlanRepository.flush(); if(testPlanCoveredRuleRepository!=null) testPlanCoveredRuleRepository.flush(); }
            case TEST_CASE -> testCaseRepository.flush();
            case UNIT_TEST -> { if(unitTestRepository!=null) unitTestRepository.flush(); }
            default -> { }
        }
    }

    private boolean protectedArtifact(SourceUpdateItem item) {
        return switch(item.getTargetType()) {
            case BUSINESS_RULE -> businessRuleRepository.findById(item.getTargetId()).map(r->Boolean.TRUE.equals(r.getIsModified()) || r.getStatus()==ReviewStatus.APPROVED).orElse(false);
            case TEST_PLAN -> testPlanRepository.findById(item.getTargetId()).map(r->Boolean.TRUE.equals(r.getIsModified()) || r.getStatus()==ReviewStatus.APPROVED).orElse(false);
            case TEST_CASE -> testCaseRepository.findById(item.getTargetId()).map(r->Boolean.TRUE.equals(r.getIsModified()) || r.getStatus()==ReviewStatus.APPROVED).orElse(false);
            case UNIT_TEST -> unitTestRepository.existsById(item.getTargetId());
            default -> true;
        };
    }

    private <R> R executeBatch(Long projectId,Long updateId,GenerationProgressStage stage,Set<Long> ids,
            java.nio.file.Path snapshot,Class<R> responseType,java.util.function.Function<Set<Long>,R> generate,
            java.util.function.BiConsumer<Set<Long>,R> persist) {
        if(checkpoints!=null) return checkpoints.executeBatch(projectId,updateId,stage,ids,snapshot,responseType,generate,persist);
        R response=generate.apply(ids); persist.accept(ids,response); return response;
    }

    private void saveSourceUpdateItem(Long updateId, SourceUpdateTargetType targetType, Long targetId,
            String targetKey, SourceUpdateAction action, String reason, Object afterData) {
        SourceUpdateItem item = new SourceUpdateItem();
        item.setSourceUpdateId(updateId);
        item.setTargetType(targetType);
        item.setTargetId(targetId);
        item.setTargetKey(targetKey);
        item.setAction(action);
        item.setReason(reason);
        item.setAfterData(afterData == null ? null : toJson(afterData));
        item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
        saveSourceUpdateItem(item);
    }

    private List<Set<Long>> partitionRuleIdsByMethod(Long projectId, Set<Long> ruleIds) {
        if (businessRuleRepository == null) return partitionIds(ruleIds, GenerationContextBuilder.MAX_TEST_PLAN_METHODS);
        Map<Long, List<Long>> byMethod = businessRuleRepository.findByProjectId(projectId).stream()
                .filter(rule -> ruleIds.contains(rule.getId()))
                .sorted(Comparator.comparing(BusinessRule::getMethodId, Comparator.nullsLast(Long::compareTo))
                        .thenComparing(BusinessRule::getId))
                .collect(Collectors.groupingBy(BusinessRule::getMethodId, LinkedHashMap::new,
                        Collectors.mapping(BusinessRule::getId, Collectors.toList())));
        Set<Long> resolved = byMethod.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        ruleIds.stream().filter(id -> !resolved.contains(id)).sorted()
                .forEach(id -> byMethod.put(null, appendId(byMethod.get(null), id)));
        List<List<Long>> methodGroups = new ArrayList<>(byMethod.values());
        List<Set<Long>> batches = new ArrayList<>();
        for (int start = 0; start < methodGroups.size(); start += GenerationContextBuilder.MAX_TEST_PLAN_METHODS) {
            batches.add(methodGroups.subList(start, Math.min(start + GenerationContextBuilder.MAX_TEST_PLAN_METHODS, methodGroups.size()))
                    .stream().flatMap(List::stream).collect(Collectors.toCollection(LinkedHashSet::new)));
        }
        return batches;
    }

    private List<Long> appendId(List<Long> ids, Long id) {
        List<Long> result = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
        result.add(id);
        return result;
    }

    private List<Set<Long>> partitionIds(Set<Long> ids, int batchSize) {
        List<Long> sortedIds = ids.stream().filter(Objects::nonNull).sorted().toList();
        List<Set<Long>> batches = new ArrayList<>();
        for (int start = 0; start < sortedIds.size(); start += batchSize) {
            batches.add(new LinkedHashSet<>(sortedIds.subList(start, Math.min(start + batchSize, sortedIds.size()))));
        }
        return batches;
    }

    /** Tái sử dụng đề xuất của analysis để không có hai quyết định cho cùng artifact. */
    private SourceUpdateItem existingProposal(Long updateId, SourceUpdateTargetType targetType, Long targetId) {
        List<SourceUpdateItem> matches = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                .filter(item -> item.getTargetType() == targetType && Objects.equals(item.getTargetId(), targetId))
                .toList();
        SourceUpdateItem item = matches.stream().filter(this::isUserDecision).findFirst()
                .orElse(matches.isEmpty() ? new SourceUpdateItem() : matches.get(0));
        for (SourceUpdateItem duplicate : matches) {
            if (duplicate != item && !isUserDecision(duplicate)) sourceUpdateItemRepository.delete(duplicate);
        }
        item.setSourceUpdateId(updateId);
        item.setTargetType(targetType);
        return item;
    }

    private boolean isUserDecision(SourceUpdateItem item) {
        return item.getReviewStatus() == SourceUpdateReviewStatus.MODIFIED
                || item.getReviewStatus() == SourceUpdateReviewStatus.REJECTED;
    }

    private String sourceBranchId(String reviewNote) {
        return SourceBranchReference.read(reviewNote);
    }

    private String decisionId(String branchId) {
        return SourceBranchReference.normalize(branchId);
    }

    /** Không sinh downstream bằng oracle cũ sau khi user đã chọn đổi yêu cầu trong draft. */
    private void requireAppliedBusinessRuleChanges(Long projectId, Long updateId) {
        if (businessRuleRepository == null) return;
        Map<Long, BusinessRule> rules = businessRuleRepository.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(BusinessRule::getId, rule -> rule));
        for (SourceUpdateItem item : sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId)) {
            if (item.getTargetType() != SourceUpdateTargetType.BUSINESS_RULE || item.getAction() != SourceUpdateAction.UPDATE
                    || item.getAfterData() == null || (item.getReviewStatus() != SourceUpdateReviewStatus.ACCEPTED
                    && item.getReviewStatus() != SourceUpdateReviewStatus.MODIFIED)) continue;
            BusinessRule current = rules.get(item.getTargetId());
            if (current == null) continue;
            try {
                var proposed = objectMapper.readValue(item.getAfterData(), com.greytest.dto.agent.GenerationResponseDtos.GeneratedBusinessRuleDto.class);
                if (!Objects.equals(current.getDescription(), proposed.description())) {
                    throw new IllegalArgumentException("Hãy áp dụng cập nhật Business Rule trước, rồi review hoặc sinh lại Test Plan/Test Case/Unit Test theo BR mới.");
                }
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Đề xuất Business Rule không hợp lệ; cần kiểm tra trước khi sinh downstream.", e);
            }
        }
    }

    private int nextBusinessRuleNumber(Long projectId) {
        if (businessRuleRepository == null) return 1;
        return businessRuleRepository.findByProjectId(projectId).stream()
                .map(BusinessRule::getRuleCode)
                .filter(Objects::nonNull)
                .map(code -> code.replaceAll("[^0-9]", ""))
                .filter(digits -> !digits.isBlank())
                .mapToInt(Integer::parseInt)
                .max().orElse(0) + 1;
    }

    private int nextTestPlanNumber(Long projectId) {
        if (testPlanRepository == null) return 1;
        return testPlanRepository.findByProjectId(projectId).stream()
                .map(TestPlan::getPlanCode)
                .filter(Objects::nonNull)
                .map(code -> code.replaceAll("[^0-9]", ""))
                .filter(digits -> !digits.isBlank())
                .mapToInt(Integer::parseInt)
                .max().orElse(0) + 1;
    }

    private int nextTestCaseNumber(Long projectId) {
        if (testCaseRepository == null) return 1;
        return testCaseRepository.findByProjectId(projectId).stream()
                .map(TestCase::getCaseCode)
                .filter(Objects::nonNull)
                .map(code -> code.replaceAll("[^0-9]", ""))
                .filter(digits -> !digits.isBlank())
                .mapToInt(Integer::parseInt)
                .max().orElse(0) + 1;
    }

    private List<Set<Long>> resolveTargetMethodBatches(Long projectId,Long updateId, ImpactSummaryDto impact) {
        Map<Long, Set<Long>> methodIdsByClass = new LinkedHashMap<>();
        List<JavaClass> classes = javaClassRepository.findByProjectId(projectId);

        for (MethodDiffItem changed : impact.changedMethods()) {
            if (changed.diffType() == MethodDiffType.DELETED) {
                continue;
            }

            JavaMethod matchedMethod = null;
            JavaClass matchedClass = null;

            var sourceClasses=classes.stream().filter(c->Objects.equals(c.getQualifiedName(),changed.qualifiedClassName()))
                .filter(c->changed.servicePath()==null || changed.servicePath().isBlank()
                    || Objects.equals(ServiceScopeResolver.normalizeServicePath(ServiceScopeResolver.modulePath(c.getFilePath())),
                        ServiceScopeResolver.normalizeServicePath(changed.servicePath())))
                .toList();
            if(sourceClasses.size()>1) throw new IllegalArgumentException("Ambiguous source module for "+changed.methodKey());
            for (JavaClass jc : sourceClasses) {
                if (Objects.equals(jc.getQualifiedName(), changed.qualifiedClassName())) {
                    matchedClass = jc;
                    List<JavaMethod> methods = javaMethodRepository.findByClassId(jc.getId());
                    for (JavaMethod m : methods) {
                        String parameterTypes = m.getParameters() == null ? ""
                                : m.getParameters().stream().map(MethodParam::type).collect(Collectors.joining(","));
                        if (Objects.equals(m.getMethodName(), changed.methodName())
                                && Objects.equals(m.getMethodName() + "(" + parameterTypes + ")", changed.signature())) {
                            matchedMethod = m;
                            break;
                        }
                    }
                    if (matchedMethod != null) break;
                }
            }

            if (matchedMethod != null) {
                // Source ứng viên chỉ đi vào context; source đang hoạt động đổi khi Apply.
                methodIdsByClass.computeIfAbsent(matchedClass.getId(), ignored -> new LinkedHashSet<>())
                        .add(matchedMethod.getId());
            } else if (changed.diffType() == MethodDiffType.ADDED) {
                var parsedMethod = new JavaParser().parseMethodDeclaration(changed.afterSource()).getResult()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Cannot parse added method: " + changed.methodKey()));
                // Method mới thêm: tạo JavaClass (nếu chưa có) và JavaMethod để có methodId hợp lệ cho AI
                if (matchedClass == null) {
                    matchedClass = new JavaClass();
                    matchedClass.setProjectId(projectId);
                    matchedClass.setQualifiedName(changed.qualifiedClassName());
                    matchedClass.setClassName(changed.className());
                    matchedClass.setPackageName(extractPackageName(changed.qualifiedClassName()));
                    matchedClass.setClassType(ClassType.SERVICE);
                    String module=changed.servicePath()==null || changed.servicePath().equals(".") ? "" : changed.servicePath()+"/";
                    matchedClass.setFilePath(module+"src/main/java/"+changed.qualifiedClassName().replace('.','/')+".java");
                    matchedClass = javaClassRepository.save(matchedClass);
                    classes.add(matchedClass);
                }

                JavaMethod newMethod = new JavaMethod();
                newMethod.setClassId(matchedClass.getId());
                newMethod.setMethodName(changed.methodName());
                newMethod.setReturnType(parsedMethod.getTypeAsString());
                newMethod.setParameters(parsedMethod.getParameters().stream()
                        .map(parameter -> new MethodParam(parameter.getNameAsString(), parameter.getTypeAsString()))
                        .toList());
                newMethod.setThrowsList(parsedMethod.getThrownExceptions().stream().map(Object::toString).toList());
                newMethod.setVisibility(parsedMethod.isPublic() ? Visibility.PUBLIC
                        : parsedMethod.isProtected() ? Visibility.PROTECTED
                        : parsedMethod.isPrivate() ? Visibility.PRIVATE : Visibility.PACKAGE_PRIVATE);
                newMethod.setSourceCode(changed.afterSource() != null ? changed.afterSource() : "");
                newMethod.setLineStart(1);
                newMethod.setLineEnd(10);
                newMethod = javaMethodRepository.save(newMethod);
                if(checkpoints!=null) {
                    var drafts=sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                        .filter(i->i.getTargetType()==SourceUpdateTargetType.METHOD && i.getAction()==SourceUpdateAction.CREATE
                            && Objects.equals(i.getTargetKey(),changed.methodKey())).toList();
                    if(drafts.size()!=1) throw new IllegalArgumentException("Missing or ambiguous added method draft "+changed.methodKey());
                    var draft=drafts.get(0); draft.setTargetId(newMethod.getId()); sourceUpdateItemRepository.save(draft);
                }
                methodIdsByClass.computeIfAbsent(matchedClass.getId(), ignored -> new LinkedHashSet<>())
                        .add(newMethod.getId());
            }
        }
        // Context BR chỉ nhận một Service class, tối đa ba method mỗi lượt như luồng sinh BR thông thường.
        List<Set<Long>> batches = new ArrayList<>();
        for (Set<Long> classMethodIds : methodIdsByClass.values()) {
            List<Long> methodIds = classMethodIds.stream().sorted().toList();
            for (int start = 0; start < methodIds.size(); start += GenerationContextBuilder.MAX_GENERATION_METHODS) {
                batches.add(new LinkedHashSet<>(methodIds.subList(start,
                        Math.min(start + GenerationContextBuilder.MAX_GENERATION_METHODS, methodIds.size()))));
            }
        }
        return batches;
    }

    private String extractPackageName(String qualifiedName) {
        if (qualifiedName == null) return "";
        int idx = qualifiedName.lastIndexOf('.');
        return idx > 0 ? qualifiedName.substring(0, idx) : "";
    }

    private ImpactSummaryDto parseImpactSummary(String json) {
        if(checkpoints!=null) return com.greytest.service.generation.GenerationImpactValidator.parse(objectMapper,json);
        if (json == null || json.isBlank()) {
            return new ImpactSummaryDto(0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        try {
            return objectMapper.readValue(json, ImpactSummaryDto.class);
        } catch (JsonProcessingException e) {
            log.warn("Không parse được impact summary: {}", e.getMessage());
            return new ImpactSummaryDto(0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize generation output",e);
        }
    }
}
