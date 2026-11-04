package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import jdk.jfr.Recording;

/** Đo pipeline hiện tại và payload tại biên mock; không sửa prompt hoặc mô phỏng latency provider thật. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "e2e.profile", matches = "true")
class LlmCriticalPathProfileTest extends PairedSourceUpdateBenchmarkTest {
    @Override String respond(String prompt) throws Exception {
        if (!Boolean.parseBoolean(System.getProperty("benchmark.instrumentation", "true"))) return super.respond(prompt);
        String stage = prompt.lines().filter(line -> line.startsWith("# Prompt: "))
                .findFirst().orElseThrow().substring("# Prompt: ".length()).strip();
        assertThat(stage).isIn("business-rule", "test-plan", "test-case", "unit-test");
        String metric = "profile." + stage + ".";
        GenerationJobContext.increment(metric + "calls");
        GenerationJobContext.count(metric + "promptChars", prompt.length());
        try (var parser = json.createParser(prompt.substring(prompt.indexOf("Context:") + "Context:".length()))) {
            JsonNode context = json.readTree(parser);
            GenerationJobContext.count(metric + "contextChars", context.toString().length());
            context.fields().forEachRemaining(field -> GenerationJobContext.count(
                    metric + "contextField." + field.getKey() + "Chars", field.getValue().toString().length()));
            Map<String, Integer> sources = new HashMap<>();
            collectSources(context, sources);
            long repeated = sources.entrySet().stream().mapToLong(e -> (long) e.getKey().length() * (e.getValue() - 1)).sum();
            GenerationJobContext.count(metric + "repeatedSourceValueChars", repeated);
        }
        try {
            String response = super.respond(prompt);
            GenerationJobContext.count(metric + "responseChars", response.length());
            return response;
        } catch (Exception failure) {
            GenerationJobContext.increment(metric + "failedCalls");
            throw failure;
        }
    }

    private static void collectSources(JsonNode node, Map<String, Integer> sources) {
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            JsonNode value = field.getValue();
            if (field.getKey().toLowerCase(java.util.Locale.ROOT).contains("source")
                    && value.isTextual() && value.asText().length() >= 32) sources.merge(value.asText(), 1, Integer::sum);
            else collectSources(value, sources);
        });
        else if (node.isArray()) node.forEach(value -> collectSources(value, sources));
    }

    @Override @Test void profileCriticalPath() throws Throwable {
        assertThat(System.getenv("LLM_PROVIDER")).isEqualTo("mock");
        assertThat(Boolean.parseBoolean(System.getProperty("greytest.source-update.lombok-directory-reuse-enabled", "true"))).isTrue();
        boolean instrumented = Boolean.parseBoolean(System.getProperty("benchmark.instrumentation", "true"));
        Path output = Path.of(System.getProperty("e2e.outputDirectory", "target/phase3-4-profile"));
        Files.createDirectories(output);
        int count = Integer.getInteger("e2e.samples", 6);
        assertThat(count).isPositive();
        int projectsBefore = jdbc.queryForObject("select count(*) from project", Integer.class);
        List<Map<String, Object>> samples = new ArrayList<>(), quality = new ArrayList<>();
        try (Recording recording = instrumented ? new Recording() : null) {
            if (recording != null) {
                recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(10));
                recording.enable("jdk.JavaMonitorEnter").withThreshold(java.time.Duration.ofMillis(1));
                recording.enable("jdk.ThreadPark").withThreshold(java.time.Duration.ofMillis(1));
                recording.start();
            }
            for (String workload : List.of("ONE_ADDED", "SIX_INDEPENDENT", "DENSE_ONE", "ONE_ADDED_128_UNRELATED", "RESUME")) {
                for (int sample = -1; sample < count; sample++) {
                    Fixture fixture = createFixture();
                    try {
                        prepare(fixture, workload);
                        dense = workload.equals("DENSE_ONE");
                        if (workload.equals("RESUME")) failNextUnitTest.set(true);
                        var cold = measureProfile(fixture, workload, true, workload.equals("RESUME"));
                        var repeat = measureProfile(fixture, workload.equals("RESUME") ? "RESUME_RETRY" : workload + "_WARM", false, false);
                        List<Map<String, Object>> windows = new ArrayList<>(List.of(cold, repeat));
                        if (workload.equals("RESUME")) {
                            assertThat(((Number) repeat.get("llmCalls")).intValue()).isPositive();
                            var warm = measureProfile(fixture, "RESUME_WARM", false, false);
                            assertReusable(repeat, warm);
                            windows.add(warm);
                        } else assertReusable(cold, repeat);
                        if (instrumented) for (var window : windows) {
                            @SuppressWarnings("unchecked") var metrics = (Map<String, Number>) window.get("metrics");
                            long callsByStage = List.of("business-rule", "test-plan", "test-case", "unit-test").stream()
                                    .mapToLong(stage -> metrics.getOrDefault("profile." + stage + ".calls", 0).longValue()).sum();
                            assertThat(callsByStage).isEqualTo(((Number) window.get("llmCalls")).longValue());
                        }
                        if (sample == -1) quality.add(Map.of("workload", workload, "generatedUnitTestsExecuted", executeGeneratedTests(fixture)));
                        else for (var window : windows) { window.put("sample", sample); samples.add(window); }
                    } finally { dense = false; failNextUnitTest.set(false); cleanupFixture(fixture); }
                }
            }
            if (recording != null) { recording.stop(); recording.dump(output.resolve("critical-path.jfr")); attachJfr(output.resolve("critical-path.jfr"), samples); }
        }
        assertThat(jdbc.queryForObject("select count(*) from project", Integer.class)).isEqualTo(projectsBefore);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("provider", "deterministic local mock; no external API calls");
        evidence.put("instrumented", instrumented); evidence.put("samplesPerWorkload", count);
        evidence.put("inputTokens", null); evidence.put("outputTokens", null);
        evidence.put("providerQueueMillis", null); evidence.put("providerNetworkMillis", null);
        evidence.put("qualityRuns", quality); evidence.put("samples", samples);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("samples.json").toFile(), evidence);
    }

    private static void assertReusable(Map<String, Object> previous, Map<String, Object> warm) {
        assertThat(warm.get("llmCalls")).isEqualTo(0);
        assertThat(warm.get("outputDigest")).isEqualTo(previous.get("outputDigest"));
        assertThat(warm.get("provenanceDigest")).isEqualTo(previous.get("provenanceDigest"));
    }
}
