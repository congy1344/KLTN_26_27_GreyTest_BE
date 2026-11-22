package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.LlmBatchExecutor;
import com.sun.net.httpserver.HttpServer;

import jakarta.validation.Validation;

/** Bộ khung ổn định của pipeline sinh: log lỗi, tách lỗi hạ tầng, giữ test đạt, chia nhỏ output bị cắt, prompt theo topic. */
class GenerationResilienceTest {
    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    // --- Mục 1: log thất bại
    @Test
    void failureLogKeepsOneJsonLinePerFailureAndCanBeDisabled(@TempDir Path dir) throws Exception {
        var service = new AiContextLogService(json, dir.toString(), false, false, true);
        service.writeFailure("unit-test", 7L, 3L, 2, LlmResponseException.provider("HTTP 503", null, 1500));
        service.writeFailure("test-case", 8L, null, 1, new LlmResponseException("bad output"));
        List<String> lines = Files.readAllLines(dir.resolve("generation-failures.jsonl"));
        assertThat(lines).hasSize(2);
        JsonNode first = json.readTree(lines.get(0));
        assertThat(first.path("projectId").asLong()).isEqualTo(7L);
        assertThat(first.path("caseId").asLong()).isEqualTo(3L);
        assertThat(first.path("classification").asText()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(first.path("retryAfterMillis").asLong()).isEqualTo(1500L);
        assertThat(json.readTree(lines.get(1)).path("message").asText()).isEqualTo("bad output");

        Path silent = dir.resolve("silent");
        new AiContextLogService(json, silent.toString(), false, false, false)
                .writeFailure("unit-test", 7L, null, 1, new LlmResponseException("ignored"));
        assertThat(silent).doesNotExist();
    }

    @Test
    void failureLogRotatesInsteadOfGrowingForever(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("generation-failures.jsonl");
        try (var big = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            big.setLength(AiContextLogService.FAILURE_LOG_MAX_BYTES);
        }
        new AiContextLogService(json, dir.toString(), false, false, true)
                .writeFailure("unit-test", 7L, null, 1, new LlmResponseException("after rotation"));
        assertThat(Files.size(dir.resolve("generation-failures.jsonl.1"))).isEqualTo(AiContextLogService.FAILURE_LOG_MAX_BYTES);
        assertThat(Files.readAllLines(file)).singleElement().asString().contains("after rotation");
    }

    // --- Mục 3: bộ đếm hạ tầng tách khỏi lượt sửa output
    private AIAgentService caseAgent(LlmClient client, GenerationResponseParser parser) {
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildTestCaseContext(1L)).thenReturn(new TestCaseContextDto(
                new ProjectContextDto(1L, "demo", "ANALYZED"), null, List.of(), List.of(), List.of(), List.of()));
        var prompts = mock(PromptManager.class);
        when(prompts.renderPrompt(anyString(), anyMap())).thenReturn(new PromptManager.RenderedPrompt("prompt", List.of()));
        return new AIAgentService(contexts, prompts, client, parser, mock(AiContextLogService.class));
    }

