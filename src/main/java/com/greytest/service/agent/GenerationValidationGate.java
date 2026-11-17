package com.greytest.service.agent;

import java.util.*;
import java.util.stream.Collectors;
import com.github.javaparser.ast.CompilationUnit;
import com.greytest.dto.UnitTestFileDto;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;
import com.greytest.service.agent.ProjectJavaVersionDetector.MockitoVersionInfo;
import com.greytest.service.agent.ProjectJavaVersionDetector.TestFramework;
import com.greytest.service.agent.TestFeasibilityAnalyzer.*;

/** Điều phối kiểm tra source, traceability và fixture trước khi lưu hoặc xuất artifact sinh bởi AI. */
public final class GenerationValidationGate {
    private GenerationValidationGate() { }

    public record PreparedUnitTests(UnitTestContextDto context, List<BlockedUnitTestDto> blockedCases) { }

    public static Optional<String> validateContext(UnitTestContextDto context) {
        return GeneratedUnitTestSemanticValidator.validateContext(context);
    }

    public static Optional<String> validateUnitTests(UnitTestContextDto context, UnitTestResponseDto response,
            TestFramework framework, MockitoVersionInfo mockitoVersion) {
        return GeneratedUnitTestSemanticValidator.validate(context, response, framework, mockitoVersion);
    }

    public static GeneratedUnitTestSemanticValidator.Validation validateUnitTestsByCase(UnitTestContextDto context,
            UnitTestResponseDto response, TestFramework framework, MockitoVersionInfo mockitoVersion) {
        return GeneratedUnitTestSemanticValidator.validateByCase(context, response, framework, mockitoVersion);
    }

    public static Optional<String> validateTestCases(TestCaseContextDto context, TestCaseResponseDto response) {
        return validateTestCases(context, response, null);
    }

    /** reviewWarnings khác null: lỗi diễn đạt thông báo exception được trả về đó để người duyệt xem, không làm case thất bại. */
    public static Optional<String> validateTestCases(TestCaseContextDto context, TestCaseResponseDto response, List<String> reviewWarnings) {
        // Gửi đủ lỗi setup lẫn lỗi bằng chứng trong một lượt sửa, không để AI chỉ thấy nhóm lỗi đầu tiên.
        var problems = java.util.stream.Stream.of(SourceMockingSupport.validateTestCases(context, response),
                UpstreamSourceEvidenceValidator.cases(context, response, reviewWarnings)).flatMap(Optional::stream).toList();
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", problems));
    }

    public static Optional<String> validatePrerequisites(Object context) {
        return UpstreamSourceEvidenceValidator.prerequisites(context);
    }

