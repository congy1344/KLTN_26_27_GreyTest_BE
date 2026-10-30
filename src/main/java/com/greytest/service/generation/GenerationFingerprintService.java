package com.greytest.service.generation;

import java.net.URI;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashSet;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.service.agent.LlmStageTokenLimits;
import com.greytest.service.agent.PromptManager;
import com.github.javaparser.ast.CompilationUnit;
import com.greytest.service.ServiceScopeResolver;

/** Tạo fingerprint canonical cho input hiệu lực và dependency source của từng stage. */
@Service
public class GenerationFingerprintService {
    private static final String VERSION = "greytest-fingerprint-v1";
    private static final Set<String> STAGES = Set.of("business-rule", "business-rule-review", "test-plan", "test-case", "unit-test", "coverage-refinement");
    private final ObjectMapper mapper;
    private final PromptManager prompts;
    private final LlmStageTokenLimits limits;
    private final Environment environment;

    public GenerationFingerprintService(ObjectMapper mapper, PromptManager prompts,
            LlmStageTokenLimits limits, Environment environment) {
        this.mapper = mapper;
        this.prompts = prompts;
        this.limits = limits;
        this.environment = environment;
    }

    public Fingerprint fingerprint(String stage, Object context, Path sourceRoot) {
        Map<String, Object> manifest = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        manifest.put("format", VERSION);
        manifest.put("stage", stage == null ? "" : stage);
        try {
            if (!STAGES.contains(stage)) throw new IllegalArgumentException("unsupported generation stage");
            JsonNode input = mapper.valueToTree(context);
            manifest.put("project", input.path("project").path("id"));
            manifest.put("inputs", effectiveInputs(stage, input));
            manifest.put("configuration", configuration(stage));
            manifest.put("promptDigest", hash(prompts.load(stage)));
            manifest.put("sourceContractDigest", hash(prompts.load("source-contract")));
            manifest.put("validator", validatorIdentity());
            List<ClassContextDto> targets = targets(classes(context), input);
            if (targets.isEmpty()) problems.add("unresolved: no target methods");
            SourceDependencyIndex index = SourceDependencyIndex.load(sourceRoot);
            var dependencies = index.dependencies(targets, problems);
            manifest.put("sourceDependencies", dependencies);
            Map<String, String> resources = new TreeMap<>(index.sourceResources(dependencies, problems));
            resources.putAll(index.resources(input.path("supportingResources"), targets, problems));
            manifest.put("resources", resources);
            if (input.path("contextWarnings").isArray() && !input.path("contextWarnings").isEmpty()) {
                problems.add("unresolved: source context contains warnings");
                manifest.put("contextWarnings", sorted(input.path("contextWarnings")));
            }
        } catch (RuntimeException exception) {
            // Không cho skip nếu snapshot hoặc dependency không còn đủ bằng chứng an toàn.
            problems.add("unresolved: " + exception.getClass().getSimpleName());
        }
        manifest.put("reusable", problems.isEmpty());
        manifest.put("problems", problems.stream().distinct().sorted().toList());
        String provenance = canonical(mapper.valueToTree(manifest)).toString();
        return new Fingerprint(hash(provenance), provenance, problems.isEmpty());
    }

    /** Dấu vết production API thực tế được output UT gọi, độc lập với byte source của test. */
    public Fingerprint outputFingerprint(Object response, Path sourceRoot) {
        return outputFingerprint(response, sourceRoot, null);
    }

