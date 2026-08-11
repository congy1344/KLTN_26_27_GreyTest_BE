package com.greytest.service.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final Pattern BUSINESS_RULE_ID_AND_METHOD = Pattern.compile(
            "\"id\"\\s*:\\s*(\\d+)\\s*,\\s*\"methodId\"\\s*:\\s*(\\d+)",
            Pattern.DOTALL);
    private static final Pattern PLAN_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*(\\d+)\\s*,\\s*\\\"businessRuleId\\\"");
    private static final Pattern CASE_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*(\\d+)\\s*,\\s*\\\"testPlanId\\\"");
    private static final Pattern BRANCH_ID = Pattern.compile("\\\"branchId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    @Override
    public String complete(String prompt) {
        String normalized = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        if (normalized.contains("prompt: business-rule-review")) return businessRuleReview(prompt);
        if (normalized.contains("prompt: business-rule")) return businessRule(prompt);
        if (normalized.contains("prompt: test-plan")) return testPlan(prompt);
        if (normalized.contains("prompt: coverage-refinement")) return testCase(prompt, true);
        if (normalized.contains("prompt: test-case")) return testCase(prompt);
        if (normalized.contains("prompt: unit-test")) return unitTest(prompt);
        throw new LlmResponseException("MockLlmClient khong nhan dien duoc prompt template.");
    }

    private String businessRule(String prompt) {
        List<Long> methodIds = methodIds(prompt);
        List<String> branchIds = branchIds(prompt);
        String description = isEnglish(prompt)
                ? "Input must be valid before executing business logic."
                : "Input phải hợp lệ trước khi thực hiện business logic.";
        StringBuilder rules = new StringBuilder();
        int ruleCount = branchIds.isEmpty() ? methodIds.size() : branchIds.size();
        for (int i = 0; i < ruleCount; i++) {
            if (i > 0) rules.append(",\n");
            rules.append("""
                        {
                          "method_id": %d,
                          "description": "%s",
                          "category": "VALIDATION",
                          "branch_id": %s
                        }""".formatted(
                    methodIds.get(Math.min(i, methodIds.size() - 1)),
                    description + (branchIds.isEmpty() ? "" : " [" + branchIds.get(i) + "]"),
                    branchIds.isEmpty() ? "null" : "\"" + branchIds.get(i) + "\""));
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
        boolean english = isEnglish(prompt);
        return """
                {
                  "reviewed_rules": [
                    {
                      "rule_id": %d,
                      "verdict": "OK",
                      "suggested_description": null,
                      "reason": "%s"
                    }
                  ],
                  "suggested_rules": [
                    {
                      "method_id": %d,
                      "description": "%s",
                      "category": "BUSINESS_LOGIC"
                    }
                  ]
                }
                """.formatted(
                ruleId,
                english ? "The rule is clear enough to generate a Test Plan." : "Rule đủ rõ để sinh Test Plan.",
                methodId,
                english ? "The system must handle business errors explicitly." : "Hệ thống phải xử lý business error rõ ràng.");
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

    private List<String> branchIds(String prompt) {
        if (prompt == null) return List.of();
        var matcher = BRANCH_ID.matcher(prompt);
        List<String> ids = new ArrayList<>();
        while (matcher.find()) {
            String branchId = matcher.group(1);
            int outcomeSeparator = branchId.indexOf("::");
            String decisionId = outcomeSeparator < 0
                    ? branchId.replaceFirst("-(TRUE|FALSE)$", "")
                    : branchId.substring(0, outcomeSeparator);
            if (!ids.contains(decisionId)) ids.add(decisionId);
        }
        return ids;
    }

    private String testPlan(String prompt) {
        Map<Long, List<Long>> rulesByMethod = ruleIdsByMethod(prompt);
        boolean english = isEnglish(prompt);
        StringBuilder plans = new StringBuilder();
        int index = 0;
        for (Map.Entry<Long, List<Long>> entry : rulesByMethod.entrySet()) {
            if (index++ > 0) plans.append(",\n");
            long methodId = entry.getKey();
            List<Long> ruleIds = entry.getValue();
            long anchorRuleId = ruleIds.get(0);
            plans.append("""
                        {
                          "method_id": %d,
                          "rule_id": %d,
                          "covered_rule_ids": %s,
                          "title": "%s",
                          "description": "%s",
                          "test_type": "HAPPY_PATH"
                        }""".formatted(
                    methodId,
                    anchorRuleId,
                    ruleIds,
                    english ? "Plan for method " + methodId : "Plan cho method " + methodId,
                    english
                            ? "Verify the method's main flows against approved Business Rules."
                            : "Kiểm tra các flow chính của method theo Business Rule đã duyệt."));
        }
        return """
                {
                  "plans": [
                %s
                  ]
                }
                """.formatted(plans);
    }

    private Map<Long, List<Long>> ruleIdsByMethod(String prompt) {
        if (prompt == null) return Map.of(1L, List.of(1L));
        var matcher = BUSINESS_RULE_ID_AND_METHOD.matcher(prompt);
        Map<Long, List<Long>> rulesByMethod = new LinkedHashMap<>();
        while (matcher.find()) {
            long ruleId = Long.parseLong(matcher.group(1));
            long methodId = Long.parseLong(matcher.group(2));
            rulesByMethod.computeIfAbsent(methodId, ignored -> new ArrayList<>()).add(ruleId);
        }
        if (rulesByMethod.isEmpty()) {
            rulesByMethod.put(1L, List.of(firstId(prompt, FIRST_RULE_ID)));
        }
        return rulesByMethod;
    }

    private String testCase(String prompt) {
        return testCase(prompt, false);
    }

    private String testCase(String prompt, boolean supplemental) {
        var matcher = PLAN_ID.matcher(prompt == null ? "" : prompt);
        List<Long> planIds = new ArrayList<>();
        while (matcher.find()) planIds.add(Long.parseLong(matcher.group(1)));
        if (planIds.isEmpty()) planIds.add(1L);
        boolean english = isEnglish(prompt);
        StringBuilder cases = new StringBuilder();
        for (int index = 0; index < planIds.size(); index++) {
            if (index > 0) cases.append(",\n");
            cases.append("""
                    {
                      "plan_id": %d,
                      "test_type": "%s",
                      "description": "%s",
                      "preconditions": "%s",
                      "test_data": { "input": {}, "mocks": {} },
                      "expected_result": "%s",
                      "priority": "HIGH",
                      "trace_source": "BR-001 -> TP-001%s"
                    }""".formatted(
                    planIds.get(index),
                    supplemental ? "EXCEPTION" : "HAPPY_PATH",
                    english
                            ? supplemental ? "Execute the missed coverage branch." : "Execute the method with valid input."
                            : supplemental ? "Thực thi nhánh coverage còn thiếu." : "Thực thi method với input hợp lệ.",
                    english ? "Input data has been created." : "Input data đã được tạo.",
                    english ? "The method returns the expected result." : "Method trả về kết quả mong đợi.",
                    supplemental ? " -> JaCoCo round" : ""));
        }
        return """
                {
                  "cases": [
                %s
                  ]
                }
                """.formatted(cases);
    }

    private String unitTest(String prompt) {
        var matcher = CASE_ID.matcher(prompt == null ? "" : prompt);
        List<Long> caseIds = new ArrayList<>();
        while (matcher.find()) caseIds.add(Long.parseLong(matcher.group(1)));
        if (caseIds.isEmpty()) caseIds.add(1L);
        StringBuilder tests = new StringBuilder();
        for (int index = 0; index < caseIds.size(); index++) {
            if (index > 0) tests.append(",\n");
            tests.append("""
                    {
                      "case_id": %d,
                      "test_class_name": "GeneratedServiceTest",
                      "test_method_name": "testGeneratedScenario_%d",
                      "package_name": "com.example",
                      "generation_type": "NEW_TEST",
                      "source_code": "package com.example;\\nclass GeneratedServiceTest {}"
                    }""".formatted(caseIds.get(index), caseIds.get(index)));
        }
        return """
                {
                  "unit_tests": [
                %s
                  ]
                }
                """.formatted(tests);
    }

    private boolean isEnglish(String prompt) {
        // Mock dùng cùng chỉ dẫn ngôn ngữ với provider thật để demo không trả sai ngôn ngữ hệ thống.
        return prompt != null && prompt.contains("Return every natural-language field in English.");
    }
}
