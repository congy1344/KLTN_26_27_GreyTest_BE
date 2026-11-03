package com.greytest.service.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.service.GenerationJobContext;
import com.greytest.service.agent.LlmStageTokenLimits;
import com.greytest.service.agent.PromptManager;
import com.greytest.service.diff.SourceDiffService;
import com.greytest.service.analysis.JavaParserHelper;

/** Benchmark CPU/I/O xác định; provider và DB được đo riêng trong pipeline benchmark. */
@EnabledIfSystemProperty(named = "source.benchmark", matches = "true")
class SourceUpdateScanBenchmarkTest {
    @TempDir Path temporary;

    @Test void oneAddedMethodAcrossFourStages() throws Exception {
        Path base = temporary.resolve("base"), candidate = temporary.resolve("candidate");
        for (Path root : List.of(base, candidate)) {
            Path sources = root.resolve("src/main/java/benchmark");
            Files.createDirectories(sources);
            for (int i = 0; i < 32; i++) Files.writeString(sources.resolve("Support" + i + ".java"),
                    "package benchmark; class Support" + i + " { int value() { return " + i + "; } }");
            Files.writeString(sources.resolve("Subject.java"), "package benchmark; @org.springframework.stereotype.Service public class Subject { public int original() { return 1; } "
                    + (root.equals(candidate) ? "public int added() { return 42; } " : "") + "}");
        }
        var json = new ObjectMapper();
        var fingerprints = new GenerationFingerprintService(json, new PromptManager(json),
                LlmStageTokenLimits.defaults(), new MockEnvironment().withProperty("llm.provider", "mock"));
        var method = new MethodContextDto(1L, "benchmark.Subject", "added", "int", List.of(), List.of(),
                "public", "public int added() { return 42; }", 1, 1, List.of(), List.of(), List.of());
        var owner = new ClassContextDto(1L, "benchmark", "Subject", "benchmark.Subject", "SERVICE",
                "src/main/java/benchmark/Subject.java", null, List.of(), List.of(method));
        var context = new BusinessRuleGenerationContextDto(new ProjectContextDto(1L, "scan-benchmark", "ANALYZED"),
                null, List.of(owner), List.of(), List.of(), List.of(), List.of());
        var output = Map.of("source_code", "package benchmark; class SubjectTest { void test() { new Subject().added(); } }");
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int sample = -1; sample < 3; sample++) {
            try (var scope = GenerationJobContext.openScope(null)) {
                long start = System.nanoTime();
                var diff = new SourceDiffService(new JavaParserHelper()).compareSnapshots(base, candidate);
                assertThat(diff.stream().filter(item -> !item.diffType().name().equals("UNCHANGED")).toList())
                        .singleElement().satisfies(item -> assertThat(item.signature()).isEqualTo("added()"));
                List<String> proofs = new ArrayList<>();
                for (String stage : List.of("business-rule", "test-plan", "test-case", "unit-test")) {
                    for (int batch = 0; batch < 8; batch++) {
                        com.greytest.service.agent.GenerationContextBuilder.withInputSnapshot(() -> {
                            var input = fingerprints.fingerprint(stage, context, candidate);
                            assertThat(input.reusable()).isTrue();
                            var result = fingerprints.outputFingerprint(output, candidate, context);
                            assertThat(result.reusable()).isTrue();
                            proofs.add(input.value() + ":" + result.value());
                            return null;
                        });
                    }
                }
                if (sample >= 0) samples.add(Map.of("wallMillis", (System.nanoTime() - start) / 1_000_000.0,
                        "metrics", GenerationJobContext.metrics(), "proofs", List.copyOf(proofs)));
            }
        }
        Path report = Path.of("target/source-scan-" + System.getProperty("benchmark.phase", "before") + ".json");
        Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "filesPerSnapshot", 33, "change", "one added method", "warmups", 1,
                "samples", samples, "scope", "diff and 32 input/output fingerprint pairs; no provider or DB")));
    }
}
