package com.greytest.service.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * LLM client that calls OpenAI Responses API when real AI testing is enabled.
 */
@Service
@ConditionalOnProperty(prefix = "llm", name = "provider", havingValue = "openai")
public class OpenAiLlmClient implements LlmClient {

    private static final int ERROR_BODY_LIMIT = 500;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;
    private final URI endpoint;

    public OpenAiLlmClient(
            ObjectMapper objectMapper,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:gpt-4o-mini}") String model,
            @Value("${llm.temperature:0.3}") double temperature,
            @Value("${llm.max-tokens:4096}") int maxTokens,
            @Value("${llm.timeout-seconds:60}") long timeoutSeconds,
            @Value("${llm.openai-url:https://api.openai.com/v1/responses}") String endpoint) {
        this(
                objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build(),
                apiKey,
                model,
                temperature,
                maxTokens,
                Duration.ofSeconds(timeoutSeconds),
                URI.create(endpoint));
    }

    OpenAiLlmClient(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            String apiKey,
            String model,
            double temperature,
            int maxTokens,
            Duration timeout,
            URI endpoint) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.endpoint = endpoint;
    }

    @Override
    public String complete(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmResponseException("LLM_API_KEY chua duoc cau hinh.");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(prompt)))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmResponseException("OpenAI API loi HTTP "
                        + response.statusCode() + ": " + snippet(response.body()));
            }
            return outputText(response.body());
        } catch (IOException exception) {
            throw new LlmResponseException("Khong goi duoc OpenAI API.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmResponseException("Bi gian doan khi goi OpenAI API.", exception);
        }
    }

    private String requestBody(String prompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("input", prompt);
        root.put("temperature", temperature);
        root.put("max_output_tokens", maxTokens);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new LlmResponseException("Khong tao duoc request OpenAI.", exception);
        }
    }

    private String outputText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String outputText = root.path("output_text").asText("");
            if (!outputText.isBlank()) {
                return outputText;
            }
            JsonNode output = root.path("output");
            if (output.isArray()) {
                for (JsonNode item : output) {
                    String text = contentText(item.path("content"));
                    if (!text.isBlank()) return text;
                }
            }
            throw new LlmResponseException("OpenAI response khong co text output.");
        } catch (JsonProcessingException exception) {
            throw new LlmResponseException("OpenAI response khong phai JSON hop le.", exception);
        }
    }

    private String contentText(JsonNode content) {
        if (!content.isArray()) return "";
        for (JsonNode item : content) {
            String text = item.path("text").asText("");
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private String snippet(String body) {
        if (body == null) return "";
        if (body.length() <= ERROR_BODY_LIMIT) return body;
        return body.substring(0, ERROR_BODY_LIMIT) + "...";
    }
}
