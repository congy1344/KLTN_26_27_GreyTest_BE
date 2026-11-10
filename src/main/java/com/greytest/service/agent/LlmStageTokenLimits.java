package com.greytest.service.agent;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Quản lý giới hạn output token theo từng công đoạn sinh bằng AI. */
@Component
public class LlmStageTokenLimits {

    private final int fallback;
    private final ConcurrentMap<String, Integer> limits = new ConcurrentHashMap<>();

    public LlmStageTokenLimits(
            @Value("${llm.max-tokens:16384}") int fallback,
            @Value("${llm.stage-max-tokens.business-rule:4096}") int businessRule,
            @Value("${llm.stage-max-tokens.business-rule-review:4096}") int businessRuleReview,
            @Value("${llm.stage-max-tokens.test-plan:6144}") int testPlan,
            @Value("${llm.stage-max-tokens.test-case:8192}") int testCase,
            @Value("${llm.stage-max-tokens.unit-test:12288}") int unitTest,
            @Value("${llm.stage-max-tokens.coverage-refinement:6144}") int coverageRefinement) {
        this.fallback = positive(fallback, "fallback");
        this.limits.put("business-rule", positive(businessRule, "business-rule"));
        this.limits.put("business-rule-review", positive(businessRuleReview, "business-rule-review"));
        this.limits.put("test-plan", positive(testPlan, "test-plan"));
        this.limits.put("test-case", positive(testCase, "test-case"));
        this.limits.put("unit-test", positive(unitTest, "unit-test"));
        this.limits.put("coverage-refinement", positive(coverageRefinement, "coverage-refinement"));
    }

    public LlmRequestOptions optionsFor(String stage) {
        return new LlmRequestOptions(limits.getOrDefault(stage, fallback));
    }

    public Map<String, Integer> getLimits() {
        return Collections.unmodifiableMap(limits);
    }

    public void updateLimit(String stage, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("Giới hạn token " + stage + " phải lớn hơn 0.");
        limits.put(stage, limit);
    }

    public void updateAll(Map<String, Integer> newLimits) {
        if (newLimits != null) {
            newLimits.forEach(this::updateLimit);
        }
    }

    public static LlmStageTokenLimits defaults() {
        return new LlmStageTokenLimits(16384, 4096, 4096, 6144, 8192, 12288, 6144);
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException("Giới hạn token " + name + " phải lớn hơn 0.");
        return value;
    }
}
