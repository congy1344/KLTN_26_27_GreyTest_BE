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

    public static Optional<String> validateTestCases(TestCaseContextDto context, TestCaseResponseDto response) {
        return SourceMockingSupport.validateTestCases(context, response);
    }

    public static Optional<String> validateReferences(Object context, Object response) {
        if (context instanceof BusinessRuleGenerationContextDto c && response instanceof BusinessRuleResponseDto result) {
            var targets = c.classes().stream().flatMap(javaClass -> javaClass.methods().stream())
                    .map(MethodContextDto::id).collect(Collectors.toSet());
            if (targets.isEmpty()) return Optional.empty();
            for (var rule : result.rules()) {
                if (!targets.contains(rule.methodId())) return Optional.of("method_id=" + rule.methodId() + " is outside the selected source methods.");
            }
        } else if (context instanceof TestPlanContextDto c && response instanceof TestPlanResponseDto result) {
            var rules = c.approvedBusinessRules().stream().collect(Collectors.toMap(BusinessRuleContextDto::id,
                    item -> item, (left, right) -> left));
            for (var plan : result.plans()) {
                Set<Long> covered = new HashSet<>(plan.coveredRuleIds());
                covered.add(plan.ruleId());
                for (Long ruleId : covered) {
                    var rule = rules.get(ruleId);
                    if (rule == null || !Objects.equals(rule.methodId(), plan.methodId())) {
                        return Optional.of("method_id=" + plan.methodId() + " has an unknown or unrelated rule_id=" + ruleId + ".");
                    }
                }
            }
        } else if (context instanceof TestCaseContextDto c && response instanceof TestCaseResponseDto result) {
            var plans = c.approvedTestPlans().stream().map(TestPlanContextItemDto::id).collect(Collectors.toSet());
            for (var item : result.cases()) {
                if (!plans.contains(item.planId())) return Optional.of("plan_id=" + item.planId() + " is outside the selected approved plans.");
            }
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
        return new PreparedUnitTests(new UnitTestContextDto(context.project(), context.analysis(), classes,
                context.approvedBusinessRules(), context.approvedTestPlans(), eligible, context.existingApprovedTestCases(),
                context.previousGeneratedUnitTests(), context.existingTests(), context.supportingResources(), context.contextWarnings()), blocked);
    }

    /** Chỉ gắn chú thích cho artifact mới chờ review; giữ nguyên BR và expected result đã duyệt. */
    public static Object addFeasibilityNotes(Object context, Object response) {
        if (context instanceof TestPlanContextDto c && response instanceof TestPlanResponseDto result) {
            var assessments = TestFeasibilityAnalyzer.analyze(c).methods().stream()
                    .collect(Collectors.toMap(MethodAssessment::methodId, item -> item, (left, right) -> left));
            return new TestPlanResponseDto(result.plans().stream().map(p -> {
                var assessment = assessments.get(p.methodId());
                String description = assessment == null ? p.description()
                        : note(p.description(), assessment.status(), assessment.findings(), assessment.strategies());
                return new GeneratedTestPlanDto(p.methodId(), p.ruleId(), p.coveredRuleIds(), p.title(), description, p.testType());
            }).toList());
        }
        if (context instanceof TestCaseContextDto c && response instanceof TestCaseResponseDto result) {
            return new TestCaseResponseDto(result.cases().stream().map(item -> {
                var plan = c.approvedTestPlans().stream().filter(p -> Objects.equals(p.id(), item.planId())).findFirst();
                if (plan.isEmpty()) return item;
                Set<Long> ruleIds = new HashSet<>();
                ruleIds.add(plan.get().businessRuleId());
                if (plan.get().coveredRuleIds() != null) ruleIds.addAll(plan.get().coveredRuleIds());
                var methodIds = c.approvedBusinessRules().stream().filter(r -> ruleIds.contains(r.id()))
                        .map(BusinessRuleContextDto::methodId).filter(Objects::nonNull).distinct().toList();
                // Không tự chọn một method nếu plan đang gộp nhiều method khác nhau.
                if (methodIds.size() != 1) return item;
                String scenario = item.description() + "\n" + item.preconditions() + "\n" + item.testData();
                var assessment = TestFeasibilityAnalyzer.analyzeScenario(c.classes(), methodIds.get(0), scenario);
                return new GeneratedTestCaseDto(item.planId(), item.testType(), item.description(),
                        note(item.preconditions(), assessment.status(), assessment.findings(), assessment.strategies()),
                        item.testData(), item.expectedResult(), item.priority(), item.traceSource());
            }).toList());
        }
        return response;
    }

    private static String note(String original, Status status, List<Finding> findings, List<String> strategies) {
        if (status == Status.READY && findings.isEmpty()) return original;
        String prefix = "[GreyTest testability: ";
        String text = original == null ? "" : original;
        String generatedNote = prefix + status + "] " + evidence(findings, strategies).replace('\n', ' ').replace('\r', ' ');
        return text.endsWith(generatedNote) ? text : text + "\n" + generatedNote;
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
