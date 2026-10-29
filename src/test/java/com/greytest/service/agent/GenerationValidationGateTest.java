package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;

class GenerationValidationGateTest {
    private final MethodContextDto method = new MethodContextDto(10L, "demo.Service", "run", "void", List.of(), List.of(),
            "public", "void run() { if (Math.random() > .8) return; }", 1, 1, List.of(), List.of(), List.of());
    private final List<ClassContextDto> classes = List.of(new ClassContextDto(1L, "demo", "Service", "demo.Service", "SERVICE",
            "src/main/java/demo/Service.java", "package demo; class Service { void run() { if (Math.random() > .8) return; } }",
            List.of(), List.of(method)));
    private final List<BusinessRuleContextDto> rules = List.of(new BusinessRuleContextDto(20L, 10L, "BR-20", "Business rule",
            null, "AI", "APPROVED", false, "IF-1"));
    private final List<TestPlanContextItemDto> plans = List.of(new TestPlanContextItemDto(30L, 20L, List.of(20L), "TP-30",
            "Scenario", "Scenario", "HAPPY_PATH", "APPROVED", false));

    @Test
    void rejectsForeignBusinessRuleMethodReferences() {
        var context = new BusinessRuleGenerationContextDto(null, null, classes, List.of(), List.of(), List.of(), List.of());
        assertThat(GenerationValidationGate.validateReferences(context,
                new BusinessRuleResponseDto(List.of(new GeneratedBusinessRuleDto(999L, "rule", "BUSINESS_LOGIC")))))
                .hasValueSatisfying(message -> assertThat(message).contains("method_id", "999"));
        assertThat(GenerationValidationGate.validateReferences(context,
                new BusinessRuleResponseDto(List.of(new GeneratedBusinessRuleDto(10L, "rule", "BUSINESS_LOGIC"))))).isEmpty();
    }

    @Test
    void rejectsPlanRulesMappedToAnotherMethod() {
        var context = new TestPlanContextDto(null, null, classes, rules, List.of());
        assertThat(GenerationValidationGate.validateReferences(context,
                new TestPlanResponseDto(List.of(new GeneratedTestPlanDto(999L, 20L, List.of(20L), "plan", "plan", "HAPPY_PATH")))))
                .hasValueSatisfying(message -> assertThat(message).contains("method_id", "999"));
        assertThat(GenerationValidationGate.validateReferences(context,
                new TestPlanResponseDto(List.of(new GeneratedTestPlanDto(10L, 20L, List.of(20L), "plan", "plan", "HAPPY_PATH"))))).isEmpty();
    }

    @Test
    void rejectsCaseOutsideTheSelectedPlans() {
        assertThat(GenerationValidationGate.validateReferences(context(), new TestCaseResponseDto(List.of(testCase(999L)))))
                .hasValueSatisfying(message -> assertThat(message).contains("plan_id", "999"));
        assertThat(GenerationValidationGate.validateReferences(context(), new TestCaseResponseDto(List.of(testCase(30L))))).isEmpty();
    }

    @Test
    void annotatesDraftCasesWithoutChangingTheExpectedBusinessOutcome() {
        var generated = testCase(30L);
        var response = (TestCaseResponseDto) GenerationValidationGate.addFeasibilityNotes(context(), new TestCaseResponseDto(List.of(generated)));
        assertThat(response.cases()).singleElement().satisfies(item -> {
            assertThat(item.preconditions()).contains("[GreyTest testability: BLOCKED]", "Service.java", "Math.random");
            assertThat(item.expectedResult()).isEqualTo("Approved business outcome");
            assertThat(item.testData()).isEqualTo(generated.testData());
            assertThat(item.planId()).isEqualTo(generated.planId());
        });
        var rulesResponse = new BusinessRuleResponseDto(List.of(new GeneratedBusinessRuleDto(10L, "Keep actual behavior", "BUSINESS_LOGIC")));
        assertThat(GenerationValidationGate.addFeasibilityNotes(context(), rulesResponse)).isSameAs(rulesResponse);
    }

    @Test
    void feasibilityNotesDoNotChangeScenarioClassificationAcrossReviewStages() {
        for (boolean explicitControl : List.of(false, true)) {
            var input = new GeneratedTestCaseDto(30L, "HAPPY_PATH", "Run service",
                    explicitControl ? "Observe the selected branch" : "Source uses Math.random() > 0.8; verify generic error handling",
                    explicitControl ? Map.of("Math.random()", "<= 0.8") : Map.of(),
                    "Approved business outcome", "HIGH", "BR-20 -> TP-30");
            var response = (TestCaseResponseDto) GenerationValidationGate.addFeasibilityNotes(context(), new TestCaseResponseDto(List.of(input)));
            var item = response.cases().get(0);
            var approved = new TestCaseContextItemDto(40L, item.planId(), "TC-40", item.testType(), item.description(),
                    item.preconditions(), item.testData(), item.expectedResult(), item.priority(), item.traceSource(), "APPROVED", false);
            var unitContext = new UnitTestContextDto(null, null, classes, rules, plans, List.of(approved), List.of(), List.of(), List.of());

            assertThat(TestFeasibilityAnalyzer.analyze(unitContext).cases()).singleElement().satisfies(assessment ->
                    assertThat(assessment.status()).isEqualTo(explicitControl
                            ? TestFeasibilityAnalyzer.Status.BLOCKED : TestFeasibilityAnalyzer.Status.UNKNOWN));
        }
    }

    private TestCaseContextDto context() {
        return new TestCaseContextDto(null, null, classes, rules, plans, List.of());
    }

    private GeneratedTestCaseDto testCase(Long planId) {
        return new GeneratedTestCaseDto(planId, "HAPPY_PATH", "Select branch", "Force Math.random() <= 0.8",
                Map.of("input", "value"), "Approved business outcome", "HIGH", "BR-20 -> TP-30");
    }
}
