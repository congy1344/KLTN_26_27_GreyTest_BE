package com.greytest.service;

import static org.assertj.core.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

/** Profile opt-in trên DB test riêng, tái sử dụng fixture/provider và semantic gates của regression suite. */
@SpringBootTest(properties = {
        "greytest.auth.token-secret=test-only-token-secret-at-least-32-bytes",
        "greytest.llm-batch.concurrency=2", "greytest.llm-batch.retry-attempts=0",
        "logging.level.root=WARN", "logging.level.com.greytest=WARN", "logging.level.org.hibernate.SQL=OFF",
        "logging.level.org.hibernate.orm.jdbc.bind=OFF", "spring.jpa.show-sql=false", "debug=false",
        "logging.level.org.springframework=WARN", "logging.level.org.springframework.jdbc.core=OFF"
})
@Import(CriticalPathProfiler.Configuration.class)
@EnabledIfSystemProperty(named = "e2e.profile", matches = "true")
class EndToEndCriticalPathProfileTest extends IncrementalPipelineBenchmarkTest {
    boolean dense;

    @Override String respond(String prompt) throws Exception {
        if (Boolean.parseBoolean(System.getProperty("benchmark.instrumentation", "true")))
            return CriticalPathProfiler.unchecked("provider.local", () -> respondFixture(prompt));
        return respondFixture(prompt);
    }

    private String respondFixture(String prompt) throws Exception {
            GenerationJobContext.count("profile.promptChars", prompt.length());
            String response = super.respond(prompt);
            if (dense && prompt.contains("Prompt: business-rule")) {
                var root = (ObjectNode) json.readTree(response);
                var rules = root.withArray("rules");
                var first = rules.get(0).deepCopy();
                for (int i = 2; i <= 3; i++) {
                    var rule = (ObjectNode) first.deepCopy();
                    rule.put("branch_id", "STMT-" + i);
                    rule.put("description", "Returns 42; executable statement " + i + ".");
                    rules.add(rule);
                }
                response = json.writeValueAsString(root);
            }
            if (dense && prompt.contains("Prompt: unit-test")) {
                var root = (ObjectNode) json.readTree(response);
                for (JsonNode unit : root.path("unit_tests")) {
                    var node = (ObjectNode) unit;
                    String old = node.path("test_method_name").asText();
                    String unique = old + "Case" + node.path("case_id").asLong();
                    node.put("test_method_name", unique);
                    node.put("source_code", node.path("source_code").asText().replace(old + "()", unique + "()"));
                }
                response = json.writeValueAsString(root);
            }
            GenerationJobContext.count("profile.responseChars", response.length());
            return response;
    }

