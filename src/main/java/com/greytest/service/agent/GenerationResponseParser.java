package com.greytest.service.agent;

import java.util.Comparator;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;

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
            throw new LlmResponseException("LLM response rỗng.", true);
        }
        try {
            String payload = normalizeJsonNumericSuffixes(jsonPayload(response));
            if (responseType == UnitTestResponseDto.class) rejectUnitTestGenerationError(payload);
            T parsed = objectMapper.readValue(payload, responseType);
            if (parsed == null) {
                throw new LlmResponseException("LLM response không đúng schema: payload null", true);
            }
            var violations = validator.validate(parsed);
            if (!violations.isEmpty()) {
                String firstError = violations.stream()
                        .min(Comparator.comparing(item -> item.getPropertyPath().toString()))
                        .map(item -> item.getPropertyPath() + " " + item.getMessage())
                        .orElse("schema không hợp lệ");
                throw new LlmResponseException("LLM response không đúng schema: " + firstError, true);
            }
            return parsed;
        } catch (com.fasterxml.jackson.databind.exc.MismatchedInputException mismatch) {
            // JSON đúng cú pháp nhưng sai cấu trúc: báo là lỗi schema kèm đường dẫn để giữ được các item hợp lệ
            // và để lượt sửa biết chính xác chỗ sai, thay vì gộp chung với JSON hỏng.
            throw new LlmResponseException("LLM response không đúng schema: " + mismatchDescription(mismatch), mismatch, true);
        } catch (JsonProcessingException exception) {
            // Output hỏng do model nondeterminism (cắt cụt, lặp từ...) → cho phép gọi lại lần 2
            throw new LlmResponseException("LLM response không phải JSON hợp lệ.", exception, true);
        }
    }

    private static String mismatchDescription(com.fasterxml.jackson.databind.exc.MismatchedInputException mismatch) {
        StringBuilder path = new StringBuilder();
        for (var reference : mismatch.getPath()) {
            if (reference.getFieldName() != null) path.append(path.isEmpty() ? "" : ".").append(reference.getFieldName());
            else if (reference.getIndex() >= 0) path.append('[').append(reference.getIndex()).append(']');
        }
        String location = path.isEmpty() ? "root" : path.toString();
        Class<?> target = mismatch.getTargetType();
        boolean objectExpected = target != null && !target.isPrimitive() && !target.isEnum() && !CharSequence.class.isAssignableFrom(target)
                && !Number.class.isAssignableFrom(target) && target != Boolean.class && !java.util.Collection.class.isAssignableFrom(target);
        if (objectExpected) {
            return location + " must be a JSON object with all of its fields, not a bare value. A preceding object was probably closed early: "
                    + "a key inside test_data.mocks or test_data.input containing a comma, quote or parenthesis must stay one complete quoted key "
                    + "with its value. Keep every field of each item, with its value, inside that item's object.";
        }
        String detail = String.valueOf(mismatch.getOriginalMessage()).lines().findFirst().orElse("");
        return location + " has the wrong JSON type: " + (detail.length() > 200 ? detail.substring(0, 200) + "..." : detail);
    }

    /** Case hợp lệ và plan có case sai schema; JSON hỏng thì không có gì để giữ. */
    public record UsableTestCases(java.util.List<com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto> valid,
            java.util.Set<Long> unusablePlanIds, String rejectedJson) { }

    /** Tách theo từng case để một case sai schema không làm mất các plan khác trong response. */
    public UsableTestCases usableTestCases(String response) {
        var valid = new java.util.ArrayList<com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto>();
        var unusable = new java.util.HashSet<Long>();
        var rejected = objectMapper.createArrayNode();
        try {
            for (JsonNode node : objectMapper.readTree(normalizeJsonNumericSuffixes(jsonPayload(response))).path("cases")) {
                try {
                    var item = objectMapper.treeToValue(node, com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto.class);
                    if (validator.validate(item).isEmpty()) {
                        valid.add(item);
                        continue;
                    }
                } catch (JsonProcessingException invalidCase) {
                    // Case không map được vào DTO được xử lý như case sai schema ở dưới.
                }
                rejected.add(node);
                if (node.path("plan_id").canConvertToLong()) unusable.add(node.path("plan_id").asLong());
            }
        } catch (JsonProcessingException invalidJson) {
            return new UsableTestCases(java.util.List.of(), java.util.Set.of(), null);
        }
        return new UsableTestCases(valid, unusable, rejected.toString());
    }

    private void rejectUnitTestGenerationError(String payload) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(payload);
        if (root == null || !root.has("generation_error")) return;
        // Thiếu source hoặc lệch Test Case phải dừng rõ lý do; retry cùng dữ kiện dễ ép AI bịa test.
        JsonNode error = root.get("generation_error");
        if (root.has("unit_tests")) {
            throw new LlmResponseException("LLM response không đúng schema: generation_error cannot accompany unit_tests.");
        }
        JsonNode caseId = error.path("case_id");
        JsonNode reason = error.path("reason");
        JsonNode details = error.path("details");
        if (!error.isObject() || !caseId.isIntegralNumber() || !caseId.canConvertToLong() || caseId.asLong() <= 0
                || !reason.isTextual() || !("MISSING_SOURCE".equals(reason.asText())
                        || "UNSUPPORTED_BEHAVIOR".equals(reason.asText()))
                || !details.isTextual() || details.asText().isBlank()) {
            throw new LlmResponseException("LLM response không đúng schema: generation_error requires a positive case_id, "
                    + "reason MISSING_SOURCE or UNSUPPORTED_BEHAVIOR, and nonempty details.");
        }
        throw new UnitTestGenerationError(caseId.asLong(), reason.asText(), details.asText());
    }

    /** Case mà AI báo không dựng được test (tìm trong cả chuỗi cause vì lỗi có thể đã bị bọc lại); null nếu là lỗi khác. */
    public static Long declinedCaseId(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof UnitTestGenerationError declined) return declined.caseId();
        }
        return null;
    }

    public static final class UnitTestGenerationError extends LlmResponseException {
        private final Long caseId;
        private final String reason;

        public UnitTestGenerationError(Long caseId, String reason, String details) {
            super("Không thể sinh Unit Test cho case_id=" + caseId + " (" + reason + "): " + details);
            this.caseId = caseId;
            this.reason = reason;
        }

        Long caseId() { return caseId; }
        String reason() { return reason; }
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
