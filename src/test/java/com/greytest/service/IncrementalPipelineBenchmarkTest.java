package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.entity.AuthUser;
import com.greytest.entity.Project;
import com.greytest.dto.GenerationProgressStage;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.SourceType;
import com.greytest.repository.ProjectRepository;
import com.greytest.repository.SourceUpdateItemRepository;
import com.greytest.service.agent.LlmClient;
import com.greytest.service.agent.LlmRequestOptions;
import com.greytest.service.analysis.AnalysisService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

/** Đo pipeline thật với provider cục bộ xác định; không gọi API LLM bên ngoài. */
@SpringBootTest(properties = {
        "greytest.auth.token-secret=test-only-token-secret-at-least-32-bytes",
        "greytest.llm-batch.concurrency=1",
        "greytest.llm-batch.retry-attempts=0"
})
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL",
        matches = "jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024")
class IncrementalPipelineBenchmarkTest {
    @Autowired IncrementalGenerationService pipeline;
    @Autowired AnalysisService analysis;
    @Autowired ProjectRepository projects;
    @Autowired SourceUpdateItemRepository items;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SourceUpdateService sourceUpdates;
    @MockBean LlmClient provider;

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger parses = new AtomicInteger();
    private final AtomicBoolean failNextUnitTest = new AtomicBoolean();
    private volatile CountDownLatch providerEntered;
    private volatile CountDownLatch providerRelease;

    @BeforeEach
    void configureTestProvider() {
        assertThat(System.getenv("LLM_PROVIDER")).isEqualTo("mock");
        when(provider.complete(anyString(), any(LlmRequestOptions.class)))
                .thenAnswer(invocation -> respond(invocation.getArgument(0)));
        when(provider.complete(anyString())).thenAnswer(invocation -> respond(invocation.getArgument(0)));
    }

    @Test
    void regeneratesOnlyChangedMethodAndItsDependency() throws Exception {
        Fixture fixture = createFixture();
        List<Map<String, Object>> evidence = new ArrayList<>();
        try {
            evidence.add(measureAll(fixture, "GENERATE_ALL_COLD"));
            Path source = fixture.root().resolve("candidate/src/main/java/benchmark/BenchmarkService.java");
            Path dependency = source.resolveSibling("BenchmarkDependency.java");
            Files.writeString(dependency, "package benchmark; class BenchmarkDependency { static int answer() { return 42; } }");
            Files.writeString(source, Files.readString(source).replace("public int value0() { return 42; }",
                    "public int value0() { return BenchmarkDependency.answer(); }"));
            var oneMethod = measureAll(fixture, "ONE_METHOD_CHANGED");
            assertThat(((Number) oneMethod.get("llmCalls")).intValue()).isLessThanOrEqualTo(4);
            assertRegeneratedAndSkipped(oneMethod, 4, 20);
            evidence.add(oneMethod);
            Files.writeString(dependency, "package benchmark; class BenchmarkDependency { static int answer() { return 21 + 21; } }");
            var helper = measureAll(fixture, "DEPENDENCY_CHANGED");
            assertRegeneratedAndSkipped(helper, 4, 20);
            evidence.add(helper);
            var repeat = measureAll(fixture, "GENERATE_ALL_UNCHANGED");
            assertThat(repeat.get("llmCalls")).isEqualTo(0);
            assertRegeneratedAndSkipped(repeat, 0, 24);
            evidence.add(repeat);
            saveScenarioEvidence("target-and-dependency", evidence);
        } finally { cleanupFixture(fixture); }
    }

    @Test
    void resumesAfterUnitProviderFailureWithoutRepeatingSuccessfulStages() throws Exception {
        Fixture fixture = createFixture();
        try {
            failNextUnitTest.set(true);
            int before = calls.get(); long started = System.nanoTime();
            assertThatThrownBy(() -> pipeline.generateAll(fixture.projectId(), fixture.updateId(), null))
                    .hasRootCauseInstanceOf(com.greytest.service.agent.LlmResponseException.class).hasStackTraceContaining("Injected benchmark failure");
            var partial = items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(fixture.updateId());
            assertThat(partial).hasSize(18).noneMatch(item -> item.getTargetType().name().equals("UNIT_TEST"));
            Map<String, Object> failure = Map.of("workload", "UNIT_PROVIDER_FAILURE", "wallMillis", (System.nanoTime() - started) / 1_000_000.0,
                    "llmCalls", calls.get() - before, "artifacts", partial.size());
            var resumed = measureAll(fixture, "RETRY_RESUME");
            assertThat(resumed.get("llmCalls")).isEqualTo(1);
            assertRegeneratedAndSkipped(resumed, 6, 18);
            saveScenarioEvidence("retry", List.of(failure, resumed));
        } finally { cleanupFixture(fixture); }
    }