    @Test void profileCriticalPath() throws Throwable {
        assertThat(System.getenv("LLM_PROVIDER")).isEqualTo("mock");
        int projectsBefore = jdbc.queryForObject("select count(*) from project", Integer.class);
        List<Map<String, Object>> samples = new ArrayList<>();
        Path output = Path.of(System.getProperty("e2e.outputDirectory", "target/phase3"));
        Files.createDirectories(output);
        int count = Integer.getInteger("e2e.samples", 10);
        assertThat(count).isPositive();
        try (var recording = new Recording()) {
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(1));
            recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(1));
            recording.enable("jdk.SocketRead").withThreshold(Duration.ofMillis(1));
            recording.enable("jdk.FileRead").withThreshold(Duration.ofMillis(1));
            recording.start();
            for (String workload : List.of("ONE_ADDED", "SIX_INDEPENDENT", "DENSE_ONE", "ONE_ADDED_128_UNRELATED", "RESUME")) {
                for (int sample = -1; sample < count; sample++) {
                    Fixture fixture = createFixture();
                    try {
                        prepare(fixture, workload);
                        dense = workload.equals("DENSE_ONE");
                        if (workload.equals("RESUME")) failNextUnitTest.set(true);
                        Map<String, Object> cold = measureProfile(fixture, workload, true, workload.equals("RESUME"));
                        Map<String, Object> repeat = measureProfile(fixture, workload.equals("RESUME") ? "RESUME_RETRY" : workload + "_WARM", false, false);
                        if (!workload.equals("RESUME")) {
                            assertThat(repeat.get("llmCalls")).isEqualTo(0);
                            assertThat(repeat.get("outputDigest")).isEqualTo(cold.get("outputDigest"));
                            assertThat(repeat.get("provenanceDigest")).isEqualTo(cold.get("provenanceDigest"));
                        } else {
                            assertThat(((Number) repeat.get("llmCalls")).intValue()).isPositive();
                        }
                        if (sample >= 0) {
                            cold.put("sample", sample); repeat.put("sample", sample);
                            samples.add(cold); samples.add(repeat);
                        }
                    } finally { dense = false; cleanupFixture(fixture); }
                }
            }
            recording.stop();
            recording.dump(output.resolve("critical-path.jfr"));
        }
        assertThat(jdbc.queryForObject("select count(*) from project", Integer.class)).isEqualTo(projectsBefore);
        attachJfr(output.resolve("critical-path.jfr"), samples);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("samples.json").toFile(), Map.of(
                "provider", "deterministic local mock; zero network latency", "batchConcurrency", 2,
                "warmupPerWorkload", 1, "samplesPerWorkload", count, "samples", samples));
    }

    static void attachJfr(Path recordingPath, List<Map<String, Object>> samples) throws Exception {
        // JFR chứa cả setup/cleanup; chỉ tổng hợp event có thread và interval khớp run đo.
        try (var events = new RecordingFile(recordingPath)) {
            while (events.hasMoreEvents()) {
                var event = events.readEvent();
                String eventName = event.getEventType().getName();
                if (eventName.equals("jdk.GCPhasePause") || eventName.equals("jdk.GarbageCollection")) {
                    long startNanos = event.getStartTime().getEpochSecond()*1_000_000_000L + event.getStartTime().getNano();
                    long endNanos = startNanos + event.getDuration().toNanos();
                    for (var run : samples) {
                        long overlap = Math.min(endNanos,(long)run.get("epochEndMillis")*1_000_000L)
                                - Math.max(startNanos,(long)run.get("epochStartMillis")*1_000_000L);
                        if (overlap <= 0) continue;
                        @SuppressWarnings("unchecked") var gc = (List<Map<String,Object>>)run.computeIfAbsent("gcEvents",ignored->new ArrayList<>());
                        gc.add(Map.of("event",eventName,"epochStartNanos",startNanos,"durationNanos",event.getDuration().toNanos(),"overlapNanos",overlap));
                    }
                    continue; // GC là process-wide; không loại event vì không có application event thread.
                }
                var eventThread = event.getEventType().getName().equals("jdk.ExecutionSample")
                        ? event.getThread("sampledThread") : event.getThread();
                if (eventThread == null) continue;
                long thread = eventThread.getJavaThreadId();
                long start = event.getStartTime().toEpochMilli(), end = event.getEndTime().toEpochMilli();
                for (var run : samples) {
                    if (start < (long) run.get("epochStartMillis") || end > (long) run.get("epochEndMillis")) continue;
                    @SuppressWarnings("unchecked") var threads = (List<Long>) run.get("threads");
                    if (!threads.contains(thread)) continue;
                    @SuppressWarnings("unchecked") var jfr = (Map<String, Object>) run.get("jfr");
                    String name = event.getEventType().getName();
                    String stack = event.getStackTrace() == null ? "no-stack" : event.getStackTrace().getFrames().stream().limit(8)
                            .map(frame -> frame.getMethod().getType().getName() + "." + frame.getMethod().getName())
                            .collect(java.util.stream.Collectors.joining(" <- "));
                    String key = name + ":" + stack;
                    @SuppressWarnings("unchecked") var stats = (Map<String, Object>) jfr.computeIfAbsent(key, ignored -> new LinkedHashMap<>(Map.of("count", 0L, "durationNanos", 0L)));
                    stats.put("count", (long) stats.get("count") + 1);
                    stats.put("durationNanos", (long) stats.get("durationNanos") + event.getDuration().toNanos());
                }
            }
        }
    }

    void prepare(Fixture fixture, String workload) throws Exception {
        Path candidate = fixture.root().resolve("candidate");
        Path file = candidate.resolve("src/main/java/benchmark/BenchmarkService.java");
        if (workload.startsWith("ONE_ADDED")) {
            writeSource(candidate, 41);
            String source = Files.readString(file);
            Files.writeString(file, source.substring(0, source.lastIndexOf('}')) + " public int added() { return 42; }\n}\n");
        } else if (workload.equals("DENSE_ONE")) {
            writeSource(candidate, 41);
            Files.writeString(file, Files.readString(file).replace("public int value0() { return 41; }",
                    "public int value0() { int value = 40; value += 2; return value; }"));
        }
        if (workload.endsWith("128_UNRELATED")) for (int i = 0; i < 128; i++) {
            String name = "Supporting" + i + ".java", source = "package benchmark; class Supporting" + i + " { private String value; }";
            Files.writeString(file.resolveSibling(name), source);
            Files.writeString(fixture.root().resolve("base/src/main/java/benchmark").resolve(name), source);
        }
    }

    Map<String, Object> measureProfile(Fixture fixture, String workload, boolean analyze, boolean expectFailure) throws Throwable {
        var run = new CriticalPathProfiler.Run(workload, fixture.projectId(), fixture.updateId());
        Map<String, Object> result = new LinkedHashMap<>();
        int before = calls.get();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpu = os.getProcessCpuTime();
        long[] gcBefore = gcCounters();
        result.put("epochStartMillis", System.currentTimeMillis());
        long started = System.nanoTime();
        try (var scope = GenerationJobContext.openScope(null)) {
            CriticalPathProfiler.Call<Void> measured = () -> {
                if (analyze) result.put("changedMethods", sourceUpdates.analyzeUpdate(fixture.projectId(), fixture.updateId(), null).totalChangedMethods());
                if (expectFailure) assertThatThrownBy(() -> pipeline.generateAll(fixture.projectId(), fixture.updateId(), null))
                        .hasStackTraceContaining("Injected benchmark failure");
                else pipeline.generateAll(fixture.projectId(), fixture.updateId(), null);
                return null;
            };
            if (Boolean.parseBoolean(System.getProperty("benchmark.instrumentation", "true")))
                CriticalPathProfiler.bind(run, () -> CriticalPathProfiler.observe("e2e", measured));
            else measured.get();
            result.put("wallMillis", (System.nanoTime() - started) / 1_000_000.0);
            result.put("processCpuMillis", (os.getProcessCpuTime() - cpu) / 1_000_000.0);
            long[] gcAfter = gcCounters();
            result.put("gcCountDelta",gcBefore[0]<0 || gcAfter[0]<0 ? null : gcAfter[0]-gcBefore[0]);
            result.put("gcMillisDelta",gcBefore[1]<0 || gcAfter[1]<0 ? null : gcAfter[1]-gcBefore[1]);
            result.put("epochEndMillis", System.currentTimeMillis());
            result.put("metrics", GenerationJobContext.metrics());
        }
        result.put("runId", run.id); result.put("projectId", run.projectId); result.put("updateId", run.updateId);
        result.put("workload", workload); result.put("llmCalls", calls.get() - before);
        var artifacts = items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(fixture.updateId());
        result.put("artifactCounts", artifacts.stream().collect(java.util.stream.Collectors.groupingBy(item -> item.getTargetType().name(), java.util.stream.Collectors.counting())));
        result.put("proposalActions", artifacts.stream().collect(java.util.stream.Collectors.groupingBy(item -> item.getTargetType().name() + "." + item.getAction().name(), java.util.stream.Collectors.counting())));
        String content = artifacts.stream().map(item -> item.getTargetType() + ":" + item.getTargetId() + ":" + item.getAfterData()).sorted().collect(java.util.stream.Collectors.joining("\n"));
        result.put("outputDigest", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        String provenance = jdbc.queryForList("select stage,target_key,fingerprint,provenance::text,output_digest,(output_json::jsonb -> 'outputProvenance')::text as output_provenance from generation_checkpoint where source_update_id=? and fingerprint is not null order by stage,target_key", fixture.updateId()).toString();
        result.put("provenanceDigest", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(provenance.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        result.put("methodIds", jdbc.queryForList("select jm.id,jm.method_name from java_method jm join java_class jc on jc.id=jm.class_id where jc.project_id=? order by jm.id", fixture.projectId()));
        result.put("targetMethods", jdbc.queryForList("""
                select 'BUSINESS_RULE' as stage,m.id as target_id,m.id as method_id from java_method m join java_class c on c.id=m.class_id where c.project_id=?
                union all select 'TEST_PLAN',b.id,b.method_id from business_rule b where b.project_id=?
                union all select 'TEST_CASE',p.id,b.method_id from test_plan p join business_rule b on b.id=p.business_rule_id where p.project_id=?
                union all select 'UNIT_TEST',c.id,b.method_id from test_case c join test_plan p on p.id=c.test_plan_id join business_rule b on b.id=p.business_rule_id where p.project_id=?
                order by stage,target_id
                """, fixture.projectId(), fixture.projectId(), fixture.projectId(), fixture.projectId()));
        @SuppressWarnings("unchecked") var targetMethods = (List<Map<String, Object>>) result.get("targetMethods");
        var targetKeys = targetMethods.stream().map(row -> row.get("stage") + ":" + row.get("target_id")).collect(java.util.stream.Collectors.toSet());
        for (var span : run.spans()) if (span.name().equals("batch.worker"))
            for (JsonNode id : json.readTree(span.targets())) assertThat(targetKeys).contains(span.stage() + ":" + id.asLong());
        List<Map<String, Object>> links = new ArrayList<>();
        for (var artifact : artifacts) {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("targetType", artifact.getTargetType()); link.put("targetId", artifact.getTargetId()); link.put("targetKey", artifact.getTargetKey()); link.put("action", artifact.getAction());
            if (artifact.getAfterData() != null && artifact.getAfterData().startsWith("{")) {
                JsonNode data = json.readTree(artifact.getAfterData());
                for (String key : List.of("id", "method_id", "rule_id", "plan_id", "case_id", "methodId", "businessRuleId", "testPlanId", "testCaseId"))
                    if (data.has(key)) link.put(key, data.get(key));
            }
            links.add(link);
        }
        result.put("artifactLinks", links);
        result.put("threads", run.spans().stream().map(CriticalPathProfiler.Span::thread).distinct().toList());
        result.put("jfr", new LinkedHashMap<String, Object>());
        result.put("spans", run.spans());
        if (TailLatencyObserver.enabled()) {
            result.put("databaseSamples",List.copyOf(run.databaseSamples));
            result.put("poolSamples",List.copyOf(run.poolSamples));
            result.put("observerErrors",List.copyOf(run.observerErrors));
        }
        return result;
    }

    private static long[] gcCounters() {
        long count=0,time=0;
        for(var collector:ManagementFactory.getGarbageCollectorMXBeans()) {
            long c=collector.getCollectionCount(),t=collector.getCollectionTime();
            if(c<0 || t<0) return new long[]{-1,-1};
            count+=c; time+=t;
        }
        return new long[]{count,time};
    }
}
