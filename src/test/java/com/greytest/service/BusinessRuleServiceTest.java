package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import com.greytest.repository.BusinessRuleRepository;
import com.greytest.repository.JavaClassRepository;
import com.greytest.repository.JavaMethodRepository;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.agent.AIAgentService;

@ExtendWith(MockitoExtension.class)
class BusinessRuleServiceTest {

    @Mock private BusinessRuleRepository businessRuleRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private JavaClassRepository javaClassRepository;
    @Mock private JavaMethodRepository javaMethodRepository;
    @Mock private AIAgentService aiAgentService;

    @Test
    void generatePersistsAiGeneratedRules() {
        mockProject();
        mockProjectSave();
        mockServiceMethods(method(11L, "createUser"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(new BusinessRuleResponseDto(List.of(
                new GeneratedBusinessRuleDto(11L, "Email phai hop le truoc khi tao user.", "VALIDATION"),
                new GeneratedBusinessRuleDto(11L, "User moi khong duoc trung email da ton tai.", "BUSINESS_LOGIC"))));
        mockBusinessRuleSave();

        List<BusinessRuleDto> rules = service().generate(1L);

        assertThat(rules).hasSize(2);
        assertThat(rules).extracting(BusinessRuleDto::description)
                .containsExactly(
                        "Email phai hop le truoc khi tao user.",
                        "User moi khong duoc trung email da ton tai.");
        assertThat(rules).extracting(BusinessRuleDto::source)
                .containsOnly(RuleSource.AI_GENERATED);
    }

    @Test
    void generateHandlesEachMethodInItsOwnRequest() {
        mockProject();
        mockProjectSave();
        mockServiceMethods(method(11L, "createUser"), method(12L, "updateUser"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(11L, "User moi phai co email hop le.", "VALIDATION"))));
        when(aiAgentService.generateBusinessRules(1L, Set.of(12L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(12L, "Chi cap nhat user dang ton tai.", "BUSINESS_LOGIC"))));
        mockBusinessRuleSave();

        List<BusinessRuleDto> rules = service().generate(1L);

        assertThat(rules).extracting(BusinessRuleDto::methodId).containsExactly(11L, 12L);
        verify(aiAgentService).generateBusinessRules(1L, Set.of(11L));
        verify(aiAgentService).generateBusinessRules(1L, Set.of(12L));
    }

    @Test
    void generateRejectsMethodOutsideCurrentBatch() {
        mockProject();
        mockServiceMethods(
                method(11L, "m11"), method(12L, "m12"), method(13L, "m13"),
                method(14L, "m14"), method(15L, "m15"), method(16L, "m16"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(new BusinessRuleResponseDto(List.of(
                new GeneratedBusinessRuleDto(12L, "Rule ngoai batch hien tai.", "BUSINESS_LOGIC"))));

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(com.greytest.service.agent.LlmResponseException.class)
                .hasMessageContaining("ngoai Service method");
    }

    @Test
    void generateKeepsEachServiceSeparateAndUsesSourceOrder() {
        mockProject();
        mockProjectSave();
        JavaClass firstService = serviceClass(10L, "src/main/java/a/AccountService.java", "a.AccountService");
        JavaClass secondService = serviceClass(20L, "src/main/java/b/PaymentService.java", "b.PaymentService");
        JavaMethod laterMethod = method(12L, "closeAccount");
        laterMethod.setLineStart(40);
        JavaMethod earlierMethod = method(11L, "openAccount");
        earlierMethod.setLineStart(20);
        JavaMethod paymentMethod = method(21L, "pay");
        paymentMethod.setLineStart(15);
        when(javaClassRepository.findByProjectIdAndClassType(1L, ClassType.SERVICE))
                .thenReturn(List.of(secondService, firstService));
        when(javaMethodRepository.findByClassIdIn(List.of(10L))).thenReturn(List.of(laterMethod, earlierMethod));
        when(javaMethodRepository.findByClassIdIn(List.of(20L))).thenReturn(List.of(paymentMethod));
        when(javaMethodRepository.findById(11L)).thenReturn(Optional.of(earlierMethod));
        when(javaMethodRepository.findById(12L)).thenReturn(Optional.of(laterMethod));
        when(javaMethodRepository.findById(21L)).thenReturn(Optional.of(paymentMethod));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        List<List<Long>> requestedBatches = new ArrayList<>();
        when(aiAgentService.generateBusinessRules(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.<Set<Long>>any())).thenAnswer(invocation -> {
                    Set<Long> methodIds = invocation.getArgument(1);
                    requestedBatches.add(List.copyOf(methodIds));
                    return new BusinessRuleResponseDto(methodIds.stream()
                            .map(id -> new GeneratedBusinessRuleDto(
                                    id, "Rule for method " + id, "BUSINESS_LOGIC"))
                            .toList());
                });
        mockBusinessRuleSave();

        service().generate(1L);

        assertThat(requestedBatches).containsExactly(
                List.of(11L),
                List.of(12L),
                List.of(21L));
    }

    @Test
    void generateDoesNotRetryOrFabricateWhenSourceHasNoRule() {
        mockProject();
        mockProjectSave();
        mockServiceMethods(method(11L, "technicalHelper"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L)))
                .thenReturn(new BusinessRuleResponseDto(List.of()));

        assertThat(service().generate(1L)).isEmpty();

        verify(aiAgentService).generateBusinessRules(1L, Set.of(11L));
    }

    @Test
    void generateRejectsResponseWhenAnIfElseBranchIsMissing() {
        mockProject();
        JavaMethod branchedMethod = method(11L, "findUser");
        branchedMethod.setLineStart(20);
        branchedMethod.setSourceCode("""
                public String findUser(boolean exists) {
                    if (exists) {
                        return "found";
                    } else {
                        return "missing";
                    }
                }
                """);
        mockServiceMethods(branchedMethod);
        when(javaMethodRepository.findById(11L)).thenReturn(Optional.of(branchedMethod));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(
                                11L, "Tra ve user khi ton tai.", "BUSINESS_LOGIC", "IF-1-TRUE"))));

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(com.greytest.service.agent.LlmResponseException.class)
                .hasMessageContaining("chua bao phu du nhanh", "IF-1-FALSE");
    }

    @Test
    void generatePersistsBranchesInSourceOrderForTraceability() {
        mockProject();
        mockProjectSave();
        JavaMethod branchedMethod = method(11L, "findUser");
        branchedMethod.setLineStart(20);
        branchedMethod.setSourceCode("""
                public String findUser(boolean exists) {
                    if (exists) {
                        return "found";
                    }
                    return "missing";
                }
                """);
        mockServiceMethods(branchedMethod);
        when(javaMethodRepository.findById(11L)).thenReturn(Optional.of(branchedMethod));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());
        when(aiAgentService.generateBusinessRules(1L, Set.of(11L))).thenReturn(
                new BusinessRuleResponseDto(List.of(
                        new GeneratedBusinessRuleDto(
                                11L, "Tra ve missing khi khong ton tai.", "BUSINESS_LOGIC", "IF-1-FALSE"),
                        new GeneratedBusinessRuleDto(
                                11L, "Tra ve user khi ton tai.", "BUSINESS_LOGIC", "IF-1-TRUE"))));
        mockBusinessRuleSave();

        List<BusinessRuleDto> rules = service().generate(1L);

        assertThat(rules).extracting(BusinessRuleDto::sourceBranchId)
                .containsExactly("IF-1-TRUE", "IF-1-FALSE");
        assertThat(rules).extracting(BusinessRuleDto::reviewNote)
                .allMatch(note -> !note.contains("SOURCE_BRANCH:"));
    }

    @Test
    void reviewPersistsAiReviewOnExistingRuleWithoutCreatingAnotherRule() {
        BusinessRule existingRule = rule(7L, 11L, "Email phai hop le.");
        mockProject();
        mockProjectSave();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existingRule));
        when(aiAgentService.reviewBusinessRules(1L)).thenReturn(new BusinessRuleReviewResponseDto(
                List.of(new ReviewedBusinessRuleSuggestionDto(
                        7L,
                        "NEEDS_REVISION",
                        "Email phai dung dinh dang va chua ton tai trong he thong.",
                        "Rule thieu dieu kien email duy nhat.")),
                List.of(new GeneratedBusinessRuleDto(
                        11L,
                        "He thong phai luu user moi sau khi validate thanh cong.",
                        "SIDE_EFFECT"))));
        mockBusinessRuleSave();

        BusinessRuleReviewDto review = service().review(1L);

        assertThat(review.reviewedRules()).singleElement()
                .satisfies(item -> {
                    assertThat(item.ruleId()).isEqualTo(7L);
                    assertThat(item.verdict()).isEqualTo("NEEDS_REVISION");
                });
        assertThat(existingRule.getReviewNote()).contains("NEEDS_REVISION", "Rule thieu dieu kien email duy nhat.");
        assertThat(existingRule.getIsModified()).isFalse();
        assertThat(review.suggestedRules()).isEmpty();
        verify(businessRuleRepository, times(1)).save(any(BusinessRule.class));
    }

    @Test
    void reviewIgnoresAdditionalRulesReturnedByAi() {
        BusinessRule existingRule = rule(7L, 11L, "Tra ve user khi ton tai.");
        mockProject();
        mockProjectSave();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existingRule));
        when(aiAgentService.reviewBusinessRules(1L)).thenReturn(new BusinessRuleReviewResponseDto(
                List.of(new ReviewedBusinessRuleSuggestionDto(7L, "OK", null, "Hop le.")),
                List.of(new GeneratedBusinessRuleDto(
                        11L, "Rule khong duoc tao trong khi review.", "BUSINESS_LOGIC", "IF-99-TRUE"))));
        mockBusinessRuleSave();

        BusinessRuleReviewDto review = service().review(1L);

        assertThat(review.suggestedRules()).isEmpty();
        verify(businessRuleRepository, times(1)).save(existingRule);
    }

    @Test
    void reviewCompletesAllDirtyBatchesInOneRequest() {
        BusinessRule first = rule(7L, 11L, "Email phai hop le.");
        BusinessRule second = rule(8L, 12L, "User phai ton tai.");
        mockProject();
        mockProjectSave();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(first, second));
        when(aiAgentService.reviewBusinessRules(1L)).thenReturn(
                new BusinessRuleReviewResponseDto(List.of(
                        new ReviewedBusinessRuleSuggestionDto(7L, "OK", null, "Rule day du.")), List.of()),
                new BusinessRuleReviewResponseDto(List.of(
                        new ReviewedBusinessRuleSuggestionDto(8L, "OK", null, "Rule day du.")), List.of()));
        mockBusinessRuleSave();

        BusinessRuleReviewDto review = service().review(1L);

        assertThat(review.reviewedRules()).extracting("ruleId").containsExactly(7L, 8L);
        assertThat(first.getIsModified()).isFalse();
        assertThat(second.getIsModified()).isFalse();
        verify(aiAgentService, times(2)).reviewBusinessRules(1L);
    }

    @Test
    void reviewRejectsRuleOutsideCurrentBatch() {
        List<BusinessRule> rules = java.util.stream.LongStream.rangeClosed(1, 11)
                .mapToObj(id -> rule(id, id, "Rule " + id))
                .toList();
        mockProject();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(rules);
        when(aiAgentService.reviewBusinessRules(1L)).thenReturn(new BusinessRuleReviewResponseDto(
                List.of(new ReviewedBusinessRuleSuggestionDto(11L, "OK", null, "Rule day du.")),
                List.of()));

        assertThatThrownBy(() -> service().review(1L))
                .isInstanceOf(com.greytest.service.agent.LlmResponseException.class)
                .hasMessageContaining("khong review Business Rule nao");
    }

    @Test
    void generateFailsClearlyWhenProjectHasNoServiceMethods() {
        mockProject();
        when(javaClassRepository.findByProjectIdAndClassType(1L, ClassType.SERVICE)).thenReturn(List.of());

        assertThatThrownBy(() -> service().generate(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("chua co service method");
    }

    @Test
    void generateSkipsAiWhenEveryServiceMethodAlreadyHasBusinessRule() {
        mockProject();
        mockServiceMethods(method(11L, "createUser"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(
                rule(7L, 11L, "Email phai hop le truoc khi tao user.")));

        assertThat(service().generate(1L)).isEmpty();

        verifyNoInteractions(aiAgentService);
    }

    @Test
    void generateOnlyAcceptsRulesForMethodsWithoutBusinessRule() {
        mockProject();
        mockProjectSave();
        mockServiceMethods(method(11L, "createUser"), method(12L, "updateUser"));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(
                rule(7L, 11L, "Email phai hop le truoc khi tao user.")));
        when(aiAgentService.generateBusinessRules(1L, Set.of(12L))).thenReturn(new BusinessRuleResponseDto(List.of(
                new GeneratedBusinessRuleDto(12L, "User cap nhat phai ton tai.", "BUSINESS_LOGIC"))));
        mockBusinessRuleSave();

        List<BusinessRuleDto> rules = service().generate(1L);

        assertThat(rules).extracting(BusinessRuleDto::methodId).containsExactly(12L);
        verify(aiAgentService).generateBusinessRules(1L, Set.of(12L));
    }

    @Test
    void reviewFailsClearlyWhenThereAreNoBusinessRules() {
        mockProject();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service().review(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("Khong co Business Rule", "can AI review");
    }

    @Test
    void reviewSkipsUntouchedAiGeneratedRules() {
        BusinessRule aiRule = rule(7L, 11L, "Email phai hop le.");
        aiRule.setSource(RuleSource.AI_GENERATED);
        aiRule.setIsModified(false);
        mockProject();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(aiRule));

        assertThatThrownBy(() -> service().review(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("can AI review");

        verifyNoInteractions(aiAgentService);
    }

    @Test
    void manualRulesCanCoverBranchesAndApproveWithoutAiReview() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.ANALYZED);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        mockProjectSave();

        JavaClass serviceClass = serviceClass(
                10L, "src/main/java/UserService.java", "demo.UserService");
        JavaMethod method = method(11L, "findUser");
        method.setClassId(10L);
        method.setSourceCode("""
                public String findUser(boolean exists) {
                    if (exists) return "found";
                    return "missing";
                }
                """);
        when(javaClassRepository.findById(10L)).thenReturn(Optional.of(serviceClass));
        when(javaClassRepository.findByProjectIdAndClassType(1L, ClassType.SERVICE))
                .thenReturn(List.of(serviceClass));
        when(javaMethodRepository.findById(11L)).thenReturn(Optional.of(method));
        when(javaMethodRepository.findByClassIdIn(List.of(10L))).thenReturn(List.of(method));

        List<BusinessRule> savedRules = new ArrayList<>();
        when(businessRuleRepository.findByProjectId(1L)).thenAnswer(ignored -> new ArrayList<>(savedRules));
        AtomicLong ids = new AtomicLong(1);
        when(businessRuleRepository.save(any(BusinessRule.class))).thenAnswer(invocation -> {
            BusinessRule rule = invocation.getArgument(0);
            if (rule.getId() == null) {
                rule.setId(ids.getAndIncrement());
                savedRules.add(rule);
            }
            return rule;
        });

        service().create(1L, new CreateBusinessRuleRequest(
                11L, "Tra ve user khi ton tai.", "IF-1-TRUE"));
        service().create(1L, new CreateBusinessRuleRequest(
                11L, "Tra ve missing khi user khong ton tai.", "IF-1-FALSE"));
        service().approve(1L);

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.BR_APPROVED);
        assertThat(service().list(1L)).extracting(BusinessRuleDto::sourceBranchId)
                .containsExactly("IF-1-TRUE", "IF-1-FALSE");
    }

    @Test
    void approveAllowsDirtyRulesAsExplicitUserDecision() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        BusinessRule dirtyRule = rule(7L, 11L, "Email phai hop le.");
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(dirtyRule));
        mockProjectSave();

        service().approve(1L);

        assertThat(dirtyRule.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(dirtyRule.getIsModified()).isFalse();
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.BR_APPROVED);
    }

    @Test
    void approveRejectsRulesThatDoNotCoverEverySourceBranch() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        JavaMethod branchedMethod = method(11L, "findUser");
        branchedMethod.setSourceCode("""
                public String findUser(boolean exists) {
                    if (exists) return "found";
                    return "missing";
                }
                """);
        mockServiceMethods(branchedMethod);
        BusinessRule trueRule = rule(7L, 11L, "Tra ve user khi ton tai.");
        trueRule.setIsModified(false);
        trueRule.setReviewNote("SOURCE_BRANCH:IF-1-TRUE\nDa review.");
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(trueRule));

        assertThatThrownBy(() -> service().approve(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("IF-1-FALSE");
    }

    @Test
    void approveRejectsUnreadableMethodSourceInsteadOfAssumingNoBranches() {
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.BR_PENDING_REVIEW);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        JavaMethod unreadableMethod = method(11L, "findUser");
        unreadableMethod.setSourceCode("public String findUser() { if (");
        mockServiceMethods(unreadableMethod);
        BusinessRule rule = rule(7L, 11L, "Tra ve user.");
        rule.setIsModified(false);
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(rule));

        assertThatThrownBy(() -> service().approve(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("Khong the xac minh nhanh source", "phan tich lai project");
    }

    @Test
    void createRejectsDuplicateDescriptionInSameProject() {
        mockProject();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(rule(
                7L,
                11L,
                "Save a daily statistical data point for an account, capturing normalized incomes, expenses, and current exchange rates.")));

        assertThatThrownBy(() -> service().create(1L, new CreateBusinessRuleRequest(
                11L,
                " save a daily statistical data point for an account, capturing normalized incomes, expenses, and current exchange rates. ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Business Rule da ton tai");
    }

    @Test
    void updateAlwaysMarksRuleDirty() {
        BusinessRule existingRule = rule(7L, 11L, "Email phai hop le.");
        mockProject();
        mockProjectSave();
        mockProjectServiceMethod(11L);
        when(businessRuleRepository.findById(7L)).thenReturn(Optional.of(existingRule));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existingRule));
        when(businessRuleRepository.save(existingRule)).thenReturn(existingRule);

        BusinessRuleDto updated = service().update(7L, new UpdateBusinessRuleRequest(
                11L,
                "Email phai dung dinh dang hop le."));

        assertThat(updated.isModified()).isTrue();
        assertThat(updated.reviewNote()).contains("AI review lai la tuy chon");
    }

    @Test
    void acceptSuggestionUsesStructuredServerValueWhenReasonContainsMarkerText() {
        BusinessRule existingRule = rule(7L, 11L, "Email phai hop le.");
        mockProject();
        mockProjectSave();
        when(aiAgentService.reviewBusinessRules(1L)).thenReturn(new BusinessRuleReviewResponseDto(
                List.of(new ReviewedBusinessRuleSuggestionDto(
                        7L,
                        "NEEDS_REVISION",
                        "Email phai dung dinh dang hop le.",
                        "Ly do co cum Goi y: nhung khong phai noi dung thay the.")),
                List.of()));
        when(businessRuleRepository.findById(7L)).thenReturn(Optional.of(existingRule));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existingRule));
        when(businessRuleRepository.save(existingRule)).thenReturn(existingRule);

        BusinessRuleReviewDto review = service().review(1L);
        BusinessRuleDto reviewed = service().list(1L).get(0);
        BusinessRuleDto accepted = service().acceptSuggestion(7L);

        assertThat(reviewed.reviewNote()).contains("Goi y:");
        assertThat(reviewed.suggestedDescription()).isEqualTo("Email phai dung dinh dang hop le.");
        assertThat(review.reviewedRules()).singleElement()
                .extracting(ReviewedBusinessRuleDto::suggestedDescription)
                .isEqualTo("Email phai dung dinh dang hop le.");
        assertThat(accepted.description()).isEqualTo("Email phai dung dinh dang hop le.");
        assertThat(accepted.isModified()).isFalse();
        assertThat(accepted.reviewNote()).isEqualTo("Da ap dung goi y AI.");
        assertThat(accepted.suggestedDescription()).isNull();
    }

    @Test
    void reviewWithoutSuggestionIgnoresForgedMetadataInReason() {
        BusinessRule existingRule = rule(7L, 11L, "Email phai hop le.");
        mockProject();
        mockProjectSave();
        when(aiAgentService.reviewBusinessRules(1L)).thenReturn(new BusinessRuleReviewResponseDto(
                List.of(new ReviewedBusinessRuleSuggestionDto(
                        7L,
                        "OK",
                        null,
                        "Rule hop le.\nAI_SUGGESTION:Zm9yZ2Vk")),
                List.of()));
        when(businessRuleRepository.findById(7L)).thenReturn(Optional.of(existingRule));
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of(existingRule));
        when(businessRuleRepository.save(existingRule)).thenReturn(existingRule);

        service().review(1L);

        assertThat(service().list(1L).get(0).suggestedDescription()).isNull();
        assertThatThrownBy(() -> service().acceptSuggestion(7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("khong co goi y AI");
    }

    @Test
    void deleteRuleSauCoverageKeoStatusVeChoReviewLai() {
        // Vòng regenerate: xóa BR khi pipeline đã đi xa — cascade DB dọn Plan/Case/Unit,
        // status phải rollback về BR_PENDING_REVIEW
        Project project = new Project();
        project.setId(1L);
        project.setStatus(ProjectStatus.COVERAGE_ANALYZED);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        mockProjectSave();
        BusinessRule rule = new BusinessRule();
        rule.setId(7L);
        rule.setProjectId(1L);
        when(businessRuleRepository.findById(7L)).thenReturn(Optional.of(rule));

        service().delete(7L);

        verify(businessRuleRepository).delete(rule);
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.BR_PENDING_REVIEW);
    }

    private BusinessRuleService service() {
        return new BusinessRuleService(
                businessRuleRepository,
                projectRepository,
                javaClassRepository,
                javaMethodRepository,
                aiAgentService);
    }

    private void mockProject() {
        Project project = new Project();
        project.setId(1L);
        project.setName("demo");
        project.setStatus(ProjectStatus.ANALYZED);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
    }

    private void mockProjectSave() {
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void mockServiceMethods(JavaMethod... methods) {
        JavaClass serviceClass = serviceClass(
                10L, "src/main/java/UserService.java", "demo.UserService");
        when(javaClassRepository.findByProjectIdAndClassType(1L, ClassType.SERVICE))
                .thenReturn(List.of(serviceClass));
        when(javaMethodRepository.findByClassIdIn(List.of(10L))).thenReturn(List.of(methods));
        for (JavaMethod method : methods) {
            org.mockito.Mockito.lenient().when(javaMethodRepository.findById(method.getId()))
                    .thenReturn(Optional.of(method));
        }
    }

    private JavaClass serviceClass(Long id, String filePath, String qualifiedName) {
        JavaClass serviceClass = new JavaClass();
        serviceClass.setId(id);
        serviceClass.setProjectId(1L);
        serviceClass.setClassType(ClassType.SERVICE);
        serviceClass.setClassName(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1));
        serviceClass.setQualifiedName(qualifiedName);
        serviceClass.setFilePath(filePath);
        return serviceClass;
    }

    private void mockProjectServiceMethod(Long methodId) {
        JavaMethod method = method(methodId, "createUser");
        method.setClassId(10L);
        JavaClass serviceClass = new JavaClass();
        serviceClass.setId(10L);
        serviceClass.setProjectId(1L);
        serviceClass.setClassType(ClassType.SERVICE);
        when(javaMethodRepository.findById(methodId)).thenReturn(Optional.of(method));
        when(javaClassRepository.findById(10L)).thenReturn(Optional.of(serviceClass));
    }

    private void mockBusinessRuleSave() {
        AtomicLong ids = new AtomicLong(100);
        when(businessRuleRepository.save(any(BusinessRule.class))).thenAnswer(invocation -> {
            BusinessRule rule = invocation.getArgument(0);
            if (rule.getId() == null) {
                rule.setId(ids.getAndIncrement());
            }
            return rule;
        });
    }

    private JavaMethod method(Long id, String name) {
        JavaMethod method = new JavaMethod();
        method.setId(id);
        method.setMethodName(name);
        method.setSourceCode("public void " + name + "() {}");
        return method;
    }

    private BusinessRule rule(Long id, Long methodId, String description) {
        BusinessRule rule = new BusinessRule();
        rule.setId(id);
        rule.setProjectId(1L);
        rule.setMethodId(methodId);
        rule.setRuleCode("BR-001");
        rule.setDescription(description);
        rule.setSource(RuleSource.USER_ADDED);
        rule.setStatus(ReviewStatus.PENDING_REVIEW);
        rule.setIsModified(true);
        return rule;
    }
}
