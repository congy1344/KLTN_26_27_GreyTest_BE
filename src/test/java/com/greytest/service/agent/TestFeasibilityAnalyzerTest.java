package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.service.agent.TestFeasibilityAnalyzer.Status;

class TestFeasibilityAnalyzerTest {
    @Test
    void blocksOnlyExplicitRandomBranchScenarioWithSourceEvidence() {
        var classes = classes("class Service { void run() { if (Math.random() > 0.8) throw new IllegalStateException(); } }", "run", List.of());
        var result = TestFeasibilityAnalyzer.analyzeScenario(classes, 7L, "Thực thi trên nhánh Math.random() <= 0.8.");
        assertThat(result.status()).isEqualTo(Status.BLOCKED);
        assertThat(result.findings()).anySatisfy(finding -> {
            assertThat(finding.filePath()).isEqualTo("src/main/java/Service.java");
            assertThat(finding.line()).isEqualTo(1);
            assertThat(finding.code()).isEqualTo("UNCONTROLLED_RANDOM_BRANCH");
        });
        assertThat(TestFeasibilityAnalyzer.analyzeScenario(classes, 7L, "Check error handling; method uses Math.random()").status())
                .isEqualTo(Status.UNKNOWN);
        assertThat(TestFeasibilityAnalyzer.analyzeScenario(classes, 7L, "Source uses Math.random() > 0.8; verify generic error handling").status())
                .isEqualTo(Status.UNKNOWN);
    }

    @Test
    void ignoresStringsUnusedHelpersAndUnselectedOverloads() {
        String source = "class Service { void run() { String s = \"Math.random() > 0.8\"; } "
                + "void run(int n) { if (Math.random() > 0.8) throw new RuntimeException(); } "
                + "double helper() { return Math.random(); } }";
        assertThat(TestFeasibilityAnalyzer.analyzeScenario(classes(source, "run", List.of()), 7L, "Math.random() <= 0.8").status())
                .isEqualTo(Status.READY);
    }

    @Test
    void doesNotTreatCustomMathAsJdkRandom() {
        var classes = classes("class Service { static class Math { static double random() { return 0.5; } } "
                + "void run() { if (Math.random() > 0.8) throw new RuntimeException(); } }", "run", List.of());
        assertThat(TestFeasibilityAnalyzer.analyzeScenario(classes, 7L, "Math.random() <= 0.8").status())
                .isNotEqualTo(Status.BLOCKED);
    }

