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
import com.greytest.service.agent.GenerationContextBuilder;
import com.greytest.service.agent.LlmResponseException;
import com.greytest.service.analysis.MethodBranchAnalyzer;

@Service
public class BusinessRuleService {

    // ponytail: tam luu suggestion trong review_note; tach cot rieng khi can lich su review.
    private static final String SUGGESTION_MARKER = "\nAI_SUGGESTION:";
    private static final String SOURCE_BRANCH_MARKER = "SOURCE_BRANCH:";

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
                "AI review la tuy chon de kiem tra do ro rang."));
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
                "Da sua thu cong. AI review lai la tuy chon."));
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
        // Cascade DB xóa Plan/Case/Unit Test gắn với rule này; đưa project về chờ review lại
        businessRuleRepository.delete(rule);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
    }

    @Transactional
    public List<BusinessRuleDto> generate(Long projectId) {
        Project project = ensureProjectExists(projectId);
        ensureBusinessRuleEditable(project);

        List<JavaMethod> serviceMethods = serviceMethods(projectId);
        Set<Long> validMethodIds = serviceMethods.stream()
                .map(JavaMethod::getId)
                .collect(Collectors.toSet());
        if (validMethodIds.isEmpty()) {
            throw new InvalidProjectStatusException(
                    "Project chua co service method nao de AI sinh Business Rule. Hay kiem tra ket qua Analysis.");
        }

        List<BusinessRule> existingRules = businessRuleRepository.findByProjectId(projectId);
        Set<Long> uncoveredMethodIds = serviceMethods.stream()
                .filter(method -> needsGeneration(method, existingRules))
                .map(JavaMethod::getId)
                .collect(Collectors.toCollection(HashSet::new));
        if (uncoveredMethodIds.isEmpty()) return List.of();

        List<BusinessRuleDto> created = new ArrayList<>();
        Set<String> existingRuleKeys = ruleKeys(existingRules);
        int firstRuleNumber = nextRuleNumber(existingRules);
        for (Set<Long> activeMethodIds : serviceMethodBatches(projectId, uncoveredMethodIds)) {
            BusinessRuleResponseDto response = aiAgentService.generateBusinessRules(projectId, activeMethodIds);
            if (response.rules().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(GeneratedBusinessRuleDto::methodId)
                    .anyMatch(methodId -> methodId == null || !activeMethodIds.contains(methodId))) {
                throw new LlmResponseException(
                        "AI tra ve Business Rule nam ngoai Service method dang phan tich: " + activeMethodIds + ".");
            }
            ensureBranchCoverage(activeMethodIds, response.rules());
            List<GeneratedBusinessRuleDto> orderedRules = new ArrayList<>();
            for (Long methodId : activeMethodIds) {
                response.rules().stream()
                        .filter(java.util.Objects::nonNull)
                        .filter(rule -> methodId.equals(rule.methodId()))
                        .sorted(Comparator.comparingInt(rule -> branchOrder(methodId, rule.branchId())))
                        .forEach(orderedRules::add);
            }
            List<BusinessRuleDto> batch = saveGeneratedRules(
                    projectId,
                    orderedRules,
                    RuleSource.AI_GENERATED,
                    activeMethodIds,
                    Set.of(),
                    existingRuleKeys,
                    firstRuleNumber + created.size());
            created.addAll(batch);
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
        List<BusinessRule> dirtyRules = existingRules.stream()
                .filter(rule -> Boolean.TRUE.equals(rule.getIsModified()))
                .toList();
        if (dirtyRules.isEmpty()) {
            throw new InvalidProjectStatusException(
                    "Khong co Business Rule do nguoi dung them hoac sua can AI review.");
        }

        Set<Long> pendingRuleIds = dirtyRules.stream().map(BusinessRule::getId).collect(Collectors.toSet());
        List<ReviewedBusinessRuleDto> reviewed = new ArrayList<>();
        while (!pendingRuleIds.isEmpty()) {
            List<BusinessRule> activeRules = dirtyRules.stream()
                    .filter(rule -> pendingRuleIds.contains(rule.getId()))
                    .sorted(Comparator.comparing(BusinessRule::getRuleCode, Comparator.nullsLast(String::compareTo)))
                    .limit(GenerationContextBuilder.MAX_REVIEW_RULES)
                    .toList();
            BusinessRuleReviewResponseDto response = aiAgentService.reviewBusinessRules(projectId);
            List<ReviewedBusinessRuleDto> reviewedBatch = applyReviewSuggestions(
                    response.reviewedRules(),
                    activeRules);
            if (reviewedBatch.isEmpty()) {
                throw new LlmResponseException("AI khong review Business Rule nao trong danh sach can review.");
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
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay Business Rule " + ruleId));
        Project project = ensureProjectExists(rule.getProjectId());
        ensureBusinessRuleEditable(project);
        String suggestion = suggestedDescription(rule.getReviewNote());
        if (suggestion == null) {
            throw new IllegalArgumentException("Business Rule khong co goi y AI de ap dung.");
        }
        ensureUniqueDescription(businessRuleRepository.findByProjectId(rule.getProjectId()), ruleId, suggestion);
        rule.setDescription(suggestion);
        rule.setSource(RuleSource.USER_MODIFIED);
        rule.setStatus(ReviewStatus.PENDING_REVIEW);
        rule.setIsModified(false);
        rule.setReviewNote(withSourceBranch(
                sourceBranchId(rule.getReviewNote()),
                "Da ap dung goi y AI."));
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        projectRepository.save(project);
        return toDto(businessRuleRepository.save(rule));
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
        ensureNoDuplicateDecisions(rules);
        List<String> missingBranches = serviceMethods(projectId).stream()
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
                    "Chua the approve vi con quyet dinh source chua co Business Rule: " + missingBranches);
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
            String key = generatedRuleKey(generatedRule);
            if (!existingRuleKeys.add(key)) continue;

            BusinessRule rule = new BusinessRule();
            rule.setProjectId(projectId);
            rule.setMethodId(generatedRule.methodId());
            rule.setRuleCode(nextRuleCode(ruleNumber++));
            rule.setDescription(generatedRule.description().trim());
            rule.setSource(source);
            rule.setStatus(ReviewStatus.PENDING_REVIEW);
            rule.setIsModified(false);
            rule.setReviewNote(withSourceBranch(
                    generatedRule.branchId(),
                    "AI category: " + generatedRule.category() + ". User review/chinh sua truoc khi approve."));
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
                        "AI chua bao phu du quyet dinh control-flow cua method " + methodId + ": " + missing);
            }
        }
    }

    private Map<Long, Set<String>> ensureValidBranchAssignments(
            Set<Long> activeMethodIds,
            List<GeneratedBusinessRuleDto> generatedRules) {
        if (generatedRules == null) {
            throw new LlmResponseException("AI khong tra ve danh sach Business Rule hop le.");
        }
        Map<Long, Set<String>> returnedByMethod = new java.util.HashMap<>();
        Set<String> uniqueAssignments = new HashSet<>();
        for (GeneratedBusinessRuleDto rule : generatedRules) {
            if (rule == null || rule.methodId() == null
                    || rule.description() == null || rule.description().isBlank()
                    || !activeMethodIds.contains(rule.methodId())) {
                throw new LlmResponseException(
                        "AI tra ve Business Rule nam ngoai Service method dang phan tich.");
            }
            Set<String> expected = requiredDecisionIds(rule.methodId());
            String branchId = decisionId(rule.branchId());
            if (expected.isEmpty()) {
                if (branchId != null) {
                    throw new LlmResponseException(
                            "AI gan branch_id cho method khong co quyet dinh control-flow: " + rule.methodId());
                }
            } else if (branchId == null || !expected.contains(branchId)) {
                throw new LlmResponseException(
                        "AI tra ve branch_id khong thuoc source method " + rule.methodId() + ": " + rule.branchId());
            }
            String assignment = branchId == null
                    ? rule.methodId() + "\n" + descriptionKey(rule.description())
                    : rule.methodId() + "\n" + branchId;
            if (!uniqueAssignments.add(assignment)) {
                throw new LlmResponseException(
                        "AI sinh trung Business Rule cho method " + rule.methodId()
                                + (branchId == null ? "." : ", quyet dinh " + branchId + "."));
            }
            if (branchId != null) {
                returnedByMethod.computeIfAbsent(rule.methodId(), ignored -> new LinkedHashSet<>()).add(branchId);
            }
        }
        return returnedByMethod;
    }

    private Set<String> requiredDecisionIds(Long methodId) {
        JavaMethod method = javaMethodRepository.findById(methodId)
                .orElseThrow(() -> new LlmResponseException(
                        "Khong tim thay Service method " + methodId + " de xac minh Business Rule."));
        return decisionIds(method);
    }

    private Set<String> decisionIds(JavaMethod method) {
        try {
            return MethodBranchAnalyzer.analyze(method.getSourceCode(), method.getLineStart()).stream()
                    .map(com.greytest.dto.SourceBranchDto::branchId)
                    .map(this::decisionId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IllegalStateException exception) {
            throw new InvalidProjectStatusException(
                    "Khong the xac minh control-flow cua method " + method.getMethodName()
                            + ". Hay phan tich lai project.");
        }
    }

    private int branchOrder(Long methodId, String branchId) {
        JavaMethod method = javaMethodRepository.findById(methodId).orElse(null);
        if (method == null || branchId == null) return Integer.MAX_VALUE;
        List<String> decisionIds = MethodBranchAnalyzer.analyze(method.getSourceCode(), method.getLineStart()).stream()
                .map(com.greytest.dto.SourceBranchDto::branchId)
                .map(this::decisionId)
                .distinct()
                .toList();
        int index = decisionIds.indexOf(decisionId(branchId));
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
            if (branchId != null && rule.getMethodId() != null) {
                keys.add(branchKey(rule.getMethodId(), branchId));
            } else if (rule.getDescription() != null) {
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

    private void ensureDecisionAvailable(
            List<BusinessRule> rules,
            Long currentRuleId,
            Long methodId,
            String branchId) {
        if (branchId == null) return;
        for (BusinessRule rule : rules) {
            if (currentRuleId != null && currentRuleId.equals(rule.getId())) continue;
            if (methodId.equals(rule.getMethodId())
                    && branchId.equals(sourceBranchId(rule.getReviewNote()))) {
                throw new IllegalArgumentException(
                        "Quyet dinh source da co Business Rule: " + branchId);
            }
        }
    }

    private void ensureNoDuplicateDecisions(List<BusinessRule> rules) {
        var branchesByDecision = new java.util.LinkedHashMap<String, List<String>>();
        for (BusinessRule rule : rules) {
            if (rule.getMethodId() == null) continue;
            String rawBranchId = rawSourceBranchId(rule.getReviewNote());
            String branchId = decisionId(rawBranchId);
            if (branchId != null) {
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
                    "Chua the approve vi mot quyet dinh source dang co nhieu Business Rule: " + duplicates);
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
        String branchId = decisionId(rule.branchId());
        return branchId == null
                ? descriptionKey(rule.description())
                : branchKey(rule.methodId(), branchId);
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
                throw new IllegalArgumentException("Method khong co quyet dinh source de gan Business Rule.");
            }
            return null;
        }
        if (branchId == null || !validBranchIds.contains(branchId)) {
            throw new IllegalArgumentException(
                    "Hay chon mot quyet dinh source hop le cho Business Rule: " + validBranchIds);
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

    // Cho phép quay lại sửa BR ở mọi pha sau (regenerate); dữ liệu pha sau bị ảnh hưởng
    // sẽ được DB cascade dọn và status rollback về BR_PENDING_REVIEW
    private void ensureBusinessRuleEditable(Project project) {
        if (project.getStatus() == ProjectStatus.UPLOADED || project.getStatus() == ProjectStatus.FAILED) {
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
