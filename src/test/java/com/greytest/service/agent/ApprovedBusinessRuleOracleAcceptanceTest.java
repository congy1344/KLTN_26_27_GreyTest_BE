package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.net.URLClassLoader;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.MethodParamDto;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;

import jakarta.validation.Validation;

/** Kiểm chứng oracle độc lập bằng cùng test trên hai implementation có cùng API. */
class ApprovedBusinessRuleOracleAcceptanceTest {
    private static final String RULE = "For non-negative balance and positive amount, transfer succeeds if balance >= amount, "
            + "returning balance - amount; otherwise throw IllegalArgumentException. No fee or minimum remaining balance.";
    private static final String SOURCE = """
            package fixture;
            public class TransferService {
                public long transfer(long balance, long amount) {
                    if (balance > amount) return balance - amount;
                    throw new IllegalArgumentException("Insufficient balance");
                }
            }
            """;
    private static final Path OUTPUT = Path.of(System.getProperty("greytest.oracle.output", "target/approved-br-oracle"));
    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptManager prompts = new PromptManager(mapper);

    @Test
    void failedCallRecordsTimingWithoutProviderErrorContent(@TempDir Path directory) throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var parser = new GenerationResponseParser(mapper, factory.getValidator());
            LlmClient failed = prompt -> { throw new IllegalStateException("private-provider-content"); };
            assertThatThrownBy(() -> generate(directory, "test-plan", Map.of(), TestPlanResponseDto.class, failed, parser))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("Provider call failed");
            String evidence = Files.readString(directory.resolve("attempts.jsonl"));
            var attempt = mapper.readTree(evidence);
            assertThat(attempt.path("elapsedMillis").asDouble()).isGreaterThanOrEqualTo(0);
            assertThat(attempt.path("status").asText()).isEqualTo("PROVIDER_ERROR");
            assertThat(attempt.path("retryCount").asInt()).isZero();
            assertThat(evidence).doesNotContain("private-provider-content");
        }
    }

    @Test
    void sameApprovedEqualityAssertionPassesCorrectCodeAndDetectsFaultyCode() throws Exception {
        String test = """
                package fixture;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.DisplayName;
                import static org.junit.jupiter.api.Assertions.*;
                public class TransferServiceTest {
                    private TransferService service;
                    @BeforeEach void setUp() { service = new TransferService(); }
                    @Test @DisplayName("Balance equals amount succeeds")
                    void equalitySucceeds() { assertEquals(0L, service.transfer(100L, 100L)); }
                    @Test void aboveSucceeds() { assertEquals(1L, service.transfer(101L, 100L)); }
                    @Test void belowRejected() {
                        assertThrows(IllegalArgumentException.class, () -> service.transfer(99L, 100L));
                    }
                }
                """;
        pairedRun(OUTPUT.resolve("offline"), "TransferServiceTest", "equalitySucceeds", test);
    }

    @Test
    void fixtureSetupFailureDoesNotCountAsDetectingBusinessDefect() {
        String test = """
                package fixture;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.BeforeEach;
                import static org.junit.jupiter.api.Assertions.*;
                public class SetupFailureTest {
                    @BeforeEach void setUp() { new TransferService().transfer(100L, 100L); }
                    @Test void equalitySucceeds() { assertTrue(true); }
                }
                """;
        assertThatThrownBy(() -> pairedRun(OUTPUT.resolve("setup-negative"), "SetupFailureTest", "equalitySucceeds", test))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @EnabledIfSystemProperty(named = "greytest.oracle.replay", matches = "true")
    void replayFrozenProviderTestsWithoutNetwork() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var parser = new GenerationResponseParser(mapper, factory.getValidator());
            for (String path : List.of("direct", "reviewed")) {
                Path directory = OUTPUT.resolve(path);
                var response = parser.parse(Files.readString(directory.resolve("unit-test.response.json")), UnitTestResponseDto.class);
                assertThat(response.unitTests()).hasSize(1);
                var unit = response.unitTests().get(0);
                pairedRun(directory, unit.testClassName(), unit.testMethodName(), unit.sourceCode());
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "greytest.oracle.live", matches = "true")
    void realProviderPreservesApprovedOracleAcrossDirectAndReviewedPaths() throws Exception {
        // Trial live phải dùng thư mục mới, tránh đọc cache hoặc gộp token của lần chạy cũ.
        for (String artifact : List.of("environment.json", "direct", "review", "reviewed", "usage")) {
            assertThat(OUTPUT.resolve(artifact)).as("Fresh output directory required for live provider run").doesNotExist();
        }
        Map<String, String> env = environment();
        assertThat(env.get("LLM_API_KEY")).as("Provider credentials are available").isNotBlank();
        Files.createDirectories(OUTPUT);
        String model = env.getOrDefault("LLM_OPENAI_MODEL", env.getOrDefault("LLM_MODEL", "gpt-5.6-luna"));
        URI endpoint = URI.create(env.getOrDefault("LLM_OPENAI_URL", "https://api.vietapi.tech/v1/chat/completions"));
        Files.writeString(OUTPUT.resolve("environment.json"), mapper.writeValueAsString(Map.of(
                "model", model, "providerHost", endpoint.getHost(), "temperature", 0.0,
                "maxOutputTokens", 6000, "javaVersion", System.getProperty("java.version"),
                "approvalActor", "AUTOMATED_FIXTURE", "scope", "synthetic fixture; not human UAT or HITL")));
        var usage = new LlmUsageRecorder(mapper, true, OUTPUT.resolve("usage"), OUTPUT.getFileName().toString(), "GREYTEST_SYNTHETIC_PILOT");
        LlmClient client = new OpenAiLlmClient(mapper, HttpClient.newHttpClient(), env.get("LLM_API_KEY"),
                model, 0.0, 6000, Duration.ofSeconds(120), endpoint, usage);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var parser = new GenerationResponseParser(mapper, factory.getValidator());
            runPath("direct", client, parser, null);
            var review = generate("review", "business-rule-review", new BusinessRuleReviewContextDto(
                    project(), null, classes(), List.of(), List.of(), List.of(rule()), List.of(), List.of()),
                    BusinessRuleReviewResponseDto.class, client, parser);
            Files.writeString(OUTPUT.resolve("review-verdict.json"), mapper.writeValueAsString(review));
            assertThat(review.reviewedRules()).hasSize(1);
            assertThat(review.reviewedRules().get(0).verdict()).isEqualTo("SOURCE_MISMATCH");
            assertThat(review.reviewedRules().get(0).suggestedDescription()).isNull();
            // Review chỉ thêm nhận xét; fixture không áp suggestion lên BR đã được người dùng xác nhận.
            runPath("reviewed", client, parser, review.reviewedRules().get(0).reason());
        }
    }

    private void runPath(String path, LlmClient client, GenerationResponseParser parser, String reviewNote)
            throws Exception {
        var approvedRule = new BusinessRuleContextDto(1L, 1L, "BR-001", RULE, reviewNote,
                "USER_ADDED", "APPROVED", false, null);
        String ruleHash = hash(approvedRule.description());
        var planOutput = generate(path, "test-plan", new TestPlanContextDto(project(), null, classes(),
                List.of(approvedRule), List.of()), TestPlanResponseDto.class, client, parser);
        List<TestPlanContextItemDto> plans = new ArrayList<>();
        for (var plan : planOutput.plans()) {
            assertThat(plan.ruleId()).isEqualTo(1L);
            assertThat(plan.methodId()).isEqualTo(1L);
            plans.add(new TestPlanContextItemDto((long) plans.size() + 1, plan.ruleId(), plan.coveredRuleIds(),
                    "TP-" + (plans.size() + 1), plan.title(), plan.description(), plan.testType(), "APPROVED", false));
        }
        var caseOutput = generate(path, "test-case", new TestCaseContextDto(project(), null, classes(),
                List.of(approvedRule), plans, List.of()), TestCaseResponseDto.class, client, parser);
        // Chọn case tại biên do AI sinh; thiếu case này phải được báo, không thay bằng output viết tay.
        var equality = caseOutput.cases().stream().filter(item -> equalityData(item.testData())).findFirst()
                .orElseThrow(() -> new AssertionError("Provider omitted positive equality case"));
        assertThat(plans.stream().anyMatch(plan -> plan.id().equals(equality.planId()))).isTrue();
        Files.writeString(OUTPUT.resolve(path).resolve("case-review.txt"),
                "Independent ground truth: positive equality balance=amount succeeds, returns 0.\n"
                + "Automated fixture selected positive equality case under frozen policy; this is not human HITL.\n"
                + "Generated input and expected result retained before controlled fixture approval.\n"
                + "Expected result retained verbatim: " + equality.expectedResult() + "\n"
                + "Other generated cases, including zero amounts outside the stated domain, were not approved.\n");
        var approvedCase = new TestCaseContextItemDto(1L, equality.planId(), "TC-001", equality.testType(),
                equality.description(), equality.preconditions(), equality.testData(), equality.expectedResult(),
                equality.priority(), equality.traceSource(), "APPROVED", false);
        Files.writeString(OUTPUT.resolve(path).resolve("approved-case.json"), mapper.writeValueAsString(approvedCase));
        var output = generate(path, "unit-test", new UnitTestContextDto(project(), null, classes(),
                List.of(approvedRule), plans, List.of(approvedCase), List.of(), List.of(), List.of()),
                UnitTestResponseDto.class, client, parser);
        assertThat(output.unitTests()).hasSize(1);
        var unit = output.unitTests().get(0);
        assertThat(unit.caseId()).isEqualTo(1L);
        assertThat(unit.packageName()).isEqualTo("fixture");
        pairedRun(OUTPUT.resolve(path), unit.testClassName(), unit.testMethodName(), unit.sourceCode());
        assertThat(hash(approvedRule.description())).isEqualTo(ruleHash);
        Files.writeString(OUTPUT.resolve(path).resolve("approved-rule.sha256"), ruleHash);
    }

    private <T> T generate(String path, String stage, Object context, Class<T> type, LlmClient client,
            GenerationResponseParser parser) throws Exception {
        return generate(OUTPUT.resolve(path), stage, context, type, client, parser);
    }

    private <T> T generate(Path directory, String stage, Object context, Class<T> type, LlmClient client,
            GenerationResponseParser parser) throws Exception {
        Files.createDirectories(directory);
        String prompt = prompts.render(stage, Map.of("context_json", context));
        Path promptFile = directory.resolve(stage + ".prompt.md");
        Path responseFile = directory.resolve(stage + ".response.json");
        // Tái chạy kiểm chứng local không cần gọi provider lại nếu input/prompt đã đóng băng.
        if (Files.exists(promptFile) && Files.exists(responseFile) && Files.readString(promptFile).equals(prompt)) {
            return parser.parse(Files.readString(responseFile), type);
        }
        Files.writeString(directory.resolve(stage + ".prompt.md"), prompt);
        long started = System.nanoTime();
        String status = "PROVIDER_ERROR";
        try {
            String raw;
            try {
                raw = client.complete(prompt, new LlmRequestOptions(6000));
            } catch (RuntimeException exception) {
                // Không đưa response HTTP/provider hoặc credential vào báo cáo test.
                throw new AssertionError("Provider call failed for " + stage + "; inspect provider configuration privately");
            }
            Files.writeString(responseFile, raw);
            status = "PARSE_ERROR";
            T result = parser.parse(raw, type);
            status = "SUCCESS";
            return result;
        } finally {
            Files.writeString(directory.resolve("attempts.jsonl"), mapper.writeValueAsString(Map.of(
                    "stage", stage, "status", status, "elapsedMillis", (System.nanoTime() - started) / 1_000_000.0,
                    "retryCount", 0, "providerTokens", "see usage/calls.jsonl; unavailable is N/A")) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private boolean equalityData(Map<String, Object> data) {
        Object input = data.getOrDefault("input", data);
        if (!(input instanceof Map<?, ?> values)) return false;
        return values.get("balance") instanceof Number balance && values.get("amount") instanceof Number amount
                && balance.longValue() > 0 && balance.longValue() == amount.longValue();
    }

    private void pairedRun(Path directory, String className, String equalityMethod, String test) throws Exception {
        assertThat(className).matches("[A-Za-z_$][A-Za-z0-9_$]*");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(className + ".java"), test);
        String immutableHash = hash(test);
        RunResult correct = compileAndRun(directory.resolve("correct"), className, test, SOURCE.replace("> amount", ">= amount"));
        RunResult faulty = compileAndRun(directory.resolve("faulty"), className, test, SOURCE);
        Files.writeString(directory.resolve("execution.json"), mapper.writeValueAsString(Map.of(
                "generatedTestSha256", immutableHash, "correct", correct, "faulty", faulty)));
        assertThat(correct.found()).isPositive();
        assertThat(correct.failed()).isZero();
        assertThat(correct.succeeded()).isEqualTo(correct.found());
        assertThat(faulty.found()).isEqualTo(correct.found());
        assertThat(faulty.failed()).isPositive();
        assertThat(faulty.succeeded() + faulty.failed()).isEqualTo(faulty.found());
        assertThat(faulty.failureClasses()).allMatch(name -> name.equals("java.lang.IllegalArgumentException")
                || name.equals("org.opentest4j.AssertionFailedError") || name.equals("java.lang.AssertionError"));
        assertThat(faulty.failureMethods()).allMatch(name -> name.equals(equalityMethod));
        assertThat(faulty.failureCauses()).allMatch(name -> name.equals("java.lang.IllegalArgumentException: Insufficient balance"));
        assertThat(faulty.failureFrames()).allMatch(frames -> frames.stream().anyMatch(frame ->
                frame.equals("fixture." + className + "#" + equalityMethod)
                || frame.startsWith("fixture." + className + "#lambda$" + equalityMethod + "$")));
        assertThat(hash(Files.readString(directory.resolve(className + ".java")))).isEqualTo(immutableHash);
    }

    private RunResult compileAndRun(Path directory, String className, String test, String source) throws Exception {
        Files.createDirectories(directory);
        Path sut = directory.resolve("TransferService.java");
        Path testFile = directory.resolve(className + ".java");
        Files.writeString(sut, source);
        Files.writeString(testFile, test);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Java 17 JDK required").isNotNull();
        try (var diagnostics = Files.newOutputStream(directory.resolve("compile.txt"))) {
            int status = compiler.run(null, diagnostics, diagnostics, "--release", "17", "-proc:none", "-classpath",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    "-d", directory.toString(), sut.toString(), testFile.toString());
            assertThat(status).as("Compilation is technical validity, not bug detection").isZero();
        }
        List<URL> urls = new ArrayList<>(List.of(directory.toUri().toURL()));
        try {
            Class.forName("org.junit.platform.launcher.core.LauncherFactory");
        } catch (ClassNotFoundException missingLauncher) {
            Path engineJar = Path.of(Class.forName("org.junit.platform.engine.TestEngine")
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            String version = engineJar.getParent().getFileName().toString();
            Path launcher = engineJar.getParent().getParent().getParent().resolve("junit-platform-launcher")
                    .resolve(version).resolve("junit-platform-launcher-" + version + ".jar");
            assertThat(launcher).as("Installed launcher matching the test engine is required").exists();
            urls.add(launcher.toUri().toURL());
        }
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader())) {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            try {
                return executeJUnit(loader, loader.loadClass("fixture." + className));
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    private RunResult executeJUnit(ClassLoader loader, Class<?> testClass) throws Exception {
        Class<?> selectors = loader.loadClass("org.junit.platform.engine.discovery.DiscoverySelectors");
        Object selector = selectors.getMethod("selectClass", Class.class).invoke(null, testClass);
        Class<?> builderType = loader.loadClass("org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder");
        Object builder = builderType.getMethod("request").invoke(null);
        builderType.getMethod("selectors", List.class).invoke(builder, List.of(selector));
        Object request = builderType.getMethod("build").invoke(builder);
        Class<?> listenerType = loader.loadClass("org.junit.platform.launcher.TestExecutionListener");
        Class<?> summaryListenerType = loader.loadClass("org.junit.platform.launcher.listeners.SummaryGeneratingListener");
        Object listener = summaryListenerType.getConstructor().newInstance();
        Object listeners = java.lang.reflect.Array.newInstance(listenerType, 1);
        java.lang.reflect.Array.set(listeners, 0, listener);
        Object launcher = loader.loadClass("org.junit.platform.launcher.core.LauncherFactory").getMethod("create").invoke(null);
        loader.loadClass("org.junit.platform.launcher.Launcher").getMethod("execute",
                loader.loadClass("org.junit.platform.launcher.LauncherDiscoveryRequest"), listeners.getClass())
                .invoke(launcher, request, listeners);
        Object summary = summaryListenerType.getMethod("getSummary").invoke(listener);
        Class<?> summaryType = loader.loadClass("org.junit.platform.launcher.listeners.TestExecutionSummary");
        List<?> failures = (List<?>) summaryType.getMethod("getFailures").invoke(summary);
        List<String> failureClasses = new ArrayList<>();
        List<String> failureMethods = new ArrayList<>();
        List<String> failureCauses = new ArrayList<>();
        List<List<String>> failureFrames = new ArrayList<>();
        Class<?> failureType = loader.loadClass("org.junit.platform.launcher.listeners.TestExecutionSummary$Failure");
        for (Object failure : failures) {
            Throwable exception = (Throwable) failureType.getMethod("getException").invoke(failure);
            failureClasses.add(exception.getClass().getName());
            Object identifier = failureType.getMethod("getTestIdentifier").invoke(failure);
            java.util.Optional<?> methodSource = (java.util.Optional<?>) loader.loadClass("org.junit.platform.launcher.TestIdentifier")
                    .getMethod("getSource").invoke(identifier);
            Class<?> methodSourceType = loader.loadClass("org.junit.platform.engine.support.descriptor.MethodSource");
            failureMethods.add(methodSource.isPresent() && methodSourceType.isInstance(methodSource.get())
                    ? (String) methodSourceType.getMethod("getMethodName").invoke(methodSource.get()) : "<non-method failure>");
            List<String> frames = new ArrayList<>();
            while (true) {
                for (StackTraceElement frame : exception.getStackTrace()) {
                    if (frame.getClassName().startsWith("fixture.")) frames.add(frame.getClassName() + "#" + frame.getMethodName());
                }
                if (exception.getCause() == null) break;
                exception = exception.getCause();
            }
            failureFrames.add(frames);
            failureCauses.add(exception.getClass().getName() + ": " + exception.getMessage());
        }
        return new RunResult((long) summaryType.getMethod("getTestsFoundCount").invoke(summary),
                (long) summaryType.getMethod("getTestsSucceededCount").invoke(summary),
                (long) summaryType.getMethod("getTestsFailedCount").invoke(summary), failureClasses, failureMethods, failureCauses, failureFrames);
    }

    private ProjectContextDto project() { return new ProjectContextDto(1L, "Synthetic transfer acceptance fixture", "ANALYZED"); }

    private BusinessRuleContextDto rule() { return new BusinessRuleContextDto(1L, 1L, "BR-001", RULE, null, "USER_ADDED", "PENDING_REVIEW", false, null); }

    private List<ClassContextDto> classes() {
        var method = new MethodContextDto(1L, "fixture.TransferService", "transfer", "long",
                List.of(new MethodParamDto("balance", "long"), new MethodParamDto("amount", "long")),
                List.of(), "public", SOURCE, 3, 6, List.of(), List.of(), List.of());
        return List.of(new ClassContextDto(1L, "fixture", "TransferService", "fixture.TransferService", "SERVICE",
                "src/main/java/fixture/TransferService.java", SOURCE, List.of(), List.of(method)));
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, String> environment() throws Exception {
        Map<String, String> values = new HashMap<>();
        Path file = Path.of(".env");
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                if (line.startsWith("#") || !line.contains("=")) continue;
                int separator = line.indexOf('=');
                String value = line.substring(separator + 1).trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
                values.put(line.substring(0, separator).trim(), value);
            }
        }
        values.putAll(System.getenv());
        return values;
    }

    public record RunResult(long found, long succeeded, long failed, List<String> failureClasses,
            List<String> failureMethods, List<String> failureCauses, List<List<String>> failureFrames) { }
}