    public static Optional<String> validateReferences(Object context, Object response) {
        // PostgreSQL không lưu được NUL; phản hồi phải được sửa trước transaction, không xóa dữ liệu ngầm.
        if (response != null && response.toString().indexOf('\0') >= 0) return Optional.of(
                "Response contains U+0000, which PostgreSQL text/JSONB cannot store. Regenerate the complete response without accidental NUL characters. "
                + "If an approved input intentionally requires this code point, use literal Java escape text and explicitly state its encoding; preserve the intended input and outcome.");
        if (context instanceof BusinessRuleGenerationContextDto c && response instanceof BusinessRuleResponseDto result) {
            var targets = c.classes().stream().flatMap(javaClass -> javaClass.methods().stream())
                    .map(MethodContextDto::id).collect(Collectors.toSet());
            if (targets.isEmpty()) return Optional.empty();
            for (var rule : result.rules()) {
                if (!targets.contains(rule.methodId())) return Optional.of("method_id=" + rule.methodId() + " is outside the selected source methods.");
            }
            return UpstreamSourceEvidenceValidator.businessRules(c, result);
        } else if (context instanceof TestPlanContextDto c && response instanceof TestPlanResponseDto result) {
            var rules = c.approvedBusinessRules().stream().collect(Collectors.toMap(BusinessRuleContextDto::id,
                    item -> item, (left, right) -> left));
            Set<Long> allCovered = new HashSet<>();
            for (var plan : result.plans()) {
                List<Long> rawCovered = plan.coveredRuleIds() == null ? List.of() : plan.coveredRuleIds();
                Set<Long> covered = new HashSet<>(rawCovered.stream().filter(Objects::nonNull).toList());
                if (plan.ruleId() != null) covered.add(plan.ruleId());
                allCovered.addAll(covered);
                for (Long ruleId : covered) {
                    var rule = rules.get(ruleId);
                    if (rule == null || !Objects.equals(rule.methodId(), plan.methodId())) {
                        var allowed = rules.values().stream().sorted(Comparator.comparing(BusinessRuleContextDto::id))
                                .collect(Collectors.groupingBy(BusinessRuleContextDto::methodId, TreeMap::new,
                                        Collectors.mapping(BusinessRuleContextDto::id, Collectors.toList())));
                        return Optional.of("method_id=" + plan.methodId() + " has an unknown or unrelated rule_id=" + ruleId
                                + ". Allowed approved rule IDs by method_id: " + allowed + ".");
                    }
                }
                // Đồng bộ điều kiện của TestPlanService: covered_rule_ids phải chứa anchor rule_id và không trùng lặp.
                if (rawCovered.isEmpty() || rawCovered.stream().anyMatch(Objects::isNull)
                        || plan.ruleId() == null || !rawCovered.contains(plan.ruleId())
                        || new HashSet<>(rawCovered).size() != rawCovered.size()) {
                    return Optional.of("method_id=" + plan.methodId() + ": covered_rule_ids " + rawCovered
                            + " must be non-empty, contain anchor rule_id=" + plan.ruleId()
                            + ", and have no duplicate rule IDs.");
                }
            }
            var missing = rules.keySet().stream().filter(id -> !allCovered.contains(id)).sorted().toList();
            if (!missing.isEmpty()) return Optional.of("Missing test plans for approved rule IDs " + missing
                    + ". Regenerate the complete plans array; preserve every approved rule and method_id.");
        } else if (context instanceof TestCaseContextDto c && response instanceof TestCaseResponseDto result) {
            var plans = c.approvedTestPlans().stream().map(TestPlanContextItemDto::id).collect(Collectors.toSet());
            Set<String> problems = new LinkedHashSet<>();
            for (var item : result.cases()) {
                if (!plans.contains(item.planId())) problems.add("plan_id=" + item.planId() + " is outside the selected approved plans.");
            }
            var covered = result.cases().stream().map(GeneratedTestCaseDto::planId).collect(Collectors.toSet());
            var missing = plans.stream().filter(id -> !covered.contains(id)).sorted().toList();
            if (!missing.isEmpty()) problems.add("Missing test cases for approved plan IDs " + missing + ".");
            if (!problems.isEmpty()) return Optional.of(String.join("\n", problems));
        }
        return Optional.empty();
    }

    public static PreparedUnitTests prepareUnitTests(UnitTestContextDto context) {
        var report = TestFeasibilityAnalyzer.analyze(context);
        var blocked = report.cases().stream().filter(item -> item.status() == Status.BLOCKED)
                .map(item -> new BlockedUnitTestDto(item.caseId(), context.approvedTestCases().stream()
                        .filter(c -> Objects.equals(c.id(), item.caseId())).map(TestCaseContextItemDto::caseCode).findFirst().orElse(null),
                        "BLOCKED: " + evidence(item.findings(), item.strategies())))
                .toList();
        if (blocked.isEmpty()) return new PreparedUnitTests(context, List.of());
        var blockedIds = blocked.stream().map(BlockedUnitTestDto::caseId).collect(Collectors.toSet());
        var eligible = context.approvedTestCases().stream().filter(c -> !blockedIds.contains(c.id())).toList();
        return new PreparedUnitTests(withCases(context, eligible), blocked);
    }

    /** Context cho lượt sửa: chỉ gồm case lỗi cùng rule, plan và source liên quan đến chúng. */
    /** Chỉ đổi tập plan cần đối chiếu; source và rule giữ nguyên làm bằng chứng. */
    public static TestCaseContextDto narrowTestCases(TestCaseContextDto context, Set<Long> planIds) {
        return new TestCaseContextDto(context.project(), context.analysis(), context.classes(), context.approvedBusinessRules(),
                context.approvedTestPlans().stream().filter(plan -> planIds.contains(plan.id())).toList(),
                context.existingTests(), context.supportingResources(), context.contextWarnings());
    }

