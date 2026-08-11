package com.greytest.service.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
    private final List<String> apiKeys;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;
    private final URI endpoint;
    private final AtomicInteger activeKeyIndex = new AtomicInteger();

    @Autowired
    public GoogleLlmClient(
            ObjectMapper objectMapper,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.api-key-fallback:}") String fallbackApiKey,
            @Value("${llm.google-model:${llm.model:gemini-3.5-flash}}") String model,
            @Value("${llm.temperature:0.3}") double temperature,
            @Value("${llm.max-tokens:4096}") int maxTokens,
            @Value("${llm.timeout-seconds:60}") long timeoutSeconds,
            @Value("${llm.google-url:https://generativelanguage.googleapis.com/v1beta/interactions}") String endpoint) {
        this(
                objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build(),
                apiKey,
                fallbackApiKey,
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
            String fallbackApiKey,
            String model,
            double temperature,
            int maxTokens,
            Duration timeout,
            URI endpoint) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.apiKeys = Stream.of(apiKey, fallbackApiKey)
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.endpoint = endpoint;
    }

    @Override
    public String complete(String prompt) {
        if (apiKeys.isEmpty()) {
            throw new LlmResponseException("LLM_API_KEY hoac LLM_API_KEY1 chua duoc cau hinh.");
        }
        String body = requestBody(prompt);
        for (int index = activeKeyIndex.get(); index < apiKeys.size(); index++) {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("x-goog-api-key", apiKeys.get(index))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return outputText(response.body());
                }
                if (index + 1 < apiKeys.size() && quotaExceeded(status, response.body())) {
                    activeKeyIndex.set(index + 1);
                    continue;
                }
                throw new LlmResponseException("Google Gemini API loi HTTP "
                        + status + ": " + snippet(response.body()), status == 429 || status >= 500);
            } catch (IOException exception) {
                throw new LlmResponseException("Khong goi duoc Google Gemini API.", exception, true);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new LlmResponseException("Bi gian doan khi goi Google Gemini API.", exception);
            }
        }
        throw new LlmResponseException("Khong co Google Gemini API key kha dung.");
    }

    private boolean quotaExceeded(int status, String responseBody) {
        if (status == 429) return true;
        String body = responseBody == null ? "" : responseBody.toLowerCase(Locale.ROOT);
        return status == 403 && (body.contains("quota") || body.contains("resource_exhausted"));
    }

    String requestBody(String prompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("input", prompt);
        ObjectNode generationConfig = root.putObject("generation_config");
        generationConfig.put("temperature", temperature);
        generationConfig.put("max_output_tokens", maxTokens);
        root.set("response_format", responseFormat(prompt));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new LlmResponseException("Khong tao duoc request Google Gemini.", exception);
        }
    }

    private ObjectNode responseFormat(String prompt) {
        ObjectNode format = objectMapper.createObjectNode();
        format.put("type", "text");
        format.put("mime_type", "application/json");
        ObjectNode schema = schemaFor(prompt);
        if (schema != null) {
            format.set("schema", schema);
        }
        return format;
    }

    private ObjectNode schemaFor(String prompt) {
        String promptHeader = promptHeader(prompt);
        if (promptHeader.equals("# prompt: business-rule-review")) {
            ObjectNode schema = objectSchema();
            ObjectNode properties = schema.putObject("properties");
            properties.set("reviewed_rules", arraySchema(reviewedRuleSchema()));
            properties.set("suggested_rules", arraySchema(businessRuleSchema()));
            required(schema, "reviewed_rules", "suggested_rules");
            return schema;
        }
        if (promptHeader.equals("# prompt: business-rule")) {
            return responseSchema("rules", businessRuleSchema());
        }
        if (promptHeader.equals("# prompt: test-plan")) {
            return responseSchema("plans", testPlanSchema());
        }
        if (promptHeader.equals("# prompt: test-case")) {
            // test_data là object tự do (input/mocks tùy method) — schema Gemini không mô tả
            // được bare object và sẽ degenerate (xả whitespace/lặp từ) ngay tại field này.
            // Bỏ schema, chỉ giữ mime_type JSON; parser + Bean Validation vẫn validate output.
            return null;
        }
        if (promptHeader.equals("# prompt: unit-test")) {
            return responseSchema("unit_tests", unitTestSchema());
        }
        return null;
    }

    private String promptHeader(String prompt) {
        if (prompt == null || prompt.isBlank()) return "";
        int lineEnd = prompt.indexOf('\n');
        String firstLine = lineEnd < 0 ? prompt : prompt.substring(0, lineEnd);
        return firstLine.trim().toLowerCase(Locale.ROOT);
    }

    private ObjectNode responseSchema(String arrayName, ObjectNode itemSchema) {
        ObjectNode schema = objectSchema();
        schema.putObject("properties").set(arrayName, arraySchema(itemSchema));
        required(schema, arrayName);
        return schema;
    }

    private ObjectNode businessRuleSchema() {
        ObjectNode schema = objectSchema();
        ObjectNode properties = schema.putObject("properties");
        properties.set("method_id", type("integer"));
        properties.set("branch_id", nullableStringSchema());
        properties.set("description", type("string"));
        properties.set("category", enumSchema("VALIDATION", "BUSINESS_LOGIC", "SIDE_EFFECT"));
        required(schema, "method_id", "branch_id", "description", "category");
        return schema;
    }

    private ObjectNode reviewedRuleSchema() {
        ObjectNode schema = objectSchema();
        ObjectNode properties = schema.putObject("properties");
        properties.set("rule_id", type("integer"));
        properties.set("verdict", enumSchema("OK", "NEEDS_REVISION", "DUPLICATE", "WRONG_METHOD", "TOO_VAGUE"));
        properties.set("suggested_description", nullableStringSchema());
        properties.set("reason", type("string"));
        required(schema, "rule_id", "verdict", "suggested_description", "reason");
        return schema;
    }

    private ObjectNode testPlanSchema() {
        ObjectNode schema = objectSchema();
        ObjectNode properties = schema.putObject("properties");
        properties.set("method_id", type("integer"));
        properties.set("rule_id", type("integer"));
        properties.set("covered_rule_ids", arraySchema(type("integer")));
        properties.set("title", type("string"));
        properties.set("description", type("string"));
        properties.set("test_type", enumSchema("HAPPY_PATH", "BOUNDARY", "EXCEPTION", "EDGE"));
        required(schema, "method_id", "rule_id", "covered_rule_ids", "title", "description", "test_type");
        return schema;
    }

    private ObjectNode unitTestSchema() {
        ObjectNode schema = objectSchema();
        ObjectNode properties = schema.putObject("properties");
        properties.set("case_id", type("integer"));
        properties.set("test_class_name", type("string"));
        properties.set("test_method_name", type("string"));
        properties.set("package_name", type("string"));
        properties.set("generation_type", enumSchema("NEW_TEST", "IMPROVE_EXISTING_TEST", "SUPPLEMENT_EXISTING_TEST"));
        properties.set("source_code", type("string"));
        required(schema, "case_id", "test_class_name", "test_method_name", "package_name",
                "generation_type", "source_code");
        return schema;
    }

    private ObjectNode arraySchema(ObjectNode itemSchema) {
        ObjectNode schema = type("array");
        schema.set("items", itemSchema);
        return schema;
    }

    private ObjectNode enumSchema(String... values) {
        ObjectNode schema = type("string");
        ArrayNode items = schema.putArray("enum");
        for (String value : values) {
            items.add(value);
        }
        return schema;
    }

    private ObjectNode nullableStringSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        ArrayNode type = schema.putArray("type");
        type.add("string");
        type.add("null");
        return schema;
    }

    private ObjectNode objectSchema() {
        return type("object");
    }

    private ObjectNode type(String type) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", type);
        return schema;
    }

    private void required(ObjectNode schema, String... names) {
        ArrayNode required = schema.putArray("required");
        for (String name : names) {
            required.add(name);
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
        String sanitized = body;
        for (String apiKey : apiKeys) {
            sanitized = sanitized.replace(apiKey, "[redacted]");
        }
        sanitized = sanitized.replaceAll("(?i)(api[-_ ]?key|authorization|token|password)\\s*[:=]\\s*[\\\"']?[^,\\\"'\\s}]+", "$1=[redacted]")
                .replaceAll("[\\r\\n\\t]+", " ");
        if (sanitized.length() <= ERROR_BODY_LIMIT) return sanitized;
        return sanitized.substring(0, ERROR_BODY_LIMIT) + "...";
    }
}
