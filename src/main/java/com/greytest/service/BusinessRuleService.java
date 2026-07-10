package com.greytest.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.BusinessRuleDto;
import com.greytest.dto.BusinessRuleReviewDto;
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
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.agent.AIAgentService;
import com.greytest.service.agent.LlmResponseException;

@Service
public class BusinessRuleService {

    private final BusinessRuleRepository businessRuleRepository;
    private final ProjectRepository projectRepository;
    private final JavaClassRepository javaClassRepository;
    private final JavaMethodRepository javaMethodRepository;
    private final AIAgentService aiAgentService;

    public BusinessRuleService(
            BusinessRuleRepository businessRuleRepository,
            ProjectRepository projectRepository,
            JavaClassRepository javaClassRepository,
            JavaMethodRepository javaMethodRepository,
            AIAgentService aiAgentService) {
        this.businessRuleRepository = businessRuleRepository;
        this.projectRepository = projectRepository;
        this.javaClassRepository = javaClassRepository;
        this.javaMethodRepository = javaMethodRepository;
        this.aiAgentService = aiAgentService;
    }

    @Transactional(readOnly = true)
    public List<BusinessRuleDto> list(Long projectId) {
        ensureProjectExists(projectId);
        return businessRuleRepository.findByProjectId(projectId).stream()
                .sorted(Comparator.comparing(BusinessRule::getRuleCode, Comparator.nullsLast(String::compareTo)))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Long projectIdForRule(Long ruleId) {
        return businessRuleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay business rule " + ruleId))
                .getProjectId();
    }

    @Transactional
    public BusinessRuleDto create(Long projectId, CreateBusinessRuleRequest request) {
        Project project = ensureProjectExists(projectId);
        ensureBusinessRuleEditable(project);
        String description = request.description().trim();
        List<BusinessRule> existingRules = businessRuleRepository.findByProjectId(projectId);
        ensureUniqueDescription(existingRules, null, description);

        BusinessRule rule = new BusinessRule();
        rule.setProjectId(projectId);
        rule.setMethodId(requireProjectServiceMethod(projectId, request.methodId()));
        rule.setRuleCode(nextRuleCode(nextRuleNumber(existingRules)));
        rule.setDescription(description);
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
        String description = request.description().trim();
        ensureUniqueDescription(businessRuleRepository.findByProjectId(rule.getProjectId()), ruleId, description);

        rule.setMethodId(requireProjectServiceMethod(rule.getProjectId(), request.methodId()));
        rule.setDescription(description);
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

        Set<Long> validMethodIds = serviceMethodIds(projectId);
        if (validMethodIds.isEmpty()) {
            throw new InvalidProjectStatusException(
                    "Project chua co service method nao de AI sinh Business Rule. Hay kiem tra ket qua Analysis.");
        }

        List<BusinessRule> existingRules = businessRuleRepository.findByProjectId(projectId);
        Set<Long> coveredMethodIds = methodIds(existingRules);
        if (coveredMethodIds.containsAll(validMethodIds)) return List.of();

        BusinessRuleResponseDto response = aiAgentService.generateBusinessRules(projectId);
        List<BusinessRuleDto> created = saveGeneratedRules(
                projectId,
                response.rules(),
                RuleSource.AI_GENERATED,
                validMethodIds,
                coveredMethodIds,
                ruleKeys(existingRules),
                nextRuleNumber(existingRules));
        if (created.isEmpty()) {
            throw new LlmResponseException("AI tra ve " + response.rules().size()
                    + " Business Rule nhung method_id khong khop service method chua co rule. ID hop le: "
                    + validMethodIds + ".");
        }
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
        return created;
    }

    @Transactional
    public BusinessRuleReviewDto review(Long projectId) {
        Project project = ensureProjectExists(projectId);
        ensureBusinessRuleEditable(project);

        List<BusinessRule> existingRules = businessRuleRepository.findByProjectId(projectId);
        if (existingRules.isEmpty()) {
            throw new InvalidProjectStatusException(
                    "Chua co Business Rule de AI review. Hay bam AI sinh BR truoc hoac them BR thu cong.");
        }
        BusinessRuleReviewResponseDto response = aiAgentService.reviewBusinessRules(projectId);
        List<ReviewedBusinessRuleDto> reviewed = applyReviewSuggestions(response.reviewedRules(), existingRules);
        List<BusinessRuleDto> suggested = saveGeneratedRules(
                projectId,
                response.suggestedRules(),
                RuleSource.AI_REVIEW_SUGGESTED,
                serviceMethodIds(projectId),
                Set.of(),
                ruleKeys(existingRules),
                nextRuleNumber(existingRules));
        if (reviewed.isEmpty() && suggested.isEmpty()) {
            throw new LlmResponseException("AI khong tra ve review hoac goi y Business Rule hop le.");
        }
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
        return new BusinessRuleReviewDto(reviewed, suggested);
    }

    @Transactional
    public List<BusinessRuleDto> approve(Long projectId) {
        Project project = ensureProjectExists(projectId);
        if (project.getStatus() != ProjectStatus.BR_PENDING_REVIEW) {
            throw new InvalidProjectStatusException("Chi co the approve Business Rule dang cho review.");
        }
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
            rule.setReviewNote(reviewNote(suggestion));
            businessRuleRepository.save(rule);
            reviewed.add(new ReviewedBusinessRuleDto(
                    rule.getId(),
                    suggestion.verdict(),
                    suggestion.suggestedDescription(),
                    suggestion.reason()));
        }
        return reviewed;
    }

