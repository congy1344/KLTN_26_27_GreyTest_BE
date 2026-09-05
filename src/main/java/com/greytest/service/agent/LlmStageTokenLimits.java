package com.greytest.service.agent;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Quản lý giới hạn output token theo từng công đoạn sinh bằng AI. */
@Component
public class LlmStageTokenLimits {

    private final int fallback;
    private final Map<String, Integer> limits;

    public LlmStageTokenLimits(
            @Value("${llm.max-tokens:16384}") int fallback,
            @Value("${llm.stage-max-tokens.business-rule:4096}") int businessRule,
            @Value("${llm.stage-max-tokens.business-rule-review:4096}") int businessRuleReview,
            @Value("${llm.stage-max-tokens.test-plan:6144}") int testPlan,
            @Value("${llm.stage-max-tokens.test-case:8192}") int testCase,
            @Value("${llm.stage-max-tokens.unit-test:12288}") int unitTest,
            @Value("${llm.stage-max-tokens.coverage-refinement:6144}") int coverageRefinement) {
        this.fallback = positive(fallback, "fallback");
        this.limits = Map.of(
                "business-rule", positive(businessRule, "business-rule"),
                "business-rule-review", positive(businessRuleReview, "business-rule-review"),
                "test-plan", positive(testPlan, "test-plan"),
                "test-case", positive(testCase, "test-case"),
                "unit-test", positive(unitTest, "unit-test"),
                "coverage-refinement", positive(coverageRefinement, "coverage-refinement"));
    }

    public LlmRequestOptions optionsFor(String stage) {
        return new LlmRequestOptions(limits.getOrDefault(stage, fallback));
    }

    public static LlmStageTokenLimits defaults() {
        return new LlmStageTokenLimits(16384, 4096, 4096, 6144, 8192, 12288, 6144);
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException("Gioi han token " + name + " phai lon hon 0.");
        return value;
    }
}
