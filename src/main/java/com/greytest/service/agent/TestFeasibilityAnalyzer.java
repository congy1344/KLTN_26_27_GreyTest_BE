package com.greytest.service.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.greytest.dto.agent.GenerationContextDtos.*;

/** Phân tích bằng chứng nguồn và chiến lược fixture, không thực thi code người dùng. */
public final class TestFeasibilityAnalyzer {
    private static final Pattern RANDOM_VALUE = Pattern.compile(
            "(?:java\\.lang\\.)?Math\\.random\\s*(?:\\(\\s*\\))?\\s*(?:=\\s*)?(?:[<>!=]=?|=)\\s*[-+.0-9]|"
            + "[-+.0-9]+\\s*[<>]=?\\s*(?:java\\.lang\\.)?Math\\.random");
    private static final Pattern RANDOM_CONTROL = Pattern.compile("(?iu)\\b(branch|force|control|when|given|nhánh|ép|đặt|giả sử|thực thi|giá trị)\\b");
    private static final Pattern NEGATED_CONTROL = Pattern.compile("(?iu)(?:do\\s+not|don't|without|no\\s+need\\s+to|không|chưa)\\s+[^.;\\r\\n]{0,45}?(?:force|control|ép|điều khiển|đặt)");
    private static final Set<String> MUTATIONS = Set.of("add", "addAll", "remove", "removeAll", "removeIf", "clear", "set", "put", "putAll", "sort", "replaceAll");
    private TestFeasibilityAnalyzer() {}

    public enum Status { READY, BLOCKED, UNKNOWN }
    public record Finding(String filePath, String methodName, Integer line, String code, String detail) {}
    public record MethodAssessment(Long methodId, Status status, List<Finding> findings, List<String> strategies) {}
    public record CaseAssessment(Long caseId, Long methodId, Status status, List<Finding> findings, List<String> strategies) {}
    public record Report(List<MethodAssessment> methods, List<CaseAssessment> cases) {}

    public static Report analyze(Object context) {
        Context selected = context(context);
        List<MethodAssessment> methods = new ArrayList<>();
        for (ClassContextDto owner : selected.classes()) {
            for (MethodContextDto method : safe(owner.methods())) {
                CaseAssessment result = inspect(owner, method, "");
                methods.add(new MethodAssessment(method.id(), result.status(), result.findings(), result.strategies()));
            }
        }
        List<CaseAssessment> cases = new ArrayList<>();
        for (TestCaseContextItemDto testCase : selected.cases()) {
            Set<Long> methodIds = new LinkedHashSet<>();
            for (TestPlanContextItemDto plan : selected.plans()) {
                if (!Objects.equals(plan.id(), testCase.testPlanId())) continue;
                Set<Long> ruleIds = new LinkedHashSet<>(safe(plan.coveredRuleIds()));
                ruleIds.add(plan.businessRuleId());
                for (BusinessRuleContextDto rule : selected.rules()) {
                    if (ruleIds.contains(rule.id()) && rule.methodId() != null) methodIds.add(rule.methodId());
                }
            }
            String scenario = text(testCase.description()) + "\n" + text(testCase.preconditions()) + "\n" + text(testCase.testData());
            CaseAssessment result = methodIds.size() == 1
                    ? analyzeScenario(selected.classes(), methodIds.iterator().next(), scenario)
                    : unknown(null, null, "AMBIGUOUS_CASE_TARGET", "Không xác định duy nhất method từ Test Plan và Business Rule; cần người dùng kiểm tra.");
            cases.add(new CaseAssessment(testCase.id(), result.methodId(), result.status(), result.findings(), result.strategies()));
        }
        return new Report(List.copyOf(methods), List.copyOf(cases));
    }

    public static CaseAssessment analyzeScenario(List<ClassContextDto> classes, Long methodId, String scenarioText) {
        for (ClassContextDto owner : safe(classes)) {
            for (MethodContextDto method : safe(owner.methods())) {
                if (methodId != null && methodId.equals(method.id())) return inspect(owner, method, text(scenarioText));
            }
        }
        return unknown(null, methodId, "MISSING_METHOD_CONTEXT", "Không có source method đã chọn; không suy luận khả năng kiểm thử.");
    }