    @Test
    void reportsMissingValidationSourceAndUnresolvedHelperConservatively() {
        var visible = classes("class Service { void run() { if (Math.random() > .8) return; } }", "run", List.of()).get(0);
        var withoutValidation = new ClassContextDto(1L, "", "Service", "Service", "CLASS", visible.filePath(),
                visible.sourceCode(), List.of(), visible.methods(), null);
        assertThat(TestFeasibilityAnalyzer.analyzeScenario(List.of(withoutValidation), 7L, "Math.random() <= 0.8").status())
                .isEqualTo(Status.UNKNOWN);
        var helper = classes("class Service { void run() { helper(); } void helper() { if (Math.random() > .8) return; } }", "run", List.of());
        var result = TestFeasibilityAnalyzer.analyzeScenario(helper, 7L, "Math.random() <= 0.8");
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.findings()).extracting(TestFeasibilityAnalyzer.Finding::code).contains("UNRESOLVED_HELPER_PATH");
    }

    @Test
    void suggestsInjectedSourcesRealFinalFixturesAndMutationSnapshots() {
        String source = "import java.util.*; import java.time.*; import java.util.function.*; class Service { "
                + "final List<String> items = new ArrayList<>(); final DoubleSupplier random; final Clock clock; "
                + "Service(DoubleSupplier random, Clock clock) { this.random = random; this.clock = clock; } "
                + "void run(List<String> input) { if (random.getAsDouble() > .8) return; "
                + "Instant.now(clock); items.add(\"a\"); input.remove(0); } }";
        var result = TestFeasibilityAnalyzer.analyzeScenario(classes(source, "run", List.of(new MethodParamDto("input", "List<String>"))),
                7L, "random.getAsDouble() <= 0.8");
        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.findings()).extracting(TestFeasibilityAnalyzer.Finding::code)
                .contains("INJECTABLE_NONDETERMINISM", "INTERNAL_FINAL_COLLABORATOR", "COLLECTION_MUTATION");
        assertThat(result.strategies()).anyMatch(text -> text.contains("trước khi"));
    }

    @Test
    void timeRiskIsUnknownAndNotBlanketBlocked() {
        var result = TestFeasibilityAnalyzer.analyzeScenario(classes("class Service { void run() { java.time.Instant.now(); } }", "run", List.of()),
                7L, "time boundary");
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.findings()).extracting(TestFeasibilityAnalyzer.Finding::code).contains("UNCONTROLLED_TIME");
    }

    @Test
    void detectsConstructorCreatedFinalCollaboratorAndKeepsLocalRandomUnknown() {
        String source = "import java.util.Random; class Service { final Random random; Service() { this.random = new Random(); } "
                + "void run() { if (random.nextDouble() > .8) return; } }";
        var result = TestFeasibilityAnalyzer.analyzeScenario(classes(source, "run", List.of()), 7L, "branch random.nextDouble() <= .8");
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.findings()).extracting(TestFeasibilityAnalyzer.Finding::code).contains("INTERNAL_FINAL_COLLABORATOR");
    }

    @Test
    void backendNotesAndNegatedControlInstructionsCannotCreateBlockedScenarios() {
        var classes = classes("class Service { void run() { if (Math.random() > .8) return; } }", "run", List.of());
        String note = "[GreyTest testability: UNKNOWN] Math.random() > 0.8; không dùng seed để ép nhánh.";
        assertThat(TestFeasibilityAnalyzer.analyzeScenario(classes, 7L,
                "Source uses Math.random() > 0.8\n" + note).status()).isEqualTo(Status.UNKNOWN);
        assertThat(TestFeasibilityAnalyzer.analyzeScenario(classes, 7L,
                "Do not force Math.random() > 0.8; check generic error handling.").status()).isEqualTo(Status.UNKNOWN);
        assertThat(TestFeasibilityAnalyzer.analyzeScenario(classes, 7L,
                "Không ép nhánh Math.random() <= 0.8; kiểm tra lỗi chung.").status()).isEqualTo(Status.UNKNOWN);
        assertThat(TestFeasibilityAnalyzer.analyzeScenario(classes, 7L,
                note + "\nThực thi trên nhánh Math.random() <= 0.8.").status()).isEqualTo(Status.BLOCKED);
    }

    @Test
    void suggestsFixedClockForInstantNowWithInjectedClockArgument() {
        var classes = classes("import java.time.*; class Service { final Clock clock; Service(Clock clock) { this.clock = clock; } "
                + "void run() { Instant.now(clock); } }", "run", List.of());
        var result = TestFeasibilityAnalyzer.analyzeScenario(classes, 7L, "timestamp");
        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.findings()).extracting(TestFeasibilityAnalyzer.Finding::code).contains("INJECTABLE_NONDETERMINISM");
        assertThat(result.strategies()).anyMatch(strategy -> strategy.contains("Clock.fixed"));
    }

    @Test
    void mapsOnlyApprovedCasesThroughPlanAndRuleAndExcludesSupportClasses() {
        var classes = classes("class Service { void run() { if (java.lang.Math.random() > .8) return; } }", "run", List.of());
        var support = new ClassContextDto(2L, "", "Support", "Support", "CLASS", "Support.java", "class Support {}", List.of(), List.of());
        var context = new UnitTestContextDto(null, null, List.of(classes.get(0), support),
                List.of(new BusinessRuleContextDto(11L, 7L, "BR", "rule", null, null, "APPROVED", false, null)),
                List.of(new TestPlanContextItemDto(12L, 11L, List.of(11L), "TP", "title", "desc", "HAPPY", "APPROVED", false)),
                List.of(new TestCaseContextItemDto(13L, 12L, "TC", "HAPPY", "run", "branch\n[GreyTest testability: UNKNOWN] Random source cần kiểm tra.",
                        Map.of("Math.random()", "<= 0.8"), "ok", "HIGH", "TP", "APPROVED", false)),
                List.of(), List.of(), List.of());
        var report = TestFeasibilityAnalyzer.analyze(context);
        assertThat(report.methods()).extracting(TestFeasibilityAnalyzer.MethodAssessment::methodId).containsExactly(7L);
        assertThat(report.cases()).singleElement().satisfies(item -> {
            assertThat(item.caseId()).isEqualTo(13L);
            assertThat(item.methodId()).isEqualTo(7L);
            assertThat(item.status()).isEqualTo(Status.BLOCKED);
        });
    }

    @Test
    void replaysActualAllocateOrderListenerScenariosWithoutExecutingSampleCode() throws Exception {
        try (var stream = getClass().getResourceAsStream("/testability/allocate-order-random.json")) {
            var fixture = new ObjectMapper().readTree(stream);
            var method = new MethodContextDto(16449L, fixture.path("qualifiedName").asText(), "listen", "void",
                    List.of(new MethodParamDto("allocateOrderRequest", "AllocateOrderRequest")), List.of(), "public", null,
                    27, 71, List.of(), List.of(), List.of());
            var owner = new ClassContextDto(1L, "eshop.inventoryservice.rabbitmq.handler", "AllocateOrderListener",
                    fixture.path("qualifiedName").asText(), "CLASS", fixture.path("filePath").asText(),
                    fixture.path("sourceCode").asText(), List.of(), List.of(method));
            assertThat(fixture.path("cases").size()).isEqualTo(5);
            for (var testCase : fixture.path("cases")) {
                var result = TestFeasibilityAnalyzer.analyzeScenario(List.of(owner), 16449L,
                        testCase.path("description").asText() + " " + testCase.path("preconditions").asText());
                assertThat(result.status()).as("actual case %s", testCase.path("id")).isEqualTo(Status.BLOCKED);
                assertThat(result.findings()).anySatisfy(finding -> {
                    assertThat(finding.filePath()).endsWith("AllocateOrderListener.java");
                    assertThat(finding.line()).isPositive();
                });
            }
        }
    }

    private static List<ClassContextDto> classes(String source, String methodName, List<MethodParamDto> params) {
        var method = new MethodContextDto(7L, "Service", methodName, "void", params, List.of(), "public", null,
                1, 1, List.of(), List.of(), List.of());
        return List.of(new ClassContextDto(1L, "", "Service", "Service", "CLASS", "src/main/java/Service.java", source, List.of(), List.of(method)));
    }
}
