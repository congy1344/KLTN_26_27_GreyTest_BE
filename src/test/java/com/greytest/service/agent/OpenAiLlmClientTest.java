package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class OpenAiLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void callsChatCompletionsApiAndReturnsMessageContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = """
                    {
                      "choices": [{"message": {"content": "chat-result"}}],
                      "output_text": "{\\"rules\\":[{\\"method_id\\":1,\\"description\\":\\"Input phai hop le.\\",\\"category\\":\\"VALIDATION\\"}]}"
                    }
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            OpenAiLlmClient client = new OpenAiLlmClient(
                    objectMapper,
                    HttpClient.newHttpClient(),
                    "test-key",
                    "gpt-4o-mini",
                    0.3,
                    512,
                    Duration.ofSeconds(5),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"));

            String output = client.complete("Prompt text");

            assertThat(output).isEqualTo("chat-result");
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("model").asText()).isEqualTo("gpt-4o-mini");
            assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("user");
            assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo("Prompt text");
            assertThat(body.path("max_tokens").asInt()).isEqualTo(512);
            assertThat(body.has("input")).isFalse();
            assertThat(body.has("max_output_tokens")).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsClearlyWhenApiKeyMissing() {
        OpenAiLlmClient client = new OpenAiLlmClient(
                objectMapper,
                HttpClient.newHttpClient(),
                "",
                "gpt-4o-mini",
                0.3,
                512,
                Duration.ofSeconds(5),
                URI.create("http://127.0.0.1:1/v1/chat/completions"));

        assertThatThrownBy(() -> client.complete("Prompt text"))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("LLM_API_KEY");
    }

    @Test
    void usesPerRequestMaxTokens() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            OpenAiLlmClient client = new OpenAiLlmClient(objectMapper, HttpClient.newHttpClient(), "key",
                    "claude-sonnet-4-6", 0.3, 16000, Duration.ofSeconds(5),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

            client.complete("prompt", new LlmRequestOptions(4096));

            assertThat(objectMapper.readTree(requestBody.get()).path("max_tokens").asInt()).isEqualTo(4096);
        } finally {
            server.stop(0);
        }
    }
}