    private List<BusinessRuleDto> saveGeneratedRules(
            Long projectId,
            List<GeneratedBusinessRuleDto> generatedRules,
            RuleSource source,
            Set<Long> validMethodIds,
            Set<Long> blockedMethodIds,
            Set<String> existingRuleKeys,
            int firstRuleNumber) {
        List<BusinessRuleDto> created = new ArrayList<>();
        int ruleNumber = firstRuleNumber;
        for (GeneratedBusinessRuleDto generatedRule : generatedRules) {
            if (!isUsableGeneratedRule(generatedRule, validMethodIds, blockedMethodIds)) continue;
            String key = descriptionKey(generatedRule.description());
            if (!existingRuleKeys.add(key)) continue;

            BusinessRule rule = new BusinessRule();
            rule.setProjectId(projectId);
            rule.setMethodId(generatedRule.methodId());
            rule.setRuleCode(nextRuleCode(ruleNumber++));
            rule.setDescription(generatedRule.description().trim());
            rule.setSource(source);
            rule.setStatus(ReviewStatus.PENDING_REVIEW);
            rule.setIsModified(false);
            rule.setReviewNote("AI category: " + generatedRule.category() + ". User review/chinh sua truoc khi approve.");
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
            return note;
        }
        return note + " Goi y: " + suggestion.suggestedDescription().trim();
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
            if (rule.getDescription() != null) {
                keys.add(descriptionKey(rule.getDescription()));
            }
        }
        return keys;
    }

    private void ensureUniqueDescription(List<BusinessRule> rules, Long currentRuleId, String description) {
        String newKey = descriptionKey(description);
        for (BusinessRule rule : rules) {
            if (currentRuleId != null && currentRuleId.equals(rule.getId())) continue;
            if (rule.getDescription() != null && descriptionKey(rule.getDescription()).equals(newKey)) {
                throw new IllegalArgumentException("Business Rule da ton tai trong project: " + rule.getRuleCode());
            }
        }
    }

    private String descriptionKey(String description) {
        return description.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
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
            throw new IllegalArgumentException("Business Rule phai lien ket voi mot Service method.");
        }
        JavaMethod method = javaMethodRepository.findById(methodId)
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay Service method " + methodId));
        JavaClass javaClass = javaClassRepository.findById(method.getClassId())
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay class cua method " + methodId));
        if (!projectId.equals(javaClass.getProjectId()) || javaClass.getClassType() != ClassType.SERVICE) {
            throw new IllegalArgumentException("Method phai la Service method cua project hien tai.");
        }
        return methodId;
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
