package com.greytest.service.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greytest.dto.AnalysisResultDto;
import com.greytest.dto.JavaClassDto;
import com.greytest.dto.JavaMethodDto;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;
import com.greytest.entity.BusinessRule;
import com.greytest.entity.Project;
import com.greytest.entity.TestCase;
import com.greytest.entity.TestPlan;
import com.greytest.entity.TestPlanCoveredRule;
import com.greytest.entity.UnitTest;
import com.greytest.entity.enums.Priority;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.RuleSource;
import com.greytest.entity.enums.TestType;
import com.greytest.repository.*;
import com.greytest.service.analysis.AnalysisManifestService;
import com.greytest.service.analysis.AnalysisService;
import com.greytest.service.analysis.ExistingTestService;
import com.greytest.service.UnitTestFileService;

import jakarta.validation.Validation;

/** Pilot kỹ thuật cùng toàn bộ service scope; không phải thẩm định oracle bởi con người. */
public final class PetclinicProjectPilot {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long PROJECT = 1L;
    static final String ORACLE = "SOURCE_DERIVED_UNVERIFIED";
    final Path source;
    final AnalysisResultDto analysis;
    final List<JavaMethodDto> targets;
    final Path analysisFile;

    public PetclinicProjectPilot(Path source) throws Exception {
        this.source = source.toAbsolutePath().normalize();
        analysisFile=Path.of(System.getProperty("greytest.petclinic.analysis",
                "../outputs/chapter-5/corpus-analysis-20261010/run2/petclinic-rest-analysis.json")).toAbsolutePath().normalize();
        AnalysisResultDto stored=JSON.readValue(Files.readString(analysisFile),AnalysisResultDto.class);
        // Dùng full DTO do analyzer production sinh tại cùng frozen commit; cập nhật source qua loader thật.
        var parsed=SnapshotAnalysisLoader.load(stored,this.source);
        if (parsed.existingTestFiles() != 0) throw new IllegalArgumentException("Production-only source required");
        List<JavaClassDto> classes=parsed.classes();
        analysis = new AnalysisResultDto(PROJECT, "PetClinic REST v3.4.0 technical pilot", "ANALYZED", classes.size(),
                classes.stream().mapToInt(c -> c.methods().size()).sum(),stored.totalEndpoints(),stored.totalRelations(),
                stored.totalControllerServiceRelations(),0,parsed.totalProductionFiles(),parsed.parsedProductionFiles(),
                parsed.failedParseFiles(),parsed.failedParseFilePaths(),classes,stored.relations(),stored.controllerServiceRelations());
        targets = classes.stream().filter(c -> "SERVICE".equals(c.classType())).flatMap(c -> c.methods().stream())
                .filter(m -> "PUBLIC".equals(m.visibility())).toList();
        if (targets.size() != 30) throw new IllegalArgumentException("Expected all 30 public service methods, got " + targets.size());
    }

