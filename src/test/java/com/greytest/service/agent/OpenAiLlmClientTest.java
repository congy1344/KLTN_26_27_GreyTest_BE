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
    void cachesOnlyStablePrefixesAndPreservesAllTextAcrossRetry() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var bodies = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
        server.createContext("/", exchange -> {
            bodies.add(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new OpenAiLlmClient(objectMapper, HttpClient.newHttpClient(), "test-key",
                    "gpt-6-luna", 0.3, 16000, Duration.ofSeconds(5), endpoint);
            var options = new LlmRequestOptions(4096).withCacheablePrefixes(java.util.List.of("instructions\n", "source \"giữ nguyên\"\n"));
            String prefix = String.join("", options.cacheablePrefixes());
            client.complete(prefix + "batch 1", options);
            client.complete(prefix + "batch 1\n# JSON correction\ninvalid output", options);
            for (int i = 0; i < 2; i++) {
                var body = bodies.get(i);
                assertThat(body.path("prompt_cache_options").path("mode").asText()).isEqualTo("explicit");
                assertThat(body.path("messages")).hasSize(3);
                for (int part = 0; part < 2; part++) {
                    var content = body.at("/messages/" + part + "/content/0");
                    assertThat(content.path("text").asText()).isEqualTo(options.cacheablePrefixes().get(part));
                    assertThat(content.path("prompt_cache_breakpoint").path("mode").asText()).isEqualTo("explicit");
                }
                assertThat(body.at("/messages/2/content").isTextual()).isTrue();
                assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(4096);
            }
            assertThat(bodies.get(0).path("messages").get(0)).isEqualTo(bodies.get(1).path("messages").get(0));
            assertThat(bodies.get(1).at("/messages/2/content").asText()).isEqualTo("batch 1\n# JSON correction\ninvalid output");
            client.complete("prefix no longer matches", options);
            assertThat(bodies.get(2).has("prompt_cache_options")).isFalse();
            assertThat(bodies.get(2).at("/messages/0/content").asText()).isEqualTo("prefix no longer matches");
            var compatibleClient = new OpenAiLlmClient(objectMapper, HttpClient.newHttpClient(), "test-key",
                    "other-model", 0.3, 16000, Duration.ofSeconds(5), endpoint);
            compatibleClient.complete(prefix + "batch 2", options);
            assertThat(bodies.get(3).has("prompt_cache_options")).isFalse();
            assertThat(bodies.get(3).at("/messages/0/content").asText()).isEqualTo(prefix + "batch 2");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timesOutWhenHeadersArriveButTheResponseBodyStalls() throws Exception {
        var releaseBody = new java.util.concurrent.CountDownLatch(1);
        var headersSent = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            headersSent.countDown();
            try { releaseBody.await(10, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new OpenAiLlmClient(objectMapper, HttpClient.newHttpClient(), "test-key", "test-model", 0.3,
                    512, Duration.ofMillis(300), URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThatThrownBy(() -> client.complete("local stalled-body regression"))
                            .isInstanceOfSatisfying(LlmResponseException.class, error -> assertThat(error.isRetryable()).isTrue())
                            .hasCauseInstanceOf(java.net.http.HttpTimeoutException.class));
            assertThat(headersSent.getCount()).isZero();
        } finally {
            releaseBody.countDown();
            server.stop(0);
        }
    }

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
            assertThat(body.path("temperature").asDouble()).isEqualTo(0.3);
            assertThat(body.has("max_completion_tokens")).isFalse();
            assertThat(body.has("reasoning_effort")).isFalse();
            assertThat(body.has("input")).isFalse();
            assertThat(body.has("max_output_tokens")).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsLunaCompatibleParametersAndPreservesPerRequestTokenLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            OpenAiLlmClient client = new OpenAiLlmClient(objectMapper, HttpClient.newHttpClient(), "test-key",
                    "gpt-6-luna", 0.3, 16000, Duration.ofSeconds(5),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"));

            String output = client.complete("Return JSON only.", new LlmRequestOptions(4096));

            assertThat(output).isEqualTo("{\"ok\":true}");
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("model").asText()).isEqualTo("gpt-6-luna");
            assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(4096);
            assertThat(body.path("reasoning_effort").asText()).isEqualTo("none");
            assertThat(body.path("temperature").asDouble()).isEqualTo(0.3);
            assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(body.has("max_tokens")).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsReasoningCompatibleParametersForO3AndO1Models() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"{\\\"reasoned\\\":true}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            OpenAiLlmClient client = new OpenAiLlmClient(objectMapper, HttpClient.newHttpClient(), "test-key",
                    "o3-mini", 0.3, 8192, Duration.ofSeconds(5),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"));

            String output = client.complete("Analyze AST boundary", new LlmRequestOptions(4096));

            assertThat(output).isEqualTo("{\"reasoned\":true}");
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("model").asText()).isEqualTo("o3-mini");
            assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(4096);
            assertThat(body.has("max_tokens")).isFalse();
            // Reasoning models reject temperature parameter
            assertThat(body.has("temperature")).isFalse();
            assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
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

    @Test
    void dynamicKeyRotationUsesSetApiKeys() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        java.util.List<String> seenAuthHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            seenAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            OpenAiLlmClient client = new OpenAiLlmClient(objectMapper, HttpClient.newHttpClient(), "init-key",
                    "gpt-4o-mini", 0.3, 1000, Duration.ofSeconds(5),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

            client.setApiKeys(java.util.List.of("slot0-key", "slot1-key"));
            assertThat(client.getApiKeys()).containsExactly("slot0-key", "slot1-key");

            client.complete("prompt 1");
            client.complete("prompt 2");

            assertThat(seenAuthHeaders).containsExactly("Bearer slot0-key", "Bearer slot1-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void updatesModelDynamically() throws Exception {
        OpenAiLlmClient client = new OpenAiLlmClient(objectMapper, HttpClient.newHttpClient(), "init-key",
                "gpt-4o-mini", 0.3, 1000, Duration.ofSeconds(5), URI.create("http://127.0.0.1:8080"));
        assertThat(client.getModel()).isEqualTo("gpt-4o-mini");

        client.setModel("gpt-4o");
        assertThat(client.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void insufficientQuota429IsNotRetryableButOrdinary429Is() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<String> nextBody = new AtomicReference<>();
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = nextBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            OpenAiLlmClient client = new OpenAiLlmClient(objectMapper, HttpClient.newHttpClient(), "test-key",
                    "gpt-4o-mini", 0.3, 1000, Duration.ofSeconds(5),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

            nextBody.set("{\"error\":{\"message\":\"You exceeded your current quota.\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}");
            assertThatThrownBy(() -> client.complete("quota"))
                    .isInstanceOfSatisfying(LlmResponseException.class, error -> {
                        assertThat(error.isRetryable()).isFalse();
                        assertThat(error.isProviderFailure()).isTrue();
                        assertThat(error.getMessage()).contains("hết credit");
                    });

            nextBody.set("{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"requests\",\"code\":\"rate_limit_exceeded\"}}");
            assertThatThrownBy(() -> client.complete("rate limit"))
                    .isInstanceOfSatisfying(LlmResponseException.class, error -> {
                        assertThat(error.isRetryable()).isTrue();
                        assertThat(error.isProviderFailure()).isTrue();
                    });
        } finally {
            server.stop(0);
        }
    }
}