    private static CaseAssessment inspect(ClassContextDto owner, MethodContextDto target, String scenario) {
        if (owner.validationSourceCode() == null || owner.validationSourceCode().isBlank()) {
            return unknown(owner, target.id(), "MISSING_VALIDATION_SOURCE", "Thiếu source đầy đủ để kiểm chứng; source hiển thị có thể đã rút gọn.");
        }
        var parsed = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(owner.validationSourceCode());
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            return unknown(owner, target.id(), "SOURCE_PARSE_FAILED", "Không parse được source đầy đủ bằng JavaParser; cần kiểm tra thủ công.");
        }
        var unit = parsed.getResult().orElseThrow();
        var owners = unit.findAll(TypeDeclaration.class).stream()
                .filter(type -> type.getFullyQualifiedName().orElse(type.getNameAsString()).equals(owner.qualifiedName())).toList();
        if (owners.size() != 1) return unknown(owner, target.id(), "METHOD_SOURCE_UNRESOLVED", "Không xác định duy nhất class chứa method đã chọn.");
        TypeDeclaration<?> type = owners.get(0);
        var candidates = type.getMethodsByName(target.methodName()).stream().filter(method -> matches(method, target)).toList();
        if (candidates.size() != 1) return unknown(owner, target.id(), "METHOD_SOURCE_UNRESOLVED", "Không xác định duy nhất overload của method đã chọn.");
        MethodDeclaration method = candidates.get(0);
        return assessMethod(owner, target, scenario, type, method);
    }

    private static CaseAssessment assessMethod(ClassContextDto owner, MethodContextDto target, String scenario,
            TypeDeclaration<?> type, MethodDeclaration method) {
        List<Finding> findings = new ArrayList<>();
        Set<String> strategies = new LinkedHashSet<>();
        Map<String, String> types = new LinkedHashMap<>();
        for (var field : type.getFields()) for (var variable : field.getVariables()) types.put(variable.getNameAsString(), variable.getTypeAsString());
        for (var parameter : method.getParameters()) types.put(parameter.getNameAsString(), parameter.getTypeAsString());
        for (var local : method.findAll(VariableDeclarator.class)) types.put(local.getNameAsString(), local.getTypeAsString());
        boolean uncertain = false;
        boolean blocked = false;
        if (method.isPrivate()) {
            findings.add(finding(owner, target, method, "PRIVATE_HELPER_ENTRY_POINT",
                    "Method private phải được kiểm thử qua entry point hiện có, chưa phải một Act trực tiếp."));
            strategies.add("Chọn public entry point có trong source và lập fixture cho toàn bộ đường gọi tới helper: "
                    + "repository collection/Optional phải chứa entity đã duyệt, client phải trả DTO cần dùng trước Act. "
                    + "Khai báo DTO riêng không cấu hình mock; không dựa vào collection rỗng hoặc null mặc định, không đổi expected result.");
            uncertain = true;
        }
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (call.findAncestor(MethodDeclaration.class).orElse(null) != method) continue;
            String scope = call.getScope().map(Object::toString).orElse("");
            String receiver = scope.startsWith("this.") ? scope.substring(5) : scope;
            String clock = clockArgument(call, types);
            if (isMathRandom(call, type, method)) {
                boolean branch = conditional(call, method) && call.findAncestor(LambdaExpr.class).isEmpty();
                String code = branch ? "UNCONTROLLED_RANDOM_BRANCH" : "UNCONTROLLED_RANDOM";
                findings.add(finding(owner, target, call, code, "Math.random() của JDK không có đầu vào điều khiển được trong fixture JUnit/Mockito hiện tại."));
                strategies.add("Không dùng seed, retry hoặc mock static java.lang.Math để ép nhánh; cần seam Random/DoubleSupplier được người dùng duyệt trước khi sinh test nhánh này.");
                blocked |= branch && requiresRandomValue(scenario);
                uncertain = true;
            } else if (isInjectedSource(types.get(receiver)) || clock != null) {
                if (clock != null) receiver = clock;
                boolean internal = createdInside(type, method, receiver);
                findings.add(finding(owner, target, call, internal ? "UNCONTROLLED_NONDETERMINISM" : "INJECTABLE_NONDETERMINISM",
                        internal ? "Nguồn random/thời gian được tạo nội bộ, chưa có seam đã kiểm chứng." : "Nguồn random/thời gian có collaborator rõ ràng: " + scope + "."));
                strategies.add(internal ? "Dùng collaborator thật; kiểm tra khả năng truyền nguồn random/thời gian trước khi yêu cầu test một nhánh cụ thể."
                        : "Cấp Random/DoubleSupplier có giá trị cố định hoặc Clock.fixed qua constructor/setter hiện có; chỉ stub API thực sự có trong source.");
                uncertain |= internal;
            } else if (uncontrolledTime(call)) {
                findings.add(finding(owner, target, call, "UNCONTROLLED_TIME", "Nguồn thời gian hệ thống chưa có Clock điều khiển được."));
                strategies.add("Không so sánh timestamp tuyệt đối từ đồng hồ hệ thống; cần Clock có thể truyền vào hoặc assertion theo khoảng thời gian phù hợp scenario.");
                uncertain = true;
            }
            if ((scope.isEmpty() || scope.equals("this")) && !type.getMethodsByName(call.getNameAsString()).isEmpty()
                    && !call.getNameAsString().equals(method.getNameAsString())) {
                // shortcut: chưa phân tích đường đi qua helper; mở rộng khi có regression chứng minh cần hỗ trợ.
                findings.add(finding(owner, target, call, "UNRESOLVED_HELPER_PATH", "Lời gọi helper nội bộ chưa được phân tích đường đi trong MVP: " + call.getNameAsString() + "."));
                strategies.add("Kiểm tra đường đi qua helper trước khi kết luận có thể điều khiển nhánh; không thay kết quả helper bằng giả định.");
                uncertain = true;
            }
            if (MUTATIONS.contains(call.getNameAsString()) && collectionType(types.get(receiver))) {
                findings.add(finding(owner, target, call, "COLLECTION_MUTATION", "Method sửa trực tiếp collection fixture: " + scope + "."));
                strategies.add("Tạo collection mutable thực; lưu bản sao expected trước khi gọi method, không tính expected từ fixture đã bị sửa.");
            }
        }
        collectFinalFixtures(owner, target, type, method, findings, strategies);
        return new CaseAssessment(null, target.id(), blocked ? Status.BLOCKED : uncertain ? Status.UNKNOWN : Status.READY,
                List.copyOf(findings), List.copyOf(strategies));
    }

    private static void collectFinalFixtures(ClassContextDto owner, MethodContextDto target, TypeDeclaration<?> type,
            MethodDeclaration method, List<Finding> findings, Set<String> strategies) {
        for (var field : type.getFields()) {
            if (!field.isFinal()) continue;
            for (var variable : field.getVariables()) {
                if (createdInside(type, method, variable.getNameAsString()) && references(method, variable.getNameAsString())) {
                    findings.add(finding(owner, target, variable, "INTERNAL_FINAL_COLLABORATOR", "Collaborator final được tạo bên trong class: " + variable.getNameAsString() + "."));
                    strategies.add("Dùng collaborator/collection thật và kiểm tra trạng thái qua API có sẵn; không giả định @InjectMocks thay được field final đã khởi tạo.");
                }
            }
        }
    }

    private static boolean requiresRandomValue(String scenario) {
        scenario = scenario.replaceAll("(?m)^\\[GreyTest testability:[^\\r\\n]*", "");
        if (NEGATED_CONTROL.matcher(scenario).find()) return false;
        var comparison = RANDOM_VALUE.matcher(scenario);
        if (!comparison.find()) return false;
        String prefix = scenario.substring(0, comparison.start()).trim();
        // Chỉ chặn yêu cầu ép nhánh/giá trị; việc nhắc lại biểu thức trong mô tả source chưa đủ bằng chứng.
        return prefix.isEmpty() || RANDOM_CONTROL.matcher(scenario).find()
                || scenario.substring(comparison.start()).matches("(?s)(?:java\\.lang\\.)?Math\\.random\\s*(?:\\(\\s*\\))?\\s*=.*");
    }

    private static String clockArgument(MethodCallExpr call, Map<String, String> types) {
        if (!call.getNameAsString().equals("now")) return null;
        for (var argument : call.getArguments()) {
            String name = argument.toString().replaceFirst("^this\\.", "");
            if (simpleType(types.get(name)).equals("Clock")) return name;
        }
        return null;
    }

    private static boolean createdInside(TypeDeclaration<?> type, MethodDeclaration method, String name) {
        if (method.findAll(VariableDeclarator.class).stream().anyMatch(value -> value.getNameAsString().equals(name) && value.getInitializer().isPresent())) return true;
        if (type.getFields().stream().flatMap(field -> field.getVariables().stream())
                .anyMatch(value -> value.getNameAsString().equals(name) && value.getInitializer().isPresent())) return true;
        return type.getConstructors().stream().anyMatch(constructor -> constructor.findAll(AssignExpr.class).stream().anyMatch(assignment -> {
            String assigned = assignment.getTarget().toString();
            if (!assigned.equals(name) && !assigned.equals("this." + name)) return false;
            return !assignment.getValue().isNameExpr() || constructor.getParameters().stream()
                    .noneMatch(parameter -> parameter.getNameAsString().equals(assignment.getValue().toString()));
        }));
    }

    private static boolean matches(MethodDeclaration method, MethodContextDto target) {
        if (method.getParameters().size() != safe(target.parameters()).size()) return false;
        for (int index = 0; index < method.getParameters().size(); index++) {
            String actual = method.getParameter(index).getTypeAsString() + (method.getParameter(index).isVarArgs() ? "[]" : "");
            String expected = target.parameters().get(index).type();
            if (!simpleType(actual).equals(simpleType(expected))) return false;
        }
        return true;
    }

    private static String simpleType(String type) {
        return text(type).replace("...", "[]").replaceAll("[a-zA-Z_$][\\w$]*\\.", "").replaceAll("\\s", "");
    }

    private static boolean isMathRandom(MethodCallExpr call, TypeDeclaration<?> owner, MethodDeclaration method) {
        if (!call.getNameAsString().equals("random") || !call.getArguments().isEmpty()) return false;
        String scope = call.getScope().map(Object::toString).orElse("");
        if (scope.equals("java.lang.Math")) return true;
        if (!scope.equals("Math")) return false;
        var unit = owner.findCompilationUnit().orElseThrow();
        if (unit.findAll(ClassOrInterfaceDeclaration.class).stream().anyMatch(type -> type.getNameAsString().equals("Math"))) return false;
        if (unit.getImports().stream().anyMatch(imported -> !imported.isStatic() && !imported.isAsterisk()
                && imported.getNameAsString().endsWith(".Math") && !imported.getNameAsString().equals("java.lang.Math"))) return false;
        if (owner.getFields().stream().anyMatch(field -> field.getVariables().stream().anyMatch(variable -> variable.getNameAsString().equals("Math")))) return false;
        return method.getParameters().stream().noneMatch(parameter -> parameter.getNameAsString().equals("Math"))
                && method.findAll(VariableDeclarator.class).stream().noneMatch(variable -> variable.getNameAsString().equals("Math"));
    }

    private static boolean conditional(Node call, MethodDeclaration method) {
        for (Node parent = call; parent != method && parent.getParentNode().isPresent(); parent = parent.getParentNode().orElseThrow()) {
            if (parent instanceof IfStmt condition && condition.getCondition().isAncestorOf(call)) return true;
            if (parent instanceof ConditionalExpr condition && condition.getCondition().isAncestorOf(call)) return true;
        }
        return false;
    }

    private static boolean uncontrolledTime(MethodCallExpr call) {
        String scope = call.getScope().map(Object::toString).orElse("");
        return (call.getNameAsString().equals("now") && call.getArguments().isEmpty()
                && Set.of("Instant", "LocalDate", "LocalDateTime", "ZonedDateTime", "OffsetDateTime", "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.ZonedDateTime", "java.time.OffsetDateTime").contains(scope))
                || (Set.of("currentTimeMillis", "nanoTime").contains(call.getNameAsString()) && Set.of("System", "java.lang.System").contains(scope));
    }

    private static boolean isInjectedSource(String type) {
        return Set.of("Random", "DoubleSupplier", "Clock").contains(simpleType(type));
    }

    private static boolean collectionType(String type) {
        return Set.of("List", "Set", "Map", "Collection", "ArrayList", "LinkedList", "HashSet", "HashMap", "Deque", "Queue").contains(simpleType(type).replaceAll("<.*>", ""));
    }

    private static boolean references(MethodDeclaration method, String name) {
        return method.findAll(NameExpr.class).stream().anyMatch(value -> value.getNameAsString().equals(name))
                || method.findAll(FieldAccessExpr.class).stream().anyMatch(value -> value.getNameAsString().equals(name) && value.getScope().toString().equals("this"));
    }

    private static Finding finding(ClassContextDto owner, MethodContextDto target, Node node, String code, String detail) {
        return new Finding(owner.filePath(), target.methodName(), node.getBegin().map(position -> position.line).orElse(target.lineStart()), code, detail);
    }

    private static CaseAssessment unknown(ClassContextDto owner, Long methodId, String code, String detail) {
        return new CaseAssessment(null, methodId, Status.UNKNOWN,
                List.of(new Finding(owner == null ? null : owner.filePath(), null, null, code, detail)), List.of());
    }

    private record Context(List<ClassContextDto> classes, List<BusinessRuleContextDto> rules,
            List<TestPlanContextItemDto> plans, List<TestCaseContextItemDto> cases) {}

    private static Context context(Object context) {
        if (context instanceof BusinessRuleGenerationContextDto value) return new Context(safe(value.classes()), List.of(), List.of(), List.of());
        if (context instanceof BusinessRuleReviewContextDto value) return new Context(safe(value.classes()), safe(value.businessRules()), List.of(), List.of());
        if (context instanceof TestPlanContextDto value) return new Context(safe(value.classes()), safe(value.approvedBusinessRules()), List.of(), List.of());
        if (context instanceof TestCaseContextDto value) return new Context(safe(value.classes()), safe(value.approvedBusinessRules()), safe(value.approvedTestPlans()), List.of());
        if (context instanceof UnitTestContextDto value) return new Context(safe(value.classes()), safe(value.approvedBusinessRules()), safe(value.approvedTestPlans()), safe(value.approvedTestCases()));
        if (context instanceof CoverageRefinementContextDto value) return new Context(safe(value.classes()), safe(value.approvedBusinessRules()), safe(value.approvedTestPlans()), safe(value.approvedTestCases()));
        return new Context(List.of(), List.of(), List.of(), List.of());
    }

    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
}