    @Test void explicitReviewsThenApplyRetainCheckpointsAndDoNotCallProviderAgain() throws Exception {
        Fixture fixture=createFixture();
        try {
            measureAll(fixture,"APPLY_SETUP");
            var methods=jdbc.queryForList("select jm.id,jm.method_name,jm.source_code from java_method jm join java_class jc on jc.id=jm.class_id where jc.project_id=?",fixture.projectId());
            for(var method:methods) {
                jdbc.update("insert into source_update_item(source_update_id,target_type,target_id,target_key,action,before_data,after_data,review_status) values(?,'METHOD',?,?, 'UPDATE',?,?,'PENDING')",
                    fixture.updateId(),method.get("id"),"benchmark.BenchmarkService#"+method.get("method_name")+"()",method.get("source_code"),
                    "public int "+method.get("method_name")+"() { return 42; }");
            }
            for(var item:items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(fixture.updateId()))
                sourceUpdates.patchItem(fixture.projectId(),fixture.updateId(),item.getId(),new com.greytest.dto.SourceUpdateItemPatchRequest(
                    null,com.greytest.entity.enums.SourceUpdateReviewStatus.ACCEPTED,null,null,item.getVersion(),item.getUserRevision(),null,null,null),null);
            int before=calls.get();
            var applied=sourceUpdates.applyUpdate(fixture.projectId(),fixture.updateId(),null);
            assertThat(applied.status()).isEqualTo(com.greytest.entity.enums.SourceUpdateStatus.APPLIED);
            assertThat(calls.get()).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from generation_checkpoint where source_update_id=? and state='SUCCEEDED' and target_key <> '__run__'",Integer.class,fixture.updateId())).isEqualTo(28);
            assertThat(jdbc.queryForList("select jm.source_code from java_method jm join java_class jc on jc.id=jm.class_id where jc.project_id=?",String.class,fixture.projectId()))
                .allMatch(source->source.contains("42"));
        } finally { cleanupFixture(fixture); }
    }

