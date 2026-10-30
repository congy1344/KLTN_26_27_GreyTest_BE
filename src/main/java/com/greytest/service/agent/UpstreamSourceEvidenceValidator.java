package com.greytest.service.agent;

import java.util.*;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;

/** Chặn suy diễn sai ở BR/TC và kiểm tra artifact nguồn trước khi tốn lượt gọi AI tiếp theo. */
final class UpstreamSourceEvidenceValidator {
    private UpstreamSourceEvidenceValidator() { }

    static Optional<String> businessRules(BusinessRuleGenerationContextDto context, BusinessRuleResponseDto response) {
        var evidence = new SourceExceptionMessageEvidence(context.classes());
        Set<String> problems = new LinkedHashSet<>();
        for (var rule : response.rules()) {
            var problem = evidence.validate(context.classes(), rule.methodId(), rule.description(), null, true);
            problem.ifPresent(problems::add);
        }
        // Gửi đủ lỗi của batch trong một lượt sửa, tránh hết retry vì mỗi lần chỉ thấy lỗi đầu tiên.
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", problems));
    }

    static Optional<String> cases(TestCaseContextDto context, TestCaseResponseDto response) {
        return cases(context, response, true);
    }

    private static Optional<String> cases(TestCaseContextDto context, TestCaseResponseDto response, boolean requireProof) {
        var index = new SourceEvidenceIndex(context.classes());
        var messages = new SourceExceptionMessageEvidence(context.classes());
        Set<String> problems = new LinkedHashSet<>();
        for (var item : response.cases()) {
            var plan = context.approvedTestPlans().stream().filter(p -> Objects.equals(p.id(), item.planId())).findFirst().orElse(null);
            if (plan == null) continue;
            var rules = linkedRules(context.approvedBusinessRules(), plan);
            // Kỳ vọng do người dùng đặt/sửa có thể cố ý phơi bày lỗi source; không ép nó khớp implementation.
            if (!sourceDerived(rules, plan)) continue;
            Set<Long> ids = new HashSet<>(); rules.forEach(r -> ids.add(r.methodId()));
            for (var source : context.classes()) for (var method : source.methods()) if (ids.contains(method.id())) {
                Map<String, Object> input = input(item);
                var problem = messages.validate(context.classes(), method.id(), item.expectedResult(), input, requireProof);
                problem.ifPresent(p -> problems.add("plan_id=" + item.planId() + ": " + p));
                // Interaction đã được BR/TP yêu cầu rõ ràng phải được giữ để kiểm chứng source, không tự sửa oracle.
                String approved = rules.stream().map(BusinessRuleContextDto::description).reduce("", (a, b) -> a + " " + b)
                        + " " + plan.title() + " " + plan.description();
                problem = new SourceCollectionCallEvidence(index, item, approved).validate(method);
                problem.ifPresent(problems::add);
            }
        }
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", problems));
    }

    static Optional<String> prerequisites(Object context) {
        List<ClassContextDto> classes;
        List<BusinessRuleContextDto> rules;
        if (context instanceof TestPlanContextDto c) { classes = c.classes(); rules = c.approvedBusinessRules(); }
        else if (context instanceof TestCaseContextDto c) { classes = c.classes(); rules = c.approvedBusinessRules(); }
        else if (context instanceof UnitTestContextDto c) { classes = c.classes(); rules = c.approvedBusinessRules(); }
        else return Optional.empty();
        var messages = new SourceExceptionMessageEvidence(classes);
        for (var rule : rules) if (sourceDerived(rule)) {
            var problem = messages.validate(classes, rule.methodId(), rule.description(), null, false);
            if (problem.isPresent()) return Optional.of("Upstream BR " + rule.id() + " (" + rule.ruleCode()
                    + ") requires review before further generation: " + problem.get() + " Do not rewrite an approved business expectation automatically.");
        }
        if (context instanceof UnitTestContextDto c) {
            var sourceContext = new TestCaseContextDto(c.project(), c.analysis(), classes, rules, c.approvedTestPlans(), c.existingTests());
            for (var item : c.approvedTestCases()) {
                if (Boolean.TRUE.equals(item.isModified()) || "APPROVED".equals(item.status())
                        && (Objects.toString(item.description(), "") + " " + Objects.toString(item.preconditions(), "")
                            + " " + Objects.toString(item.expectedResult(), "") + " " + Objects.toString(item.traceSource(), "")).contains("SOURCE_MISMATCH")) continue;
                var generated = new GeneratedTestCaseDto(item.testPlanId(), item.testType(), item.description(), item.preconditions(),
                        item.testData(), item.expectedResult(), item.priority(), item.traceSource());
                var problem = cases(sourceContext, new TestCaseResponseDto(List.of(generated)), false);
                if (problem.isPresent()) return Optional.of("Upstream TC " + item.id() + " (" + item.caseCode()
                        + ") requires review before Unit Test generation: " + problem.get() + " Preserve approved requirements; review the originating BR/TC.");
            }
        }
        return Optional.empty();
    }

    private static boolean sourceDerived(BusinessRuleContextDto rule) {
        return "AI_GENERATED".equals(rule.source()) && !Boolean.TRUE.equals(rule.isModified())
                && !(Objects.toString(rule.reviewNote(), "") + " " + rule.description()).contains("SOURCE_MISMATCH");
    }
    private static boolean sourceDerived(List<BusinessRuleContextDto> rules, TestPlanContextItemDto plan) {
        return !rules.isEmpty() && rules.stream().allMatch(UpstreamSourceEvidenceValidator::sourceDerived)
                && !Boolean.TRUE.equals(plan.isModified()) && !Objects.toString(plan.description(), "").contains("SOURCE_MISMATCH");
    }
    private static List<BusinessRuleContextDto> linkedRules(List<BusinessRuleContextDto> rules, TestPlanContextItemDto plan) {
        Set<Long> ids = new HashSet<>(plan.coveredRuleIds() == null ? List.of() : plan.coveredRuleIds()); ids.add(plan.businessRuleId());
        return rules.stream().filter(r -> ids.contains(r.id())).toList();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> input(GeneratedTestCaseDto item) {
        Object value = item.testData() == null ? null : item.testData().get("input");
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
}
