package com.greytest.service.agent;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON Schema strict (Structured Outputs) cho các stage có output cố định: business-rule, business-rule-review, test-plan.
 * Không có: Test Case (test_data là map tự do) và Unit Test (hai nhánh test_classes|generation_error không ép được ở root).
 */
final class LlmResponseSchemas {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Optional<JsonNode>> CACHE = new ConcurrentHashMap<>();

    private LlmResponseSchemas() { }

    /** Schema của prompt, hoặc null nếu stage không dùng Structured Outputs. */
    static JsonNode forPrompt(String promptName) {
        if (promptName == null) return null;
        String stage = promptName.endsWith(".md") ? promptName.substring(0, promptName.length() - 3) : promptName;
        return CACHE.computeIfAbsent(stage, LlmResponseSchemas::load).orElse(null);
    }

    /** Tên schema hợp lệ cho OpenAI (chữ, số, gạch dưới/ngang). */
    static String nameFor(String promptName) {
        return promptName == null ? null : promptName.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static Optional<JsonNode> load(String stage) {
        if (!stage.matches("[a-z-]+")) return Optional.empty();
        try (InputStream in = LlmResponseSchemas.class.getResourceAsStream("/schemas/" + stage + ".json")) {
            return in == null ? Optional.empty() : Optional.of(MAPPER.readTree(in));
        } catch (IOException unreadable) {
            throw new IllegalStateException("Không đọc được schema " + stage, unreadable);
        }
    }
}