    @Test
    void concurrentRequestDoesNotDuplicateProviderCallsOrArtifacts() throws Exception {
        Fixture fixture = createFixture();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        providerEntered = new CountDownLatch(1); providerRelease = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> measureAll(fixture, "CONCURRENT_OWNER"));
            assertThat(providerEntered.await(15, TimeUnit.SECONDS)).isTrue();
            int before = calls.get(); long started = System.nanoTime();
            assertThatThrownBy(() -> pipeline.generateAll(fixture.projectId(), fixture.updateId(), null))
                    .isInstanceOf(com.greytest.exception.GenerationInProgressException.class);
            assertThat(calls.get()).isEqualTo(before);
            Map<String, Object> rejected = Map.of("workload", "CONCURRENT_REJECTED", "wallMillis", (System.nanoTime() - started) / 1_000_000.0,
                    "llmCalls", 0);
            providerRelease.countDown();
            var completed = first.get(30, TimeUnit.SECONDS);
            assertThat(completed.get("llmCalls")).isEqualTo(5);
            saveScenarioEvidence("concurrent", List.of(completed, rejected));
        } finally {
            providerRelease.countDown(); executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS); cleanupFixture(fixture);
        }
    }

    @Test
    void protectsHumanEditedProposalWhenSourceChanges() throws Exception {
        Fixture fixture = createFixture();
        try {
            measureAll(fixture, "PROTECTED_EDIT_SETUP");
            var item = items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(fixture.updateId()).stream()
                    .filter(i -> i.getTargetType().name().equals("BUSINESS_RULE")).findFirst().orElseThrow();
            String edited = item.getAfterData().replace("Returns 42", "Human approved: Returns 42");
            sourceUpdates.patchItem(fixture.projectId(), fixture.updateId(), item.getId(),
                    new com.greytest.dto.SourceUpdateItemPatchRequest(null, com.greytest.entity.enums.SourceUpdateReviewStatus.MODIFIED,
                            edited, null, item.getVersion(), item.getUserRevision(), null, null, null), null);
            Path source = fixture.root().resolve("candidate/src/main/java/benchmark/BenchmarkService.java");
            // Đổi mọi method tương đương để chắc chắn proposal đã sửa thuộc scope stale.
            Files.writeString(source, Files.readString(source).replace("return 42;", "return 21 + 21;"));
            int before = calls.get(); long started = System.nanoTime();
            assertThatThrownBy(() -> pipeline.generateAll(fixture.projectId(), fixture.updateId(), null))
                    .hasRootCauseInstanceOf(com.greytest.service.generation.GenerationConflictException.class);
            assertThat(items.findById(item.getId()).orElseThrow().getAfterData()).isEqualTo(edited);
            assertThat(jdbc.queryForObject("select count(*) from generation_checkpoint where source_update_id=? and state='CONFLICT'", Integer.class, fixture.updateId())).isPositive();
            saveScenarioEvidence("protected-edit", List.of(Map.of("workload", "PROTECTED_EDIT_CONFLICT",
                    "wallMillis", (System.nanoTime() - started) / 1_000_000.0, "llmCalls", calls.get() - before,
                    "humanAfterDataPreserved", true)));
        } finally { cleanupFixture(fixture); }
    }

    @Test
    void repairsCorruptCheckpointOutputWithoutBlessingUntrackedChanges() throws Exception {
        Fixture fixture = createFixture();
        try {
            var cold = measureAll(fixture, "CORRUPTION_SETUP");
            var item = items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(fixture.updateId()).stream()
                    .filter(i -> i.getTargetType().name().equals("BUSINESS_RULE")).findFirst().orElseThrow();
            jdbc.update("update source_update_item set after_data=cast(? as jsonb) where id=?", item.getAfterData().replace("Returns 42", "Corrupt unchecked description"), item.getId());
            var repaired = measureAll(fixture, "CORRUPT_OUTPUT_REPAIR");
            assertThat(repaired.get("outputDigest")).isEqualTo(cold.get("outputDigest"));
            assertThat(items.findById(item.getId()).orElseThrow().getUserRevision()).isZero();
            assertRegeneratedAndSkipped(repaired, 1, 23);
            saveScenarioEvidence("corrupt-checkpoint", List.of(repaired));
        } finally { cleanupFixture(fixture); }
    }

    private void assertRegeneratedAndSkipped(Map<String, Object> result, int regenerated, int skipped) {
        @SuppressWarnings("unchecked") var metrics = (Map<String, Object>) result.get("metrics");
        assertThat(((Number) metrics.getOrDefault("artifact.regenerated", 0)).intValue()).isEqualTo(regenerated);
        assertThat(((Number) metrics.getOrDefault("checkpoint.skip", 0)).intValue()).isEqualTo(skipped);
    }

    @Test void missingUnitCheckpointsRecoverFromValidatedPersistentCacheWithoutCallingProvider() throws Exception {
        Fixture fixture=createFixture();
        try {
            var original=measureAll(fixture,"PERSISTENT_CACHE_SETUP");
            jdbc.update("delete from generation_checkpoint where source_update_id=? and stage='UNIT_TEST'",fixture.updateId());
            var resumed=measureAll(fixture,"PERSISTENT_CACHE_RECOVERY");
            assertThat(resumed.get("llmCalls")).isEqualTo(0);
            assertThat(resumed.get("outputDigest")).isEqualTo(original.get("outputDigest"));
            assertRegeneratedAndSkipped(resumed,6,18);
            @SuppressWarnings("unchecked") var metrics=(Map<String,Object>)resumed.get("metrics");
            assertThat(((Number)metrics.getOrDefault("cache.hit",0)).intValue()).isEqualTo(1);
            saveScenarioEvidence("persistent-cache",List.of(resumed));
        } finally { cleanupFixture(fixture); }
    }

    private Map<String, Object> measureAll(Fixture fixture, String workload) throws Exception {
        int before = calls.get(); long started = System.nanoTime();
        pipeline.generateAll(fixture.projectId(), fixture.updateId(), null);
        double elapsed = (System.nanoTime() - started) / 1_000_000.0;
        var stored = items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(fixture.updateId());
        assertThat(stored).hasSize(24);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (var item : stored) counts.merge(item.getTargetType().name(), 1L, Long::sum);
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("BUSINESS_RULE", 6L, "TEST_PLAN", 6L, "TEST_CASE", 6L, "UNIT_TEST", 6L));
        String content = stored.stream().map(item -> item.getTargetType() + ":" + item.getTargetId() + ":" + item.getAfterData())
                .sorted().collect(java.util.stream.Collectors.joining("\n"));
        String metricsJson = jdbc.queryForObject("select metrics::text from generation_checkpoint where source_update_id=? and stage='PIPELINE' and target_key='__run__'", String.class, fixture.updateId());
        Map<String, Object> result = new LinkedHashMap<>(); result.put("workload", workload);
        result.put("wallMillis", elapsed); result.put("llmCalls", calls.get() - before);
        result.put("artifacts", stored.size()); result.put("artifactCounts", counts);
        result.put("outputDigest", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))));
        result.put("metrics", json.readValue(metricsJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        return result;
    }

    private void saveScenarioEvidence(String scenario, List<Map<String, Object>> samples) throws Exception {
        Path output = Path.of("../.greytest-generation-verification/incremental-optimization/scenario-" + scenario + ".json");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scenario", scenario,
                "provider", "deterministic local TEST provider; no external API", "measurements", samples,
                "limitations", "Single correctness workload sample; not a production latency estimate or before/after benchmark")));
    }

    private void cleanupFixture(Fixture fixture) throws Exception {
        jdbc.update("delete from project where id = ? and name = ?", fixture.projectId(), fixture.name());
        try (var paths = Files.walk(fixture.root())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test
    void benchmarkColdAndUnchangedRepeat() throws Exception {
        assertThat(System.getenv("LLM_PROVIDER")).isEqualTo("mock");
        Logger logger = (Logger) LoggerFactory.getLogger("com.greytest.service.analysis.JavaSourceScanner");
        var previousScannerLevel=logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        AppenderBase<ILoggingEvent> counter = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().startsWith("Classified source:")) parses.incrementAndGet();
            }
        };
        counter.setContext(logger.getLoggerContext());
        counter.start();
        logger.addAppender(counter);
        int historicalProjects = jdbc.queryForObject("select count(*) from project", Integer.class);
        List<Map<String, Object>> samples = new ArrayList<>();
        try {
            // Một lượt làm nóng riêng; không đưa Spring startup hoặc tạo fixture vào thời gian đo.
            for (int sample = -1; sample < 3; sample++) {
                Fixture fixture = createFixture();
                try {
                    Map<String, Object> cold = measure(fixture, sample, "COLD");
                    Map<String, Object> repeat = measure(fixture, sample, "UNCHANGED_REPEAT");
                    assertThat(repeat.get("outputDigest")).isEqualTo(cold.get("outputDigest"));
                    assertThat(repeat.get("artifactCounts")).isEqualTo(cold.get("artifactCounts"));
                    if (sample >= 0) { samples.add(cold); samples.add(repeat); }
                } finally {
                    jdbc.update("delete from project where id = ? and name = ?", fixture.projectId(), fixture.name());
                    try (var paths = Files.walk(fixture.root())) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                    }
                }
            }
            assertThat(jdbc.queryForObject("select count(*) from project", Integer.class)).isEqualTo(historicalProjects);
            String phase = System.getProperty("benchmark.phase", "before");
            Path output = Path.of("../.greytest-generation-verification/incremental-optimization/benchmark-" + phase + ".json");
            if ("before".equals(phase) && Files.exists(output)) {
                throw new IllegalStateException("Baseline already exists; use -Dbenchmark.phase=after to preserve it.");
            }
            Files.createDirectories(output.toAbsolutePath().getParent());
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("provider", "deterministic local TEST LlmClient; no network/API latency");
            report.put("database", "greytest_incremental_test_20261024");
            report.put("warmupPairs", 1);
            report.put("samplePairs", 3);
            report.put("methods", 6);
            report.put("batchConcurrency", 1);
            report.put("apiMode",System.getProperty("benchmark.api","stages"));
            report.put("timing", "actual BR -> TP -> TC -> UT service calls; excludes startup and fixture setup");
            report.put("parseMetric", "JavaSourceScanner Classified source log events (full scanner invocations, not individual ASTs)");
            report.put("historicalProjectsBeforeAndAfter", historicalProjects);
            report.put("limitations", List.of("Small constant-return fixture; UPDATE proposals only", "No production provider latency, retries, build execution, dependency or concurrent workload", "Digest includes fixture IDs and is comparable within each cold/repeat pair"));
            report.put("samples", samples);
            Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            StringBuilder csv = new StringBuilder("sample,workload,wallMillis,llmCalls,analysisScanEvents,artifacts,outputDigest\n");
            for (var s : samples) csv.append(s.get("sample")).append(',').append(s.get("workload")).append(',')
                    .append(s.get("wallMillis")).append(',').append(s.get("llmCalls")).append(',')
                    .append(s.get("analysisScanEvents")).append(',').append(s.get("artifacts")).append(',')
                    .append(s.get("outputDigest")).append('\n');
            Files.writeString(output.resolveSibling("benchmark-" + phase + ".csv"), csv);
            System.out.println("INCREMENTAL_BENCHMARK " + json.writeValueAsString(report));
        } finally {
            logger.detachAppender(counter);
            counter.stop();
            logger.setLevel(previousScannerLevel);
        }
    }

    private Map<String, Object> measure(Fixture fixture, int sample, String workload) throws Exception {
        int callsBefore = calls.get();
        int parsesBefore = parses.get();
        long started = System.nanoTime();
        Map<String, Double> stageMillis = new LinkedHashMap<>();
        JsonNode measuredMetrics=null;
        if("all".equals(System.getProperty("benchmark.api","stages"))) {
            pipeline.generateAll(fixture.projectId(),fixture.updateId(),null);
        } else for (GenerationProgressStage stage : GenerationProgressStage.values()) {
            long stageStarted = System.nanoTime();
            pipeline.generateIncrementalStage(fixture.projectId(), fixture.updateId(), stage, (AuthUser) null);
            stageMillis.put(stage.name(), (System.nanoTime() - stageStarted) / 1_000_000.0);
        }
        double elapsed = (System.nanoTime() - started) / 1_000_000.0;
        if("all".equals(System.getProperty("benchmark.api","stages"))) {
            measuredMetrics=json.readTree(jdbc.queryForObject("select metrics::text from generation_checkpoint where source_update_id=? and stage='PIPELINE' and target_key='__run__'",String.class,fixture.updateId()));
            for(var stage:GenerationProgressStage.values()) stageMillis.put(stage.name(),measuredMetrics.path("stage."+stage.name()+".wallNanos").asDouble()/1_000_000.0);
            if(workload.equals("UNCHANGED_REPEAT")) {
                assertThat(measuredMetrics.path("checkpoint.skip").asInt()).isEqualTo(24);
                assertThat(measuredMetrics.path("artifact.regenerated").asInt()).isZero();
            }
        }
        var stored = items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(fixture.updateId());
        Map<String, Long> counts = new LinkedHashMap<>();
        for (var item : stored) counts.merge(item.getTargetType().name(), 1L, Long::sum);
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("BUSINESS_RULE", 6L, "TEST_PLAN", 6L, "TEST_CASE", 6L, "UNIT_TEST", 6L));
        assertThat(stored).allSatisfy(item -> {
            assertThat(item.getAfterData()).isNotBlank();
            assertThat(item.getAction().name()).isEqualTo("UPDATE");
        });
        String content = stored.stream().map(item -> item.getTargetType() + ":" + item.getTargetId() + ":" + item.getAfterData())
                .sorted().collect(java.util.stream.Collectors.joining("\n"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sample", sample); result.put("workload", workload); result.put("wallMillis", elapsed);
        if(measuredMetrics!=null) result.put("metrics",measuredMetrics);
        result.put("stageMillis", stageMillis); result.put("llmCalls", calls.get() - callsBefore);
        result.put("analysisScanEvents", parses.get() - parsesBefore); result.put("artifactCounts", counts);
        result.put("artifacts", stored.size());
        result.put("outputDigest", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))));
        return result;
    }

    private Fixture createFixture() throws Exception {
        Path root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "incremental-benchmark-");
        Path base = root.resolve("base");
        Path candidate = root.resolve("candidate");
        writeSource(base, 41); writeSource(candidate, 42);
        String name = "incremental-benchmark-" + UUID.randomUUID();
        Project project = new Project();
        project.setName(name); project.setSourceType(SourceType.ZIP); project.setStoragePath(base.toString());
        project.setStatus(ProjectStatus.UPLOADED);
        project = projects.save(project);
        long projectId = project.getId();
        try {
            var analyzed = analysis.analyze(projectId);
            List<Long> ruleIds = new ArrayList<>(), planIds = new ArrayList<>(), caseIds = new ArrayList<>(), unitIds = new ArrayList<>();
            List<Map<String, Object>> changed = new ArrayList<>();
            for (var method : analyzed.classes().get(0).methods()) {
                String description = "Returns 42 from " + method.methodName() + ".";
                Long ruleId = id("insert into business_rule(project_id,method_id,rule_code,description,source,status,is_modified) values(?,?,?,?,'AI_GENERATED','APPROVED',false) returning id",
                        projectId, method.id(), "BR-" + method.methodName(), description);
                Long planId = id("insert into test_plan(project_id,business_rule_id,plan_code,title,description,test_type,status,is_modified) values(?,?,?,?,?,'HAPPY_PATH','APPROVED',false) returning id",
                        projectId, ruleId, "TP-" + method.methodName(), method.methodName(), description);
                jdbc.update("insert into test_plan_covered_rule(test_plan_id,business_rule_id) values(?,?)", planId, ruleId);
                Long caseId = id("insert into test_case(test_plan_id,case_code,test_type,description,preconditions,test_data,expected_result,priority,trace_source,status,is_modified) values(?,?,'HAPPY_PATH',?,'Create service',cast(? as jsonb),'Returns 42','HIGH',?,'APPROVED',false) returning id",
                        planId, "TC-" + method.methodName(), "Call " + method.methodName(), "{\"input\":{},\"mocks\":{}}", "BR-" + method.methodName() + " -> TP-" + method.methodName());
                Long unitId = id("insert into unit_test(test_case_id,test_class_name,test_method_name,package_name,generation_type,source_code,file_path) values(?, 'BenchmarkServiceTest',?,'benchmark','NEW_TEST',?,?) returning id",
                        caseId, "test" + method.methodName(), testSource(method.methodName()), "src/test/java/benchmark/BenchmarkServiceTest.java");
                ruleIds.add(ruleId); planIds.add(planId); caseIds.add(caseId); unitIds.add(unitId);
                changed.add(Map.of("className", "BenchmarkService", "qualifiedClassName", "benchmark.BenchmarkService",
                        "methodName", method.methodName(), "signature", method.methodName() + "()", "methodKey", "benchmark.BenchmarkService#" + method.methodName() + "()",
                        "diffType", "MODIFIED", "reason", "constant changed", "isServiceMethod", true,
                        "afterSource", "public int " + method.methodName() + "() { return 42; }"));
            }
            assertThat(ruleIds).hasSize(6);
            long baseRevision = id("insert into source_revision(project_id,source_type,storage_path) values(?,'ZIP',?) returning id", projectId, base.toString());
            long candidateRevision = id("insert into source_revision(project_id,source_type,storage_path) values(?,'ZIP',?) returning id", projectId, candidate.toString());
            String impact = json.writeValueAsString(Map.of("totalChangedMethods", 6, "changedMethods", changed,
                    "affectedServiceMethods", changed.stream().map(m -> m.get("methodKey")).toList(),
                    "affectedBusinessRuleIds", ruleIds, "affectedTestPlanIds", planIds, "affectedTestCaseIds", caseIds, "affectedUnitTestIds", unitIds));
            long updateId = id("insert into source_update(project_id,base_revision_id,candidate_revision_id,status,total_changed_methods,impact_summary) values(?,?,?,'ANALYZED',6,cast(? as jsonb)) returning id",
                    projectId, baseRevision, candidateRevision, impact);
            jdbc.update("update project set active_source_revision_id=?, active_source_update_id=? where id=?", baseRevision, updateId, projectId);
            return new Fixture(projectId, updateId, root, name);
        } catch (Exception exception) {
            jdbc.update("delete from project where id = ? and name = ?", projectId, name);
            throw exception;
        }
    }

    private Long id(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    private void writeSource(Path root, int value) throws Exception {
        Path source = root.resolve("src/main/java/benchmark/BenchmarkService.java");
        Files.createDirectories(source.getParent());
        StringBuilder body = new StringBuilder("package benchmark; import org.springframework.stereotype.Service; @Service public class BenchmarkService {\n");
        for (int i = 0; i < 6; i++) body.append(" public int value").append(i).append("() { return ").append(value).append("; }\n");
        Files.writeString(source, body.append("}\n"));
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>benchmark</groupId><artifactId>fixture</artifactId><version>1</version><properties><maven.compiler.release>17</maven.compiler.release></properties><dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.10.0</version><scope>test</scope></dependency></dependencies></project>");
    }

    private String respond(String prompt) throws Exception {
        calls.incrementAndGet();
        CountDownLatch entered = providerEntered;
        if (entered != null) {
            entered.countDown();
            if (!providerRelease.await(20, TimeUnit.SECONDS)) throw new AssertionError("Benchmark owner provider was not released");
        }
        if (prompt.contains("Prompt: unit-test") && failNextUnitTest.compareAndSet(true, false)) {
            throw new com.greytest.service.agent.LlmResponseException("Injected benchmark failure at unit-test provider", false);
        }
        int contextStart = prompt.indexOf("Context:");
        assertThat(contextStart).isGreaterThanOrEqualTo(0);
        // Jackson đọc đúng một JSON object; phần source-contract phía sau không thuộc context.
        JsonNode context;
        try (var parser = json.createParser(prompt.substring(contextStart + "Context:".length()))) {
            context = json.readTree(parser);
        }
        Map<Long, JsonNode> methods = new LinkedHashMap<>();
        for (JsonNode owner : context.path("classes")) for (JsonNode method : owner.path("methods")) methods.put(method.path("id").asLong(), method);
        List<Map<String, Object>> output = new ArrayList<>();
        String field;
        if (prompt.contains("Prompt: business-rule")) {
            field = "rules";
            for (var method : methods.values()) {
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("method_id", method.path("id").asLong());
                rule.put("description", "Returns 42 from " + method.path("methodName").asText() + ".");
                rule.put("category", "BUSINESS_LOGIC");
                rule.put("branch_id", method.path("branches").isEmpty() ? null : method.path("branches").get(0).path("branchId").asText());
                output.add(rule);
            }
        } else if (prompt.contains("Prompt: test-plan")) {
            field = "plans";
            for (JsonNode rule : context.path("approvedBusinessRules")) output.add(Map.of("method_id", rule.path("methodId").asLong(),
                    "rule_id", rule.path("id").asLong(), "covered_rule_ids", List.of(rule.path("id").asLong()),
                    "title", "Verify " + methods.get(rule.path("methodId").asLong()).path("methodName").asText(), "description", rule.path("description").asText(), "test_type", "HAPPY_PATH"));
        } else if (prompt.contains("Prompt: test-case")) {
            field = "cases";
            for (JsonNode plan : context.path("approvedTestPlans")) output.add(Map.of("plan_id", plan.path("id").asLong(),
                    "test_type", "HAPPY_PATH", "description", plan.path("title").asText(), "preconditions", "Create service",
                    "test_data", Map.of("input", Map.of(), "mocks", Map.of()), "expected_result", "Returns 42", "priority", "HIGH",
                    "trace_source", plan.path("planCode").asText()));
        } else if (prompt.contains("Prompt: unit-test")) {
            field = "unit_tests";
            for (JsonNode testCase : context.path("approvedTestCases")) {
                JsonNode plan = find(context.path("approvedTestPlans"), testCase.path("testPlanId").asLong());
                JsonNode rule = find(context.path("approvedBusinessRules"), plan.path("businessRuleId").asLong());
                String method = methods.get(rule.path("methodId").asLong()).path("methodName").asText();
                output.add(Map.of("case_id", testCase.path("id").asLong(), "test_class_name", "BenchmarkServiceTest",
                        "test_method_name", "test" + method, "package_name", "benchmark", "generation_type", "IMPROVE_EXISTING_TEST", "source_code", testSource(method)));
            }
        } else throw new AssertionError("Unknown benchmark prompt");
        assertThat(output).isNotEmpty();
        return json.writeValueAsString(Map.of(field, output));
    }

    private JsonNode find(JsonNode nodes, long id) {
        for (JsonNode node : nodes) if (node.path("id").asLong() == id) return node;
        throw new AssertionError("Missing benchmark context ID " + id);
    }

    private String testSource(String method) {
        return "package benchmark;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n"
                + "class BenchmarkServiceTest { @Test void test" + method + "() { BenchmarkService service = new BenchmarkService(); assertEquals(42, service." + method + "()); } }\n";
    }

    private record Fixture(long projectId, long updateId, Path root, String name) { }
}
