package com.greytest.service.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * LLM client goi Google Gemini API khi can test that voi provider google.
 */
@Service
@ConditionalOnProperty(prefix = "llm", name = "provider", havingValue = "google")
public class GoogleLlmClient implements LlmClient {

    private static final int ERROR_BODY_LIMIT = 500;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;
    private final URI endpoint;

    @Autowired
    public GoogleLlmClient(
            ObjectMapper objectMapper,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:gemini-3.5-flash}") String model,
            @Value("${llm.temperature:0.3}") double temperature,
            @Value("${llm.max-tokens:4096}") int maxTokens,
            @Value("${llm.timeout-seconds:60}") long timeoutSeconds,
            @Value("${llm.google-url:https://generativelanguage.googleapis.com/v1beta/interactions}") String endpoint) {
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

    GoogleLlmClient(
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
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(prompt)))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmResponseException("Google Gemini API loi HTTP "
                        + response.statusCode() + ": " + snippet(response.body()));
            }
            return outputText(response.body());
        } catch (IOException exception) {
            throw new LlmResponseException("Khong goi duoc Google Gemini API.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmResponseException("Bi gian doan khi goi Google Gemini API.", exception);
        }
    }

    private String requestBody(String prompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("input", prompt);
        ObjectNode generationConfig = root.putObject("generation_config");
        generationConfig.put("temperature", temperature);
        generationConfig.put("max_output_tokens", maxTokens);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new LlmResponseException("Khong tao duoc request Google Gemini.", exception);
        }
    }

    private String outputText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String outputText = root.path("output_text").asText("");
            if (!outputText.isBlank()) {
                return outputText;
            }
            outputText = outputContentText(root.path("output"));
            if (!outputText.isBlank()) {
                return outputText;
            }
            outputText = candidateText(root.path("candidates"));
            if (!outputText.isBlank()) {
                return outputText;
            }
            outputText = stepsText(root.path("steps"));
            if (!outputText.isBlank()) {
                return outputText;
            }
            throw new LlmResponseException("Google Gemini response khong co text output: " + snippet(responseBody));
        } catch (JsonProcessingException exception) {
            throw new LlmResponseException("Google Gemini response khong phai JSON hop le.", exception);
        }
    }

    private String outputContentText(JsonNode output) {
        if (!output.isArray()) return "";
        for (JsonNode item : output) {
            String text = partsText(item.path("content"));
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private String candidateText(JsonNode candidates) {
        if (!candidates.isArray()) return "";
        for (JsonNode candidate : candidates) {
            String text = partsText(candidate.path("content").path("parts"));
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private String stepsText(JsonNode steps) {
        if (!steps.isArray()) return "";
        for (int i = steps.size() - 1; i >= 0; i--) {
            String text = partsText(steps.get(i).path("content"));
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private String partsText(JsonNode parts) {
        if (!parts.isArray()) return "";
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            String value = part.path("text").asText("");
            if (!value.isBlank()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(value);
            }
        }
        return text.toString();
    }

    private String snippet(String body) {
        if (body == null) return "";
        if (body.length() <= ERROR_BODY_LIMIT) return body;
        return body.substring(0, ERROR_BODY_LIMIT) + "...";
    }
}
