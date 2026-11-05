package com.greytest.service.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.*;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.agent.ProjectJavaVersionDetector.*;

import jakarta.validation.Validation;
import org.springframework.context.i18n.LocaleContextHolder;

/** Thực nghiệm prompt có kiểm soát trên context mall-tiny đã đóng băng, không ghi DB ứng dụng. */
public final class MallTinyUnitPromptComparison {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final String JAVA_MARKER = "\n\n# Java compatibility detected\n";
    static final String LANGUAGE_MARKER = "\n\n# Output language\n";
    static final int WORKERS = 4;
    static final Method CALL;
    static {
        try {
            CALL = AIAgentService.class.getDeclaredMethod("call", String.class, Long.class, Object.class,
                    Class.class, String.class, Path.class);
            CALL.setAccessible(true);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    record Batch(int number, Path log, String logHash, UnitTestContextDto context, String contextJson,
            String originalPrompt, String oldBody, String pom, List<Long> caseIds) { }

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(System.getProperty("greytest.comparison.root", "..")).toAbsolutePath().normalize();
        Path output = Path.of(System.getProperty("greytest.comparison.output",
                root.resolve("outputs/chapter-5/unit-prompt-optimization-20261103/comparison").toString())).toAbsolutePath();
        List<Batch> batches = readBatches(root.resolve("log"));
        if (!Boolean.getBoolean("greytest.comparison.live")) { prepare(output, batches); return; }
        if (!Files.isRegularFile(output.resolve("preflight.json"))) throw new IllegalStateException("Run prepare first");
        Map<String,String> environment = Map.copyOf(PetclinicProjectPilot.environment());
        requireSamePreflight(output, batches, environment);
        if(environment.getOrDefault("LLM_API_KEY", "").isBlank()) throw new IllegalStateException("Provider key is not configured");
        Path live = output.resolve("live");
        if (Files.exists(live)) throw new IllegalStateException("Live destination exists; choose a new output for another run");
        Files.createDirectories(live);
        long pairedStart = System.nanoTime();
        List<Map<String,Object>> arms = new ArrayList<>();
        for (String arm : List.of("baseline", "optimized")) arms.add(runArm(live, arm, batches, environment));
        write(live.resolve("comparison.json"), Map.of("arms", arms,
                "pairedWallSeconds", (System.nanoTime()-pairedStart)/1e9,
                "order", List.of("baseline", "optimized"), "repetitions", 1,
                "limits", limitations()));
        System.out.println("COMPARISON_COMPLETE " + live.resolve("comparison.json"));
    }

    static List<Batch> readBatches(Path logs) throws Exception {
        List<Batch> result = new ArrayList<>();
        try (var paths = Files.list(logs)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().matches("20261103-084[0-9-]+-unit-test\\.log")).sorted().toList()) {
                String raw = Files.readString(path);
                String contextJson = fenced(raw, "## context_json", "json");
                JsonNode node = JSON.readTree(contextJson);
                if (node.path("project").path("id").asLong() != 389) continue;
                UnitTestContextDto context = JSON.readValue(contextJson, UnitTestContextDto.class);
                String prompt = fenced(raw, "## rendered_prompt", "text");
                int technical = prompt.indexOf(JAVA_MARKER);
                if (technical < 0 || prompt.indexOf(LANGUAGE_MARKER, technical) < 0) throw new IllegalStateException("Missing technical appendix: " + path);
                String pomStart = "{pom.xml=";
                int pomBegin = prompt.indexOf(pomStart, technical);
                int pomEnd = prompt.lastIndexOf("}", prompt.indexOf(LANGUAGE_MARKER, technical));
                if (pomBegin < 0 || pomEnd < pomBegin) throw new IllegalStateException("Missing logged pom.xml");
                List<Long> ids = context.approvedTestCases().stream().map(TestCaseContextItemDto::id).sorted().toList();
                result.add(new Batch(result.size()+1, path, hash(raw), context, contextJson, prompt,
                        prompt.substring(0, technical), prompt.substring(pomBegin+pomStart.length(), pomEnd), ids));
            }
        }
        Set<Long> cases = result.stream().flatMap(b -> b.caseIds().stream()).collect(Collectors.toSet());
        if (result.size()!=23 || cases.size()!=89 || result.stream().mapToInt(b -> b.caseIds().size()).sum()!=89)
            throw new IllegalStateException("Expected exactly 23 contexts and 89 unique cases");
        return List.copyOf(result);
    }

    static String fenced(String log, String heading, String language) {
        String marker = heading + "\n```" + language + "\n";
        int start = log.indexOf(marker);
        if (start<0) throw new IllegalArgumentException("Missing " + heading);
        start += marker.length();
        int end = log.indexOf("\n```", start);
        if(end<0) throw new IllegalArgumentException("Unclosed " + heading);
        return log.substring(start,end);
    }

    static void prepare(Path output, List<Batch> batches) throws Exception {
        if (Files.exists(output)) throw new IllegalStateException("Prepare output exists; never overwrite evidence");
        Files.createDirectories(output);
        List<Map<String,Object>> entries = new ArrayList<>();
        for (Batch batch : batches) {
            Path dir = output.resolve(String.format("inputs/batch-%02d",batch.number()));
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("context.json"), batch.contextJson());
            Files.writeString(dir.resolve("logged-prompt.txt"), batch.originalPrompt());
            Files.writeString(dir.resolve("pom.xml"), batch.pom());
            var before = capture(batch,"baseline"); var after = capture(batch,"optimized");
            Files.writeString(dir.resolve("baseline-first-prompt.txt"), before.prompt());
            Files.writeString(dir.resolve("optimized-first-prompt.txt"), after.prompt());
            if (!before.prompt().equals(batch.originalPrompt())) throw new IllegalStateException("Baseline reconstruction differs from logged prompt: " + batch.number());
            if (!before.contextHash().equals(after.contextHash())) throw new IllegalStateException("Context changed across arms");
            entries.add(Map.of("batch",batch.number(),"log",batch.log().getFileName().toString(),"logSha256",batch.logHash(),
                    "caseIds",batch.caseIds(),"contextSha256",hash(batch.contextJson()),"typedContextSha256",before.contextHash(),
                    "baselinePromptChars",before.prompt().length(),"optimizedPromptChars",after.prompt().length(),
                    "baselineFirstPromptSha256",hash(before.prompt()),"optimizedFirstPromptSha256",hash(after.prompt())));
        }
        Map<String,String> env = PetclinicProjectPilot.environment();
        write(output.resolve("preflight.json"), Map.of("createdAt",Instant.now().toString(),"batches",entries,
                "model",model(env),"temperature",PetclinicProjectPilot.number(env,"LLM_TEMPERATURE",0.3),
                "maxOutputTokens",PetclinicProjectPilot.integer(env,"LLM_MAX_TOKENS_UNIT_TEST",12288),
                "configuration",publicConfiguration(env),
                "order",List.of("baseline","optimized"),"concurrency",WORKERS,"limits",limitations()));
        System.out.println("PREPARED_23_BATCHES_89_CASES " + output);
    }

    record Capture(String prompt,String contextHash) { }
    static Capture capture(Batch batch,String arm) throws Exception {
        var captured = new String[1];
        var contextHash = new String[1];
        LlmClient client = new LlmClient() {
            public String complete(String prompt) { captured[0]=prompt;throw new CaptureStop(); }
            public String complete(String prompt,LlmRequestOptions options) { return complete(prompt); }
        };
        try { invoke(agent(batch,arm,client,LlmStageTokenLimits.defaults(), actual -> {
            try {contextHash[0]=hash(JSON.writeValueAsString(actual));}
            catch(Exception e) {throw new IllegalStateException(e);}
        }),batch.context()); }
        catch(CaptureStop expected) { }
        if(captured[0]==null) throw new IllegalStateException("Prerequisites blocked before provider: batch " + batch.number());
        return new Capture(captured[0],contextHash[0]);
    }
    static final class CaptureStop extends RuntimeException { }

    static AIAgentService agent(Batch batch,String arm,LlmClient client,LlmStageTokenLimits limits) {
        return agent(batch,arm,client,limits,ignored -> { });
    }
    static AIAgentService agent(Batch batch,String arm,LlmClient client,LlmStageTokenLimits limits,
            java.util.function.Consumer<Object> observer) {
        PromptManager manager = new PromptManager(JSON) {
            @Override public RenderedPrompt renderPrompt(String stage,Map<String,?> variables) {
                Object effective = variables.get("context_json");
                if(!JSON.valueToTree(effective).equals(JSON.valueToTree(batch.context())))
                    throw new IllegalStateException("Gateway changed frozen context before rendering; batch " + batch.number());
                observer.accept(effective);
                if (arm.equals("optimized")) return super.renderPrompt(stage,variables);
                // Lấy nguyên prompt cũ, bỏ appendix để gateway thêm chính xác một lần như production.
                int shared=batch.oldBody().indexOf("# Shared source evidence\n");
                int data=shared<0?-1:batch.oldBody().indexOf("Context:\n",shared);
                if(shared<0||data<0) throw new IllegalStateException("Missing baseline cacheable prefix boundaries");
                return new RenderedPrompt(batch.oldBody(),List.of(batch.oldBody().substring(0,shared),
                        batch.oldBody().substring(shared,data)));
            }
        };
        var detector=mock(ProjectJavaVersionDetector.class);
        when(detector.detect(anyLong(),any())).thenReturn(Optional.of(new JavaVersionInfo("8","pom.xml")));
        when(detector.detectTestFramework(anyLong(),any())).thenReturn(Optional.of(new TestFrameworkInfo(TestFramework.JUNIT5,"pom.xml")));
        when(detector.detectMockitoVersion(anyLong(),any())).thenReturn(Optional.empty());
        when(detector.buildEvidence(anyLong(),any())).thenReturn(Map.of("pom.xml",batch.pom()));
        return new AIAgentService(null,manager,client,
                new GenerationResponseParser(JSON,Validation.buildDefaultValidatorFactory().getValidator()),
                new AiContextLogService(JSON,falsePath(),false,false),null,null,null,detector,limits);
    }
    static String falsePath() { return ".disabled-comparison-context-log"; }
    static UnitTestResponseDto invoke(AIAgentService agent,UnitTestContextDto context) throws Exception {
        var locale=LocaleContextHolder.getLocale();
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
        try {return (UnitTestResponseDto)CALL.invoke(agent,"unit-test",389L,context,UnitTestResponseDto.class,null,null);}
        catch(InvocationTargetException error) {
            if(error.getCause() instanceof Exception cause) throw cause;
            throw error;
        } finally {LocaleContextHolder.setLocale(locale);}
    }

    static Map<String,Object> runArm(Path output,String arm,List<Batch> batches,Map<String,String> env) throws Exception {
        Path dir=output.resolve(arm);Files.createDirectories(dir);
        var pool=Executors.newFixedThreadPool(WORKERS);
        long started=System.nanoTime();
        List<Future<Map<String,Object>>> pending=new ArrayList<>();
        try {
            for(Batch batch:batches) pending.add(pool.submit(()->runBatch(dir,arm,batch,env)));
            List<Map<String,Object>> results=new ArrayList<>();
            for(var future:pending) results.add(future.get());
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("arm",arm);result.put("wallSeconds",(System.nanoTime()-started)/1e9);result.put("batches",results);
            write(dir.resolve("stage-summary.json"),result);
            return result;
        } finally {pool.shutdown();}
    }

    static Map<String,Object> runBatch(Path output,String arm,Batch batch,Map<String,String> env) throws Exception {
        Path dir=output.resolve(String.format("batch-%02d",batch.number()));Files.createDirectories(dir);
        var results=new LinkedHashMap<String,Object>();results.put("batch",batch.number());results.put("caseIds",batch.caseIds());
        String inputHash=hash(JSON.writeValueAsString(batch.context()));
        var logs=new ArrayList<String>(); long started=System.nanoTime();
        try {
            GenerationJobContext.bind(null,logs::add);
            var tracked=PetclinicProjectPilot.trackedClient(dir,env,arm);
            var limits=new LlmStageTokenLimits(16384,4096,4096,6144,8192,
                    PetclinicProjectPilot.integer(env,"LLM_MAX_TOKENS_UNIT_TEST",12288),6144);
            UnitTestResponseDto response=invoke(agent(batch,arm,tracked,limits),batch.context());
            var ids=response.unitTests().stream().map(GeneratedUnitTestDto::caseId).sorted().toList();
            if(!ids.equals(batch.caseIds())) throw new IllegalStateException("Approved case ID set was not preserved");
            write(dir.resolve("validated-response.json"),response);
            results.put("status","STATIC_VALIDATION_PASSED");results.put("validatedCases",ids.size());
            results.put("executedTests",null);
        } catch(Exception error) {
            results.put("status","GENERATION_FAILED");results.put("validatedCases",0);
            results.put("errorClass",error.getClass().getSimpleName());
            results.put("error",PetclinicProjectPilot.redact(Objects.toString(error.getMessage(),""),env));
        } finally {
            results.put("seconds",(System.nanoTime()-started)/1e9);results.put("runtimeMetrics",GenerationJobContext.metrics());
            results.put("logs",logs);results.put("inputContextSha256",inputHash);
            results.put("contextUnchanged",inputHash.equals(hash(JSON.writeValueAsString(batch.context()))));
            GenerationJobContext.clear();
            write(dir.resolve("result.json"),results);
        }
        System.out.println(arm+" batch "+batch.number()+" "+results.get("status"));
        return results;
    }

    static void requireSamePreflight(Path output,List<Batch> batches,Map<String,String> environment) throws Exception {
        JsonNode saved=JSON.readTree(Files.readString(output.resolve("preflight.json")));
        if(!saved.path("configuration").equals(JSON.valueToTree(publicConfiguration(environment))))
            throw new IllegalStateException("Effective public provider configuration changed since preflight");
        if(saved.path("batches").size()!=batches.size()) throw new IllegalStateException("Corpus count changed");
        for(Batch batch:batches) {
            JsonNode old=saved.path("batches").get(batch.number()-1);
            if(!old.path("logSha256").asText().equals(batch.logHash())) throw new IllegalStateException("Original log changed");
            for(String arm:List.of("baseline","optimized")) {
                String key=arm.equals("baseline")?"baselineFirstPromptSha256":"optimizedFirstPromptSha256";
                if(!old.path(key).asText().equals(hash(capture(batch,arm).prompt()))) throw new IllegalStateException("Prompt changed after preflight: "+arm);
            }
        }
    }
    static List<String> limitations() {return List.of(
            "One fresh contiguous pass per arm; baseline then optimized; provider load/order/cache are not controlled statistically.",
            "Same current parser, prerequisites, feasibility and semantic validators; no application DB, quota or durable response cache.",
            "No production source root: output dependency fingerprint and compilation/execution are not evaluated; static validation is not runtime correctness.",
            "Historical prompts/responses are supplemental; missing historical provider usage is N/A, never character-derived token counts.");}
    static String model(Map<String,String> env){return env.getOrDefault("LLM_OPENAI_MODEL",env.getOrDefault("LLM_MODEL","gpt-4o-mini"));}
    static Map<String,Object> publicConfiguration(Map<String,String> env) throws Exception {
        if(!env.getOrDefault("LLM_PROVIDER","openai").equals("openai")) throw new IllegalArgumentException("Comparison requires configured OpenAI-compatible provider");
        return Map.of("provider","openai","model",model(env),
                "temperature",PetclinicProjectPilot.number(env,"LLM_TEMPERATURE",0.3),
                "maxOutputTokens",PetclinicProjectPilot.integer(env,"LLM_MAX_TOKENS_UNIT_TEST",12288),
                "jsonMode",Boolean.parseBoolean(env.getOrDefault("LLM_OPENAI_JSON_MODE","true")),
                "timeoutSeconds",PetclinicProjectPilot.integer(env,"LLM_TIMEOUT_SECONDS",60),
                "endpointSha256",hash(env.getOrDefault("LLM_OPENAI_URL","https://api.vietapi.tech/v1/chat/completions")));
    }
    static String hash(String value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    static void write(Path path,Object value) throws Exception {Files.writeString(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));}
}
