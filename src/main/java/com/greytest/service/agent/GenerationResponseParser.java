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
            T parsed = objectMapper.readValue(normalizeJsonNumericSuffixes(jsonPayload(response)), responseType);
            if (parsed == null) {
                throw new LlmResponseException("LLM response khong dung schema: payload null");
            }
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
            // Output hỏng do model nondeterminism (cắt cụt, lặp từ...) → cho phép gọi lại lần 2
            throw new LlmResponseException("LLM response khong phai JSON hop le.", exception, true);
        }
    }

    private String jsonPayload(String response) {
        String text = stripCodeFence(response.trim());
        if (text.startsWith("{")) return text;
        String extracted = firstJsonObject(text);
        return extracted.isBlank() ? text : extracted;
    }

    /**
     * Một số model viết số theo cú pháp Java (ví dụ {@code 1001L}) dù đã được
     * yêu cầu trả JSON. Chỉ bỏ hậu tố số ở ngoài chuỗi để giữ nguyên dữ liệu text.
     */
    private String normalizeJsonNumericSuffixes(String text) {
        StringBuilder normalized = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                normalized.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
                normalized.append(current);
                continue;
            }
            if ((current == 'L' || current == 'l' || current == 'F' || current == 'f'
                    || current == 'D' || current == 'd')
                    && index > 0 && Character.isDigit(text.charAt(index - 1))) {
                continue;
            }
            normalized.append(current);
        }
        return normalized.toString();
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
