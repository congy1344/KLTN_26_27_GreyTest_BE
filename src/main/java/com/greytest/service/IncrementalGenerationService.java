package com.greytest.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.dto.SourceUpdateDto;
import com.greytest.dto.agent.GenerationResponseDtos.BusinessRuleResponseDto;
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
     */
    @Transactional
    public SourceUpdateDto generateIncrementalStage(
            Long projectId,
            Long updateId,
            GenerationProgressStage stage,
            AuthUser user) {

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        sourceUpdateService.checkProjectAccess(project, user);

        SourceUpdate update = sourceUpdateRepository.findByIdAndProjectIdForUpdate(updateId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bản cập nhật " + updateId));

        if (update.getStatus() != SourceUpdateStatus.ANALYZED
                && update.getStatus() != SourceUpdateStatus.READY_TO_APPLY) {
            throw new IllegalArgumentException("Bản cập nhật phải ở trạng thái ANALYZED trước khi sinh AI: " + update.getStatus());
        }

        update.setStatus(SourceUpdateStatus.GENERATING);
        sourceUpdateRepository.save(update);

        ImpactSummaryDto impact = parseImpactSummary(update.getImpactSummary());

        switch (stage) {
            case BUSINESS_RULE -> generateIncrementalBusinessRules(projectId, updateId, impact);
            case TEST_PLAN -> generateIncrementalTestPlans(projectId, updateId, impact);
            case TEST_CASE -> generateIncrementalTestCases(projectId, updateId, impact);
            case UNIT_TEST -> generateIncrementalUnitTests(projectId, updateId, impact);
        }

        update.setStatus(SourceUpdateStatus.READY_TO_APPLY);
        update = sourceUpdateRepository.save(update);

        List<SourceUpdateItem> items = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
        return sourceUpdateMapper.toUpdateDto(update, items);
    }

    private void generateIncrementalBusinessRules(Long projectId, Long updateId, ImpactSummaryDto impact) {
        Set<Long> targetMethodIds = resolveTargetMethodIds(projectId, impact);

        if (targetMethodIds.isEmpty()) {
            log.info("Không có target method nào cần sinh lại BR cho project {}", projectId);
            return;
        }

        log.info("Gọi AI Agent sinh Business Rules cho {} methodIds: {}", targetMethodIds.size(), targetMethodIds);
        BusinessRuleResponseDto response = aiAgentService.generateBusinessRules(projectId, targetMethodIds);

        if (response != null && response.rules() != null) {
            for (var br : response.rules()) {
                SourceUpdateItem item = new SourceUpdateItem();
                item.setSourceUpdateId(updateId);
                item.setTargetType(SourceUpdateTargetType.BUSINESS_RULE);
                BusinessRule existing = businessRuleRepository != null
                        ? businessRuleRepository.findByProjectId(projectId).stream()
                                .filter(r -> Objects.equals(r.getMethodId(), br.methodId()))
                                .findFirst().orElse(null)
                        : null;
                item.setTargetId(existing == null ? null : existing.getId());
                item.setAction(existing == null ? SourceUpdateAction.CREATE : SourceUpdateAction.UPDATE);
                item.setReason("Business Rule mới sinh tăng dần từ AI");
                item.setAfterData(toJson(br));
                item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
                sourceUpdateItemRepository.save(item);
            }
        }
    }

    private void generateIncrementalTestPlans(Long projectId, Long updateId, ImpactSummaryDto impact) {
        Set<Long> ruleIds = new HashSet<>(impact.affectedBusinessRuleIds());

        if (ruleIds.isEmpty()) {
            log.info("Không có Business Rule nào cần sinh Test Plan tăng dần cho project {}", projectId);
            return;
        }

        log.info("Gọi AI Agent sinh Test Plan cho {} ruleIds: {}", ruleIds.size(), ruleIds);
        TestPlanResponseDto response = aiAgentService.generateTestPlan(projectId, ruleIds);

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
                item.setTargetId(existing == null ? null : existing.getId());
                item.setAction(existing == null ? SourceUpdateAction.CREATE : SourceUpdateAction.UPDATE);
                item.setReason("Test Plan mới sinh tăng dần từ AI");
                item.setAfterData(toJson(tp));
                item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
                sourceUpdateItemRepository.save(item);
            }
        }
    }

    private void generateIncrementalTestCases(Long projectId, Long updateId, ImpactSummaryDto impact) {
        Set<Long> planIds = new HashSet<>(impact.affectedTestPlanIds());

        if (planIds.isEmpty()) {
            log.info("Không có Test Plan nào cần sinh Test Case tăng dần cho project {}", projectId);
            return;
        }

        log.info("Gọi AI Agent sinh Test Cases cho {} planIds: {}", planIds.size(), planIds);
        TestCaseResponseDto response = aiAgentService.generateTestCases(projectId, planIds);

        if (response != null && response.cases() != null) {
            // Xóa các đề xuất TEST_CASE cũ của bản cập nhật này nếu đã sinh trước đó
            List<SourceUpdateItem> existingItems = sourceUpdateItemRepository.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(updateId);
            for (SourceUpdateItem item : existingItems) {
                if (item.getTargetType() == SourceUpdateTargetType.TEST_CASE) {
                    sourceUpdateItemRepository.delete(item);
                }
            }

            // Nhóm các test case mới theo planId
            Map<Long, List<GeneratedTestCaseDto>> newCasesByPlan = response.cases().stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.groupingBy(GeneratedTestCaseDto::planId));

            for (Long planId : planIds) {
                List<GeneratedTestCaseDto> newCases = newCasesByPlan.getOrDefault(planId, List.of());
                List<TestCase> oldCases = testCaseRepository != null
                        ? testCaseRepository.findByTestPlanId(planId)
                        : List.of();

                int matchCount = Math.min(oldCases.size(), newCases.size());
                // Cập nhật các case tương ứng
                for (int i = 0; i < matchCount; i++) {
                    SourceUpdateItem item = new SourceUpdateItem();
                    item.setSourceUpdateId(updateId);
                    item.setTargetType(SourceUpdateTargetType.TEST_CASE);
                    item.setTargetId(oldCases.get(i).getId());
                    item.setAction(SourceUpdateAction.UPDATE);
                    item.setReason("Cập nhật Test Case theo Test Plan bị ảnh hưởng");
                    item.setAfterData(toJson(newCases.get(i)));
                    item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
                    sourceUpdateItemRepository.save(item);
                }

                // Nếu số case mới nhiều hơn cũ: thêm mới
                for (int i = matchCount; i < newCases.size(); i++) {
                    SourceUpdateItem item = new SourceUpdateItem();
                    item.setSourceUpdateId(updateId);
                    item.setTargetType(SourceUpdateTargetType.TEST_CASE);
                    item.setTargetId(null);
                    item.setAction(SourceUpdateAction.CREATE);
                    item.setReason("Thêm mới Test Case theo Test Plan bị ảnh hưởng");
                    item.setAfterData(toJson(newCases.get(i)));
                    item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
                    sourceUpdateItemRepository.save(item);
                }

                // Nếu số case cũ nhiều hơn mới: đánh dấu xóa các case thừa
                for (int i = matchCount; i < oldCases.size(); i++) {
                    SourceUpdateItem item = new SourceUpdateItem();
                    item.setSourceUpdateId(updateId);
                    item.setTargetType(SourceUpdateTargetType.TEST_CASE);
                    item.setTargetId(oldCases.get(i).getId());
                    item.setAction(SourceUpdateAction.REMOVE);
                    item.setReason("Xóa Test Case cũ không còn phù hợp");
                    item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
                    sourceUpdateItemRepository.save(item);
                }
            }
        }
    }

    private void generateIncrementalUnitTests(Long projectId, Long updateId, ImpactSummaryDto impact) {
        Set<Long> caseIds = new HashSet<>(impact.affectedTestCaseIds());

        if (caseIds.isEmpty()) {
            log.info("Không có Test Case nào cần sinh Unit Test tăng dần cho project {}", projectId);
            return;
        }

        log.info("Gọi AI Agent sinh Unit Tests cho {} caseIds: {}", caseIds.size(), caseIds);
        UnitTestResponseDto response = aiAgentService.generateUnitTests(projectId, caseIds, impact.changedMethods());

        if (response != null && response.unitTests() != null) {
            for (var ut : response.unitTests()) {
                if (ut == null || !caseIds.contains(ut.caseId())) continue;
                SourceUpdateItem item = new SourceUpdateItem();
                item.setSourceUpdateId(updateId);
                item.setTargetType(SourceUpdateTargetType.UNIT_TEST);
                UnitTest existing = unitTestRepository.findByTestCaseId(ut.caseId());
                item.setTargetId(existing == null ? null : existing.getId());
                item.setAction(existing == null ? SourceUpdateAction.CREATE : SourceUpdateAction.UPDATE);
                item.setReason("Unit Test mới sinh tăng dần từ AI");
                item.setAfterData(toJson(ut));
                item.setReviewStatus(SourceUpdateReviewStatus.ACCEPTED);
                sourceUpdateItemRepository.save(item);
            }
        }
    }

    private Set<Long> resolveTargetMethodIds(Long projectId, ImpactSummaryDto impact) {
        Set<Long> methodIds = new HashSet<>();
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
                    if (matchedMethod == null) {
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
                methodIds.add(matchedMethod.getId());
            } else if (changed.diffType() == MethodDiffType.ADDED) {
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
                newMethod.setReturnType("void");
                newMethod.setVisibility(Visibility.PUBLIC);
                newMethod.setSourceCode(changed.afterSource() != null ? changed.afterSource() : "");
                newMethod.setLineStart(1);
                newMethod.setLineEnd(10);
                newMethod = javaMethodRepository.save(newMethod);
                methodIds.add(newMethod.getId());
            }
        }
        return methodIds;
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
