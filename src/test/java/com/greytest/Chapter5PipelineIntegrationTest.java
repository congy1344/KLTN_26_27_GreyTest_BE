package com.greytest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.AuthUserRepository;
import com.greytest.service.agent.LlmClient;
import com.greytest.service.agent.LlmResponseException;

/** Kiểm thử HTTP → service → PostgreSQL/storage thật; chỉ thay ranh giới provider LLM. */
@SpringBootTest(properties = {
        "spring.config.import=", "llm.provider=mock", "llm.usage.enabled=false",
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://127.0.0.1:55439/chapter5_integration}",
        "spring.datasource.username=${SPRING_DATASOURCE_USERNAME:chapter5_test}",
        "spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:}",
        "greytest.auth.token-secret=chapter5-integration-test-secret-at-least-32-bytes"})
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Import(Chapter5PipelineIntegrationTest.ProviderConfig.class)
class Chapter5PipelineIntegrationTest {
    static final String SOURCE = "package sample; import org.springframework.stereotype.Service; "
            + "@Service public class PriceService { public int discount(int amount) { return amount >= 100 ? 10 : 0; } }";
    static final String RULE = "For amount equal to 100, discount returns 10.";
    static final Path STORAGE = createStorage();
    static final Path EVIDENCE = Path.of("../outputs/chapter-5/integration-20261009/snapshots",
            "run-" + System.currentTimeMillis()).toAbsolutePath().normalize();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired FixtureProvider provider;
    @Autowired ProjectRepository projects;
    @Autowired AuthUserRepository users;
    @Autowired @Qualifier("generationExecutor") java.util.concurrent.Executor executor;
    String owner;
    String other;
    String caseName;
    int snapshotSequence;
    final List<Long> projectIds = new ArrayList<>();
    final List<Long> userIds = new ArrayList<>();

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("greytest.storage.path", STORAGE::toString);
    }

    @BeforeEach
    void registerUsers(TestInfo info) throws Exception {
        caseName = info.getTestMethod().orElseThrow().getName();
        provider.reset();
        owner = register();
        other = register();
    }

    @AfterEach
    void cleanupCommittedFixtures() throws Exception {
        provider.release.countDown();
        awaitWorkersIdle();
        for (long id : projectIds) {
            // Worker chạy thread riêng: đợi terminal và lock được nhả trước khi xóa fixture đã commit.
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (true) {
                var response = mvc.perform(delete("/api/projects/" + id).header("Authorization", owner))
                        .andReturn().getResponse();
                if (response.getStatus() == 204 || response.getStatus() == 404) break;
                assertThat(response.getStatus()).isEqualTo(409);
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(25);
            }
        }
        projectIds.clear();
        users.deleteAllById(userIds);
        userIds.clear();
    }

    @AfterAll
    static void cleanupStorage() throws Exception {
        org.springframework.util.FileSystemUtils.deleteRecursively(STORAGE);
    }

    @Test
    void it01UploadAnalyzeSeparatesProductionAndExistingTests() throws Exception {
        long id = analyzedProject();
        JsonNode analysis = getJson(id, "analysis");
        assertThat(analysis.path("status").asText()).isEqualTo("ANALYZED");
        assertThat(analysis.path("totalClasses").asInt()).isEqualTo(1);
        assertThat(analysis.path("totalMethods").asInt()).isEqualTo(1);
        assertThat(analysis.path("existingTestFiles").asInt()).isEqualTo(1);
        assertThat(analysis.path("failedParseFiles").asInt()).isZero();
        JsonNode manifest = getJson(id, "analysis/manifest");
        assertThat(manifest.path("classes").toString()).isEqualTo("[\"sample.PriceService\"]");
        assertThat(manifest.path("methods").toString()).isEqualTo("[\"sample.PriceService#discount(int):int\"]");
        JsonNode existing = getJson(id, "existing-tests");
        assertThat(existing.size()).isEqualTo(1);
        assertThat(existing.get(0).path("testClassName").asText()).isEqualTo("PriceServiceExistingTest");
        assertThat(projects.findById(id).orElseThrow().getStoragePath()).startsWith(STORAGE.toString());
    }

    @Test
    void it02ApprovedRulesDrivePersistedPlanCaseAndExecutableAssertion() throws Exception {
        long id = analyzedProject();
        createRule(id);
        generate(id, "test-plans", "TEST_PLAN", "FAILED");
        assertThat(getJson(id, "test-plans").size()).isZero();
        runPipeline(id);
        JsonNode rules = getJson(id, "business-rules");
        JsonNode plans = getJson(id, "test-plans");
        JsonNode cases = getJson(id, "test-cases");
        JsonNode tests = getJson(id, "unit-tests");
        assertThat(rules.get(0).path("description").asText()).isEqualTo(RULE);
        assertThat(plans.get(0).path("businessRuleId").asLong()).isEqualTo(rules.get(0).path("id").asLong());
        assertThat(cases.get(0).path("testPlanId").asLong()).isEqualTo(plans.get(0).path("id").asLong());
        assertThat(cases.get(0).path("expectedResult").asText()).isEqualTo("10");
        assertThat(tests.get(0).path("testCaseId").asLong()).isEqualTo(cases.get(0).path("id").asLong());
        assertThat(tests.get(0).path("sourceCode").asText())
                .contains("new PriceService()", "assertEquals(10, sut.discount(100))");
        compileAndRunGeneratedTest(tests.get(0).path("sourceCode").asText());
        mvc.perform(get("/api/projects/" + id + "/unit-tests/download").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip"));
    }

    @Test
    void it03CoverageHistoryTraceabilityAndExportsPreserveStoredArtifacts() throws Exception {
        long id = analyzedProject();
        createRule(id);
        runPipeline(id);
        uploadCoverage(id, 1, 1);
        JsonNode latest = uploadCoverage(id, 0, 2);
        assertThat(latest.path("round").asInt()).isEqualTo(2);
        assertThat(latest.path("lineCoverage").asDouble()).isEqualTo(100);
        assertThat(latest.path("previousLineCoverage").asDouble()).isEqualTo(50);
        assertThat(latest.path("requirementCoverage").asDouble()).isEqualTo(100);
        assertThat(getJson(id, "coverage/history").size()).isEqualTo(2);
        JsonNode trace = getJson(id, "traceability");
        assertThat(trace.path("rows").size()).isEqualTo(1);
        assertThat(trace.path("rows").get(0).path("unitTestId").asLong()).isPositive();
        assertThat(trace.path("uncoveredRules").size()).isZero();
        for (String format : List.of("json", "markdown")) {
            String report = mvc.perform(get("/api/projects/" + id + "/export").param("format", format)
                    .header("Authorization", owner)).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(report).contains(RULE, "PriceServiceBoundaryTest");
        }
    }

    @Test
    void it04UnauthorizedReadsMutationsAndPauseCannotChangeOwnerState() throws Exception {
        long id = analyzedProject();
        createRule(id);
        postJson(id, "business-rules/approve");
        provider.block = true;
        accepted(id, "test-plans", "TEST_PLAN");
        assertThat(provider.entered.await(10, TimeUnit.SECONDS)).isTrue();
        String before = getJson(id, "business-rules").toString();
        String state = getJson(id, "generation-progress/TEST_PLAN").toString();
        assertThat(mapper.readTree(state).path("status").asText()).isEqualTo("RUNNING");
        for (String route : List.of("analysis", "business-rules", "unit-tests", "traceability", "coverage/history")) {
            mvc.perform(get("/api/projects/" + id + "/" + route).header("Authorization", other))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/projects/" + id + "/business-rules/approve").header("Authorization", other))
                .andExpect(status().isForbidden());
        String pause = "/api/projects/" + id + "/generation-progress/TEST_PLAN/pause";
        mvc.perform(post(pause)).andExpect(status().isUnauthorized());
        mvc.perform(post(pause).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(post(pause).header("Authorization", other)).andExpect(status().isForbidden());
        assertThat(getJson(id, "generation-progress/TEST_PLAN").toString()).isEqualTo(state);
        assertThat(getJson(id, "business-rules").toString()).isEqualTo(before);
        mvc.perform(post(pause).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
        provider.release.countDown();
        awaitWorkersIdle();
        assertThat(getJson(id, "generation-progress/TEST_PLAN").path("status").asText()).isEqualTo("PAUSED");
    }

    @Test
    void it05ProviderFailureAndDuplicateJobKeepApprovedRuleAndNoPartialPlans() throws Exception {
        long id = analyzedProject();
        createRule(id);
        postJson(id, "business-rules/approve");
        String before = getJson(id, "business-rules").toString();
        provider.block = true;
        provider.fail = true;
        accepted(id, "test-plans", "TEST_PLAN");
        assertThat(provider.entered.await(10, TimeUnit.SECONDS)).isTrue();
        mvc.perform(post("/api/projects/" + id + "/test-plans/generate").header("Authorization", owner))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/projects/" + id + "/business-rules/approve").header("Authorization", owner))
                .andExpect(status().isConflict());
        provider.release.countDown();
        awaitTerminal(id, "TEST_PLAN", "FAILED");
        assertThat(getJson(id, "test-plans").size()).isZero();
        assertThat(getJson(id, "business-rules").toString()).isEqualTo(before);
        assertThat(projects.findById(id).orElseThrow().getStatus().name()).isEqualTo("BR_APPROVED");
    }

    private String register() throws Exception {
        JsonNode result = json(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", UUID.randomUUID() + "@chapter5.test",
                        "password", "chapter5-test-password", "fullName", "Chapter 5 fixture"))))
                .andExpect(status().isOk()));
        userIds.add(result.path("user").path("id").asLong());
        return "Bearer " + result.path("token").asText();
    }

    private long analyzedProject() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId>"
                    + "<artifactId>chapter5</artifactId><version>1</version></project>");
            entry(zip, "src/main/java/sample/PriceService.java", SOURCE);
            entry(zip, "src/test/java/sample/PriceServiceExistingTest.java", "package sample; "
                    + "import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.assertEquals; "
                    + "class PriceServiceExistingTest { @Test void belowBoundary() { assertEquals(0, new PriceService().discount(99)); } }");
        }
        long id = json(mvc.perform(multipart("/api/projects/upload").file(new MockMultipartFile("file",
                "chapter5.zip", "application/zip", bytes.toByteArray())).header("Authorization", owner))
                .andExpect(status().isCreated())).path("id").asLong();
        projectIds.add(id);
        postJson(id, "analyze");
        return id;
    }

    private void createRule(long id) throws Exception {
        long methodId = getJson(id, "analysis").path("classes").get(0).path("methods").get(0).path("id").asLong();
        request(post("/api/projects/" + id + "/business-rules").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("methodId", methodId, "description", RULE,
                        "sourceBranchId", "TERNARY-1"))))
                .andExpect(status().isCreated());
    }

    private void runPipeline(long id) throws Exception {
        postJson(id, "business-rules/approve");
        generate(id, "test-plans", "TEST_PLAN", "COMPLETED");
        postJson(id, "test-plans/approve");
        generate(id, "test-cases", "TEST_CASE", "COMPLETED");
        postJson(id, "test-cases/approve");
        generate(id, "unit-tests", "UNIT_TEST", "COMPLETED");
    }

    private void generate(long id, String resource, String stage, String expected) throws Exception {
        accepted(id, resource, stage);
        awaitTerminal(id, stage, expected);
    }

    private void accepted(long id, String resource, String stage) throws Exception {
        var result = request(post("/api/projects/" + id + "/" + resource + "/generate"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.stage").value(stage))
                .andExpect(jsonPath("$.status").value("QUEUED")).andExpect(jsonPath("$.message").isNotEmpty());
        snapshot(id, resource + "-accepted", json(result));
    }

    private void awaitTerminal(long id, String stage, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonNode progress;
        do {
            progress = getJson(id, "generation-progress/" + stage);
            if (List.of("COMPLETED", "FAILED", "PAUSED").contains(progress.path("status").asText())) {
                assertThat(progress.path("status").asText()).withFailMessage(progress.toPrettyString()).isEqualTo(expected);
                awaitWorkersIdle();
                return;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Generation timeout: " + progress);
    }

    private void awaitWorkersIdle() throws Exception {
        var pool = ((org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) executor).getThreadPoolExecutor();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (pool.getActiveCount() != 0 || !pool.getQueue().isEmpty()) {
            assertThat(System.nanoTime()).as("Generation workers must finish before mutation/cleanup").isLessThan(deadline);
            Thread.sleep(25);
        }
    }

    private JsonNode uploadCoverage(long id, int missed, int covered) throws Exception {
        String xml = "<?xml version='1.0'?><report name='fixture'><package name='sample'>"
                + "<class name='sample/PriceService' sourcefilename='PriceService.java'>"
                + "<method name='discount' desc='(I)I' line='1'><counter type='LINE' missed='" + missed + "' covered='" + covered + "'/>"
                + "<counter type='BRANCH' missed='1' covered='1'/></method></class>"
                + "<sourcefile name='PriceService.java'><line nr='1' mi='" + missed + "' ci='" + covered + "' mb='1' cb='1'/></sourcefile>"
                + "</package><counter type='LINE' missed='" + missed + "' covered='" + covered + "'/>"
                + "<counter type='BRANCH' missed='1' covered='1'/></report>";
        return json(mvc.perform(multipart("/api/projects/" + id + "/coverage/upload")
                .file(new MockMultipartFile("file", "jacoco.xml", "application/xml", xml.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", owner)).andExpect(status().isCreated()));
    }

    private JsonNode getJson(long id, String resource) throws Exception {
        return snapshot(id, resource, json(request(get("/api/projects/" + id + "/" + resource)).andExpect(status().isOk())));
    }
    private JsonNode postJson(long id, String resource) throws Exception {
        return snapshot(id, resource, json(request(post("/api/projects/" + id + "/" + resource)).andExpect(status().isOk())));
    }
    private JsonNode snapshot(long id, String resource, JsonNode response) throws Exception {
        // Chỉ gọi từ API project; không ghi response đăng nhập hoặc Authorization vào evidence.
        Path directory = EVIDENCE.resolve(caseName);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(String.format("%04d-%s.json", ++snapshotSequence,
                resource.replace('/', '-'))), response.toPrettyString());
        return response;
    }
    private ResultActions request(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", owner));
    }
    private JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    private static Path createStorage() {
        try { return Files.createTempDirectory("greytest-chapter5-it-"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void compileAndRunGeneratedTest(String testSource) throws Exception {
        Path dir = Files.createTempDirectory(STORAGE, "generated-replay-");
        Path production = dir.resolve("PriceService.java");
        Path test = dir.resolve("PriceServiceBoundaryTest.java");
        Files.writeString(production, SOURCE);
        Files.writeString(test, testSource);
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Run integration test with a JDK").isNotNull();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        assertThat(compiler.run(null, null, null, "-classpath", classpath, "-d", dir.toString(),
                production.toString(), test.toString())).isZero();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{dir.toUri().toURL()}, getClass().getClassLoader())) {
            var type = loader.loadClass("sample.PriceServiceBoundaryTest");
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            var method = type.getDeclaredMethod("atBoundary");
            method.setAccessible(true);
            method.invoke(constructor.newInstance());
        }
    }
    private static void entry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @TestConfiguration
    static class ProviderConfig {
        @Bean @Primary FixtureProvider fixtureProvider(ObjectMapper mapper) { return new FixtureProvider(mapper); }
    }

    /** Provider fixture trả JSON theo ID thật trong prompt, không thay gateway/parser/service. */
    static class FixtureProvider implements LlmClient {
        final ObjectMapper mapper;
        volatile boolean block;
        volatile boolean fail;
        CountDownLatch entered;
        CountDownLatch release;
        FixtureProvider(ObjectMapper mapper) { this.mapper = mapper; reset(); }
        void reset() { block = false; fail = false; entered = new CountDownLatch(1); release = new CountDownLatch(1); }
        @Override public String complete(String prompt) {
            entered.countDown();
            if (block) {
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            if (fail) throw new LlmResponseException("Chapter5 fixture provider unavailable");
            try {
                String normalized = prompt.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("prompt: test-plan")) {
                    long method = id(prompt, "id", "classQualifiedName");
                    long rule = id(prompt, "id", "methodId");
                    return mapper.writeValueAsString(Map.of("plans", List.of(Map.of("method_id", method,
                            "rule_id", rule, "covered_rule_ids", List.of(rule), "title", "Boundary at 100",
                            "description", RULE, "test_type", "BOUNDARY"))));
                }
                if (normalized.contains("prompt: coverage-refinement")) {
                    long plan = id(prompt, "id", "businessRuleId");
                    return mapper.writeValueAsString(Map.of("cases", List.of(Map.of("plan_id", plan,
                            "test_type", "BOUNDARY", "description", "Below 100 returns zero",
                            "preconditions", "PriceService available",
                            "test_data", Map.of("input", Map.of("amount", 99), "mocks", Map.of()),
                            "expected_result", "0", "priority", "HIGH",
                            "trace_source", "BR-001 -> TP-001 -> JaCoCo round 2"))));
                }
                if (normalized.contains("prompt: test-case")) {
                    long plan = id(prompt, "id", "businessRuleId");
                    return mapper.writeValueAsString(Map.of("cases", List.of(Map.of("plan_id", plan,
                            "test_type", "BOUNDARY", "description", RULE, "preconditions", "PriceService available",
                            "test_data", Map.of("input", Map.of("amount", 100), "mocks", Map.of()),
                            "expected_result", "10", "priority", "HIGH", "trace_source", "BR-001 -> TP-001"))));
                }
                if (normalized.contains("prompt: unit-test")) {
                    long testCase = id(prompt, "id", "testPlanId");
                    boolean supplemental = prompt.contains("Below 100 returns zero");
                    String className = supplemental ? "PriceServiceGapTest" : "PriceServiceBoundaryTest";
                    String methodName = supplemental ? "belowBoundary" : "atBoundary";
                    String source = "package sample; import org.junit.jupiter.api.Test; "
                            + "import static org.junit.jupiter.api.Assertions.assertEquals; "
                            + "class " + className + " { @Test void " + methodName + "() { "
                            + "PriceService sut = new PriceService(); "
                            + (supplemental ? "assertEquals(0, sut.discount(99));" : "assertEquals(10, sut.discount(100));") + " } }";
                    return mapper.writeValueAsString(Map.of("unit_tests", List.of(Map.of("case_id", testCase,
                            "test_class_name", className, "test_method_name", methodName,
                            "package_name", "sample", "generation_type", "SUPPLEMENT_EXISTING_TEST", "source_code", source))));
                }
                throw new IllegalArgumentException("Unexpected fixture prompt");
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        private long id(String prompt, String key, String next) {
            var match = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)\\s*,\\s*\"" + next + "\"").matcher(prompt);
            if (!match.find()) throw new IllegalArgumentException("Missing context ID before " + next);
            return Long.parseLong(match.group(1));
        }
    }
}
