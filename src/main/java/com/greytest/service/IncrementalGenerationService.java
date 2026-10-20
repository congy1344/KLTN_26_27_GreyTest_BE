package com.greytest.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.GenerationContextBuilder;

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
    private final TestCaseRepository testCaseRepository;
    private final SourceUpdateMapper sourceUpdateMapper;
    private final ObjectMapper objectMapper;

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
            ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.sourceUpdateRepository = sourceUpdateRepository;
        this.sourceUpdateItemRepository = sourceUpdateItemRepository;
        this.javaClassRepository = javaClassRepository;
        this.javaMethodRepository = javaMethodRepository;
        this.unitTestRepository = unitTestRepository;
        this.businessRuleRepository = businessRuleRepository;
        this.testPlanRepository = testPlanRepository;
        this.testCaseRepository = testCaseRepository;
        this.sourceUpdateService = sourceUpdateService;
        this.aiAgentService = aiAgentService;
        this.sourceUpdateMapper = sourceUpdateMapper;
        this.objectMapper = objectMapper;
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
                sourceUpdateMapper, objectMapper);
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

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        sourceUpdateService.checkProjectAccess(project, user);

        SourceUpdate update = sourceUpdateRepository.findByIdAndProjectId(updateId, projectId)
                .or(() -> sourceUpdateRepository.findByIdAndProjectIdForUpdate(updateId, projectId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bản cập nhật " + updateId));

        if (update.getStatus() != SourceUpdateStatus.ANALYZED
                && update.getStatus() != SourceUpdateStatus.READY_TO_APPLY) {
            throw new IllegalArgumentException("Bản cập nhật phải ở trạng thái ANALYZED trước khi sinh AI: " + update.getStatus());
        }

        if (stage != GenerationProgressStage.BUSINESS_RULE) requireAppliedBusinessRuleChanges(projectId, updateId);
        update.setStatus(SourceUpdateStatus.GENERATING);
        sourceUpdateRepository.save(update);

        ImpactSummaryDto impact = parseImpactSummary(update.getImpactSummary());

        try {
            switch (stage) {
                case BUSINESS_RULE -> generateIncrementalBusinessRules(projectId, updateId, impact);
                case TEST_PLAN -> generateIncrementalTestPlans(projectId, updateId, impact);
                case TEST_CASE -> generateIncrementalTestCases(projectId, updateId, impact);
                case UNIT_TEST -> generateIncrementalUnitTests(projectId, update, impact);
            }

            update.setStatus(SourceUpdateStatus.READY_TO_APPLY);
            update = sourceUpdateRepository.save(update);
        } catch (Exception ex) {
            log.error("Lỗi khi sinh tăng dần stage {}: {}", stage, ex.getMessage(), ex);
            List<SourceUpdateItem> items = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
            update.setStatus(items.isEmpty() ? SourceUpdateStatus.ANALYZED : SourceUpdateStatus.READY_TO_APPLY);
            sourceUpdateRepository.save(update);
            throw ex;
        }

        List<SourceUpdateItem> items = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
        return sourceUpdateMapper.toUpdateDto(update, items);
    }

    private void generateIncrementalBusinessRules(Long projectId, Long updateId, ImpactSummaryDto impact) {
        List<Set<Long>> batches = resolveTargetMethodBatches(projectId, impact);

        if (batches.isEmpty()) {
            log.info("Không có target method nào cần sinh lại BR cho project {}", projectId);
            return;
        }

        List<GeneratedBusinessRuleDto> generatedRules = new ArrayList<>();
        for (Set<Long> methodIds : batches) {
            log.info("Gọi AI Agent sinh Business Rules cho batch {} methodIds: {}", methodIds.size(), methodIds);
            BusinessRuleResponseDto response = aiAgentService.generateBusinessRules(projectId, methodIds);
            if (response != null && response.rules() != null) {
                generatedRules.addAll(response.rules());
            }
        }

        if (!generatedRules.isEmpty()) {
            int nextBrNumber = nextBusinessRuleNumber(projectId);
            log.info("Generated business rules: {}", generatedRules);
            log.info("Next business rule number: {}", nextBrNumber);
            for (var br : generatedRules) {
                if (br == null || !batches.stream().anyMatch(ids -> ids.contains(br.methodId()))) continue;
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
                    item = existingProposal(updateId, SourceUpdateTargetType.BUSINESS_RULE, existing.getId());
                    if (isUserDecision(item)) continue;
                    item.setTargetId(existing.getId());
                    item.setAction(SourceUpdateAction.UPDATE);
                    item.setReason("Cập nhật Business Rule theo method bị thay đổi");
                } else if (businessRuleRepository != null) {
                    // Tạo BusinessRule tạm thời với status APPROVED để các stage TP/TC/UT tiếp theo có context đọc được
                    BusinessRule newRule = new BusinessRule();
                    newRule.setProjectId(projectId);
                    newRule.setMethodId(br.methodId());
                    newRule.setRuleCode(String.format("BR-%03d", nextBrNumber++));
                    newRule.setDescription(br.description());
                    if (br.branchId() != null) newRule.setReviewNote("SOURCE_BRANCH:" + decisionId(br.branchId()));
                    newRule.setSource(RuleSource.AI_GENERATED);
                    newRule.setStatus(ReviewStatus.APPROVED);
                    newRule.setIsModified(false);
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
                sourceUpdateItemRepository.save(item);
            }
        }
    }

    private void generateIncrementalTestPlans(Long projectId, Long updateId, ImpactSummaryDto impact) {
        Set<Long> removedRuleIds = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                .filter(i -> i.getTargetType() == SourceUpdateTargetType.BUSINESS_RULE && i.getAction() == SourceUpdateAction.REMOVE)
                .map(SourceUpdateItem::getTargetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> ruleIds = new HashSet<>(impact.affectedBusinessRuleIds());
        ruleIds.removeAll(removedRuleIds);

        // Gom thêm các Business Rule mới được tạo (CREATE) từ stage trước
        sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                .filter(i -> i.getTargetType() == SourceUpdateTargetType.BUSINESS_RULE && i.getAction() == SourceUpdateAction.CREATE)
                .map(SourceUpdateItem::getTargetId)
                .filter(Objects::nonNull)
                .forEach(ruleIds::add);

        if (ruleIds.isEmpty()) {
            log.info("Không có Business Rule nào cần sinh Test Plan tăng dần cho project {}", projectId);
            return;
        }

        log.info("Gọi AI Agent sinh Test Plan cho {} ruleIds: {}", ruleIds.size(), ruleIds);
        TestPlanResponseDto response = aiAgentService.generateTestPlan(projectId, ruleIds);

        if (response != null && response.plans() != null) {
            int nextTpNumber = nextTestPlanNumber(projectId);
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
                    newPlan.setPlanCode(String.format("TP-%03d", nextTpNumber++));
                    newPlan.setTitle(tp.title());
                    newPlan.setDescription(tp.description());
                    newPlan.setTestType(TestType.valueOf(tp.testType()));
                    newPlan.setStatus(ReviewStatus.APPROVED);
                    newPlan.setIsModified(false);
                    newPlan = testPlanRepository.save(newPlan);
                    item.setTargetId(newPlan.getId());
                    item.setAction(SourceUpdateAction.CREATE);
                    item.setReason("Thêm mới Test Plan cho Business Rule mới");
                }
                item.setAfterData(toJson(tp));
                item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
                sourceUpdateItemRepository.save(item);
            }
        }
    }

    private void generateIncrementalTestCases(Long projectId, Long updateId, ImpactSummaryDto impact) {
        Set<Long> removedPlanIds = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                .filter(i -> i.getTargetType() == SourceUpdateTargetType.TEST_PLAN && i.getAction() == SourceUpdateAction.REMOVE)
                .map(SourceUpdateItem::getTargetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> planIds = new HashSet<>(impact.affectedTestPlanIds());
        planIds.removeAll(removedPlanIds);

        // Gom thêm các Test Plan mới được tạo (CREATE) từ stage trước
        sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                .filter(i -> i.getTargetType() == SourceUpdateTargetType.TEST_PLAN && i.getAction() == SourceUpdateAction.CREATE)
                .map(SourceUpdateItem::getTargetId)
                .filter(Objects::nonNull)
                .forEach(planIds::add);

        if (planIds.isEmpty()) {
            log.info("Không có Test Plan nào cần sinh Test Case tăng dần cho project {}", projectId);
            return;
        }

        log.info("Gọi AI Agent sinh Test Cases cho {} planIds: {}", planIds.size(), planIds);
        TestCaseResponseDto response = aiAgentService.generateTestCases(projectId, planIds);

        if (response != null && response.cases() != null) {
            // Nhóm các test case mới theo planId
            Map<Long, List<GeneratedTestCaseDto>> newCasesByPlan = response.cases().stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.groupingBy(GeneratedTestCaseDto::planId));

            int nextTestCaseNumber = nextTestCaseNumber(projectId);
            for (Long planId : planIds) {
                List<GeneratedTestCaseDto> newCases = newCasesByPlan.getOrDefault(planId, List.of());
                List<TestCase> oldCases = testCaseRepository != null
                        ? testCaseRepository.findByTestPlanId(planId)
                        : List.of();

                int matchCount = Math.min(oldCases.size(), newCases.size());
                // Cập nhật các case tương ứng
                for (int i = 0; i < matchCount; i++) {
                    TestCase oldCase = oldCases.get(i);
                    GeneratedTestCaseDto proposed = newCases.get(i);
                    SourceUpdateItem item = existingProposal(updateId, SourceUpdateTargetType.TEST_CASE, oldCase.getId());
                    if (isUserDecision(item)) continue;
                    item.setSourceUpdateId(updateId);
                    item.setTargetType(SourceUpdateTargetType.TEST_CASE);
                    item.setTargetId(oldCases.get(i).getId());
                    item.setAction(SourceUpdateAction.UPDATE);
                    item.setReason("Cập nhật Test Case theo Test Plan bị ảnh hưởng");
                    item.setAfterData(toJson(proposed));
                    // Kỳ vọng và dữ liệu đã duyệt chỉ đổi sau khi người dùng chấp nhận đề xuất.
                    item.setReviewStatus(oldCase.getStatus() == ReviewStatus.APPROVED
                            && (!Objects.equals(oldCase.getExpectedResult(), proposed.expectedResult())
                                    || !Objects.equals(oldCase.getTestData(), proposed.testData())
                                    || !Objects.equals(oldCase.getPreconditions(), proposed.preconditions()))
                            ? SourceUpdateReviewStatus.PENDING : SourceUpdateReviewStatus.ACCEPTED);
                    sourceUpdateItemRepository.save(item);
                }

                // Nếu số case mới nhiều hơn cũ: thêm mới
                for (int i = matchCount; i < newCases.size(); i++) {
                    GeneratedTestCaseDto dto = newCases.get(i);
                    Long createdCaseId = null;
                    if (testCaseRepository != null) {
                        TestCase newTc = new TestCase();
                        newTc.setTestPlanId(planId);
                        newTc.setCaseCode(String.format("TC-%03d", nextTestCaseNumber++));
                        newTc.setTestType(TestType.valueOf(dto.testType()));
                        newTc.setDescription(dto.description());
                        newTc.setPreconditions(dto.preconditions());
                        newTc.setTestData(dto.testData());
                        newTc.setExpectedResult(dto.expectedResult());
                        newTc.setPriority(Priority.valueOf(dto.priority()));
                        newTc.setTraceSource(dto.traceSource());
                        newTc.setStatus(ReviewStatus.APPROVED);
                        newTc.setIsModified(false);
                        newTc = testCaseRepository.save(newTc);
                        createdCaseId = newTc.getId();
                    }
                    SourceUpdateItem item = new SourceUpdateItem();
                    item.setSourceUpdateId(updateId);
                    item.setTargetType(SourceUpdateTargetType.TEST_CASE);
                    item.setTargetId(createdCaseId);
                    item.setAction(SourceUpdateAction.CREATE);
                    item.setReason("Thêm mới Test Case theo Test Plan bị ảnh hưởng");
                    item.setAfterData(toJson(dto));
                    item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
                    sourceUpdateItemRepository.save(item);
                }

                // Nếu số case cũ nhiều hơn mới: đánh dấu xóa các case thừa
                for (int i = matchCount; i < oldCases.size(); i++) {
                    SourceUpdateItem item = existingProposal(updateId, SourceUpdateTargetType.TEST_CASE, oldCases.get(i).getId());
                    if (isUserDecision(item)) continue;
                    item.setSourceUpdateId(updateId);
                    item.setTargetType(SourceUpdateTargetType.TEST_CASE);
                    item.setTargetId(oldCases.get(i).getId());
                    item.setAction(SourceUpdateAction.REMOVE);
                    item.setReason("Xóa Test Case cũ không còn phù hợp");
                    item.setAfterData(null);
                    item.setReviewStatus(oldCases.get(i).getStatus() == ReviewStatus.APPROVED
                            ? SourceUpdateReviewStatus.PENDING : SourceUpdateReviewStatus.ACCEPTED);
                    sourceUpdateItemRepository.save(item);
                }
            }
        }
    }

    private void generateIncrementalUnitTests(Long projectId, SourceUpdate update, ImpactSummaryDto impact) {
        Long updateId = update.getId();
        Set<Long> removedCaseIds = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                .filter(i -> i.getTargetType() == SourceUpdateTargetType.TEST_CASE && i.getAction() == SourceUpdateAction.REMOVE)
                .map(SourceUpdateItem::getTargetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> caseIds = new HashSet<>(impact.affectedTestCaseIds());
        caseIds.removeAll(removedCaseIds);

        // Gom thêm các Test Case mới được tạo (CREATE) từ stage trước
        sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId).stream()
                .filter(i -> i.getTargetType() == SourceUpdateTargetType.TEST_CASE && i.getAction() == SourceUpdateAction.CREATE)
                .map(SourceUpdateItem::getTargetId)
                .filter(Objects::nonNull)
                .forEach(caseIds::add);

        if (caseIds.isEmpty()) {
            log.info("Không có Test Case nào cần sinh Unit Test tăng dần cho project {}", projectId);
            return;
        }

        log.info("Gọi AI Agent sinh Unit Tests cho {} caseIds: {}", caseIds.size(), caseIds);
        UnitTestResponseDto response = aiAgentService.generateSnapshotUnitTests(
                projectId, caseIds, sourceUpdateService.getCandidateSourcePath(update));

        if (response != null && response.unitTests() != null) {
            for (var ut : response.unitTests()) {
                if (ut == null || !caseIds.contains(ut.caseId())) continue;
                SourceUpdateItem item = new SourceUpdateItem();
                item.setSourceUpdateId(updateId);
                item.setTargetType(SourceUpdateTargetType.UNIT_TEST);
                UnitTest existing = unitTestRepository != null ? unitTestRepository.findByTestCaseId(ut.caseId()) : null;
                item.setTargetId(existing == null ? null : existing.getId());
                item.setAction(existing == null ? SourceUpdateAction.CREATE : SourceUpdateAction.UPDATE);
                item.setReason("Unit Test mới sinh tăng dần từ AI");
                item.setAfterData(toJson(ut));
                item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
                sourceUpdateItemRepository.save(item);
            }
        }
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
        if (reviewNote == null || !reviewNote.startsWith("SOURCE_BRANCH:")) return null;
        return decisionId(reviewNote.substring("SOURCE_BRANCH:".length()).split("\\R", 2)[0]);
    }

    private String decisionId(String branchId) {
        if (branchId == null || branchId.isBlank()) return null;
        String normalized = branchId.trim();
        int separator = normalized.indexOf("::");
        return separator < 0 ? normalized.replaceFirst("-(TRUE|FALSE)$", "") : normalized.substring(0, separator);
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

    private List<Set<Long>> resolveTargetMethodBatches(Long projectId, ImpactSummaryDto impact) {
        Map<Long, Set<Long>> methodIdsByClass = new LinkedHashMap<>();
        List<JavaClass> classes = javaClassRepository.findByProjectId(projectId);

        for (MethodDiffItem changed : impact.changedMethods()) {
            if (changed.diffType() == MethodDiffType.DELETED) {
                continue;
            }

            JavaMethod matchedMethod = null;
            JavaClass matchedClass = null;

            for (JavaClass jc : classes) {
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
                    if (matchedMethod == null && changed.diffType() != MethodDiffType.ADDED) {
                        List<JavaMethod> nameMatches = methods.stream()
                                .filter(m -> Objects.equals(m.getMethodName(), changed.methodName()))
                                .toList();
                        if (nameMatches.size() == 1) {
                            matchedMethod = nameMatches.get(0);
                        }
                    }
                    if (matchedMethod != null) break;
                }
            }

            if (matchedMethod != null) {
                // Đảm bảo method đã sửa có source code mới nhất để AI sinh BR chính xác
                if (changed.diffType() == MethodDiffType.MODIFIED && changed.afterSource() != null
                        && !changed.afterSource().equals(matchedMethod.getSourceCode())) {
                    matchedMethod.setSourceCode(changed.afterSource());
                    matchedMethod = javaMethodRepository.save(matchedMethod);
                }
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
                    matchedClass.setFilePath("");
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
            return "{}";
        }
    }
}
