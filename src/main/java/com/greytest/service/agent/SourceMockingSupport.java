package com.greytest.service.agent;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestCaseContextDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.TestCaseResponseDto;

/** Đối chiếu setup mock với khai báo field thật, không suy đoán khả năng inject từ tên dependency. */
final class SourceMockingSupport {
    private SourceMockingSupport() { }

    static Optional<String> validateTestCases(TestCaseContextDto context, TestCaseResponseDto response) {
        for (var testCase : response.cases()) {
            var plan = context.approvedTestPlans().stream().filter(item -> item.id().equals(testCase.planId())).findFirst();
            if (plan.isEmpty()) continue;
            Set<Long> ruleIds = new java.util.HashSet<>(plan.get().coveredRuleIds());
            ruleIds.add(plan.get().businessRuleId());
            Set<Long> methodIds = context.approvedBusinessRules().stream().filter(rule -> ruleIds.contains(rule.id()))
                    .map(rule -> rule.methodId()).collect(java.util.stream.Collectors.toSet());
            for (var source : context.classes()) {
                if (source.methods().stream().noneMatch(method -> methodIds.contains(method.id()))) continue;
                Set<String> invalid = unsupportedMocks(source, testCase.testData());
                if (!invalid.isEmpty()) return Optional.of("plan_id=" + testCase.planId() + ": " + source.qualifiedName()
                        + " internally initializes final field(s) " + invalid + "; Mockito cannot inject these mocks. "
                        + "Use the real collaborator and source-proven observable results; preserve the plan and business expectation.");
            }
        }
        return Optional.empty();
    }

    static boolean canReconsiderSetup(UnitTestContextDto context, GenerationResponseParser.UnitTestGenerationError error) {
        if (!"UNSUPPORTED_BEHAVIOR".equals(error.reason())) return false;
        var testCase = context.approvedTestCases().stream().filter(item -> item.id().equals(error.caseId())).findFirst();
        if (testCase.isEmpty()) return false;
        // Chỉ thử đổi cách dựng fixture khi chính source chứng minh mock gợi ý không thể inject; không sửa Test Case đã lưu.
        var plans = context.approvedTestPlans().stream().filter(plan -> plan.id().equals(testCase.get().testPlanId())).toList();
        Set<Long> ruleIds = new java.util.HashSet<>();
        plans.forEach(plan -> { ruleIds.add(plan.businessRuleId()); ruleIds.addAll(plan.coveredRuleIds()); });
        Set<Long> methodIds = context.approvedBusinessRules().stream().filter(rule -> ruleIds.contains(rule.id()))
                .map(rule -> rule.methodId()).collect(java.util.stream.Collectors.toSet());
        return context.classes().stream().filter(source -> source.methods().stream().anyMatch(method -> methodIds.contains(method.id())))
                .anyMatch(source -> !unsupportedMocks(source, testCase.get().testData()).isEmpty());
    }

    static Set<String> unsupportedMocks(ClassContextDto source, Map<String, Object> testData) {
        if (testData == null || !(testData.get("mocks") instanceof Map<?, ?> mocks)) return Set.of();
        Set<String> paths = new LinkedHashSet<>();
        mockPaths(mocks, "", paths);
        Set<String> invalid = new LinkedHashSet<>();
        for (String field : createdFinalFields(source)) {
            if (paths.stream().map(path -> path.startsWith("this.") ? path.substring(5) : path)
                    .anyMatch(path -> path.equals(field) || path.startsWith(field + "."))) invalid.add(field);
        }
        return invalid;
    }

    private static Set<String> createdFinalFields(ClassContextDto source) {
        String code = source.validationSourceCode() == null ? source.sourceCode() : source.validationSourceCode();
        if (code == null || code.isBlank()) return Set.of();
        try {
            var parsed = new com.github.javaparser.JavaParser(new com.github.javaparser.ParserConfiguration()
                    .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21)).parse(code);
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) return Set.of();
            var unit = parsed.getResult().get();
            var declarations = unit.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream()
                    .map(type -> (com.github.javaparser.ast.body.TypeDeclaration<?>) type)
                    .filter(type -> type.getFullyQualifiedName().filter(source.qualifiedName()::equals).isPresent()
                            || unit.getPackageDeclaration().isEmpty() && type.getNameAsString().equals(source.className())).toList();
            if (declarations.size() != 1) return Set.of();
            Set<String> fields = new LinkedHashSet<>();
            declarations.get(0).getFields().stream().filter(field -> field.isFinal()).forEach(field -> field.getVariables().stream()
                    .filter(variable -> variable.getInitializer().filter(value -> value.isObjectCreationExpr() || value.isMethodCallExpr()).isPresent())
                    .forEach(variable -> fields.add(variable.getNameAsString())));
            return fields;
        } catch (com.github.javaparser.ParseProblemException exception) {
            return Set.of();
        }
    }

    private static void mockPaths(Map<?, ?> mocks, String prefix, Set<String> paths) {
        mocks.forEach((key, value) -> {
            if (!(key instanceof String name)) return;
            String path = prefix + name;
            paths.add(path);
            if (value instanceof Map<?, ?> nested) mockPaths(nested, path + ".", paths);
        });
    }
}
