package com.greytest.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.BusinessRuleDto;
import com.greytest.dto.BusinessRuleReviewDto;
import com.greytest.dto.CreateBusinessRuleRequest;
import com.greytest.dto.ReviewedBusinessRuleDto;
import com.greytest.dto.UpdateBusinessRuleRequest;
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
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;

@Service
public class BusinessRuleService {

    private final BusinessRuleRepository businessRuleRepository;
    private final ProjectRepository projectRepository;
    private final JavaClassRepository javaClassRepository;
    private final JavaMethodRepository javaMethodRepository;

    public BusinessRuleService(
            BusinessRuleRepository businessRuleRepository,
            ProjectRepository projectRepository,
            JavaClassRepository javaClassRepository,
            JavaMethodRepository javaMethodRepository) {
        this.businessRuleRepository = businessRuleRepository;
        this.projectRepository = projectRepository;
        this.javaClassRepository = javaClassRepository;
        this.javaMethodRepository = javaMethodRepository;
    }

    @Transactional(readOnly = true)
    public List<BusinessRuleDto> list(Long projectId) {
        ensureProjectExists(projectId);
        return businessRuleRepository.findByProjectId(projectId).stream()
                .sorted(Comparator.comparing(BusinessRule::getRuleCode, Comparator.nullsLast(String::compareTo)))
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public BusinessRuleDto create(Long projectId, CreateBusinessRuleRequest request) {
        Project project = ensureProjectExists(projectId);
        ensureBusinessRuleEditable(project);

        BusinessRule rule = new BusinessRule();
        rule.setProjectId(projectId);
        rule.setMethodId(request.methodId());
        rule.setRuleCode(nextRuleCode(projectId));
        rule.setDescription(request.description().trim());
        rule.setSource(RuleSource.USER_ADDED);
        rule.setStatus(ReviewStatus.PENDING_REVIEW);
        rule.setIsModified(false);
        rule.setReviewNote("Cho AI review de kiem tra do ro rang va de xuat bo sung.");
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
        return toDto(businessRuleRepository.save(rule));
    }

    @Transactional
    public BusinessRuleDto update(Long ruleId, UpdateBusinessRuleRequest request) {
        BusinessRule rule = businessRuleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay business rule " + ruleId));
        Project project = ensureProjectExists(rule.getProjectId());
        ensureBusinessRuleEditable(project);

        rule.setMethodId(request.methodId());
        rule.setDescription(request.description().trim());
        rule.setSource(RuleSource.USER_MODIFIED);
        rule.setStatus(ReviewStatus.PENDING_REVIEW);
        rule.setIsModified(true);
        rule.setReviewNote("Da sua thu cong, can AI review lai truoc khi approve.");
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
        return toDto(businessRuleRepository.save(rule));
    }

    @Transactional
    public void delete(Long ruleId) {
        BusinessRule rule = businessRuleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay business rule " + ruleId));
        Project project = ensureProjectExists(rule.getProjectId());
        ensureBusinessRuleEditable(project);
        businessRuleRepository.delete(rule);
    }

    @Transactional
    public List<BusinessRuleDto> generate(Long projectId) {
        Project project = ensureProjectExists(projectId);
        ensureBusinessRuleEditable(project);

        Set<Long> coveredMethodIds = existingCoveredMethodIds(projectId);
        List<JavaMethod> serviceMethods = serviceMethods(projectId);
        List<BusinessRuleDto> created = new ArrayList<>();
        for (JavaMethod method : serviceMethods) {
            if (coveredMethodIds.contains(method.getId())) continue;
            BusinessRule rule = new BusinessRule();
            rule.setProjectId(projectId);
            rule.setMethodId(method.getId());
            rule.setRuleCode(nextRuleCode(projectId));
            rule.setDescription("Method " + method.getMethodName()
                    + " phai thuc hien dung rang buoc nghiep vu, validate input va xu ly truong hop loi lien quan.");
            rule.setSource(RuleSource.AI_GENERATED);
            rule.setStatus(ReviewStatus.PENDING_REVIEW);
            rule.setIsModified(false);
            rule.setReviewNote("AI auto sinh tu static analysis. User can review/chinh sua truoc khi approve.");
            created.add(toDto(businessRuleRepository.save(rule)));
            coveredMethodIds.add(method.getId());
        }
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
        return created;
    }

    @Transactional
    public BusinessRuleReviewDto review(Long projectId) {
        Project project = ensureProjectExists(projectId);
        ensureBusinessRuleEditable(project);

        List<ReviewedBusinessRuleDto> reviewed = new ArrayList<>();
        for (BusinessRule rule : businessRuleRepository.findByProjectId(projectId)) {
            String verdict = isWeakRule(rule) ? "NEEDS_REVISION" : "OK";
            String reason = isWeakRule(rule)
                    ? "Rule con ngan hoac chua gan method, nen bo sung dieu kien dau vao/ket qua mong doi."
                    : "Rule du ro de lam input sinh test plan.";
            rule.setReviewNote(reason);
            businessRuleRepository.save(rule);
            reviewed.add(new ReviewedBusinessRuleDto(rule.getId(), verdict, rule.getDescription(), reason));
        }

        List<BusinessRuleDto> suggested = suggestMissingRules(projectId);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
        return new BusinessRuleReviewDto(reviewed, suggested);
    }

    @Transactional
    public List<BusinessRuleDto> approve(Long projectId) {
        Project project = ensureProjectExists(projectId);
        List<BusinessRule> rules = businessRuleRepository.findByProjectId(projectId);
        if (rules.isEmpty()) {
            throw new InvalidProjectStatusException("Can co it nhat mot Business Rule truoc khi approve.");
        }
        for (BusinessRule rule : rules) {
            rule.setStatus(ReviewStatus.APPROVED);
            if (rule.getReviewNote() == null || rule.getReviewNote().isBlank()) {
                rule.setReviewNote("Approved by user.");
            }
            businessRuleRepository.save(rule);
        }
        project.setStatus(ProjectStatus.BR_APPROVED);
        projectRepository.save(project);
        return rules.stream().map(this::toDto).toList();
    }

    private List<BusinessRuleDto> suggestMissingRules(Long projectId) {
        Set<Long> coveredMethodIds = existingCoveredMethodIds(projectId);
        List<BusinessRuleDto> suggested = new ArrayList<>();
        for (JavaMethod method : serviceMethods(projectId)) {
            if (coveredMethodIds.contains(method.getId())) continue;
            BusinessRule rule = new BusinessRule();
            rule.setProjectId(projectId);
            rule.setMethodId(method.getId());
            rule.setRuleCode(nextRuleCode(projectId));
            rule.setDescription("Bo sung business rule cho method " + method.getMethodName()
                    + " de mo ta dieu kien thanh cong, bien va loi nghiep vu.");
            rule.setSource(RuleSource.AI_REVIEW_SUGGESTED);
            rule.setStatus(ReviewStatus.PENDING_REVIEW);
            rule.setIsModified(false);
            rule.setReviewNote("AI review phat hien service method chua co Business Rule bao phu.");
            suggested.add(toDto(businessRuleRepository.save(rule)));
            break;
        }
        return suggested;
    }

    private List<JavaMethod> serviceMethods(Long projectId) {
        List<Long> serviceClassIds = javaClassRepository.findByProjectIdAndClassType(projectId, ClassType.SERVICE)
                .stream()
                .map(JavaClass::getId)
                .toList();
        if (serviceClassIds.isEmpty()) return List.of();
        return javaMethodRepository.findByClassIdIn(serviceClassIds).stream()
                .sorted(Comparator.comparing(JavaMethod::getMethodName)
                        .thenComparing(JavaMethod::getId))
                .toList();
    }

    private Set<Long> existingCoveredMethodIds(Long projectId) {
        Set<Long> covered = new HashSet<>();
        for (BusinessRule rule : businessRuleRepository.findByProjectId(projectId)) {
            if (rule.getMethodId() != null) {
                covered.add(rule.getMethodId());
            }
        }
        return covered;
    }

    private boolean isWeakRule(BusinessRule rule) {
        return rule.getMethodId() == null
                || rule.getDescription() == null
                || rule.getDescription().trim().length() < 20;
    }

    private String nextRuleCode(Long projectId) {
        int next = businessRuleRepository.findByProjectId(projectId).size() + 1;
        return "BR-" + String.format("%03d", next);
    }

    private Project ensureProjectExists(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private void ensureBusinessRuleEditable(Project project) {
        if (!Set.of(ProjectStatus.ANALYZED, ProjectStatus.BR_PENDING_REVIEW, ProjectStatus.BR_APPROVED)
                .contains(project.getStatus())) {
            throw new InvalidProjectStatusException(
                    "Chi co the thao tac Business Rule sau khi project da ANALYZED.");
        }
    }

    private BusinessRuleDto toDto(BusinessRule rule) {
        return new BusinessRuleDto(
                rule.getId(),
                rule.getProjectId(),
                rule.getMethodId(),
                rule.getRuleCode(),
                rule.getDescription(),
                rule.getReviewNote(),
                rule.getSource(),
                rule.getStatus(),
                rule.getIsModified(),
                rule.getCreatedAt(),
                rule.getUpdatedAt());
    }
}
