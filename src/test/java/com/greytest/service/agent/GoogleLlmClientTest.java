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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class GoogleLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
            GoogleLlmClient client = new GoogleLlmClient(
                    objectMapper,
                    HttpClient.newHttpClient(),
                    "test-key",
                    "gemini-3.5-flash",
                    0.3,
                    512,
                    Duration.ofSeconds(5),
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
            GoogleLlmClient client = new GoogleLlmClient(
                    objectMapper,
                    HttpClient.newHttpClient(),
                    "test-key",
                    "gemini-3.5-flash",
                    0.3,
                    512,
                    Duration.ofSeconds(5),
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
            GoogleLlmClient client = new GoogleLlmClient(
                    objectMapper,
                    HttpClient.newHttpClient(),
                    "test-key",
                    "gemini-3.5-flash",
                    0.3,
                    512,
                    Duration.ofSeconds(5),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/interactions"));

            String output = client.complete("Prompt text");

            assertThat(output).contains("\"rules\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsClearlyWhenApiKeyMissing() {
        GoogleLlmClient client = new GoogleLlmClient(
                objectMapper,
                HttpClient.newHttpClient(),
                "",
                "gemini-3.5-flash",
                0.3,
                512,
                Duration.ofSeconds(5),
                URI.create("http://127.0.0.1:1/v1beta/interactions"));

        assertThatThrownBy(() -> client.complete("Prompt text"))
                .isInstanceOf(LlmResponseException.class)
                .hasMessageContaining("LLM_API_KEY");
    }
}
