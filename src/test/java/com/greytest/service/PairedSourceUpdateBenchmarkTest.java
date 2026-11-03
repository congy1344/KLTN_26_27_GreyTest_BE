package com.greytest.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.net.URL;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jdk.jfr.Recording;

/** A/B c?ng fixture logic/provider; setup, semantic gates v? ch?y UT n?m ngo?i interval ?o. */
@org.springframework.test.context.TestPropertySource(properties = {
        "greytest.source-update.jdbc-read-optimization-enabled=true"
})
@EnabledIfSystemProperty(named = "e2e.profile", matches = "true")
class PairedSourceUpdateBenchmarkTest extends EndToEndCriticalPathProfileTest {
    private static final String JDBC_READ = "greytest.source-update.jdbc-read-optimization-enabled";
    private String variantFlag = JDBC_READ;

    @Override @Test void profileCriticalPath() throws Throwable {
        String task = System.getProperty("benchmark.optimization", "B");
        assertThat(task).isIn("B", "CONFIG_DIRECTORY");
        variantFlag = task.equals("B") ? JDBC_READ : "greytest.source-update.lombok-directory-reuse-enabled";
        boolean instrumented = Boolean.parseBoolean(System.getProperty("benchmark.instrumentation", "true"));
        boolean jfr = Boolean.parseBoolean(System.getProperty("benchmark.jfr", "true"));
        assertThat(instrumented || !jfr).as("Uninstrumented control also requires -Dbenchmark.jfr=false").isTrue();
        int count = Integer.getInteger("e2e.samples", 10);
        assertThat(count).isPositive().isEven();
        Path output = Path.of(task.equals("B") ? "target/phase3-2-b" : "target/phase3-3-config-directory");
        output = Path.of(output + (instrumented ? "-profile" : "-control"));
        output = Path.of(System.getProperty("e2e.outputDirectory", output.toString()));
        Files.createDirectories(output);
        int projectsBefore = jdbc.queryForObject("select count(*) from project", Integer.class);
        List<Map<String, Object>> samples = new ArrayList<>();
        List<Map<String, Object>> qualityRuns = new ArrayList<>();
        try (Recording recording = jfr ? new Recording() : null) {
            if (recording != null) {
                recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(10));
                recording.enable("jdk.JavaMonitorEnter").withThreshold(java.time.Duration.ofMillis(1));
                recording.enable("jdk.ThreadPark").withThreshold(java.time.Duration.ofMillis(1));
                recording.enable("jdk.SocketRead").withThreshold(java.time.Duration.ofMillis(1));
                recording.enable("jdk.FileRead").withThreshold(java.time.Duration.ofMillis(1));
                recording.start();
            }
            List<String> workloads = List.of("ONE_ADDED", "SIX_INDEPENDENT", "DENSE_ONE", "ONE_ADDED_128_UNRELATED", "RESUME");
            String selected = System.getProperty("benchmark.workload", "");
            if (!selected.isBlank()) {
                assertThat(selected).isIn(workloads);
                workloads = List.of(selected);
            }
            for (String workload : workloads) {
                List<Boolean> orders = new ArrayList<>();
                for (int i = 0; i < count; i++) orders.add(i % 2 == 0);
                Collections.shuffle(orders, new Random(32026L + workload.hashCode()));
                for (int pair = -1; pair < count; pair++) {
                    boolean firstOptimized = pair >= 0 && orders.get(pair);
                    Map<String, JsonNode> correctness = new HashMap<>();
                    for (int order = 0; order < 2; order++) {
                        boolean optimized = order == 0 ? firstOptimized : !firstOptimized;
                        String variant = optimized ? "optimized" : "legacy";
                        Fixture fixture = createFixture();
                        try {
                            prepare(fixture, workload);
                            dense = workload.equals("DENSE_ONE");
                            if (workload.equals("RESUME")) failNextUnitTest.set(true);
                            Map<String, Object> cold = variantMeasure(fixture, workload, true, workload.equals("RESUME"), optimized);
                            Map<String, Object> repeat = variantMeasure(fixture, workload.equals("RESUME") ? "RESUME_RETRY" : workload + "_WARM", false, false, optimized);
                            if (!workload.equals("RESUME")) {
                                assertThat(repeat.get("llmCalls")).isEqualTo(0);
                                assertThat(repeat.get("outputDigest")).isEqualTo(cold.get("outputDigest"));
                                assertThat(repeat.get("provenanceDigest")).isEqualTo(cold.get("provenanceDigest"));
                            } else {
                                assertThat(((Number) repeat.get("llmCalls")).intValue()).isPositive();
                                var settled = variantMeasure(fixture, "RESUME_WARM", false, false, optimized);
                                assertThat(settled.get("llmCalls")).isEqualTo(0);
                                assertThat(settled.get("outputDigest")).isEqualTo(repeat.get("outputDigest"));
                                assertThat(settled.get("provenanceDigest")).isEqualTo(repeat.get("provenanceDigest"));
                                if (pair >= 0) { tag(settled, pair, order, variant); samples.add(settled); }
                            }
                            JsonNode semantic = semanticEvidence(fixture);
                            correctness.put(variant, semantic);
                            cold.put("semanticEvidence", semantic);
                            if (pair == -1) qualityRuns.add(Map.of("workload", workload, "variant", variant,
                                    "generatedUnitTestsExecuted", executeGeneratedTests(fixture)));
                            if (pair >= 0) {
                                tag(cold, pair, order, variant); tag(repeat, pair, order, variant);
                                samples.add(cold); samples.add(repeat);
                            }
                        } finally { dense = false; failNextUnitTest.set(false); cleanupFixture(fixture); }
                    }
                    assertThat(correctness.get("optimized")).as("Semantic A/B equality: %s pair %s", workload, pair)
                            .isEqualTo(correctness.get("legacy"));
                }
            }
            if (recording != null) { recording.stop(); recording.dump(output.resolve("critical-path.jfr")); attachJfr(output.resolve("critical-path.jfr"), samples); }
        }
        assertThat(jdbc.queryForObject("select count(*) from project", Integer.class)).isEqualTo(projectsBefore);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("samples.json").toFile(), Map.of(
                "optimization", task, "instrumented", instrumented, "jfr", jfr, "seed", 32026,
                "provider", "deterministic local mock; zero network latency", "batchConcurrency", 2,
                "warmupPairsPerWorkload", 1, "samplePairsPerWorkload", count, "qualityRuns", qualityRuns, "samples", samples));
    }

    private void tag(Map<String, Object> sample, int pair, int order, String variant) {
        sample.put("pair", pair); sample.put("order", order); sample.put("variant", variant);
    }

    private Map<String, Object> variantMeasure(Fixture fixture, String workload, boolean analyze, boolean failure,
            boolean optimized) {
        try (var scope = GenerationJobContext.openScope(null)) {
            // Phase 3.3 giữ B1+B2 ở cả hai nhánh; chỉ đổi flag đang được đo.
            return GenerationJobContext.withValue(JDBC_READ, true, () -> GenerationJobContext.withValue(variantFlag, optimized, () -> {
                        try { return measureProfile(fixture, workload, analyze, failure); }
                        catch (Throwable error) { return rethrow(error); }
                    }));
        }
    }

    @SuppressWarnings("unchecked") private static <T, E extends Throwable> T rethrow(Throwable error) throws E { throw (E) error; }

    private JsonNode semanticEvidence(Fixture fixture) throws Exception {
        Map<String, Map<String, String>> identities = new HashMap<>();
        for (String type : List.of("METHOD", "BUSINESS_RULE", "TEST_PLAN", "TEST_CASE", "UNIT_TEST")) identities.put(type, new HashMap<>());
        var methods = jdbc.queryForList("select m.id,m.method_name from java_method m join java_class c on c.id=m.class_id where c.project_id=?", fixture.projectId());
        for (var method : methods) identities.get("METHOD").put(method.get("id").toString(), "benchmark.BenchmarkService#" + method.get("method_name") + "()");
        Map<String, String> labels = new HashMap<>();
        for (String type : List.of("BUSINESS_RULE", "TEST_PLAN", "TEST_CASE", "UNIT_TEST")) {
            String sql = switch (type) {
                case "BUSINESS_RULE" -> "select b.id,b.method_id parent,b.rule_code label from business_rule b where b.project_id=? order by b.id";
                case "TEST_PLAN" -> "select p.id,p.business_rule_id parent,p.plan_code label from test_plan p where p.project_id=? order by p.id";
                case "TEST_CASE" -> "select c.id,c.test_plan_id parent,c.case_code label from test_case c join test_plan p on p.id=c.test_plan_id where p.project_id=? order by c.id";
                default -> "select u.id,u.test_case_id parent,u.test_method_name label from unit_test u join test_case c on c.id=u.test_case_id join test_plan p on p.id=c.test_plan_id where p.project_id=? order by u.id";
            };
            String parent = switch (type) { case "BUSINESS_RULE" -> "METHOD"; case "TEST_PLAN" -> "BUSINESS_RULE"; case "TEST_CASE" -> "TEST_PLAN"; default -> "TEST_CASE"; };
            Map<String, Integer> ordinals = new HashMap<>();
            for (var row : jdbc.queryForList(sql, fixture.projectId())) {
                String owner = Objects.requireNonNull(identities.get(parent).get(row.get("parent").toString()));
                String identity = owner + "/" + type + ":" + ordinals.merge(owner, 1, Integer::sum);
                identities.get(type).put(row.get("id").toString(), identity);
                if (!type.equals("UNIT_TEST")) labels.put(row.get("label").toString(), identity);
            }
        }
        ObjectNode evidence = json.createObjectNode();
        JsonNode impact = json.readTree(jdbc.queryForObject("select impact_summary::text from source_update where id=?", String.class, fixture.updateId()));
        evidence.set("impact", canonical(impact, "", identities, labels));
        ArrayNode artifacts = evidence.putArray("artifacts");
        for (var item : items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(fixture.updateId())) {
            ObjectNode artifact = json.createObjectNode();
            String type = item.getTargetType().name();
            artifact.put("type", type); artifact.put("action", item.getAction().name());
            String targetKey = normalizeTargetKey(item.getTargetKey(), identities);
            artifact.put("identity", item.getTargetId() == null ? targetKey : identities.get(type).get(item.getTargetId().toString()));
            artifact.put("targetKey", targetKey);
            String after = item.getAfterData();
            artifact.set("data", canonical(after == null ? NullNode.instance : after.stripLeading().startsWith("{") ? json.readTree(after) : TextNode.valueOf(after), type, identities, labels));
            artifacts.add(artifact);
        }
        ArrayNode checkpoints = evidence.putArray("checkpoints");
        for (var row : jdbc.queryForList("select stage,target_key,provenance::text,output_json from generation_checkpoint where source_update_id=? and fingerprint is not null", fixture.updateId())) {
            String stage = row.get("stage").toString();
            String type = switch (stage) { case "BUSINESS_RULE" -> "METHOD"; case "TEST_PLAN" -> "BUSINESS_RULE"; case "TEST_CASE" -> "TEST_PLAN"; default -> "TEST_CASE"; };
            ObjectNode checkpoint = json.createObjectNode(); checkpoint.put("stage", stage);
            checkpoint.put("target", identities.get(type).getOrDefault(row.get("target_key").toString(), row.get("target_key").toString()));
            checkpoint.set("input", canonical(json.readTree(row.get("provenance").toString()), "", identities, labels));
            JsonNode envelope = json.readTree(row.get("output_json").toString());
            JsonNode output = envelope.path("outputProvenance");
            checkpoint.set("output", canonical(output.isTextual() ? json.readTree(output.asText()) : output, "", identities, labels));
            checkpoints.add(checkpoint);
        }
        evidence.set("artifacts", sorted(artifacts)); evidence.set("checkpoints", sorted(checkpoints));
        assertThat(jdbc.queryForList("select target_type, target_id, target_key from source_update_item where source_update_id=? group by target_type,target_id,target_key having count(*)>1", fixture.updateId())).isEmpty();
        return evidence;
    }

    static String normalizeTargetKey(String key, Map<String, Map<String, String>> ids) {
        if (key == null) return null;
        var matcher = java.util.regex.Pattern.compile("^(plan|case):([0-9]+)").matcher(key);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(normalized, java.util.regex.Matcher.quoteReplacement(matcher.group(1) + ":" + Objects.requireNonNull(ids.get(matcher.group(1).equals("plan") ? "TEST_PLAN" : "TEST_CASE").get(matcher.group(2)))));
        matcher.appendTail(normalized); return normalized.toString();
    }

    // Ch? ??i kh?a k? thu?t c?a fixture; gi? nguy?n m? t?, expected result v? source nghi?p v?.
    static JsonNode canonical(JsonNode node, String type, Map<String, Map<String, String>> ids, Map<String, String> labels) {
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            String own = node.has("methodName") ? "METHOD" : node.has("ruleCode") ? "BUSINESS_RULE" : node.has("planCode") ? "TEST_PLAN" : node.has("caseCode") ? "TEST_CASE" : type;
            node.fields().forEachRemaining(field -> {
                String key = field.getKey(); JsonNode value = field.getValue();
                String target = switch (key) {
                    case "method_id", "methodId", "relatedMethodId", "callerMethodId" -> "METHOD";
                    case "rule_id", "ruleId", "businessRuleId", "covered_rule_ids", "coveredRuleIds", "affectedBusinessRuleIds" -> "BUSINESS_RULE";
                    case "plan_id", "planId", "testPlanId", "affectedTestPlanIds" -> "TEST_PLAN";
                    case "case_id", "caseId", "testCaseId", "affectedTestCaseIds" -> "TEST_CASE";
                    case "affectedUnitTestIds" -> "UNIT_TEST";
                    case "id" -> own;
                    default -> "";
                };
                if (key.equals("project") && value.isNumber()) result.put(key, "fixture-project");
                else if (!target.isEmpty() && value.isNumber()) result.put(key, Objects.requireNonNull(ids.get(target).get(value.asText()), "Unknown " + target + " " + value));
                else if (!target.isEmpty() && value.isArray()) {
                    ArrayNode array = result.putArray(key);
                    value.forEach(id -> array.add(Objects.requireNonNull(ids.get(target).get(id.asText()))));
                    result.set(key, sorted(array));
                } else if (List.of("ruleCode", "rule_code", "planCode", "plan_code", "caseCode", "case_code", "traceSource", "trace_source").contains(key) && value.isTextual()) {
                    String text = value.asText();
                    for (String label : labels.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) text = text.replace(label, labels.get(label));
                    result.put(key, text);
                } else if ((key.equals("test_method_name") || key.equals("testMethodName") || key.equals("source_code") || key.equals("sourceCode")) && value.isTextual()) {
                    String text = value.asText();
                    String originalName = node.path("test_method_name").asText(node.path("testMethodName").asText(""));
                    var matcher = java.util.regex.Pattern.compile("testvalue0Case([0-9]+)").matcher(originalName);
                    if (matcher.matches()) {
                        String identity = Objects.requireNonNull(ids.get("TEST_CASE").get(matcher.group(1)));
                        String normalizedName = "testvalue0Case" + identity.replaceAll("[^a-zA-Z0-9]", "_");
                        text = key.equals("source_code") || key.equals("sourceCode")
                                ? text.replace("void " + originalName + "()", "void " + normalizedName + "()") : normalizedName;
                    }
                    result.put(key, text);
                } else {
                    JsonNode normalized = canonical(value, own, ids, labels);
                    if (normalized.isArray() && List.of("changedMethods", "affectedServiceMethods", "approvedBusinessRules", "relatedBusinessRules", "businessRules", "approvedTestPlans", "approvedTestCases", "existingTests", "previousGeneratedUnitTests", "sourceDependencies", "problems").contains(key)) normalized = sorted((ArrayNode) normalized);
                    result.set(key, normalized);
                }
            });
            return result;
        }
        if (node.isArray()) { ArrayNode result = JsonNodeFactory.instance.arrayNode(); node.forEach(value -> result.add(canonical(value, type, ids, labels))); return result; }
        return node;
    }

    private static ArrayNode sorted(ArrayNode array) {
        List<JsonNode> values = new ArrayList<>(); array.forEach(values::add); values.sort(Comparator.comparing(JsonNode::toString));
        ArrayNode result = JsonNodeFactory.instance.arrayNode(); values.forEach(result::add); return result;
    }

    private int executeGeneratedTests(Fixture fixture) throws Exception {
        int executed = 0;
        for (var item : items.findBySourceUpdateIdOrderByTargetTypeAscIdAsc(fixture.updateId())) {
            if (!item.getTargetType().name().equals("UNIT_TEST")) continue;
            JsonNode data = json.readTree(item.getAfterData());
            String className = data.path("testClassName").asText(data.path("test_class_name").asText());
            String packageName = data.path("packageName").asText(data.path("package_name").asText());
            String source = data.path("sourceCode").asText(data.path("source_code").asText());
            assertThat(source).isNotBlank(); assertThat(className).matches("[a-zA-Z_$][a-zA-Z0-9_$]*");
            Path directory = fixture.root().resolve("executed-tests/" + item.getId()); Files.createDirectories(directory);
            Path test = directory.resolve(className + ".java"); Files.writeString(test, source);
            List<String> arguments = new ArrayList<>(List.of("--release", "17", "-proc:none", "-classpath", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), "-d", directory.toString(), test.toString()));
            try (var files = Files.walk(fixture.root().resolve("candidate/src/main/java"))) { files.filter(path -> path.toString().endsWith(".java")).forEach(path -> arguments.add(path.toString())); }
            try (var diagnostics = Files.newOutputStream(directory.resolve("compile.txt"))) {
                assertThat(ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, arguments.toArray(String[]::new))).as("Actual proposal source compiles: %s", item.getId()).isZero();
            }
            List<URL> urls = new ArrayList<>(List.of(directory.toUri().toURL()));
            try { Class.forName("org.junit.platform.launcher.core.LauncherFactory"); }
            catch (ClassNotFoundException missing) {
                Path engine = Path.of(Class.forName("org.junit.platform.engine.TestEngine").getProtectionDomain().getCodeSource().getLocation().toURI());
                String version = engine.getParent().getFileName().toString();
                Path launcher = engine.getParent().getParent().getParent().resolve("junit-platform-launcher").resolve(version).resolve("junit-platform-launcher-" + version + ".jar");
                assertThat(launcher).exists(); urls.add(launcher.toUri().toURL());
            }
            try (var loader = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader())) {
                Thread thread = Thread.currentThread(); ClassLoader previous = thread.getContextClassLoader(); thread.setContextClassLoader(loader);
                try {
                    Object selector = loader.loadClass("org.junit.platform.engine.discovery.DiscoverySelectors").getMethod("selectClass", Class.class).invoke(null, loader.loadClass(packageName + "." + className));
                    Class<?> builderType = loader.loadClass("org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder");
                    Object builder = builderType.getMethod("request").invoke(null); builderType.getMethod("selectors", List.class).invoke(builder, List.of(selector)); Object request = builderType.getMethod("build").invoke(builder);
                    Class<?> listenerType = loader.loadClass("org.junit.platform.launcher.TestExecutionListener"); Class<?> summaryListener = loader.loadClass("org.junit.platform.launcher.listeners.SummaryGeneratingListener");
                    Object listener = summaryListener.getConstructor().newInstance(); Object listeners = java.lang.reflect.Array.newInstance(listenerType, 1); java.lang.reflect.Array.set(listeners, 0, listener);
                    Object launcher = loader.loadClass("org.junit.platform.launcher.core.LauncherFactory").getMethod("create").invoke(null);
                    loader.loadClass("org.junit.platform.launcher.Launcher").getMethod("execute", loader.loadClass("org.junit.platform.launcher.LauncherDiscoveryRequest"), listeners.getClass()).invoke(launcher, request, listeners);
                    Object summary = summaryListener.getMethod("getSummary").invoke(listener); Class<?> summaryType = loader.loadClass("org.junit.platform.launcher.listeners.TestExecutionSummary");
                    assertThat((long) summaryType.getMethod("getTestsFoundCount").invoke(summary)).isPositive();
                    assertThat((long) summaryType.getMethod("getTestsFailedCount").invoke(summary)).as("Generated UT runtime failures: %s", summaryType.getMethod("getFailures").invoke(summary)).isZero();
                    assertThat((long) summaryType.getMethod("getTestsSucceededCount").invoke(summary)).isEqualTo((long) summaryType.getMethod("getTestsFoundCount").invoke(summary));
                    executed++;
                } finally { thread.setContextClassLoader(previous); }
            }
        }
        assertThat(executed).isPositive(); return executed;
    }
}
