package com.greytest.service.agent;

import java.util.Comparator;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validator;

/**
 * Parse raw JSON tu LLM va validate DTO bang Bean Validation san co.
 */
@Service
public class GenerationResponseParser {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public GenerationResponseParser(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public <T> T parse(String response, Class<T> responseType) {
        if (response == null || response.isBlank()) {
            throw new LlmResponseException("LLM response rong.");
        }
        try {
            T parsed = objectMapper.readValue(jsonPayload(response), responseType);
            var violations = validator.validate(parsed);
            if (!violations.isEmpty()) {
                String firstError = violations.stream()
                        .min(Comparator.comparing(item -> item.getPropertyPath().toString()))
                        .map(item -> item.getPropertyPath() + " " + item.getMessage())
                        .orElse("schema khong hop le");
                throw new LlmResponseException("LLM response khong dung schema: " + firstError);
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new LlmResponseException("LLM response khong phai JSON hop le.", exception);
        }
    }

    private String jsonPayload(String response) {
        String text = stripCodeFence(response.trim());
        if (text.startsWith("{")) return text;
        String extracted = firstJsonObject(text);
        return extracted.isBlank() ? text : extracted;
    }

    private String stripCodeFence(String text) {
        if (!text.startsWith("```")) return text;
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) return text;
        int closingFence = text.lastIndexOf("```");
        if (closingFence <= firstLineEnd) return text;
        return text.substring(firstLineEnd + 1, closingFence).trim();
    }

    private String firstJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) return "";
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (current == '{') depth++;
            if (current == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return "";
    }
}