    @Test
    void providerFailuresDoNotConsumeOutputRepairAttempts() {
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any()))
                .thenThrow(LlmResponseException.provider("HTTP 503", null, 0))
                .thenReturn("broken", "{\"cases\":[]}");
        var parser = mock(GenerationResponseParser.class);
        var response = new TestCaseResponseDto(List.of());
        when(parser.parse(eq("broken"), eq(TestCaseResponseDto.class)))
                .thenThrow(new LlmResponseException("LLM response không đúng schema: x", true));
        when(parser.usableTestCases("broken")).thenReturn(new GenerationResponseParser.UsableTestCases(List.of(), java.util.Set.of(), null));
        when(parser.parse(eq("{\"cases\":[]}"), eq(TestCaseResponseDto.class))).thenReturn(response);
        // Output hỏng ở lượt 2 vẫn còn một lượt sửa dù lượt 1 đã lỗi hạ tầng.
        assertThat(caseAgent(client, parser).generateTestCases(1L)).isEqualTo(response);
        verify(client, times(3)).complete(anyString(), any());
    }

    @Test
    void providerRetriesAreBoundedAndNotMultipliedByBatchRetries() {
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any())).thenThrow(LlmResponseException.provider("HTTP 503", null, 0));
        var agent = caseAgent(client, mock(GenerationResponseParser.class));
        var batches = new LlmBatchExecutor(1, 2, 0);
        try {
            assertThatThrownBy(() -> batches.map(List.of(1), ignored -> agent.generateTestCases(1L)))
                    .hasRootCauseMessage("HTTP 503");
            verify(client, times(4)).complete(anyString(), any());
        } finally {
            ReflectionTestUtils.invokeMethod(batches, "shutdown");
        }
    }

    // --- Mục 4: Retry-After và finish_reason
    private <T> T withServer(int status, String header, String body, java.util.function.Function<URI, T> call) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            if (header != null) exchange.getResponseHeaders().add("Retry-After", header);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            return call.apply(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        } finally {
            server.stop(0);
        }
    }

    private OpenAiLlmClient openAi(URI endpoint) {
        return new OpenAiLlmClient(json, HttpClient.newHttpClient(), "test-key", "other-model", 0.3, 100, Duration.ofSeconds(5), endpoint);
    }

    private LlmResponseException openAiFailure(int status, String header, String body) throws Exception {
        return withServer(status, header, body, endpoint -> {
            try {
                openAi(endpoint).complete("prompt");
                throw new AssertionError("expected failure");
            } catch (LlmResponseException failure) {
                return failure;
            }
        });
    }

    @Test
    void openAiReadsRetryAfterAndClassifiesProviderFailures() throws Exception {
        var seconds = openAiFailure(503, "7", "{}");
        assertThat(seconds.isProviderFailure()).isTrue();
        assertThat(seconds.isRetryable()).isTrue();
        assertThat(seconds.getRetryAfterMillis()).isEqualTo(7_000L);

        String future = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(120)
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
        assertThat(openAiFailure(429, future, "{}").getRetryAfterMillis()).isBetween(60_000L, 121_000L);
        assertThat(openAiFailure(429, "not-a-date", "{}").getRetryAfterMillis()).isZero();

        var badRequest = openAiFailure(400, null, "{}");
        assertThat(badRequest.isProviderFailure()).isFalse();
        assertThat(badRequest.isRetryable()).isFalse();
    }

    @Test
    void truncatedOutputFromEitherProviderIsReportedInsteadOfParsed() throws Exception {
        var openAi = openAiFailure(200, null, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"cases\\\":[\"}}]}");
        assertThat(openAi).isInstanceOf(LlmResponseException.OutputTruncated.class);
        assertThat(openAi.isRetryable()).isFalse();
        String complete = withServer(200, null, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}",
                endpoint -> openAi(endpoint).complete("prompt"));
        assertThat(complete).isEqualTo("{}");

        withServer(200, null, "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"{\"}]}}]}", endpoint -> {
            var google = new GoogleLlmClient(json, HttpClient.newHttpClient(), List.of("key"), "model", 0.3, 100, Duration.ofSeconds(5), endpoint);
            assertThatThrownBy(() -> google.complete("prompt")).isInstanceOf(LlmResponseException.OutputTruncated.class);
            return null;
        });
    }

    @Test
    void truncatedCallIsNotRetriedWholeAndSplitsDownToSingleItems() {
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any())).thenThrow(new LlmResponseException.OutputTruncated("cut"));
        assertThatThrownBy(() -> caseAgent(client, mock(GenerationResponseParser.class)).generateTestCases(1L))
                .isInstanceOf(LlmResponseException.OutputTruncated.class);
        verify(client, times(1)).complete(anyString(), any());

        List<List<Integer>> calls = new ArrayList<>();
        var results = LlmBatchExecutor.splitOnTruncation(List.of(1, 2, 3), part -> {
            calls.add(part);
            if (part.size() > 1) throw new LlmResponseException.OutputTruncated("cut");
            return part.get(0) * 10;
        });
        assertThat(results).containsExactly(10, 20, 30);
        assertThat(calls).hasSize(5);
        assertThatThrownBy(() -> LlmBatchExecutor.splitOnTruncation(List.of(1), part -> {
            throw new LlmResponseException.OutputTruncated("still cut");
        })).hasMessage("still cut");
    }

    // --- Danh sách dependency trong prompt phải khớp đúng điều validator kiểm tra
    private static final String MISSING_DEPENDENCY = "require @Mock or @Spy dependencies";

    private List<String> validatorDependencyProblems(UnitTestContextDto context, ClassContextDto target, List<String> dependencies) throws Exception {
        StringBuilder source = new StringBuilder("package ").append(target.packageName()).append(";\n")
                .append("import org.junit.jupiter.api.Test; import org.junit.jupiter.api.extension.ExtendWith;\n")
                .append("import org.mockito.InjectMocks; import org.mockito.Mock; import org.mockito.junit.jupiter.MockitoExtension;\n");
        dependencies.stream().filter(name -> name.contains(".")).forEach(name -> source.append("import ").append(name).append(";\n"));
        source.append("@ExtendWith(MockitoExtension.class) class GeneratedTest {\n");
        for (int index = 0; index < dependencies.size(); index++) {
            String name = dependencies.get(index);
            source.append("@Mock ").append(name.substring(name.lastIndexOf('.') + 1)).append(" dependency").append(index).append(";\n");
        }
        source.append("@InjectMocks ").append(target.className()).append(" service;\n@Test void run() { }\n}\n");
        var unitTests = context.approvedTestCases().stream().map(item -> new GeneratedUnitTestDto(item.id(), "GeneratedTest", "run",
                target.packageName(), "NEW_TEST", source.toString())).toList();
        return GeneratedUnitTestSemanticValidator.validateByCase(context, new UnitTestResponseDto(unitTests), null, null).problems().stream()
                .filter(problem -> problem.contains(MISSING_DEPENDENCY)).toList();
    }

    @Test
    void declaringEveryListedDependencySatisfiesTheValidatorAndOmittingOneDoesNot() throws Exception {
        var context = twoCaseContext();
        var target = context.classes().stream().filter(type -> !type.methods().isEmpty()).findFirst().orElseThrow();
        String prompt = new PromptManager(json).render("unit-test", Map.of("context_json", context));
        int start = prompt.indexOf("\"requiredTestDependencies\":") + "\"requiredTestDependencies\":".length();
        List<String> listed = new ArrayList<>();
        json.readTree(prompt.substring(start, prompt.indexOf(']', start) + 1)).forEach(item -> listed.add(item.asText()));
        assertThat(listed).isNotEmpty();

        assertThat(validatorDependencyProblems(context, target, listed)).isEmpty();

        String omitted = listed.get(0);
        assertThat(validatorDependencyProblems(context, target, listed.subList(1, listed.size())))
                .isNotEmpty().allSatisfy(problem -> assertThat(problem).contains(omitted.substring(omitted.lastIndexOf('.') + 1)));
    }

    // --- Mục 2 + 7: giữ test đạt, lượt sửa chỉ gửi case lỗi
    private JsonNode doctorFixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/agent/doctor-constructor-import.json")) {
            return json.readTree(stream);
        }
    }

    /** Hai case cùng plan từ fixture thật; khôi phục validationSource như builder tạo trong runtime. */
    private UnitTestContextDto twoCaseContext() throws Exception {
        var context = json.treeToValue(doctorFixture().get("context"), UnitTestContextDto.class);
        var classes = context.classes().stream().map(type -> new ClassContextDto(type.id(), type.packageName(),
                type.className(), type.qualifiedName(), type.classType(), type.filePath(), type.sourceCode(),
                type.annotations(), type.methods())).toList();
        ObjectNode copy = json.valueToTree(context.approvedTestCases().get(0));
        copy.put("id", SECOND_CASE);
        var cases = new ArrayList<>(context.approvedTestCases());
        cases.add(json.treeToValue(copy, TestCaseContextItemDto.class));
        return new UnitTestContextDto(context.project(), context.analysis(), classes, context.approvedBusinessRules(),
                context.approvedTestPlans(), cases, context.existingApprovedTestCases(),
                context.previousGeneratedUnitTests(), context.existingTests(), context.supportingResources(), context.contextWarnings());
    }

    private static final long SECOND_CASE = 987_654L;

    private String correctedSource() throws Exception {
        try (var stream = getClass().getResourceAsStream("/agent/doctor-null-input-corrected.java.txt")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private GeneratedUnitTestDto test(long caseId, String source) throws Exception {
        var original = json.treeToValue(doctorFixture().get("response"), UnitTestResponseDto.class).unitTests().get(0);
        return new GeneratedUnitTestDto(caseId, original.testClassName(), original.testMethodName(),
                original.packageName(), original.generationType(), source);
    }

    private String body(GeneratedUnitTestDto... tests) throws Exception {
        return json.writeValueAsString(new UnitTestResponseDto(List.of(tests)));
    }

    private record PartialRun(AIAgentService agent, LlmClient client, PromptManager prompts) { }

    private PartialRun partialRun(UnitTestContextDto context, String first, String second) throws Exception {
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildUnitTestContext(1L)).thenReturn(context);
        var prompts = mock(PromptManager.class);
        when(prompts.renderPrompt(anyString(), anyMap())).thenReturn(new PromptManager.RenderedPrompt("PROMPT", List.of()));
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any())).thenReturn(first, second);
        var validators = Validation.buildDefaultValidatorFactory();
        var agent = new AIAgentService(contexts, prompts, client,
                new GenerationResponseParser(json, validators.getValidator()), mock(AiContextLogService.class));
        return new PartialRun(agent, client, prompts);
    }

    @Test
    void validatorReportsProblemsPerCaseAndInTheSameFlatMessage() throws Exception {
        var context = twoCaseContext();
        long firstCase = context.approvedTestCases().get(0).id();
        var response = new UnitTestResponseDto(List.of(test(firstCase, correctedSource()),
                test(SECOND_CASE, json.treeToValue(doctorFixture().get("response"), UnitTestResponseDto.class).unitTests().get(0).sourceCode())));
        var validation = GeneratedUnitTestSemanticValidator.validateByCase(context, response, null, null);
        assertThat(validation.byCase()).containsOnlyKeys(SECOND_CASE);
        assertThat(validation.message()).isEqualTo(GeneratedUnitTestSemanticValidator.validate(context, response, null, null));
    }

    @Test
    void repairAndBasePromptsDoNotLetSuggestedMocksKeepAnUnusedStub() {
        var prompts = new PromptManager(json);
        assertThat(prompts.load("unit-test")).contains("preconditions describe a fixture value", "saved.capture()");
        assertThat(prompts.load("unit-test-full")).contains("saved.capture()");
    }

    @Test
    void keepsPassingTestAndRepairsOnlyTheFailingCase() throws Exception {
        var context = twoCaseContext();
        long firstCase = context.approvedTestCases().get(0).id();
        String badSource = json.treeToValue(doctorFixture().get("response"), UnitTestResponseDto.class).unitTests().get(0).sourceCode();
        var good = test(firstCase, correctedSource());
        var run = partialRun(context, body(good, test(SECOND_CASE, badSource)), body(test(SECOND_CASE, correctedSource())));

        var result = run.agent().generateUnitTests(1L);

        assertThat(result.unitTests()).extracting(GeneratedUnitTestDto::caseId).containsExactlyInAnyOrder(firstCase, SECOND_CASE);
        var attempts = ArgumentCaptor.forClass(String.class);
        verify(run.client(), times(2)).complete(attempts.capture(), any());
        assertThat(attempts.getAllValues().get(1)).contains(badSource, "case_id=" + SECOND_CASE)
                .doesNotContain("case_id=" + firstCase + " source_code");
        var rendered = ArgumentCaptor.forClass(Map.class);
        verify(run.prompts(), times(2)).renderPrompt(anyString(), rendered.capture());
        var narrowed = (UnitTestContextDto) rendered.getAllValues().get(1).get("context_json");
        assertThat(narrowed.approvedTestCases()).extracting(TestCaseContextItemDto::id).containsExactly(SECOND_CASE);
    }

    @Test
    void handsTheLastErrorAndOutputToTheRecoveryCall() throws Exception {
        var context = twoCaseContext();
        long firstCase = context.approvedTestCases().get(0).id();
        String badSource = json.treeToValue(doctorFixture().get("response"), UnitTestResponseDto.class).unitTests().get(0).sourceCode();

        var partial = partialRun(context, body(test(firstCase, correctedSource()), test(SECOND_CASE, badSource)), body(test(SECOND_CASE, badSource)))
                .agent().generateUnitTests(1L);

        // Output lần cuối là JSON thô nên so theo định danh case, còn lỗi không kèm tiền tố nội bộ.
        assertThat(partial.repairNote()).contains("Previous invalid response", String.valueOf(SECOND_CASE))
                .doesNotContain("Unit Test semantic validation failed:");
        assertThat(partial.repairNote().substring(0, partial.repairNote().indexOf("# Previous invalid response"))).isNotBlank();
    }

    @Test
    void recoveryPromptCarriesThePreviousFailureAndUnitTestGuidance() throws Exception {
        var context = twoCaseContext();
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildUnitTestContext(1L, Set.of(SECOND_CASE))).thenReturn(GenerationValidationGate.narrowUnitTests(context, Set.of(SECOND_CASE)));
        var prompts = mock(PromptManager.class);
        when(prompts.renderPrompt(anyString(), anyMap())).thenReturn(new PromptManager.RenderedPrompt("PROMPT", List.of()));
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any())).thenReturn(body(test(SECOND_CASE, correctedSource())));
        var agent = new AIAgentService(contexts, prompts, client,
                new GenerationResponseParser(json, Validation.buildDefaultValidatorFactory().getValidator()), mock(AiContextLogService.class));

        agent.repairUnitTests(1L, Set.of(SECOND_CASE), "stub user.getId() is never used");

        var sent = ArgumentCaptor.forClass(String.class);
        verify(client).complete(sent.capture(), any());
        assertThat(sent.getValue()).contains("stub user.getId() is never used", "test_classes").doesNotContain("decision id");
    }

    @Test
    void returnsPassingTestsWhenRepairAttemptsRunOutButNotForSourceUpdates() throws Exception {
        var context = twoCaseContext();
        long firstCase = context.approvedTestCases().get(0).id();
        String badSource = json.treeToValue(doctorFixture().get("response"), UnitTestResponseDto.class).unitTests().get(0).sourceCode();
        String first = body(test(firstCase, correctedSource()), test(SECOND_CASE, badSource));
        String again = body(test(SECOND_CASE, badSource));

        var partial = partialRun(context, first, again).agent().generateUnitTests(1L);
        assertThat(partial.unitTests()).extracting(GeneratedUnitTestDto::caseId).containsExactly(firstCase);

        // Luồng cập nhật source cần đủ kết quả cho từng checkpoint, không được nhận phần thiếu.
        var incremental = partialRun(context, first, again).agent();
        GenerationJobContext.bind(1L, ignored -> { });
        GenerationJobContext.setSourceUpdateId(5L);
        try {
            assertThatThrownBy(() -> incremental.generateUnitTests(1L)).isInstanceOf(LlmResponseException.class);
        } finally {
            GenerationJobContext.clear();
        }
    }

    @Test
    void missingUnitTestListIsARepairableErrorNotACrash() throws Exception {
        var contexts = mock(GenerationContextBuilder.class);
        when(contexts.buildUnitTestContext(1L)).thenReturn(twoCaseContext());
        var prompts = mock(PromptManager.class);
        when(prompts.renderPrompt(anyString(), anyMap())).thenReturn(new PromptManager.RenderedPrompt("PROMPT", List.of()));
        var client = mock(LlmClient.class);
        when(client.complete(anyString(), any())).thenReturn("{}");
        var parser = mock(GenerationResponseParser.class);
        when(parser.parse(anyString(), eq(UnitTestResponseDto.class))).thenReturn(new UnitTestResponseDto(null));
        var agent = new AIAgentService(contexts, prompts, client, parser, mock(AiContextLogService.class));
        assertThatThrownBy(() -> agent.generateUnitTests(1L)).isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("unit_tests is missing");
        verify(client, times(2)).complete(anyString(), any());
    }

    @Test
    void narrowingDropsRelatedDataOfPassingCasesOnly() throws Exception {
        var context = twoCaseContext();
        var narrowed = GenerationValidationGate.narrowUnitTests(context, Set.of(SECOND_CASE));
        assertThat(narrowed.approvedTestCases()).extracting(TestCaseContextItemDto::id).containsExactly(SECOND_CASE);
        // Chỉ còn plan của case lỗi, nhưng class target và rule của nó vẫn phải đủ để sửa.
        Long planId = narrowed.approvedTestCases().get(0).testPlanId();
        assertThat(narrowed.approvedTestPlans()).extracting(TestPlanContextItemDto::id).containsExactly(planId);
        assertThat(narrowed.approvedBusinessRules()).isNotEmpty();
        assertThat(narrowed.classes()).anyMatch(type -> !type.methods().isEmpty());
        assertThat(context.approvedTestCases()).hasSize(2);
    }

    // --- Mục 5: gom đủ lỗi Test Case
    private TestCaseContextDto caseContext(String source) throws Exception {
        return json.readValue("""
                {"project":{"id":1,"projectName":"demo","status":"ANALYZED"},
                 "classes":[{"id":1,"packageName":"p","className":"Svc","qualifiedName":"p.Svc","sourceCode":%s,
                   "methods":[{"id":9,"methodName":"run","parameters":[],"classQualifiedName":"p.Svc"}]}],
                 "approvedBusinessRules":[{"id":5,"methodId":9,"description":"rule","source":"USER"}],
                 "approvedTestPlans":[{"id":1,"businessRuleId":5,"coveredRuleIds":[5]},{"id":2,"businessRuleId":5,"coveredRuleIds":[5]}],
                 "existingTests":[]}
                """.formatted(json.writeValueAsString(source)), TestCaseContextDto.class);
    }

    @Test
    void reportsEveryTestCaseProblemInOneRound() throws Exception {
        var context = caseContext("package p; class Svc { private final java.util.List<String> items = new java.util.ArrayList<>(); void run() { } }");
        var outside = new GeneratedTestCaseDto(3L, "EDGE", "d", "p", Map.of(), "r", "LOW", "t");
        assertThat(GenerationValidationGate.validateReferences(context, new TestCaseResponseDto(List.of(outside))))
                .hasValueSatisfying(message -> assertThat(message).contains("plan_id=3 is outside", "Missing test cases for approved plan IDs [1, 2]"));

        Map<String, Object> mocks = Map.of("mocks", Map.of("items", "mocked"));
        var first = new GeneratedTestCaseDto(1L, "EDGE", "d", "p", mocks, "r", "LOW", "t");
        var second = new GeneratedTestCaseDto(2L, "EDGE", "d", "p", mocks, "r", "LOW", "t");
        assertThat(GenerationValidationGate.validateTestCases(context, new TestCaseResponseDto(List.of(first, second))))
                .hasValueSatisfying(message -> assertThat(message).contains("plan_id=1", "plan_id=2"));
    }

    // --- Mục 8, 9, 10: prompt
    private final PromptManager manager = new PromptManager(json);

    private ObjectNode promptContext(String targetSource) {
        ObjectNode context = json.createObjectNode();
        var classes = context.putArray("classes");
        var target = classes.addObject().put("qualifiedName", "p.Svc").put("sourceCode", targetSource);
        target.putArray("methods").addObject().put("id", 1).put("methodName", "run");
        return context;
    }

    @Test
    void selectsUpstreamTopicsFromEvidenceAndFallsBackToFullPolicies() {
        ObjectNode context = promptContext("class Svc { void run(int x) { switch (x) { default: } } }");
        String rule = manager.render("business-rule", Map.of("context_json", context));
        assertThat(rule).contains("# Relevant policy: switch").doesNotContain("# Relevant policy: format", "# Relevant policy: cross-service");

        String testCase = manager.render("test-case", Map.of("context_json", context));
        assertThat(testCase).contains("# Relevant policy: switch").doesNotContain("Cryptographic fixture validity");

        context.withArray("dependencyCalls").addObject().put("calleeMethod", "x");
        assertThat(manager.render("business-rule", Map.of("context_json", context))).contains("# Relevant policy: cross-service");

        for (Object unknown : List.of("{}", Map.of(), Map.of("classes", List.of(Map.of("sourceCode", ""))))) {
            assertThat(manager.render("test-case", Map.of("context_json", unknown))).contains("Cryptographic fixture validity");
            assertThat(manager.render("business-rule", Map.of("context_json", unknown))).contains("For SWITCH, include every supported case mapping");
        }
    }

    @Test
    void projectDataWithDoubleBracesIsNotMistakenForATemplateVariable() {
        ObjectNode context = promptContext("class Svc { java.util.Map<String, String> m = new java.util.HashMap<>() {{ put(\"a\", \"b\"); }}; void run() { } }");
        assertThat(manager.render("business-rule", Map.of("context_json", context, "stable_guidance", "pom has {{placeholder}}")))
                .contains("{{ put", "{{placeholder}}");
        assertThatThrownBy(() -> manager.render("test-plan", Map.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("còn biến");
    }

    @Test
    void loopTopicNeedsARealLoopNotTheEnglishWordFor() {
        ObjectNode prose = promptContext("class Svc { void run() { } }");
        prose.put("note", "Rule for the service while running");
        assertThat(UpstreamPromptGuidance.selectedTopics("business-rule", prose)).doesNotContain("loop");
        assertThat(UpstreamPromptGuidance.selectedTopics("business-rule", promptContext("class Svc { void run() { for (int i = 0; i < 1; i++) { } } }"))).contains("loop");
    }

    @Test
    void generalOutcomeRuleStaysInTheCoreTestCasePrompt() {
        assertThat(manager.load("test-case")).contains("Describe observable business outcomes independently");
    }

    @Test
    void splittingPromptsLosesNoRule() {
        for (String stage : List.of("test-case", "business-rule")) {
            Set<String> split = new LinkedHashSet<>(List.of(manager.load(stage).replace("\r\n", "\n").split("\n")));
            UpstreamPromptGuidance.policyResources(stage).stream().filter(name -> !name.endsWith("-full"))
                    .forEach(name -> split.addAll(List.of(manager.load(name).replace("\r\n", "\n").split("\n"))));
            assertThat(split).as(stage).containsAll(List.of(manager.load(stage + "-full").replace("\r\n", "\n").split("\n")));
        }
    }

    @Test
    void upstreamTopicChangeInvalidatesCacheIdentity() {
        var changed = new PromptManager(json) {
            @Override public String load(String name) {
                return super.load(name) + (name.equals("test-case-crypto") ? "\nchanged" : "");
            }
        };
        assertThat(changed.promptPolicyIdentity("test-case")).isNotEqualTo(manager.promptPolicyIdentity("test-case"));
        assertThat(changed.promptPolicyIdentity("business-rule")).isEqualTo(manager.promptPolicyIdentity("business-rule"));
    }

    @Test
    void stableGuidanceSitsInsideTheCacheablePrefixBeforeBatchSources() {
        var first = manager.renderPrompt("unit-test", Map.of("context_json", unitContext("class A { }"), "stable_guidance", "# Java compatibility detected\nJava 17"));
        var second = manager.renderPrompt("unit-test", Map.of("context_json", unitContext("class B { }"), "stable_guidance", "# Java compatibility detected\nJava 17"));
        assertThat(first.cacheablePrefixes().get(0)).contains("# Java compatibility detected").isEqualTo(second.cacheablePrefixes().get(0));
        assertThat(first.text()).startsWith(first.cacheablePrefixes().get(0));
        assertThat(first.text().indexOf("# Java compatibility detected")).isLessThan(first.text().indexOf("# Shared source evidence"));
    }

    private ObjectNode unitContext(String source) {
        ObjectNode context = promptContext(source);
        context.putArray("approvedTestCases").addObject().put("id", 1);
        return context;
    }

    @Test
    void cachesStablePoliciesAheadOfPerBatchSource() {
        ObjectNode context = promptContext("class Svc { void run(int x) { switch (x) { default: } } }");
        var rendered = manager.renderPrompt("business-rule", Map.of("context_json", context));
        // Policy theo chủ đề giống nhau giữa các batch → prefix cache thứ hai; source dùng chung đã cắt theo batch thì không.
        assertThat(rendered.cacheablePrefixes()).hasSize(2);
        assertThat(rendered.cacheablePrefixes().get(1)).contains("# Relevant policy").doesNotContain("# Shared source evidence");
        assertThat(rendered.text()).startsWith(String.join("", rendered.cacheablePrefixes()));
        assertThat(rendered.text().indexOf("# Relevant policy")).isLessThan(rendered.text().indexOf("# Shared source evidence"));
    }

    @Test
    void unitTestKeepsPerBatchDependencyInventoryAfterSharedSource() {
        ObjectNode context = unitContext("package p; class Svc { private final Repo repo; Svc(Repo repo) { this.repo = repo; } void run(int x) { switch (x) { default: repo.toString(); } } }");
        context.withArray("classes").addObject().put("qualifiedName", "p.Repo").put("sourceCode", "package p; interface Repo { }").putArray("methods");
        var rendered = manager.renderPrompt("unit-test", Map.of("context_json", context));
        String prefixes = String.join("", rendered.cacheablePrefixes());
        assertThat(prefixes).doesNotContain("# Source-confirmed fixture/API inventory", "# Shared source evidence");
        assertThat(rendered.text().indexOf("# Source-confirmed fixture/API inventory")).isGreaterThan(rendered.text().indexOf("# Shared source evidence"));
    }

    @Test
    void dropsClassMetadataAlreadyCarriedBySource() {
        ObjectNode context = unitContext("package p; class Svc { Helper helper = new Helper(); void run() { } }");
        ((ObjectNode) context.at("/classes/0")).put("id", 77).put("packageName", "p").put("className", "Svc")
                .put("filePath", "src/main/java/p/Svc.java").putArray("annotations").add("Service");
        ((ObjectNode) context.at("/classes/0/methods/0")).put("classQualifiedName", "p.Svc").putArray("throwsList");
        context.withArray("classes").addObject().put("id", 78).put("qualifiedName", "p.Helper").put("className", "Helper")
                .put("packageName", "p").put("filePath", "src/main/java/p/Helper.java").put("sourceCode", "package p; class Helper { }")
                .putArray("methods");
        String prompt = manager.render("unit-test", Map.of("context_json", context));
        String json = prompt.substring(prompt.indexOf("Context:"), prompt.indexOf("\n", prompt.indexOf("Context:") + 10));
        assertThat(json).contains("\"qualifiedName\":\"p.Svc\"", "\"filePath\":\"src/main/java/p/Svc.java\"", "\"qualifiedName\":\"p.Helper\"", "\"id\":1")
                .doesNotContain("\"packageName\"", "\"id\":77", "\"id\":78", "Helper.java", "\"classQualifiedName\"", "\"throwsList\"", "\"annotations\"");
    }

    @Test
    void businessRulePromptSendsSignaturesForSupportingClassesOnly() {
        // Class đích tự tạo Helper và ném MissingException trong method đích nên cả hai thuộc phần method chạy qua.
        ObjectNode context = promptContext("class Svc { Helper helper = new Helper(); void run() { int targetBodyMarker = 1; throw new MissingException(\"x\"); } }");
        var classes = context.withArray("classes");
        classes.addObject().put("qualifiedName", "p.Helper").put("sourceCode",
                "/** comment-marker */ class Helper { private int count; Helper() { int ctorMarker = 1; } int size() { int helperBodyMarker = 2; return 0; } }")
                .putArray("methods");
        classes.addObject().put("qualifiedName", "p.MissingException").put("sourceCode",
                "class MissingException extends RuntimeException { MissingException(String id) { super(\"Missing \" + id); } }")
                .putArray("methods");
        ObjectNode before = context.deepCopy();

        String rule = manager.render("business-rule", Map.of("context_json", context));

        assertThat(rule).contains("targetBodyMarker", "private int count", "int size();", "super(\\\"Missing \\\" + id)")
                .doesNotContain("helperBodyMarker", "ctorMarker", "comment-marker");
        assertThat(context).isEqualTo(before);
        // Bước Unit Test vẫn cần full source của class phụ.
        ObjectNode unit = before.deepCopy();
        unit.putArray("approvedTestCases").addObject().put("id", 1);
        assertThat(manager.render("unit-test", Map.of("context_json", unit))).contains("helperBodyMarker");
        assertThat(PromptSourceContext.declarationsOnly("class Broken {")).isEqualTo("class Broken {");
    }

    @Test
    void businessRulePromptKeepsBodiesOfParentsAndStaticHelpersTheTargetRunsThrough() {
        ObjectNode context = promptContext("import static p.Checks.requirePositive; class Svc extends Base<String> { void run(int x) { Limits.verify(x); requirePositive(x); } }");
        var classes = context.withArray("classes");
        for (String[] item : List.of(
                new String[] {"Base", "class Base<T> extends Root { void guard() { int baseMarker = 1; } }"},
                new String[] {"Root", "class Root { void top() { int rootMarker = 1; } }"},
                new String[] {"Limits", "class Limits { static void verify(int x) { int limitsMarker = 1; } }"},
                new String[] {"Checks", "class Checks { static void requirePositive(int x) { int checksMarker = 1; } }"},
                new String[] {"Unrelated", "class Unrelated { void other() { int unrelatedMarker = 1; } }"})) {
            classes.addObject().put("className", item[0]).put("sourceCode", item[1]).putArray("methods");
        }
        String rule = manager.render("business-rule", Map.of("context_json", context));
        assertThat(rule).contains("baseMarker", "rootMarker", "limitsMarker", "checksMarker").doesNotContain("unrelatedMarker");
    }

    // --- Mục 11
    @Test
    void unitTestPromptShowsInjectMocksExample() {
        assertThat(manager.load("unit-test")).contains("Example for @InjectMocks errors");
        assertThat(manager.load("unit-test-full")).contains("Example for @InjectMocks errors");
    }

    // --- Mục 12: chặn sớm case không có đường gọi công khai
    private TestFeasibilityAnalyzer.CaseAssessment assessPrivate(String source) throws Exception {
        var method = json.readValue("{\"id\":1,\"methodName\":\"helper\",\"parameters\":[]}", MethodContextDto.class);
        var owner = new ClassContextDto(1L, "p", "Svc", "p.Svc", "SERVICE", "Svc.java", source, List.of(), List.of(method));
        return TestFeasibilityAnalyzer.analyzeScenario(List.of(owner), 1L, "");
    }

    @Test
    void blocksPrivateMethodOnlyWhenNoEntryPointCanReachIt() throws Exception {
        var dead = assessPrivate("package p; class Svc { public void run() { } private void other() { helper(); } private void helper() { } }");
        assertThat(dead.status()).isEqualTo(TestFeasibilityAnalyzer.Status.BLOCKED);
        assertThat(dead.findings()).extracting(TestFeasibilityAnalyzer.Finding::code).contains("NO_PUBLIC_ENTRY_POINT");

        for (String reachable : List.of(
                "package p; class Svc { public void run() { helper(); } private void helper() { } }",
                "package p; class Svc { public void run() { other(); } private void other() { helper(); } private void helper() { } }",
                "package p; class Svc { public Runnable run() { return this::helper; } private void helper() { } }",
                "package p; class Svc { private final Runnable task = () -> helper(); private void helper() { } }")) {
            var assessed = assessPrivate(reachable);
            assertThat(assessed.status()).as(reachable).isNotEqualTo(TestFeasibilityAnalyzer.Status.BLOCKED);
            assertThat(assessed.findings()).extracting(TestFeasibilityAnalyzer.Finding::code).contains("PRIVATE_HELPER_ENTRY_POINT");
        }
    }

    private TestCaseContextDto caseContextWithRules(String rules) throws Exception {
        String method = "void run() { throw new IllegalStateException(\"Thông điệp trong source\"); }";
        String source = "package p; class Svc { " + method + " }";
        return json.readValue("""
                {"project":{"id":1,"projectName":"demo","status":"ANALYZED"},
                 "classes":[{"id":1,"packageName":"p","className":"Svc","qualifiedName":"p.Svc","sourceCode":%s,"validationSourceCode":%s,
                   "methods":[{"id":9,"methodName":"run","returnType":"void","parameters":[],"classQualifiedName":"p.Svc","sourceCode":%s,"lineStart":1,"lineEnd":1}]}],
                 "approvedBusinessRules":[%s],
                 "approvedTestPlans":[{"id":1,"businessRuleId":5,"coveredRuleIds":[5]}],
                 "existingTests":[]}
                """.formatted(json.writeValueAsString(source), json.writeValueAsString(source), json.writeValueAsString(method), rules), TestCaseContextDto.class);
    }

    @Test void anExpectationFromAUserRuleOnTheSameMethodIsNotCheckedAgainstSource() throws Exception {
        String aiRule = "{\"id\":5,\"methodId\":9,\"description\":\"rule\",\"source\":\"AI_GENERATED\"}";
        var item = new GeneratedTestCaseDto(1L, "EDGE", "d", "p", Map.of(),
                "Ném IllegalStateException với thông điệp \"Thông điệp người dùng đặt\"", "LOW", "t");
        var response = new TestCaseResponseDto(List.of(item));

        assertThat(GenerationValidationGate.validateTestCases(caseContextWithRules(aiRule), response))
                .hasValueSatisfying(message -> assertThat(message).contains("Thông điệp người dùng đặt"));
        // Plan của BR AI nhưng cùng method với BR người dùng sửa: kỳ vọng theo người dùng vẫn phải đi qua.
        assertThat(GenerationValidationGate.validateTestCases(caseContextWithRules(aiRule
                + ",{\"id\":6,\"methodId\":9,\"description\":\"sửa\",\"source\":\"USER_MODIFIED\"}"), response).orElse(""))
                .doesNotContain("Thông điệp người dùng đặt");
    }

    @Test void userRulesOfTheSameMethodStayVisibleWhenABatchIsNarrowed() {
        var rule = (java.util.function.BiFunction<Long, String, BusinessRuleContextDto>) (id, source) ->
                new BusinessRuleContextDto(id, id == 3L ? 8L : 9L, "BR-" + id, "d", null, source, "APPROVED", false, null);
        var kept = UpstreamSourceEvidenceValidator.withUserAuthoredSiblings(List.of(
                rule.apply(1L, "AI_GENERATED"), rule.apply(2L, "AI_GENERATED"),
                rule.apply(3L, "USER_MODIFIED"), rule.apply(4L, "USER_ADDED")), java.util.Set.of(1L));
        // Rule 2 chỉ là rule AI cùng method, rule 3 là của method khác nên đều không kéo vào.
        assertThat(kept).extracting(BusinessRuleContextDto::id).containsExactly(1L, 4L);
    }
}
