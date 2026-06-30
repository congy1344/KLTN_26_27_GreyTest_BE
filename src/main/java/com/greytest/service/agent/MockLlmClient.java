package com.greytest.service.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * LLM fake deterministic cho dev/test, khong can API key.
 */
@Service
@ConditionalOnProperty(prefix = "llm", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private static final Pattern METHOD_ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)\\s*,\\s*\"classQualifiedName\"");
    private static final Pattern FIRST_RULE_ID = Pattern.compile(
            "\"businessRules\"\\s*:\\s*\\[\\s*\\{\\s*\"id\"\\s*:\\s*(\\d+)",
            Pattern.DOTALL);

    @Override
    public String complete(String prompt) {
        String normalized = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        if (normalized.contains("prompt: business-rule-review")) return businessRuleReview(prompt);
        if (normalized.contains("prompt: business-rule")) return businessRule(prompt);
        if (normalized.contains("prompt: test-plan")) return testPlan();
        if (normalized.contains("prompt: test-case")) return testCase();
        if (normalized.contains("prompt: unit-test")) return unitTest();
        throw new LlmResponseException("MockLlmClient khong nhan dien duoc prompt template.");
    }

    private String businessRule(String prompt) {
        List<Long> methodIds = methodIds(prompt);
        StringBuilder rules = new StringBuilder();
        for (int i = 0; i < methodIds.size(); i++) {
            if (i > 0) rules.append(",\n");
            rules.append("""
                        {
                          "method_id": %d,
                          "description": "Input phai hop le truoc khi thuc hien nghiep vu.",
                          "category": "VALIDATION"
                        }""".formatted(methodIds.get(i)));
        }
        return """
                {
                  "rules": [
                %s
                  ]
                }
                """.formatted(rules);
    }

    private String businessRuleReview(String prompt) {
        long methodId = firstId(prompt, METHOD_ID);
        long ruleId = firstId(prompt, FIRST_RULE_ID);
        return """
                {
                  "reviewed_rules": [
                    {
                      "rule_id": %d,
                      "verdict": "OK",
                      "suggested_description": null,
                      "reason": "Rule du ro de sinh test plan."
                    }
                  ],
                  "suggested_rules": [
                    {
                      "method_id": %d,
                      "description": "He thong phai xu ly truong hop loi nghiep vu ro rang.",
                      "category": "BUSINESS_LOGIC"
                    }
                  ]
                }
                """.formatted(ruleId, methodId);
    }

    private long firstId(String prompt, Pattern pattern) {
        if (prompt == null) return 1L;
        var matcher = pattern.matcher(prompt);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 1L;
    }

    private List<Long> methodIds(String prompt) {
        if (prompt == null) return List.of(1L);
        var matcher = METHOD_ID.matcher(prompt);
        List<Long> ids = new ArrayList<>();
        while (matcher.find()) {
            ids.add(Long.parseLong(matcher.group(1)));
        }
        return ids.isEmpty() ? List.of(1L) : ids;
    }

    private String testPlan() {
        return """
                {
                  "plans": [
                    {
                      "rule_id": 1,
                      "title": "Happy path cho rule chinh",
                      "description": "Kiem tra luong thanh cong khi tat ca dieu kien hop le.",
                      "test_type": "HAPPY_PATH"
                    }
                  ]
                }
                """;
    }

    private String testCase() {
        return """
                {
                  "cases": [
                    {
                      "plan_id": 1,
                      "test_type": "HAPPY_PATH",
                      "description": "Thuc thi method voi input hop le.",
                      "preconditions": "Du lieu dau vao da duoc tao.",
                      "test_data": { "input": {}, "mocks": {} },
                      "expected_result": "Method tra ve ket qua thanh cong.",
                      "priority": "HIGH",
                      "trace_source": "BR-001 -> TP-001"
                    }
                  ]
                }
                """;
    }

    private String unitTest() {
        return """
                {
                  "unit_tests": [
                    {
                      "case_id": 1,
                      "test_class_name": "GeneratedServiceTest",
                      "test_method_name": "testGeneratedScenario_Success",
                      "package_name": "com.example",
                      "generation_type": "NEW_TEST",
                      "source_code": "package com.example;\\nclass GeneratedServiceTest {}"
                    }
                  ]
                }
                """;
    }
}
