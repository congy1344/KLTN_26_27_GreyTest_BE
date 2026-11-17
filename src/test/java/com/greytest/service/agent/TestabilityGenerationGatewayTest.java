package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;
import jakarta.validation.Validation;

class TestabilityGenerationGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String SOURCE = "package demo; class Service { void execute() { if (Math.random() > 0.8) return; } int count() { return 1; } }";

    @Test
    void blocksOnlyTheUncontrollableScenarioBeforeCallingLlm(@TempDir Path logs) {
        var context = context(true, false);
        var client = new RecordingClient("should never be called");
        var result = agent(context, client, logs).generateUnitTests(1L, Set.of(40L));

        assertThat(result.unitTests()).isEmpty();
        assertThat(result.blockedCases()).singleElement().satisfies(blocked -> {
            assertThat(blocked.caseId()).isEqualTo(40L);
            assertThat(blocked.details()).contains("Math.random", "Service.java");
        });
        assertThat(client.prompts).isEmpty();
    }

    @Test
    void generatesAnIndependentCaseAndKeepsBlockedCaseMetadata(@TempDir Path logs) throws Exception {
        var context = context(true, true);
        var client = new RecordingClient(mapper.writeValueAsString(validResponse()));
        var result = agent(context, client, logs).generateUnitTests(1L, Set.of(40L, 41L));

        assertThat(result.unitTests()).extracting(GeneratedUnitTestDto::caseId).containsExactly(41L);
        assertThat(result.blockedCases()).extracting(BlockedUnitTestDto::caseId).containsExactly(40L);
        assertThat(client.prompts).singleElement().satisfies(prompt -> assertThat(prompt)
                .contains("TC-41", "Testability assessment").doesNotContain("\"caseCode\" : \"TC-40\""));
    }

    @Test
    void suppliesAnEvidenceBasedReportToEarlyGenerationStages() {
        var unit = context(true, true);
        var plan = new TestPlanContextDto(null, null, unit.classes(), unit.approvedBusinessRules(), List.of());
        String prompt = new PromptManager(mapper).render("test-plan", Map.of("context_json", plan));

        assertThat(prompt).contains("Testability assessment", "UNCONTROLLED_RANDOM", "Service.java", "strategies");
    }

    @Test
    void unitTestPromptCompactsTestabilityWithoutDroppingFindings() throws Exception {
        String source = "package demo; class Service {\n void execute() {\n helper();\n helper();\n if (Math.random() > 0.8) return; }\n"
                + " int count() { return 1; }\n private void helper() { } }";
        var methods = List.of(method(10L, "execute", "void", "void execute() { helper(); helper(); if (Math.random() > 0.8) return; }"),
                method(11L, "count", "int", "int count() { return 1; }"));
        var classes = List.of(new ClassContextDto(1L, "demo", "Service", "demo.Service", "SERVICE",
                "src/main/java/demo/Service.java", source, List.of(), methods));
        var unit = new UnitTestContextDto(null, null, classes, List.of(rule(20L, 10L), rule(21L, 11L)), List.of(plan(30L, 20L), plan(31L, 21L)),
                List.of(new TestCaseContextItemDto(40L, 30L, "TC-40", "HAPPY_PATH", "Execute the selected branch",
                                "Force Math.random() <= 0.8 for this scenario", Map.of(), "returns", "HIGH", "BR-20 -> TP-30", "APPROVED", false),
                        new TestCaseContextItemDto(41L, 31L, "TC-41", "HAPPY_PATH", "Returns one",
                                "Construct the service", Map.of(), "returns 1", "HIGH", "BR-21 -> TP-31", "APPROVED", false)),
                List.of(), List.of(), List.of());

        String prompt = new PromptManager(mapper).render("unit-test", Map.of("context_json", unit));
        String section = prompt.substring(prompt.indexOf("# Testability assessment"));
        var report = mapper.readTree(section.substring(section.indexOf("\n{") + 1));

        var execute = report.path("methods").get(0);
        assertThat(execute.path("filePath").asText()).isEqualTo("src/main/java/demo/Service.java");
        assertThat(execute.path("methodName").asText()).isEqualTo("execute");
        // Hai lời gọi helper cùng code/detail gộp thành một finding với đủ hai dòng; vị trí đã đưa lên cấp method.
        assertThat(execute.path("findings")).hasSize(2);
        assertThat(execute.path("findings").get(0).toString()).doesNotContain("filePath", "methodName");
        assertThat(execute.path("findings").get(0).path("code").asText()).isEqualTo("UNRESOLVED_HELPER_PATH");
        assertThat(execute.path("findings").get(0).path("lines").toString()).isEqualTo("[3,4]");
        assertThat(execute.path("findings").get(1).path("code").asText()).isEqualTo("UNCONTROLLED_RANDOM_BRANCH");
        assertThat(execute.path("strategies")).hasSize(2);
        assertThat(report.path("methods").get(1).toString()).isEqualTo("{\"methodId\":11,\"status\":\"READY\"}");
        // Case 40 khác đánh giá method (BLOCKED) nên giữ đủ; case 41 trùng hoàn toàn với method nên chỉ còn tham chiếu.
        assertThat(report.path("cases").get(0).path("status").asText()).isEqualTo("BLOCKED");
        assertThat(report.path("cases").get(0).path("findings")).hasSize(2);
        assertThat(report.path("cases").get(1).toString()).isEqualTo("{\"caseId\":41,\"methodId\":11}");
        assertThat(section).contains("lines lists every line");

        var plan = new TestPlanContextDto(null, null, classes, unit.approvedBusinessRules(), List.of());
        assertThat(new PromptManager(mapper).render("test-plan", Map.of("context_json", plan)))
                .contains("\"findings\":[{\"filePath\":\"src/main/java/demo/Service.java\",\"methodName\":\"execute\",\"line\":3");
    }

    @Test
    void doesNotAcceptLlmSuppliedBlockedMetadata() throws Exception {
        var parser = new GenerationResponseParser(mapper, Validation.buildDefaultValidatorFactory().getValidator());
        String json = mapper.writeValueAsString(validResponse()).replaceFirst("\\{",
                "{\"blockedCases\":[{\"caseId\":40,\"caseCode\":\"TC-40\",\"details\":\"invented\"}],");
        var response = parser.parse(json, UnitTestResponseDto.class);

        assertThat(response.blockedCases()).isNullOrEmpty();
    }

    private AIAgentService agent(UnitTestContextDto context, RecordingClient client, Path logs) {
        var builder = mock(GenerationContextBuilder.class);
        when(builder.buildUnitTestContext(eq(1L), anySet())).thenReturn(context);
        return new AIAgentService(builder, new PromptManager(mapper), client,
                new GenerationResponseParser(mapper, Validation.buildDefaultValidatorFactory().getValidator()),
                new AiContextLogService(mapper, logs.toString()));
    }

    private UnitTestResponseDto validResponse() {
        return new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(41L, "ServiceTest", "countReturnsOne", "demo", "NEW_TEST",
                "package demo; class ServiceTest { @org.junit.jupiter.api.Test void countReturnsOne() { org.junit.jupiter.api.Assertions.assertEquals(1, new Service().count()); } }")));
    }

    private UnitTestContextDto context(boolean randomCase, boolean pureCase) {
        var methods = List.of(method(10L, "execute", "void", "void execute() { if (Math.random() > 0.8) return; }"),
                method(11L, "count", "int", "int count() { return 1; }"));
        var classes = List.of(new ClassContextDto(1L, "demo", "Service", "demo.Service", "SERVICE",
                "src/main/java/demo/Service.java", SOURCE, List.of(), methods));
        var rules = List.of(rule(20L, 10L), rule(21L, 11L));
        var plans = List.of(plan(30L, 20L), plan(31L, 21L));
        List<TestCaseContextItemDto> cases = new ArrayList<>();
        if (randomCase) cases.add(new TestCaseContextItemDto(40L, 30L, "TC-40", "HAPPY_PATH", "Execute the selected branch",
                "Force Math.random() <= 0.8 for this scenario", Map.of(), "returns", "HIGH", "BR-20 -> TP-30", "APPROVED", false));
        if (pureCase) cases.add(new TestCaseContextItemDto(41L, 31L, "TC-41", "HAPPY_PATH", "Returns one",
                "Construct the service", Map.of(), "returns 1", "HIGH", "BR-21 -> TP-31", "APPROVED", false));
        return new UnitTestContextDto(null, null, classes, rules, plans, cases, List.of(), List.of(), List.of());
    }

    private MethodContextDto method(Long id, String name, String result, String source) {
        return new MethodContextDto(id, "demo.Service", name, result, List.of(), List.of(), "public", source,
                1, 1, List.of(), List.of(), List.of());
    }

    private BusinessRuleContextDto rule(Long id, Long method) {
        return new BusinessRuleContextDto(id, method, "BR-" + id, "Source-defined rule", null, "AI", "APPROVED", false, null);
    }

    private TestPlanContextItemDto plan(Long id, Long rule) {
        return new TestPlanContextItemDto(id, rule, List.of(rule), "TP-" + id, "Scenario", "Scenario", "HAPPY_PATH", "APPROVED", false);
    }

    private static class RecordingClient implements LlmClient {
        private final String response;
        private final List<String> prompts = new ArrayList<>();
        private RecordingClient(String response) { this.response = response; }
        @Override public String complete(String prompt) { prompts.add(prompt); return response; }
    }
}
