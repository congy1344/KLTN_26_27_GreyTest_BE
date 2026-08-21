package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class LlmUsageFailureRecordingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    @Test
    void recordsProviderUsageWhenSuccessfulHttpResponseHasNoText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String response = """
                    {
                      "model": "gpt-5.6-luna",
                      "status": "failed",
                      "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}
                    }
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            LlmUsageRecorder recorder = new LlmUsageRecorder(
                    objectMapper, true, tempDirectory, "failed-output-run", "GREYTEST");
            OpenAiLlmClient client = new OpenAiLlmClient(
                    objectMapper,
                    HttpClient.newHttpClient(),
                    "test-key",
                    "gpt-5.6-luna",
                    0.3,
                    512,
                    Duration.ofSeconds(5),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"),
                    recorder);

            assertThatThrownBy(() -> client.complete("# Prompt: unit-test"))
                    .isInstanceOf(LlmResponseException.class)
                    .hasMessageContaining("khong co text output");

            JsonNode summary = objectMapper.readTree(
                    tempDirectory.resolve("failed-output-run/summary.json").toFile());
            assertThat(summary.path("total").path("totalTokens").asLong()).isEqualTo(15);
            assertThat(summary.path("byStage").path("UNIT_TEST").path("calls").asLong()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