    /** Module lấy từ context đã chọn, không suy đoán từ package do LLM sinh. */
    public Fingerprint outputFingerprint(Object response, Path sourceRoot, Object context) {
        List<String> problems = new ArrayList<>();
        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("format", "greytest-output-dependencies-v1");
        try {
            JsonNode output = response instanceof String text ? mapper.readTree(text) : mapper.valueToTree(response);
            JsonNode tests = output.path("unit_tests");
            if (tests.isMissingNode()) tests = output.path("unitTests");
            List<String> sources = new ArrayList<>();
            if (tests.isArray()) for (JsonNode test : tests) {
                String source = test.path("source_code").asText(test.path("sourceCode").asText(""));
                if (source.isBlank()) problems.add("unresolved empty output source");
                else sources.add(source);
            }
            else if (output.has("source_code") || output.has("sourceCode")) {
                sources.add(output.path("source_code").asText(output.path("sourceCode").asText("")));
            }
            if (!tests.isMissingNode() && sources.isEmpty()) problems.add("unresolved missing unit test output");
            if (!sources.isEmpty()) {
                SourceDependencyIndex index = SourceDependencyIndex.load(sourceRoot);
                synchronized (index) {
                    try {
                    String module = context instanceof String scope ? scope : null;
                    if (context != null && module == null) {
                        var modules = targets(classes(context), mapper.valueToTree(context)).stream()
                                .map(type -> ServiceScopeResolver.modulePath(type.filePath())).distinct().toList();
                        if (modules.size() != 1) throw new IllegalArgumentException("Output requires one target module");
                        module = modules.get(0);
                    }
                    index.outputModule(module);
                    var dependencies = new GeneratedOutputDependencyResolver(index, problems, module).resolve(sources);
                    manifest.put("sourceDependencies", dependencies);
                    manifest.put("resources", index.sourceResources(dependencies, problems));
                    } finally { index.outputModule(null); index.releaseUncachedFacades(); }
                }
            }
        } catch (RuntimeException | java.io.IOException exception) {
            problems.add("unresolved output dependencies: " + exception.getClass().getSimpleName());
        }
        manifest.put("problems", problems.stream().distinct().sorted().toList());
        String provenance = canonical(mapper.valueToTree(manifest)).toString();
        return new Fingerprint(hash(provenance), provenance, problems.isEmpty());
    }

    private ObjectNode effectiveInputs(String stage, JsonNode input) {
        ObjectNode result = mapper.createObjectNode();
        for (String name : List.of("approvedBusinessRules", "relatedBusinessRules", "businessRules", "approvedTestPlans", "approvedTestCases", "coverageGaps")) {
            if (input.has(name)) result.set(name, sorted(withoutReviewMetadata(input.path(name))));
        }
        if (input.has("round")) result.set("round", input.path("round"));
        if (stage.equals("unit-test")) {
            Set<String> ownPaths = new HashSet<>();
            input.path("previousGeneratedUnitTests").forEach(test -> ownPaths.add(test.path("filePath").asText().replace('\\', '/')));
            ArrayNode externalTests = mapper.createArrayNode();
            input.path("existingTests").forEach(test -> {
                if (!ownPaths.contains(test.path("filePath").asText().replace('\\', '/'))) externalTests.add(test);
            });
            result.set("existingTests", sorted(externalTests));
        } else if (input.has("existingTests")) result.set("existingTests", sorted(input.path("existingTests")));
        return result;
    }

