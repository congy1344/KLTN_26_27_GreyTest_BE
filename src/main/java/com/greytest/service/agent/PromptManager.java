package com.greytest.service.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Doc prompt template tu resources/prompts va thay bien {{name}}.
 */
@Service
public class PromptManager {

    private static final String PROMPT_DIR = "prompts/";

    private final ObjectMapper objectMapper;

    public PromptManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String load(String templateName) {
        String safeName = templateName.endsWith(".md") ? templateName : templateName + ".md";
        if (!safeName.matches("[a-z0-9-]+\\.md")) {
            throw new IllegalArgumentException("Ten prompt template khong hop le: " + templateName);
        }
        try {
            return new ClassPathResource(PROMPT_DIR + safeName)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Khong doc duoc prompt template: " + safeName, exception);
        }
    }

    public String render(String templateName, Map<String, ?> variables) {
        String prompt = load(templateName);
        for (Map.Entry<String, ?> entry : variables.entrySet()) {
            prompt = prompt.replace("{{" + entry.getKey() + "}}", variableValue(entry.getValue()));
        }
        if (prompt.contains("{{")) {
            throw new IllegalArgumentException("Prompt template con bien chua duoc thay the: " + templateName);
        }
        return prompt;
    }

    private String variableValue(Object value) {
        if (value == null) return "";
        if (value instanceof String text) return text;
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Khong serialize duoc bien prompt", exception);
        }
    }
}
