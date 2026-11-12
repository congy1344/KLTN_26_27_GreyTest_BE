package com.greytest.service.agent;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.greytest.service.agent.UnitTestPromptGuidance.Topic;

/** Chọn hướng dẫn chuyên biệt cho Business Rule và Test Case từ bằng chứng của context, như cơ chế topic của Unit Test. */
final class UpstreamPromptGuidance {
    private static final String FORMAT = "String\\.format|\\.formatted\\(|MessageFormat";
    private static final String SWITCH = "\\bswitch\\b";

    private static final Map<String, List<Topic>> TOPICS = Map.of(
            "business-rule", List.of(
                    new Topic("format", FORMAT),
                    new Topic("resources", "\"supportingResources\":\\[\\{|\"contextWarnings\":\\[\""),
                    new Topic("cross-service", "\"dependencyCalls\":\\[\\{"),
                    new Topic("switch", SWITCH),
                    new Topic("loop", "\\b(?:FOR|FOREACH|WHILE|DO_WHILE)-\\d|\\b(?:for|while)\\s*\\(")),
            "test-case", List.of(
                    new Topic("format", FORMAT),
                    new Topic("crypto", "\\b(?:jwt|Jwts|hmac|HS256|HS384|HS512|SecretKey)\\b|javax\\.crypto|jsonwebtoken"),
                    new Topic("persistence", "\\.save\\w*\\("),
                    new Topic("switch", SWITCH),
                    new Topic("identity", "\\bUUID\\b|randomUUID")));

    private UpstreamPromptGuidance() { }

    static boolean supports(String stage) {
        return TOPICS.containsKey(stage);
    }

    static List<String> policyResources(String stage) {
        return Stream.concat(Stream.of(stage + "-full"), TOPICS.get(stage).stream().map(topic -> stage + "-" + topic.name())).toList();
    }

    /** Thiếu source thì không đủ căn cứ để loại chính sách nào, nơi gọi phải dùng bản đầy đủ. */
    static boolean hasEvidence(JsonNode context) {
        if (context == null || !context.isObject() || !context.path("classes").isArray() || context.path("classes").isEmpty()) return false;
        for (JsonNode owner : context.path("classes")) {
            if (!owner.path("sourceCode").isTextual() || owner.path("sourceCode").asText().isBlank()) return false;
        }
        return true;
    }

    static List<String> selectedTopics(String stage, JsonNode context) {
        String evidence = context.toString();
        return TOPICS.get(stage).stream().filter(topic -> topic.trigger().matcher(evidence).find()).map(Topic::name).toList();
    }

    static String render(String stage, JsonNode context, Function<String, String> load) {
        return selectedTopics(stage, context).stream().map(topic -> load.apply(stage + "-" + topic))
                .collect(Collectors.joining("\n"));
    }
}
