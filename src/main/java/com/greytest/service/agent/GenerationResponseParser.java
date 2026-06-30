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
            T parsed = objectMapper.readValue(response, responseType);
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
}