    public static void main(String[] arguments) throws Exception {
        Path source = Path.of(requiredProperty("greytest.petclinic.source"));
        Path output = Path.of(requiredProperty("greytest.petclinic.output")).toAbsolutePath().normalize();
        var pilot = new PetclinicProjectPilot(source);
        if (Boolean.getBoolean("greytest.petclinic.prepare")) {
            Files.createDirectories(output);
            write(output.resolve("prepared-analysis.json"), pilot.analysis);
            write(output.resolve("prepared-targets.json"), pilot.targetManifest());
            List<Map<String,Object>> problems=new ArrayList<>();
            for (var target : pilot.targets) {
                var fixture = new Fixture(pilot.analysis, pilot.source);
                BusinessRuleGenerationContextDto context;
                try { context=fixture.builder.buildBusinessRuleGenerationContext(PROJECT, Set.of(target.id())); }
                catch(LlmResponseException error) {
                    problems.add(Map.of("target",target.id(),"message",error.getMessage()));continue;
                }
                if (context.classes().stream().flatMap(c -> c.methods().stream()).noneMatch(m -> target.id().equals(m.id())))
                    throw new IllegalStateException("Target omitted from context");
                if (!context.existingTests().isEmpty()) throw new IllegalStateException("Existing test leakage");
                write(output.resolve("prepared-contexts/M"+target.id()+".json"), context);
            }
            write(output.resolve("context-problems.json"),problems);
            System.out.println("Prepared production contexts: " + (pilot.targets.size()-problems.size()) + " ready / "
                    + pilot.targets.size() + " targets, " + problems.size() + " blocked; without network");
            return;
        }
        if (!Boolean.getBoolean("greytest.petclinic.live")) throw new IllegalArgumentException("Explicit live opt-in required");
        var env = environment();
        if (!"openai".equals(env.getOrDefault("LLM_PROVIDER", "openai"))) throw new IllegalArgumentException("OpenAI-compatible provider required");
        if (env.getOrDefault("LLM_API_KEY", "").isBlank()) throw new IllegalArgumentException("Provider credentials missing");
        int concurrency = Integer.parseInt(System.getProperty("greytest.petclinic.concurrency", "1"));
        if (concurrency < 1 || concurrency > 2) throw new IllegalArgumentException("Pilot supports 1 or 2 workers");
        Files.createDirectories(output);
        String model = env.getOrDefault("LLM_OPENAI_MODEL", env.getOrDefault("LLM_MODEL", "gpt-4o-mini"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("dataset", "petclinic-rest"); metadata.put("commit", "26a80b7a510af1fda0acdfb45d65aa480bdb7607");
        metadata.put("startedAt", Instant.now().toString()); metadata.put("oracleStatus", ORACLE);
        metadata.put("approvalActor", "AUTOMATED_TECHNICAL_PILOT"); metadata.put("humanReviewVerified", false);
        metadata.put("requirementCoverage", "N/A"); metadata.put("umr", "N/A"); metadata.put("stability", "N/A");
        metadata.put("model", model); metadata.put("temperature", number(env, "LLM_TEMPERATURE", 0.3));
        metadata.put("baselineMaxTokens", integer(env, "LLM_MAX_TOKENS", 16384));
        metadata.put("sourceSha256", pilot.sourceIdentity());
        metadata.put("productionAnalysisArtifact",pilot.analysisFile.toString());
        metadata.put("productionAnalysisSha256",hash(Files.readString(pilot.analysisFile)));
        metadata.put("options", Map.of("temperature",number(env,"LLM_TEMPERATURE",0.3),
                "maxTokens",integer(env,"LLM_MAX_TOKENS",16384),"timeoutSeconds",integer(env,"LLM_TIMEOUT_SECONDS",60),
                "businessRuleTokens",integer(env,"LLM_MAX_TOKENS_BUSINESS_RULE",4096),
                "planTokens",integer(env,"LLM_MAX_TOKENS_TEST_PLAN",6144),
                "caseTokens",integer(env,"LLM_MAX_TOKENS_TEST_CASE",8192),
                "unitTokens",integer(env,"LLM_MAX_TOKENS_UNIT_TEST",12288),
                "jsonMode",Boolean.parseBoolean(env.getOrDefault("LLM_OPENAI_JSON_MODE","true"))));
        metadata.put("concurrency", concurrency); metadata.put("targets", pilot.targetManifest());
        metadata.put("limitations", List.of("Core agent pilot with in-memory repositories, not HTTP/DB/UI E2E",
                "Source-derived AI rules are not an independently verified business oracle",
                "No existing tests in provider context; generated test execution is separate",
                "Production agent retries/repairs are retained and counted; baselines are one request",
                "Concurrent pilot wall times do not support a latency ranking"));
        Path metadataPath = output.resolve("pilot.json");
        if (Files.exists(metadataPath)) {
            JsonNode previous = JSON.readTree(Files.readString(metadataPath));
            validateCheckpoint(previous,metadata);
        } else write(metadataPath, metadata);
        List<JavaMethodDto> selected = pilot.targets;
        String requested = System.getProperty("greytest.petclinic.targets", "all");
        if (!requested.equals("all")) {
            Set<Long> ids = java.util.Arrays.stream(requested.split(",")).map(Long::parseLong).collect(Collectors.toSet());
            selected = selected.stream().filter(m -> ids.contains(m.id())).toList();
            if (selected.size() != ids.size()) throw new IllegalArgumentException("Unknown target ID");
        }
        long started = System.nanoTime();
        var executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Callable<Void>> work = selected.stream().<Callable<Void>>map(method -> () -> {
                pilot.runMethod(method, output, env);
                return null;
            }).toList();
            for (var result : executor.invokeAll(work)) result.get();
        } finally { executor.shutdown(); }
        write(output.resolve("invocation-" + System.currentTimeMillis() + ".json"), Map.of("targets", selected.stream().map(JavaMethodDto::id).toList(),
                "wallSeconds", (System.nanoTime() - started) / 1e9, "concurrency", concurrency));
    }

    List<Map<String, Object>> targetManifest() {
        return targets.stream().map(method -> {
            var owner = owner(method.id());
            return Map.<String,Object>of("id", method.id(), "class", owner.qualifiedName(), "path", owner.filePath(),
                    "signature", method.methodName() + "(" + method.parameters().stream().map(p -> p.type()).collect(Collectors.joining(",")) + ")");
        }).toList();
    }

    JavaClassDto owner(Long method) {
        return analysis.classes().stream().filter(c -> c.methods().stream().anyMatch(m -> m.id().equals(method))).findFirst().orElseThrow();
    }

    void runMethod(JavaMethodDto method, Path output, Map<String,String> env) throws Exception {
        Path directory = output.resolve(String.format("M%03d-%s", method.id(), method.methodName()));
        Files.createDirectories(directory);
        Fixture fixture = new Fixture(analysis, source);
        var ruleContext = fixture.builder.buildBusinessRuleGenerationContext(PROJECT, Set.of(method.id()));
        write(directory.resolve("business-rule.context.json"), ruleContext);
        write(directory.resolve("source-whitelist.json"), sourceWhitelist(ruleContext.classes()));
        List<String> routes = new ArrayList<>(List.of("llm-only", "prompt-based", "grey-box"));
        java.util.Collections.rotate(routes, -(targets.indexOf(method) % 3));
        Path rulesPath = directory.resolve("source-derived-rules.json");
        BusinessRuleResponseDto generatedRules;
        try {
            if (Files.exists(rulesPath)) generatedRules = JSON.readValue(Files.readString(rulesPath), BusinessRuleResponseDto.class);
            else {
                var client = trackedClient(directory.resolve("rule-setup"), env, "RULE_SETUP");
                generatedRules = agent(fixture, client, env).generateBusinessRules(PROJECT, Set.of(method.id()));
                write(rulesPath, generatedRules);
            }
            fixture.approveRules(generatedRules);
        } catch (Exception error) {
            write(directory.resolve("rule-setup-failure.json"), failure(error));
            // LLM-only vẫn có thể thử khi bước suy luận BR bị lỗi, giữ đủ record phạm vi.
            for (String route : routes) {
                if (route.equals("llm-only")) runBaseline(route, method, directory, env, ruleContext, List.of());
                else write(directory.resolve(route + ".blocked.json"), Map.of("status", "BLOCKED_RULE_SETUP", "oracleStatus", ORACLE));
            }
            return;
        }
        var rules = fixture.builder.buildTestPlanContext(PROJECT).approvedBusinessRules();
        for (String route : routes) {
            if (!route.equals("grey-box")) runBaseline(route, method, directory, env, ruleContext, rules);
            else runGrey(method, directory, env, fixture);
        }
        System.out.println("Completed target " + method.id() + " " + method.methodName());
    }

    void runBaseline(String route, JavaMethodDto method, Path directory, Map<String,String> env,
            BusinessRuleGenerationContextDto context, List<BusinessRuleContextDto> rules) throws Exception {
        Path folder = directory.resolve(route);
        if (Files.exists(folder.resolve("result.json"))) return;
        Files.createDirectories(folder);
        long started = System.nanoTime();
        try {
            String prompt = baselinePrompt(route, owner(method.id()), method, context.classes(), rules);
            String raw = trackedClient(folder, env, route).complete(prompt, new LlmRequestOptions(integer(env, "LLM_MAX_TOKENS", 16384)));
            JsonNode parsed = JSON.readTree(raw);
            if (!parsed.path("plans").isArray() || !parsed.path("cases").isArray() || !parsed.path("unit_tests").isArray()
                    || parsed.path("plans").isEmpty() || parsed.path("cases").isEmpty() || parsed.path("unit_tests").isEmpty())
                throw new IllegalArgumentException("Expected nonempty plans, cases and unit_tests arrays");
            write(folder.resolve("generated.json"), parsed);
            exportUnits(folder,parsed.path("unit_tests"));
            write(folder.resolve("result.json"), Map.of("status", "GENERATED_UNVALIDATED", "seconds", (System.nanoTime()-started)/1e9,
                    "plans", parsed.path("plans").size(), "cases", parsed.path("cases").size(), "unitTests", parsed.path("unit_tests").size(),
                    "oracleStatus", ORACLE, "requirementCoverage", "N/A", "umr", "N/A"));
        } catch (Exception error) {
            var record = failure(error); record.put("seconds", (System.nanoTime()-started)/1e9);
            write(folder.resolve("result.json"), record);
        }
    }

    void runGrey(JavaMethodDto method, Path directory, Map<String,String> env, Fixture fixture) throws Exception {
        Path folder = directory.resolve("grey-box");
        if (Files.exists(folder.resolve("result.json"))) return;
        Files.createDirectories(folder);
        long started = System.nanoTime();
        AIAgentService agent = agent(fixture, trackedClient(folder, env, "GREY_BOX_AGENT"), env);
        try {
            Path planPath = folder.resolve("plans.json");
            TestPlanResponseDto plans = Files.exists(planPath) ? JSON.readValue(Files.readString(planPath), TestPlanResponseDto.class)
                    : agent.generateTestPlan(PROJECT);
            write(planPath, plans); fixture.approvePlans(plans);
            write(folder.resolve("pre-case-context.json"), fixture.builder.buildTestCaseContext(PROJECT));
            Path casePath = folder.resolve("cases.json");
            TestCaseResponseDto cases = Files.exists(casePath) ? JSON.readValue(Files.readString(casePath), TestCaseResponseDto.class)
                    : agent.generateTestCases(PROJECT);
            write(casePath, cases); fixture.approveCases(cases);
            write(folder.resolve("pre-unit-context.json"), fixture.builder.buildUnitTestContext(PROJECT));
            Path unitPath = folder.resolve("unit-tests.json");
            UnitTestResponseDto units = Files.exists(unitPath) ? JSON.readValue(Files.readString(unitPath), UnitTestResponseDto.class)
                    : agent.generateUnitTests(PROJECT);
            write(unitPath, units);
            ObjectNode combined = JSON.createObjectNode();
            combined.set("plans", JSON.valueToTree(plans).path("plans"));
            combined.set("cases", JSON.valueToTree(cases).path("cases"));
            combined.set("unit_tests", JSON.valueToTree(units).path("unit_tests"));
            write(folder.resolve("generated.json"), combined);
            exportUnits(folder,combined.path("unit_tests"));
            write(folder.resolve("result.json"), Map.of("status", "GENERATED_UNVALIDATED", "seconds", (System.nanoTime()-started)/1e9,
                    "plans", plans.plans().size(), "cases", cases.cases().size(), "unitTests", units.unitTests().size(),
                    "blockedCases", units.blockedCases(), "oracleStatus", ORACLE, "requirementCoverage", "N/A", "umr", "N/A"));
        } catch (Exception error) {
            var record = failure(error); record.put("seconds", (System.nanoTime()-started)/1e9);
            write(folder.resolve("result.json"), record);
        }
    }

    static AIAgentService agent(Fixture fixture, LlmClient client, Map<String,String> env) throws Exception {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        return new AIAgentService(fixture.builder, new PromptManager(JSON), client,
                new GenerationResponseParser(JSON, validator), mock(AiContextLogService.class), fixture.projects,
                null, null, new ProjectJavaVersionDetector(fixture.projects), new LlmStageTokenLimits(
                    integer(env,"LLM_MAX_TOKENS",16384), integer(env,"LLM_MAX_TOKENS_BUSINESS_RULE",4096),
                    integer(env,"LLM_MAX_TOKENS_BUSINESS_RULE_REVIEW",4096),integer(env,"LLM_MAX_TOKENS_TEST_PLAN",6144),
                    integer(env,"LLM_MAX_TOKENS_TEST_CASE",8192),integer(env,"LLM_MAX_TOKENS_UNIT_TEST",12288),
                    integer(env,"LLM_MAX_TOKENS_COVERAGE_REFINEMENT",6144)));
    }

    static String baselinePrompt(String route, JavaClassDto owner, JavaMethodDto method, List<ClassContextDto> classes,
            List<BusinessRuleContextDto> rules) throws Exception {
        String schema = """
            {"plans":[{"id":1,"method_id":METHOD_ID,"title":"...","description":"...","test_type":"HAPPY_PATH"}],
             "cases":[{"id":1,"plan_id":1,"test_type":"HAPPY_PATH","description":"...","preconditions":"...",
                       "test_data":{},"expected_result":"...","priority":"HIGH","trace_source":"..."}],
             "unit_tests":[{"case_id":1,"test_class_name":"...Test","test_method_name":"...",
                            "package_name":"...","generation_type":"NEW_TEST","source_code":"complete Java class"}]}
            """;
        StringBuilder prompt = new StringBuilder("# Baseline " + route + "\nGenerate Test Plan, Test Case and runnable JUnit 5 + Mockito Unit Test for exactly this target:\n")
                .append(owner.qualifiedName()).append("#").append(method.methodName()).append("(")
                .append(method.parameters().stream().map(p -> p.type()).collect(Collectors.joining(","))).append(")\n")
                .append("Return one JSON object matching this schema, with meaningful nonempty arrays, local IDs and valid links. ")
                .append("Each Unit Test item corresponds to a Case and contains its own complete Java test class. ")
                .append("Use Java 17, JUnit Jupiter 5 and Mockito; no database/server required. Include tests only for the target method. ")
                .append("Do not alter production. Include necessary imports and exact constructor/dependency wiring. ")
                .append("Return JSON only.\nSchema:\n").append(schema.replace("METHOD_ID",method.id().toString()));
        if (route.equals("prompt-based")) {
            prompt.append("\nBusiness rules (source-derived draft for a technical pilot, not independently verified):\n")
                    .append(JSON.writeValueAsString(rules.stream().map(r -> Map.of("id",r.id(),"description",r.description())).toList()))
                    .append("\nPreserve these rules' expected behavior and make explicit links from plans/cases to rule IDs.");
        }
        prompt.append("\nProduction source files:\n");
        for (ClassContextDto c : classes) prompt.append("\nFILE ").append(c.filePath()).append("\n").append(c.sourceCode()).append("\n");
        return prompt.toString();
    }

    static LlmClient trackedClient(Path folder, Map<String,String> env, String route) throws Exception {
        Files.createDirectories(folder);
        var usage = new LlmUsageRecorder(JSON,true,folder.resolve("usage"),"provider",route);
        var client = new OpenAiLlmClient(JSON,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(integer(env,"LLM_TIMEOUT_SECONDS",60))).build(),
                env.get("LLM_API_KEY"),env.getOrDefault("LLM_OPENAI_MODEL",env.getOrDefault("LLM_MODEL","gpt-4o-mini")),
                number(env,"LLM_TEMPERATURE",0.3),integer(env,"LLM_MAX_TOKENS",16384),
                Duration.ofSeconds(integer(env,"LLM_TIMEOUT_SECONDS",60)),
                URI.create(env.getOrDefault("LLM_OPENAI_URL","https://api.vietapi.tech/v1/chat/completions")),
                Boolean.parseBoolean(env.getOrDefault("LLM_OPENAI_JSON_MODE","true")),usage);
        int previous;
        try (var files = Files.list(folder)) { previous=(int)files.filter(p -> p.getFileName().toString().endsWith(".prompt.txt")).count(); }
        AtomicInteger calls = new AtomicInteger(previous);
        return new LlmClient() {
            @Override public String complete(String prompt) { return complete(prompt,new LlmRequestOptions(integer(env,"LLM_MAX_TOKENS",16384))); }
            @Override public String complete(String prompt,LlmRequestOptions options) {
                int call = calls.incrementAndGet(); long started = System.nanoTime(); String status="PROVIDER_ERROR";
                try {
                    Files.writeString(folder.resolve(String.format("%03d.prompt.txt",call)), redact(prompt,env));
                    String response=client.complete(prompt,options);
                    Files.writeString(folder.resolve(String.format("%03d.response.json",call)),redact(response,env));
                    status="RECEIVED_UNVALIDATED"; return response;
                } catch (java.io.IOException error) { throw new IllegalStateException("Cannot save pilot evidence"); }
                finally {
                    try { Files.writeString(folder.resolve("attempts.jsonl"),JSON.writeValueAsString(Map.of("call",call,"route",route,
                        "status",status,"seconds",(System.nanoTime()-started)/1e9,"maxOutputTokens",options.maxTokens(),
                        "timestamp",Instant.now().toString()))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
                    catch (Exception error) { throw new IllegalStateException("Cannot save attempt record"); }
                }
            }
        };
    }

    static List<Map<String,String>> sourceWhitelist(List<ClassContextDto> classes) throws Exception {
        List<Map<String,String>> entries = new ArrayList<>();
        for (ClassContextDto c:classes) entries.add(Map.of("path",c.filePath(),"qualifiedName",c.qualifiedName(),
                "contextSourceSha256",hash(c.sourceCode()==null?"":c.sourceCode())));
        return entries;
    }

    String sourceIdentity() throws Exception {
        StringBuilder identity=new StringBuilder();
        try(var files=Files.walk(source)) {
            for(Path path:files.filter(Files::isRegularFile).sorted().toList()) {
                String relative=source.relativize(path).toString().replace('\\','/');
                if(relative.startsWith("target/")||relative.startsWith(".git/"))continue;
                identity.append(relative).append(':').append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))).append('\n');
            }
        }
        return hash(identity.toString());
    }

    static void exportUnits(Path folder,JsonNode output) throws Exception {
        List<UnitTest> entities=new ArrayList<>(); List<Map<String,Object>> originals=new ArrayList<>();
        Set<String> originalBodies=new java.util.HashSet<>();
        int number=0;
        for(JsonNode value:output) {
            String name=value.path("test_class_name").asText(),pkg=value.path("package_name").asText();
            if(!name.matches("[A-Za-z_$][A-Za-z0-9_$]*")||!pkg.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
                throw new IllegalArgumentException("Invalid generated Java file identity");
            String code=value.path("source_code").asText();
            UnitTest unit=new UnitTest();unit.setId((long)++number);unit.setTestCaseId(value.path("case_id").asLong());
            unit.setTestClassName(name);unit.setTestMethodName(value.path("test_method_name").asText());unit.setPackageName(pkg);
            unit.setSourceCode(code);unit.setFilePath(pkg.replace('.','/')+"/"+name+".java");entities.add(unit);
            Path raw=folder.resolve("original-java").resolve(String.format("%03d",number)).resolve(unit.getFilePath());
            Files.createDirectories(raw.getParent());Files.writeString(raw,code);
            var bodies=testBodies(code);originalBodies.addAll(bodies);
            originals.add(Map.of("caseId",unit.getTestCaseId(),"testClass",pkg+"."+name,"path",folder.relativize(raw).toString(),"sha256",hash(code),"testBodies",bodies.size()));
        }
        var merged=new UnitTestFileService().mergeByClass(entities);
        List<Map<String,Object>> exports=new ArrayList<>();Set<String> exportedBodies=new java.util.HashSet<>();
        for(var file:merged) {
            Path path=folder.resolve("exported-java").resolve(file.filePath()).normalize();
            if(!path.startsWith(folder.resolve("exported-java").normalize()))throw new IllegalArgumentException("Unsafe export path");
            Files.createDirectories(path.getParent());Files.writeString(path,file.sourceCode());
            var bodies=testBodies(file.sourceCode());exportedBodies.addAll(bodies);
            exports.add(Map.of("caseIds",file.caseIds(),"testClass",file.packageName()+"."+file.testClassName(),
                    "path",folder.relativize(path).toString(),"sha256",hash(file.sourceCode()),"testBodies",bodies.size()));
        }
        Set<String> missing=new java.util.HashSet<>(originalBodies);missing.removeAll(exportedBodies);
        write(folder.resolve("export-manifest.json"),Map.of("exporter","production UnitTestFileService.mergeByClass",
                "originals",originals,"exports",exports,"originalDistinctTestBodies",originalBodies.size(),
                "exportedDistinctTestBodies",exportedBodies.size(),"missingOriginalTestBodies",missing.size()));
        if(!missing.isEmpty())throw new IllegalStateException("Export changed or lost original test bodies; inspect manifest");
    }

    static Set<String> testBodies(String code) {
        var parsed=new com.github.javaparser.JavaParser(new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21)).parse(code);
        if(!parsed.isSuccessful())throw new IllegalArgumentException("Generated Java does not parse");
        return parsed.getResult().orElseThrow().findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                .filter(m->m.getAnnotations().stream().anyMatch(a->Set.of("Test","ParameterizedTest","RepeatedTest",
                        "org.junit.jupiter.api.Test","org.junit.jupiter.params.ParameterizedTest").contains(a.getNameAsString())))
                .map(m->m.getNameAsString()+":"+m.getBody().map(Object::toString).orElse("")).collect(Collectors.toSet());
    }

    static Map<String,Object> failure(Exception error) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("status","GENERATION_FAILED");
        result.put("exceptionClass",error.getClass().getSimpleName()); result.put("oracleStatus",ORACLE);
        result.put("requirementCoverage","N/A");result.put("umr","N/A");
        // Chỉ lưu loại lỗi; thông điệp provider có thể chứa dữ liệu nhạy cảm.
        return result;
    }

    static void validateCheckpoint(JsonNode previous,Map<String,Object> metadata) {
        JsonNode current=JSON.valueToTree(metadata);
        for(String key:List.of("model","sourceSha256","options","productionAnalysisSha256","targets")) {
            if(!previous.has(key)||!previous.path(key).equals(current.path(key)))
                throw new IllegalArgumentException("Checkpoint mismatch: "+key);
        }
    }

    static String redact(String value,Map<String,String> env) {
        String key=env.getOrDefault("LLM_API_KEY",""); return key.isEmpty()?value:value.replace(key,"[REDACTED]");
    }
    static String hash(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    static void write(Path path,Object value) throws Exception { Files.createDirectories(path.getParent()); Files.writeString(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
    static int integer(Map<String,String> env,String key,int fallback) { return Integer.parseInt(env.getOrDefault(key,Integer.toString(fallback))); }
    static double number(Map<String,String> env,String key,double fallback) { return Double.parseDouble(env.getOrDefault(key,Double.toString(fallback))); }
    static String requiredProperty(String key) { String value=System.getProperty(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+key);return value; }
    static Map<String,String> environment() throws Exception {
        Map<String,String> values=new HashMap<>();
        Path file=Path.of(".env");
        if (Files.exists(file)) for(String line:Files.readAllLines(file)) {
            if(line.strip().startsWith("#")||!line.contains("="))continue;
            int split=line.indexOf('=');String key=line.substring(0,split).strip(),value=line.substring(split+1).strip();
            if(value.length()>1 && ((value.startsWith("\"")&&value.endsWith("\""))||(value.startsWith("'")&&value.endsWith("'"))))value=value.substring(1,value.length()-1);
            if(key.startsWith("LLM_"))values.put(key,value);
        }
        System.getenv().forEach((key,value)->{if(key.startsWith("LLM_"))values.put(key,value);}); return values;
    }

    /** Repository trong bộ nhớ giữ flow context thật, không giả kết quả AI hoặc ghi DB ứng dụng. */
    static final class Fixture {
        final List<BusinessRule> rules=new ArrayList<>(); final List<TestPlan> plans=new ArrayList<>();
        final List<TestPlanCoveredRule> links=new ArrayList<>(); final List<TestCase> cases=new ArrayList<>();
        final ProjectRepository projects=mock(ProjectRepository.class);
        final GenerationContextBuilder builder;
        Fixture(AnalysisResultDto analysis,Path source) {
            var analysisService=mock(AnalysisService.class);when(analysisService.getAnalysisResult(PROJECT)).thenReturn(analysis);
            var existing=mock(ExistingTestService.class);when(existing.list(PROJECT)).thenReturn(List.of());
            var ruleRepo=mock(BusinessRuleRepository.class);when(ruleRepo.findByProjectId(PROJECT)).thenAnswer(call->List.copyOf(rules));
            var planRepo=mock(TestPlanRepository.class);when(planRepo.findByProjectId(PROJECT)).thenAnswer(call->List.copyOf(plans));
            var linkRepo=mock(TestPlanCoveredRuleRepository.class);when(linkRepo.findByTestPlanIdIn(any())).thenAnswer(call->List.copyOf(links));
            var caseRepo=mock(TestCaseRepository.class);when(caseRepo.findByTestPlanId(anyLong())).thenAnswer(call->cases.stream()
                    .filter(c->c.getTestPlanId().equals(call.getArgument(0))).toList());
            Project project=new Project();project.setId(PROJECT);project.setStoragePath(source.toString());
            when(projects.findById(PROJECT)).thenReturn(Optional.of(project));
            builder=new GenerationContextBuilder(analysisService,mock(AnalysisManifestService.class),existing,ruleRepo,planRepo,
                    linkRepo,caseRepo,mock(UnitTestRepository.class),projects);
        }
        void approveRules(BusinessRuleResponseDto response) {
            for(var value:response.rules()) {
                BusinessRule rule=new BusinessRule();rule.setId((long)rules.size()+1);rule.setProjectId(PROJECT);rule.setMethodId(value.methodId());
                rule.setRuleCode("BR-"+rule.getId());rule.setDescription(value.description());rule.setSource(RuleSource.AI_GENERATED);
                rule.setReviewNote(value.branchId()==null?null:"SOURCE_BRANCH:"+value.branchId());
                rule.setStatus(ReviewStatus.APPROVED);rule.setIsModified(false);rules.add(rule);
            }
        }
        void approvePlans(TestPlanResponseDto response) {
            for(var value:response.plans()) {
                TestPlan plan=new TestPlan();plan.setId((long)plans.size()+1);plan.setProjectId(PROJECT);plan.setBusinessRuleId(value.ruleId());
                plan.setPlanCode("TP-"+plan.getId());plan.setTitle(value.title());plan.setDescription(value.description());
                plan.setTestType(TestType.valueOf(value.testType()));plan.setStatus(ReviewStatus.APPROVED);plan.setIsModified(false);plans.add(plan);
                for(Long rule:value.coveredRuleIds()) {var link=new TestPlanCoveredRule();link.setTestPlanId(plan.getId());link.setBusinessRuleId(rule);links.add(link);}
            }
        }
        void approveCases(TestCaseResponseDto response) {
            for(var value:response.cases()) {
                TestCase test=new TestCase();test.setId((long)cases.size()+1);test.setTestPlanId(value.planId());test.setCaseCode("TC-"+test.getId());
                test.setTestType(TestType.valueOf(value.testType()));test.setDescription(value.description());test.setPreconditions(value.preconditions());
                test.setTestData(value.testData());test.setExpectedResult(value.expectedResult());test.setPriority(Priority.valueOf(value.priority()));
                test.setTraceSource(value.traceSource());test.setStatus(ReviewStatus.APPROVED);test.setIsModified(false);cases.add(test);
            }
        }
    }
}
