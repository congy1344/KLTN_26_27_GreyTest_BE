package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class LlmUsageRecorderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    @Test
    void writesJsonLinesAndStageSummaryWhenEnabled() throws Exception {
        LlmUsageRecorder recorder = new LlmUsageRecorder(
                objectMapper, true, tempDirectory, "piggymetrics-luna-01", "GREYTEST");
        LlmTokenUsage usage = new LlmTokenUsage(
                100, 20, 40, 10, 140, LlmTokenUsage.UsageSource.PROVIDER);

        recorder.record("# Prompt: test-case\ncontext", "openai", "gpt-5.6-luna", usage, 850);

        Path runDirectory = tempDirectory.resolve("piggymetrics-luna-01");
        String call = Files.readString(runDirectory.resolve("calls.jsonl"));
        JsonNode summary = objectMapper.readTree(runDirectory.resolve("summary.json").toFile());
        assertThat(call).contains("\"stage\":\"TEST_CASE\"", "\"usageSource\":\"PROVIDER\"");
        assertThat(summary.path("total").path("totalTokens").asLong()).isEqualTo(140);
        assertThat(summary.path("byStage").path("TEST_CASE").path("calls").asLong()).isEqualTo(1);
    }

    @Test
    void doesNotCreateFilesWhenDisabled() {
        LlmUsageRecorder recorder = new LlmUsageRecorder(
                objectMapper, false, tempDirectory, "disabled-run", "GREYTEST");

        recorder.record(
                "# Prompt: unit-test",
                "google",
                "gemini",
                new LlmTokenUsage(1, 0, 1, 0, 2, LlmTokenUsage.UsageSource.PROVIDER),
                10);

        assertThat(tempDirectory.resolve("disabled-run")).doesNotExist();
    }

    @Test
    void registersAsSpringBeanWithMultipleConstructors() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class)
                .withUserConfiguration(LlmUsageRecorder.class)
                .run(context -> assertThat(context).hasSingleBean(LlmUsageRecorder.class));
    }
}
