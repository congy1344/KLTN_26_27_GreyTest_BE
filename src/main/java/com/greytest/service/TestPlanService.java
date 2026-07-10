package com.greytest.service;

import java.util.Comparator;
import java.util.List;
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
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.TestType;
import com.greytest.exception.InvalidProjectStatusException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.TestPlanRepository;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.LlmResponseException;

/**
 * Quản lý vòng đời Test Plan sau khi Business Rule đã được phê duyệt.
 */
@Service
public class TestPlanService {

    private final TestPlanRepository testPlanRepository;
    private final BusinessRuleRepository businessRuleRepository;
    private final ProjectRepository projectRepository;
    private final AIAgentService aiAgentService;

    public TestPlanService(
            TestPlanRepository testPlanRepository,
            BusinessRuleRepository businessRuleRepository,
            ProjectRepository projectRepository,
            AIAgentService aiAgentService) {
        this.testPlanRepository = testPlanRepository;
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

        TestPlanResponseDto response = aiAgentService.generateTestPlan(projectId);
        List<TestPlan> validPlans = buildGeneratedPlans(projectId, response.plans(), approvedRules);
        if (validPlans.isEmpty()) {
            throw new LlmResponseException("AI khong tra ve Test Plan hop le cho Business Rule da approve.");
        }
        Set<Long> missingRuleIds = approvedRules.stream()
                .map(BusinessRule::getId)
                .collect(Collectors.toCollection(TreeSet::new));
        validPlans.stream().map(TestPlan::getBusinessRuleId).forEach(missingRuleIds::remove);
        if (!missingRuleIds.isEmpty()) {
            throw new LlmResponseException("AI chua sinh Test Plan cho Business Rule: " + missingRuleIds);
        }

        List<TestPlan> oldPlans = testPlanRepository.findByProjectId(projectId);
        if (!oldPlans.isEmpty()) {
            testPlanRepository.deleteAll(oldPlans);
        }
        List<TestPlanDto> created = testPlanRepository.saveAll(validPlans).stream()
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
        return toDto(testPlanRepository.save(plan));
    }

    @Transactional
    public TestPlanDto update(Long planId, UpdateTestPlanRequest request) {
        TestPlan plan = testPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay Test Plan " + planId));
        Project project = ensureProjectExists(plan.getProjectId());
        ensurePlanEditable(project);
        BusinessRule rule = ensureApprovedRule(plan.getProjectId(), request.businessRuleId());

        plan.setBusinessRuleId(rule.getId());
        plan.setTitle(request.title().trim());
        plan.setDescription(request.description().trim());
        plan.setTestType(request.testType());
        plan.setStatus(ReviewStatus.PENDING_REVIEW);
        plan.setIsModified(true);
        project.setStatus(ProjectStatus.PLAN_PENDING_REVIEW);
        projectRepository.save(project);
        return toDto(testPlanRepository.save(plan));
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

    private List<TestPlan> buildGeneratedPlans(
            Long projectId,
            List<GeneratedTestPlanDto> generatedPlans,
            List<BusinessRule> approvedRules) {
        Set<Long> approvedRuleIds = approvedRules.stream()
                .map(BusinessRule::getId)
                .collect(Collectors.toSet());
        int[] planNumber = {1};
        return generatedPlans.stream()
                .filter(plan -> isUsableGeneratedPlan(plan, approvedRuleIds))
                .map(plan -> generatedPlan(projectId, plan, planNumber[0]++))
                .toList();
    }

    private boolean isUsableGeneratedPlan(GeneratedTestPlanDto plan, Set<Long> approvedRuleIds) {
        return plan != null
                && approvedRuleIds.contains(plan.ruleId())
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

    private void ensureCanGenerate(Project project) {
        if (!Set.of(ProjectStatus.BR_APPROVED, ProjectStatus.PLAN_PENDING_REVIEW).contains(project.getStatus())) {
            throw new InvalidProjectStatusException(
                    "Chi co the sinh Test Plan sau khi Business Rule da APPROVED.");
        }
    }

    private void ensurePlanEditable(Project project) {
        if (!Set.of(ProjectStatus.BR_APPROVED, ProjectStatus.PLAN_PENDING_REVIEW, ProjectStatus.PLAN_APPROVED)
                .contains(project.getStatus())) {
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
                plan.getCreatedAt());
    }
}