    private JsonNode withoutReviewMetadata(JsonNode values) {
        if (!values.isArray()) return values;
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode value : values) {
            if (!value.isObject()) { result.add(value); continue; }
            ObjectNode copy = value.deepCopy();
            copy.remove(List.of("status", "isModified", "reviewNote", "createdAt", "updatedAt"));
            if (copy.path("coveredRuleIds").isArray()) copy.set("coveredRuleIds", sorted(copy.path("coveredRuleIds")));
            result.add(copy);
        }
        return result;
    }

    /** So sánh dependency giữa hai snapshot, dùng lại AST đã parse bởi bước diff. */
    public static Map<String, Fingerprint> sourceFingerprints(List<ClassContextDto> classes, Path root,
            Map<String, CompilationUnit> parsedUnits) {
        // Mỗi snapshot dùng chung dữ liệu build/resource, rồi giải phóng scope sau khi diff.
        try (var scope = com.greytest.service.GenerationJobContext.openScope(com.greytest.service.GenerationJobContext.actorUserId())) {
            return com.greytest.service.agent.GenerationContextBuilder.withInputSnapshot(() -> {
                SourceDependencyIndex index = SourceDependencyIndex.load(root, parsedUnits);
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Fingerprint> result = new TreeMap<>();
                for (ClassContextDto owner : classes) {
                    for (MethodContextDto method : owner.methods()) {
                        var single = new ClassContextDto(owner.id(), owner.packageName(), owner.className(), owner.qualifiedName(),
                                owner.classType(), owner.filePath(), null, owner.annotations(), List.of(method));
                        List<String> problems = new ArrayList<>();
                        Map<String, Object> manifest = new TreeMap<>();
                        manifest.put("format", VERSION);
                        var dependencies = index.dependencies(List.of(single), problems);
                        manifest.put("sourceDependencies", dependencies);
                        manifest.put("resources", index.sourceResources(dependencies, problems));
                        manifest.put("problems", problems.stream().distinct().sorted().toList());
                        String evidence = mapper.valueToTree(manifest).toString();
                        String parameters = method.parameters() == null ? "" : method.parameters().stream()
                                .map(com.greytest.dto.MethodParamDto::type).collect(java.util.stream.Collectors.joining(","));
                        String key = ServiceScopeResolver.modulePath(owner.filePath()) + ":" + owner.qualifiedName()
                                + "#" + method.methodName() + "(" + parameters + ")";
                        result.put(key, new Fingerprint(hash(evidence), evidence, problems.isEmpty()));
                    }
                }
                return result;
            });
        }
    }

    private List<ClassContextDto> targets(List<ClassContextDto> classes, JsonNode input) {
        Set<Long> methodIds = new HashSet<>();
        input.path("approvedBusinessRules").forEach(rule -> methodIds.add(rule.path("methodId").asLong()));
        input.path("businessRules").forEach(rule -> methodIds.add(rule.path("methodId").asLong()));
        List<ClassContextDto> result = new ArrayList<>();
        for (ClassContextDto owner : classes) {
            var methods = owner.methods() == null ? List.<MethodContextDto>of() : owner.methods();
            if (!methodIds.isEmpty()) methods = methods.stream().filter(method -> methodIds.contains(method.id())).toList();
            else if (classes.stream().anyMatch(type -> "SERVICE".equalsIgnoreCase(type.classType()))
                    && !"SERVICE".equalsIgnoreCase(owner.classType())) continue;
            if (!methods.isEmpty()) result.add(new ClassContextDto(owner.id(), owner.packageName(), owner.className(),
                    owner.qualifiedName(), owner.classType(), owner.filePath(), null, owner.annotations(), methods));
        }
        return result;
    }

    private List<ClassContextDto> classes(Object context) {
        if (context instanceof BusinessRuleGenerationContextDto value) return value.classes();
        if (context instanceof BusinessRuleReviewContextDto value) return value.classes();
        if (context instanceof TestPlanContextDto value) return value.classes();
        if (context instanceof TestCaseContextDto value) return value.classes();
        if (context instanceof UnitTestContextDto value) return value.classes();
        if (context instanceof CoverageRefinementContextDto value) return value.classes();
        throw new IllegalArgumentException("unsupported context type");
    }

    private Map<String, Object> configuration(String stage) {
        String provider = environment.getProperty("llm.provider", "mock").toLowerCase(Locale.ROOT);
        String model = environment.getProperty("llm." + provider + "-model",
                environment.getProperty("llm.model", provider.equals("google") ? "gemini-3.5-flash" : "gpt-4o-mini"));
        String defaultUrl = provider.equals("google") ? "https://generativelanguage.googleapis.com/v1beta/interactions"
                : "https://api.openai.com/v1/chat/completions";
        String endpoint = environment.getProperty("llm." + provider + "-url", defaultUrl);
        try {
            URI uri = URI.create(endpoint);
            endpoint = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception exception) { endpoint = "invalid-endpoint"; }
        return Map.of("provider", provider, "model", model, "endpoint", endpoint,
                "temperature", environment.getProperty("llm.temperature", "0.3"), "maxTokens", limits.optionsFor(stage).maxTokens(),
                "locale", LocaleContextHolder.getLocale().toLanguageTag(), "jsonMode", environment.getProperty("llm.openai-json-mode", "true"));
    }

    private Map<String, String> validatorIdentity() {
        Map<String, String> result = new TreeMap<>();
        result.put("version", environment.getProperty("llm.validator-version", "source-semantic-v1"));
        for (String type : List.of("GenerationResponseParser", "GeneratedUnitTestSemanticValidator", "TestFeasibilityAnalyzer", "AIAgentService", "GenerationValidationGate")) {
            try (var stream = getClass().getResourceAsStream("/com/greytest/service/agent/" + type + ".class")) {
                if (stream == null) throw new IllegalStateException("Missing validator " + type);
                result.put(type, hash(stream.readAllBytes()));
            } catch (java.io.IOException exception) { throw new IllegalStateException("Cannot read validator identity", exception); }
        }
        return result;
    }

    private JsonNode sorted(JsonNode list) {
        if (!list.isArray()) return canonical(list);
        List<JsonNode> values = new ArrayList<>(); list.forEach(value -> values.add(canonical(value)));
        values.sort(Comparator.comparing(JsonNode::toString));
        ArrayNode result = mapper.createArrayNode(); values.forEach(result::add); return result;
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            var names = new ArrayList<String>(); value.fieldNames().forEachRemaining(names::add); names.sort(String::compareTo);
            for (String name : names) {
                result.set(name, canonical(value.path(name)));
            }
            return result;
        }
        if (value.isArray()) { ArrayNode result = mapper.createArrayNode(); value.forEach(item -> result.add(canonical(item))); return result; }
        return value;
    }

    static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public record Fingerprint(String value, String provenance, boolean reusable) {}
}
