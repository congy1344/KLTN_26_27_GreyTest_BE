package com.greytest.service.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Doc prompt template tu resources/prompts va thay bien {{name}}.
 */
@Service
public class PromptManager {

    private static final String PROMPT_DIR = "prompts/";
    private static final Set<String> SOURCE_STAGES = Set.of(
            "business-rule", "business-rule-review", "test-plan", "test-case", "unit-test");

    private final ObjectMapper objectMapper;

    public PromptManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String load(String templateName) {
        String safeName = templateName.endsWith(".md") ? templateName : templateName + ".md";
        if (!safeName.matches("[a-z0-9-]+\\.md")) {
            throw new IllegalArgumentException("Tên prompt template không hợp lệ: " + templateName);
        }
        try {
            return new ClassPathResource(PROMPT_DIR + safeName)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Không đọc được prompt template: " + safeName, exception);
        }
    }

    public String render(String templateName, Map<String, ?> variables) {
        String prompt = load(templateName);
        for (Map.Entry<String, ?> entry : variables.entrySet()) {
            prompt = prompt.replace("{{" + entry.getKey() + "}}", variableValue(entry.getValue()));
        }
        if (prompt.contains("{{")) {
            throw new IllegalArgumentException("Prompt template còn biến chưa được thay thế: " + templateName);
        }
        String stage = templateName.endsWith(".md") ? templateName.substring(0, templateName.length() - 3) : templateName;
        if (SOURCE_STAGES.contains(stage)) {
            prompt += "\n\n" + load("source-contract");
            var report = TestFeasibilityAnalyzer.analyze(variables.get("context_json"));
            if (!report.methods().isEmpty() || !report.cases().isEmpty()) {
                prompt += "\n\n# Testability assessment\nThe following backend-derived findings are bounded static evidence, not proof that tests executed. "
                        + "Use their fixture strategies while preserving approved requirements. READY means no recognized blocker; "
                        + "UNKNOWN requires checking the missing evidence. Keep source-defined business rules even when their unit tests are blocked.\n"
                        + variableValue(report);
            }
        }
        return prompt;
    }

    private String variableValue(Object value) {
        if (value == null) return "";
        if (value instanceof String text) return text;
        try {
            // Chỉ bỏ khoảng trắng JSON để giảm payload; giữ nguyên source, null và dữ liệu nghiệp vụ.
            return objectMapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Không serialize được biến prompt", exception);
        }
    }
}
