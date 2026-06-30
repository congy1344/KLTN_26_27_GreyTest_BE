package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.greytest.dto.BusinessRuleDto;
import com.greytest.dto.BusinessRuleReviewDto;
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
        when(aiAgentService.generateBusinessRules(1L)).thenReturn(new BusinessRuleResponseDto(List.of(
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
    void reviewPersistsAiReviewNotesAndSuggestions() {
        BusinessRule existingRule = rule(7L, 11L, "Email phai hop le.");
        mockProject();
        mockProjectSave();
        mockServiceMethods(method(11L, "createUser"));
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
        assertThat(review.suggestedRules()).singleElement()
                .satisfies(rule -> {
                    assertThat(rule.source()).isEqualTo(RuleSource.AI_REVIEW_SUGGESTED);
                    assertThat(rule.description()).contains("luu user moi");
                });
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
    void reviewFailsClearlyWhenThereAreNoBusinessRules() {
        mockProject();
        when(businessRuleRepository.findByProjectId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service().review(1L))
                .isInstanceOf(InvalidProjectStatusException.class)
                .hasMessageContaining("Chua co Business Rule");
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
        JavaClass serviceClass = new JavaClass();
        serviceClass.setId(10L);
        serviceClass.setProjectId(1L);
        serviceClass.setClassType(ClassType.SERVICE);
        serviceClass.setClassName("UserService");
        when(javaClassRepository.findByProjectIdAndClassType(1L, ClassType.SERVICE))
                .thenReturn(List.of(serviceClass));
        when(javaMethodRepository.findByClassIdIn(List.of(10L))).thenReturn(List.of(methods));
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
        rule.setIsModified(false);
        return rule;
    }
}