    public static UnitTestContextDto narrowUnitTests(UnitTestContextDto context, Set<Long> caseIds) {
        var narrowed = withCases(context, context.approvedTestCases().stream().filter(c -> caseIds.contains(c.id())).toList());
        var planIds = narrowed.approvedTestCases().stream().map(TestCaseContextItemDto::testPlanId).collect(Collectors.toSet());
        var plans = narrowed.approvedTestPlans().stream().filter(p -> planIds.contains(p.id())).toList();
        Set<Long> ruleIds = new HashSet<>();
        plans.forEach(p -> {
            ruleIds.add(p.businessRuleId());
            if (p.coveredRuleIds() != null) ruleIds.addAll(p.coveredRuleIds());
        });
        var rules = UpstreamSourceEvidenceValidator.withUserAuthoredSiblings(narrowed.approvedBusinessRules(), ruleIds);
        var originalTargets = context.classes().stream().filter(c -> !c.methods().isEmpty()).map(ClassContextDto::qualifiedName).collect(Collectors.toSet());
        var targetSources = narrowed.classes().stream().filter(c -> !c.methods().isEmpty()).map(c -> Objects.toString(c.validationSourceCode(), "")).toList();
        // Class target của case đã đạt chỉ giữ lại khi source của case còn lỗi vẫn nhắc tới nó.
        var classes = narrowed.classes().stream().filter(c -> !c.methods().isEmpty() || !originalTargets.contains(c.qualifiedName())
                || targetSources.stream().anyMatch(source -> source.contains(c.className()))).toList();
        return new UnitTestContextDto(narrowed.project(), narrowed.analysis(), classes, rules, plans, narrowed.approvedTestCases(),
                narrowed.existingApprovedTestCases(), narrowed.previousGeneratedUnitTests(), narrowed.existingTests(),
                narrowed.supportingResources(), narrowed.contextWarnings());
    }

    private static UnitTestContextDto withCases(UnitTestContextDto context, List<TestCaseContextItemDto> eligible) {
        var eligiblePlans = eligible.stream().map(TestCaseContextItemDto::testPlanId).collect(Collectors.toSet());
        Set<Long> ruleIds = new HashSet<>();
        context.approvedTestPlans().stream().filter(p -> eligiblePlans.contains(p.id())).forEach(p -> {
            ruleIds.add(p.businessRuleId());
            if (p.coveredRuleIds() != null) ruleIds.addAll(p.coveredRuleIds());
        });
        var methodIds = context.approvedBusinessRules().stream().filter(r -> ruleIds.contains(r.id()))
                .map(BusinessRuleContextDto::methodId).collect(Collectors.toSet());
        var classes = context.classes().stream().map(c -> new ClassContextDto(c.id(), c.packageName(), c.className(),
                c.qualifiedName(), c.classType(), c.filePath(), c.sourceCode(), c.annotations(),
                c.methods().stream().filter(m -> methodIds.contains(m.id())).toList(), c.validationSourceCode())).toList();
        return new UnitTestContextDto(context.project(), context.analysis(), classes,
                context.approvedBusinessRules(), context.approvedTestPlans(), eligible, context.existingApprovedTestCases(),
                context.previousGeneratedUnitTests(), context.existingTests(), context.supportingResources(), context.contextWarnings());
    }

    public static String evidence(List<Finding> findings, List<String> strategies) {
        String source = findings.stream().limit(5).map(f -> f.code() + " @ " + f.filePath() + ":" + f.line()
                + " " + f.methodName() + " — " + f.detail()).collect(Collectors.joining("; "));
        return source + (strategies.isEmpty() ? "" : " | " + String.join("; ", strategies));
    }

    public static Optional<String> validateExportFile(UnitTestFileDto file, CompilationUnit unit, String path) {
        var type = unit.getTypes().stream().filter(t -> t.getNameAsString().equals(file.testClassName())).findFirst();
        if (type.isEmpty()) return Optional.of("Không tìm thấy test class trong source: " + file.testClassName());
        String expectedPackage = file.packageName() == null ? "" : file.packageName();
        if (!unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("").equals(expectedPackage)) {
            return Optional.of("Package Unit Test không khớp với source: " + file.testClassName());
        }
        String expectedPath = "src/test/java/" + (expectedPackage.isBlank() ? "" : expectedPackage.replace('.', '/') + "/")
                + file.testClassName() + ".java";
        var importProblems = GeneratedMockitoImportValidator.validate(unit);
        if (!importProblems.isEmpty()) return Optional.of(String.join(" ", importProblems));
        return expectedPath.equals(path) ? Optional.empty() : Optional.of("Đường dẫn Unit Test không khớp với package/class");
    }
}
