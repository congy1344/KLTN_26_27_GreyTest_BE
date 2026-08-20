package com.greytest.service.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * Ghi token usage của từng LLM call ra file local khi developer chủ động bật.
 */
@Slf4j
@Service
public class LlmUsageRecorder {

    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Path runDirectory;
    private final String runId;
    private final String method;
    private final UsageAggregate total = new UsageAggregate();
    private final Map<String, UsageAggregate> byStage = new LinkedHashMap<>();

    @Autowired
    public LlmUsageRecorder(
            ObjectMapper objectMapper,
            @Value("${llm.usage.enabled:false}") boolean enabled,
            @Value("${llm.usage.output-dir:../log/llm-usage}") String outputDirectory,
            @Value("${llm.usage.run-id:}") String configuredRunId,
            @Value("${llm.usage.method:GREYTEST}") String method) {
        this(objectMapper, enabled, Path.of(outputDirectory), configuredRunId, method);
    }

    LlmUsageRecorder(
            ObjectMapper objectMapper,
            boolean enabled,
            Path outputDirectory,
            String configuredRunId,
            String method) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.runId = safeRunId(configuredRunId);
        this.runDirectory = outputDirectory.resolve(runId).toAbsolutePath().normalize();
        this.method = method == null || method.isBlank() ? "GREYTEST" : method.strip();
    }

    static LlmUsageRecorder noop() {
        return new LlmUsageRecorder(new ObjectMapper(), false, Path.of("."), "disabled", "GREYTEST");
    }

    public synchronized void record(
            String prompt,
            String provider,
            String model,
            LlmTokenUsage usage,
            long latencyMillis) {
        if (!enabled) return;

        String stage = stage(prompt);
        total.add(usage, latencyMillis);
        byStage.computeIfAbsent(stage, ignored -> new UsageAggregate()).add(usage, latencyMillis);
        try {
            Files.createDirectories(runDirectory);
            appendCall(stage, provider, model, usage, latencyMillis);
            writeSummary();
            log.info(
                    "LLM usage [{}] {} {}: input={}, output={}, reasoning={}, total={}, cumulative={}",
                    stage, provider, model, usage.inputTokens(), usage.outputTokens(),
                    usage.reasoningTokens(), usage.totalTokens(), total.totalTokens);
        } catch (IOException exception) {
            // Metric dev không được phép làm thất bại luồng sinh artifact chính.
            log.warn("Không thể ghi LLM usage vào {}: {}", runDirectory, exception.getMessage());
        }
    }

    private void appendCall(
            String stage,
            String provider,
            String model,
            LlmTokenUsage usage,
            long latencyMillis) throws IOException {
        ObjectNode call = objectMapper.createObjectNode();
        call.put("timestamp", Instant.now().toString());
        call.put("runId", runId);
        call.put("method", method);
        call.put("callNumber", total.calls);
        call.put("stage", stage);
        call.put("provider", provider);
        call.put("model", model);
        addUsage(call, usage, latencyMillis);
        Files.writeString(
                runDirectory.resolve("calls.jsonl"),
                objectMapper.writeValueAsString(call) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private void writeSummary() throws IOException {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("updatedAt", Instant.now().toString());
        summary.put("runId", runId);
        summary.put("method", method);
        summary.set("total", total.toJson(objectMapper));
        ObjectNode stages = summary.putObject("byStage");
        byStage.forEach((name, aggregate) -> stages.set(name, aggregate.toJson(objectMapper)));
        String content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
        Path summaryPath = runDirectory.resolve("summary.json");
        Path temporaryPath = runDirectory.resolve("summary.json.tmp");
        Files.writeString(
                temporaryPath,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporaryPath, summaryPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryPath, summaryPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void addUsage(ObjectNode node, LlmTokenUsage usage, long latencyMillis) {
        node.put("inputTokens", usage.inputTokens());
        node.put("cachedInputTokens", usage.cachedInputTokens());
        node.put("outputTokens", usage.outputTokens());
        node.put("reasoningTokens", usage.reasoningTokens());
        node.put("totalTokens", usage.totalTokens());
        node.put("latencyMillis", latencyMillis);
        node.put("usageSource", usage.source().name());
    }

    private String stage(String prompt) {
        String header = prompt == null ? "" : prompt.lines().findFirst().orElse("").toLowerCase();
        if (header.contains("business-rule-review")) return "BUSINESS_RULE_REVIEW";
        if (header.contains("business-rule")) return "BUSINESS_RULE";
        if (header.contains("test-plan")) return "TEST_PLAN";
        if (header.contains("coverage-refinement")) return "COVERAGE_REFINEMENT";
        if (header.contains("test-case")) return "TEST_CASE";
        if (header.contains("unit-test")) return "UNIT_TEST";
        return "UNKNOWN";
    }

    private static String safeRunId(String configuredRunId) {
        String value = configuredRunId == null || configuredRunId.isBlank()
                ? RUN_ID_FORMAT.format(Instant.now())
                : configuredRunId.strip();
        String sanitized = value.replaceAll("[^a-zA-Z0-9._-]", "-");
        return sanitized.isBlank() ? RUN_ID_FORMAT.format(Instant.now()) : sanitized;
    }

    private static final class UsageAggregate {
        private long calls;
        private long estimatedCalls;
        private long inputTokens;
        private long cachedInputTokens;
        private long outputTokens;
        private long reasoningTokens;
        private long totalTokens;
        private long latencyMillis;

        private void add(LlmTokenUsage usage, long callLatencyMillis) {
            calls++;
            if (usage.source() == LlmTokenUsage.UsageSource.ESTIMATED) estimatedCalls++;
            inputTokens += usage.inputTokens();
            cachedInputTokens += usage.cachedInputTokens();
            outputTokens += usage.outputTokens();
            reasoningTokens += usage.reasoningTokens();
            totalTokens += usage.totalTokens();
            latencyMillis += callLatencyMillis;
        }

        private ObjectNode toJson(ObjectMapper objectMapper) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("calls", calls);
            node.put("estimatedCalls", estimatedCalls);
            node.put("inputTokens", inputTokens);
            node.put("cachedInputTokens", cachedInputTokens);
            node.put("outputTokens", outputTokens);
            node.put("reasoningTokens", reasoningTokens);
            node.put("totalTokens", totalTokens);
            node.put("latencyMillis", latencyMillis);
            return node;
        }
    }
}
