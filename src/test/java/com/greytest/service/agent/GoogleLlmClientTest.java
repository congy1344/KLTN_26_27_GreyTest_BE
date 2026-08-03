package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class GoogleLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void requestBodyAddsJsonResponseFormatForKnownPrompts() throws Exception {
        GoogleLlmClient client = client("test-key", URI.create("http://127.0.0.1:1/v1beta/interactions"));

        Map<String, String> prompts = Map.of(
                "# Prompt: business-rule\nContext: {}", "rules",
                "# Prompt: business-rule-review\nContext: {}", "reviewed_rules",
                "# Prompt: test-plan\nContext: {}", "plans",
                "# Prompt: unit-test\nContext: {}", "unit_tests");

        for (Map.Entry<String, String> entry : prompts.entrySet()) {
            var root = objectMapper.readTree(client.requestBody(entry.getKey()));
            var responseFormat = root.path("response_format");

            assertThat(responseFormat.path("type").asText()).isEqualTo("text");
            assertThat(responseFormat.path("mime_type").asText()).isEqualTo("application/json");
            assertThat(responseFormat.path("schema").path("properties").has(entry.getValue())).isTrue();
        }
    }

    @Test
    void testCasePromptOmitsSchemaButKeepsJsonMimeType() throws Exception {
        GoogleLlmClient client = client("test-key", URI.create("http://127.0.0.1:1/v1beta/interactions"));

        // test_data là object tự do — gửi schema bare object làm Gemini degenerate,
        // nên prompt test-case chỉ ép mime_type JSON, không ép schema
        var root = objectMapper.readTree(client.requestBody("# Prompt: test-case\nContext: {}"));
        var responseFormat = root.path("response_format");

        assertThat(responseFormat.path("mime_type").asText()).isEqualTo("application/json");
        assertThat(responseFormat.has("schema")).isFalse();
    }

    @Test
    void reviewSchemaAllowsNullSuggestedDescription() throws Exception {
        GoogleLlmClient client = client("test-key", URI.create("http://127.0.0.1:1/v1beta/interactions"));

        var root = objectMapper.readTree(client.requestBody("# Prompt: business-rule-review\nContext: {}"));
        var type = root.path("response_format")
                .path("schema")
                .path("properties")
                .path("reviewed_rules")
                .path("items")
                .path("properties")
                .path("suggested_description")
                .path("type");

        assertThat(type.get(0).asText()).isEqualTo("string");
        assertThat(type.get(1).asText()).isEqualTo("null");
    }

    @Test
    void schemaUsesPromptHeaderOnly() throws Exception {
        GoogleLlmClient client = client("test-key", URI.create("http://127.0.0.1:1/v1beta/interactions"));

        var root = objectMapper.readTree(client.requestBody("""
                # Prompt: test-plan

                Context:
                {"source_code":"# Prompt: business-rule-review"}
                """));
        var properties = root.path("response_format").path("schema").path("properties");

        assertThat(properties.has("plans")).isTrue();
        assertThat(properties.has("reviewed_rules")).isFalse();
    }

    @Test
    void callsInteractionsApiAndReturnsOutputText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = """
                    {
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
            GoogleLlmClient client = client(
                    "test-key",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/interactions"));

            String output = client.complete("Prompt text");

            assertThat(output).contains("\"rules\"");
            assertThat(apiKey.get()).isEqualTo("test-key");
            assertThat(requestBody.get())
                    .contains("\"model\":\"gemini-3.5-flash\"", "\"input\":\"Prompt text\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void switchesToFallbackKeyWhenPrimaryQuotaIsExhausted() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> apiKeys = new CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            apiKeys.add(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            boolean primary = apiKeys.size() == 1;
            String response = primary
                    ? "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}"
                    : "{\"output_text\":\"{\\\"rules\\\":[]}\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(primary ? 429 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            GoogleLlmClient client = client(
                    "primary-key",
                    "fallback-key",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/interactions"));

            assertThat(client.complete("Prompt text")).contains("\"rules\"");
            assertThat(client.complete("Prompt text")).contains("\"rules\"");
            assertThat(apiKeys).containsExactly("primary-key", "fallback-key", "fallback-key");
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @MethodSource("googleErrorCases")
    void switchesKeyOnlyForQuotaErrors(int status, String errorBody, boolean shouldFallback) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> apiKeys = new CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            String key = exchange.getRequestHeaders().getFirst("x-goog-api-key");
            apiKeys.add(key);
            boolean fallback = key.equals("fallback-key");
            String response = fallback ? "{\"output_text\":\"{\\\"rules\\\":[]}\"}" : errorBody;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fallback ? 200 : status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            GoogleLlmClient client = client(
                    "primary-key",
                    "fallback-key",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/interactions"));

            if (shouldFallback) {
                assertThat(client.complete("Prompt text")).contains("\"rules\"");
                assertThat(apiKeys).containsExactly("primary-key", "fallback-key");
            } else {
                assertThatThrownBy(() -> client.complete("Prompt text"))
                        .isInstanceOf(LlmResponseException.class)
                        .hasMessageNotContaining("primary-key");
                assertThat(apiKeys).containsExactly("primary-key");
            }
        } finally {
            server.stop(0);
        }
    }

    static Stream<Arguments> googleErrorCases() {
        return Stream.of(
                Arguments.of(403, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}", true),
                Arguments.of(403, "{\"error\":{\"status\":\"API_KEY_INVALID\",\"api_key\":\"primary-key\"}}", false),
                Arguments.of(400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"api_key\":\"primary-key\"}}", false));
    }

    @Test
    void doesNotSwitchKeyOnIoFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> apiKeys = new CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            apiKeys.add(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            exchange.close();
        });
        server.start();
        try {
            GoogleLlmClient client = client(
                    "primary-key",
                    "fallback-key",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/interactions"));

            assertThatThrownBy(() -> client.complete("Prompt text"))
                    .isInstanceOf(LlmResponseException.class)
                    .hasMessageContaining("Khong goi duoc");
            assertThat(apiKeys).containsOnly("primary-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsTextFromGenerateContentCandidates() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String response = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "text": "{\\"rules\\":[{\\"method_id\\":1,\\"description\\":\\"Input phai hop le.\\",\\"category\\":\\"VALIDATION\\"}]}"
                              }
                            ]
                          }
                        }
                      ]
                    }
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            GoogleLlmClient client = client(
                    "test-key",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/interactions"));

            String output = client.complete("Prompt text");

            assertThat(output).contains("\"rules\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsTextFromInteractionSteps() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String response = """
                    {
                      "steps": [
                        {
                          "content": [
                            {
                              "type": "text",
                              "text": "{\\"rules\\":[{\\"method_id\\":1,\\"description\\":\\"Input phai hop le.\\",\\"category\\":\\"VALIDATION\\"}]}"
                            }
                          ]
                        }
                      ]
                    }
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            GoogleLlmClient client = client(
                    "test-key",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/interactions"));

            String output = client.complete("Prompt text");

            assertThat(output).contains("\"rules\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsClearlyWhenApiKeyMissing() {
        GoogleLlmClient client = client(
                "",
                URI.create("http://127.0.0.1:1/v1beta/interactions"));

        assertThatThrownBy(() -> client.complete("Prompt text"))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("LLM_API_KEY");
    }

    private GoogleLlmClient client(String apiKey, URI endpoint) {
        return client(apiKey, "", endpoint);
    }

    private GoogleLlmClient client(String apiKey, String fallbackApiKey, URI endpoint) {
        return new GoogleLlmClient(
                objectMapper,
                HttpClient.newHttpClient(),
                apiKey,
                fallbackApiKey,
                "gemini-3.5-flash",
                0.3,
                512,
                Duration.ofSeconds(5),
                endpoint);
    }
}
