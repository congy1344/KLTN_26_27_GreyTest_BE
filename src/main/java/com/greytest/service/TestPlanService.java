package com.greytest.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.CreateTestPlanRequest;
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

    private final TestPlanRepository testPlanRepository;
    private final TestPlanCoveredRuleRepository testPlanCoveredRuleRepository;
    private final BusinessRuleRepository businessRuleRepository;
    private final ProjectRepository projectRepository;
    private final AIAgentService aiAgentService;

    public TestPlanService(
            TestPlanRepository testPlanRepository,
            TestPlanCoveredRuleRepository testPlanCoveredRuleRepository,
            BusinessRuleRepository businessRuleRepository,
            ProjectRepository projectRepository,
            AIAgentService aiAgentService) {
        this.testPlanRepository = testPlanRepository;
        this.testPlanCoveredRuleRepository = testPlanCoveredRuleRepository;
        this.businessRuleRepository = businessRuleRepository;
        this.projectRepository = projectRepository;
        this.aiAgentService = aiAgentService;
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
    public Long projectIdForPlan(Long planId) {
        return testPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay Test Plan " + planId))
                .getProjectId();
    }

    @Transactional
    public List<TestPlanDto> generate(Long projectId) {
        Project project = ensureProjectExists(projectId);
        ensureCanGenerate(project);

        List<BusinessRule> approvedRules = businessRuleRepository.findByProjectIdAndStatus(projectId, ReviewStatus.APPROVED);
        if (approvedRules.isEmpty()) {
            throw new InvalidProjectStatusException("Can co it nhat mot Business Rule APPROVED truoc khi sinh Test Plan.");
        }

        List<GeneratedTestPlanDto> generatedPlans = new ArrayList<>();
        for (List<BusinessRule> batch : methodBatches(approvedRules)) {
            Set<Long> batchRuleIds = ruleIds(batch);
            TestPlanResponseDto response = aiAgentService.generateTestPlan(projectId, batchRuleIds);
            ensureBatchMatchesMethods(response.plans(), rulesByMethod(batch));
            generatedPlans.addAll(response.plans());
        }

        List<GeneratedPlanDraft> validPlanDrafts = buildGeneratedPlanDrafts(projectId, generatedPlans, approvedRules);
        if (validPlanDrafts.isEmpty()) {
            throw new LlmResponseException("AI khong tra ve Test Plan hop le cho Business Rule da approve.");
        }

        List<TestPlan> oldPlans = testPlanRepository.findByProjectId(projectId);
        if (!oldPlans.isEmpty()) {
            testPlanRepository.deleteAll(oldPlans);
            testPlanRepository.flush();
        }
        List<TestPlan> savedPlans = testPlanRepository.saveAll(validPlanDrafts.stream()
                        .map(GeneratedPlanDraft::plan)
                        .toList());
        testPlanCoveredRuleRepository.saveAll(coveredRuleLinks(savedPlans, validPlanDrafts));
        List<TestPlanDto> created = savedPlans.stream()
                .map(this::toDto)
                .toList();
        project.setStatus(ProjectStatus.PLAN_PENDING_REVIEW);
        projectRepository.save(project);
        return created;
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
    public List<TestPlanDto> approve(Long projectId) {
        Project project = ensureProjectExists(projectId);
        if (project.getStatus() != ProjectStatus.PLAN_PENDING_REVIEW) {
            throw new InvalidProjectStatusException("Chi co the approve Test Plan dang cho review.");
        }
        List<TestPlan> plans = testPlanRepository.findByProjectId(projectId);
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
            List<BusinessRule> approvedRules) {
        Set<Long> approvedRuleIds = approvedRules.stream()
                .map(BusinessRule::getId)
                .collect(Collectors.toSet());
        int[] planNumber = {1};
        return generatedPlans.stream()
                .filter(plan -> isUsableGeneratedPlan(plan, approvedRuleIds))
                .map(plan -> new GeneratedPlanDraft(
                        generatedPlan(projectId, plan, planNumber[0]++),
                        new TreeSet<>(plan.coveredRuleIds())))
                .toList();
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
        for (GeneratedTestPlanDto plan : plans) {
            if (plan == null || plan.methodId() == null || !expectedRulesByMethod.containsKey(plan.methodId())) {
                throw new LlmResponseException("AI tra ve Test Plan nam ngoai batch method: " + expectedRulesByMethod.keySet());
            }
            Set<Long> expectedRuleIds = expectedRulesByMethod.get(plan.methodId());
            Set<Long> coveredRuleIds = plan.coveredRuleIds() == null
                    ? Set.of()
                    : new TreeSet<>(plan.coveredRuleIds());
            if (plan.coveredRuleIds() == null
                    || plan.coveredRuleIds().size() != coveredRuleIds.size()
                    || coveredRuleIds.isEmpty()
                    || !expectedRuleIds.containsAll(coveredRuleIds)) {
                throw new LlmResponseException("AI tra ve covered_rule_ids nam ngoai method "
                        + plan.methodId() + ": " + expectedRuleIds);
            }
            if (plan.ruleId() == null || !coveredRuleIds.contains(plan.ruleId())) {
                throw new LlmResponseException("AI tra ve anchor rule_id khong thuoc method: " + plan.ruleId());
            }
            returnedRulesByMethod.computeIfAbsent(plan.methodId(), ignored -> new TreeSet<>()).addAll(coveredRuleIds);
        }
        Set<Long> expectedMethodIds = new TreeSet<>(expectedRulesByMethod.keySet());
        Set<Long> returnedMethodIds = new TreeSet<>(returnedRulesByMethod.keySet());
        if (!returnedMethodIds.equals(expectedMethodIds)) {
            Set<Long> missingMethodIds = new TreeSet<>(expectedMethodIds);
            missingMethodIds.removeAll(returnedMethodIds);
            throw new LlmResponseException("AI chua sinh Test Plan cho method: " + missingMethodIds);
        }
        for (Map.Entry<Long, Set<Long>> entry : expectedRulesByMethod.entrySet()) {
            if (!entry.getValue().equals(returnedRulesByMethod.get(entry.getKey()))) {
                throw new LlmResponseException("AI chua cover dung Business Rule cho method "
                        + entry.getKey() + ": " + entry.getValue());
            }
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
        if (!projectId.equals(rule.getProjectId()) || rule.getStatus() != ReviewStatus.APPROVED) {
            throw new IllegalArgumentException("Business Rule phai thuoc project hien tai va o trang thai APPROVED.");
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
            ProjectStatus.BR_APPROVED, ProjectStatus.PLAN_PENDING_REVIEW, ProjectStatus.PLAN_APPROVED,
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
                plan.getTitle(),
                plan.getDescription(),
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
