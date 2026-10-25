package com.greytest.service.agent;

import java.util.*;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;

/** Đối chiếu boolean assertion với fixture và phép làm giàu bằng tập ID đã chứng minh từ source. */
final class GeneratedBooleanAssertionValidator {
    private static final Object UNKNOWN = new Object();
    private static final Object NULL = new Object();
    private static final Object NON_NULL = new Object();
    private record Fixture(ClassOrInterfaceDeclaration type, Map<String, Object> fields) { }
    private record Stub(String scope, String method, List<Expression> arguments, Expression value) { }
    private record Rule(String setter, String keyGetter, Object selected, boolean assigned, Stub evidence, Set<String> mutations) { }
    private record Visit(MethodDeclaration method, Set<String> lists, Map<String, Object> facts) { }
    private final Map<String, ClassOrInterfaceDeclaration> types = new LinkedHashMap<>();
    private final Map<String, Expression> locals = new HashMap<>();
    private final List<Stub> stubs = new ArrayList<>();
    private final Map<String, Object> values = new HashMap<>();
    private final Set<Stub> returnedDependencies = new HashSet<>();
    private final Set<Stub> provenArguments = new HashSet<>();
    private ClassOrInterfaceDeclaration owner;
    private MethodDeclaration target;
    private MethodDeclaration testMethod;
    private CompilationUnit test;

    private GeneratedBooleanAssertionValidator() { }

    static List<String> validate(UnitTestContextDto context, String ownerName, String method, CompilationUnit test) {
        if (context.contextWarnings() != null && context.contextWarnings().stream()
                .anyMatch(w -> w.toLowerCase(Locale.ROOT).contains("lombok"))) return List.of();
        if (context.supportingResources() != null && context.supportingResources().stream()
                .filter(r -> r.kind().equals("LOMBOK_CONFIGURATION"))
                .anyMatch(r -> r.content().contains("lombok.accessors.") || r.content().matches("(?s).*lombok\\.getter\\.noIsPrefix\\s*=\\s*true.*"))) return List.of();
        var checker = new GeneratedBooleanAssertionValidator();
        checker.test = test;
        if (test.findAll(AnnotationExpr.class).stream().anyMatch(a -> a.getName().getIdentifier().equals("Spy"))) return List.of();
        for (var source : context.classes()) {
            String text = source.validationSourceCode() == null ? source.sourceCode() : source.validationSourceCode();
            if (text == null) continue;
            new JavaParser().parse(text).getResult().ifPresent(unit -> unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(type -> type.getFullyQualifiedName().filter(source.qualifiedName()::equals).isPresent())
                    .forEach(type -> checker.types.put(source.qualifiedName(), type)));
        }
        checker.owner = checker.types.get(ownerName);
        if (checker.owner == null) return List.of();
        var methods = checker.owner.getMethodsByName(method);
        if (methods.size() != 1) return List.of();
        checker.target = methods.get(0);
        List<String> problems = new ArrayList<>();
        for (var candidate : test.findAll(MethodDeclaration.class)) {
            if (candidate.findAll(MethodCallExpr.class).stream().noneMatch(checker::isTargetCall)) continue;
            checker.testMethod = candidate;
            checker.locals.clear(); checker.values.clear(); checker.stubs.clear();
            checker.returnedDependencies.clear(); checker.provenArguments.clear();
            // shortcut: Chỉ chứng minh Arrange/Act/Assert thẳng; nhánh/loop/helper setup cần chạy test thật.
            if (!candidate.findAll(IfStmt.class).isEmpty() || !candidate.findAll(ForStmt.class).isEmpty()
                    || !candidate.findAll(ForEachStmt.class).isEmpty() || !candidate.findAll(WhileStmt.class).isEmpty()
                    || !candidate.findAll(DoStmt.class).isEmpty() || !candidate.findAll(SwitchStmt.class).isEmpty()
                    || !candidate.findAll(TryStmt.class).isEmpty() || !candidate.findAll(ConditionalExpr.class).isEmpty()) continue;
            var acts = candidate.findAll(MethodCallExpr.class).stream().filter(checker::isTargetCall).toList();
            if (acts.size() != 1 || candidate.findAll(MethodCallExpr.class).stream()
                    .anyMatch(c -> c.getNameAsString().equals("thenReturn") && c.getBegin().orElseThrow().isAfter(acts.get(0).getBegin().orElseThrow()))) continue;
            // Fixture có mutation cần trace riêng; bỏ qua thay vì dùng giá trị constructor đã cũ.
            if (candidate.findAll(MethodCallExpr.class).stream().anyMatch(c ->
                    c.getNameAsString().matches("set[A-Z].*") && !c.getArguments().isEmpty()
                    || Set.of("add", "addAll", "remove", "clear", "reverse", "sort", "shuffle").contains(c.getNameAsString()))) continue;
            candidate.findAll(VariableDeclarator.class).forEach(variable -> variable.getInitializer()
                    .ifPresent(value -> checker.locals.put(variable.getNameAsString(), value)));
            if (!candidate.findAll(AssignExpr.class).isEmpty()) continue;
            if (candidate.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    (call.getScope().isEmpty() || call.getScope().get().isThisExpr())
                        && test.findAll(MethodDeclaration.class).stream().anyMatch(m -> m != candidate && m.getNameAsString().equals(call.getNameAsString()))
                    || call.getBegin().orElseThrow().isBefore(acts.get(0).getBegin().orElseThrow())
                        && call.getScope().map(e -> checker.value(e, new HashSet<>())).filter(Fixture.class::isInstance)
                            .map(Fixture.class::cast).filter(f -> checker.property(f.type(), call.getNameAsString()) == null).isPresent())) continue;
            for (var call : candidate.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals("thenReturn") || call.getArguments().size() != 1
                        || call.getScope().filter(Expression::isMethodCallExpr).isEmpty()) continue;
                var when = call.getScope().get().asMethodCallExpr();
                if (!when.getNameAsString().equals("when") || when.getArguments().size() != 1
                        || !checker.imported(when, Set.of("org.mockito.Mockito"))
                        || !when.getArgument(0).isMethodCallExpr()) continue;
                var invocation = when.getArgument(0).asMethodCallExpr();
                invocation.getScope().filter(Expression::isNameExpr).ifPresent(scope -> checker.stubs.add(
                        new Stub(scope.toString(), invocation.getNameAsString(), new ArrayList<>(invocation.getArguments()), call.getArgument(0))));
            }
            if (candidate.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getInitializer().filter(e -> e.isNameExpr()
                    && checker.stubs.stream().anyMatch(stub -> stub.scope().equals(e.toString()))).isPresent())) continue;
            Object result = checker.returnedValue();
            if (result == UNKNOWN) continue;
            List<Rule> rules = checker.rules();
            for (var call : candidate.findAll(MethodCallExpr.class)) {
                if (!Set.of("assertTrue", "assertFalse").contains(call.getNameAsString()) || call.getArguments().isEmpty()
                        || !checker.imported(call, Set.of("org.junit.jupiter.api.Assertions"))) continue;
                var condition = call.getArgument(0);
                if (!condition.isMethodCallExpr()) continue;
                var getter = condition.asMethodCallExpr();
                Object receiver = getter.getScope().map(e -> checker.value(e, new HashSet<>())).orElse(UNKNOWN);
                if (!(receiver instanceof Fixture fixture)) continue;
                String property = checker.property(fixture.type(), getter.getNameAsString());
                if (property == null || !checker.directSetter(fixture.type(), property)) continue;
                var matching = rules.stream().filter(rule -> rule.setter().equals("set" + upper(property))).toList();
                if (matching.size() != 1) continue;
                Rule rule = matching.get(0);
                String keyProperty = checker.property(fixture.type(), rule.keyGetter());
                Object key = keyProperty == null ? UNKNOWN : fixture.fields().getOrDefault(keyProperty, UNKNOWN);
                Object initial = fixture.fields().getOrDefault(property, UNKNOWN);
                if (key == UNKNOWN || !(initial instanceof Boolean) || !(rule.selected() instanceof Collection<?> selected)
                        || selected.stream().anyMatch(item -> item == UNKNOWN)) continue;
                Set<Stub> evidence = new HashSet<>(checker.returnedDependencies); evidence.add(rule.evidence());
                var unverified = evidence.stream().filter(stub -> !checker.provenArguments.contains(stub) && !checker.verified(stub)).toList();
                if (!unverified.isEmpty()) {
                    for (var stub : unverified) {
                        String problem = "Source assertion contract: verify the exact mocked interaction " + stub.scope() + "."
                                + stub.method() + stub.arguments() + " after the SUT call before relying on its returned fixtures/ID membership. "
                                + "Add Mockito.verify with the same arguments, then preserve the approved Test Case assertions.";
                        if (!problems.contains(problem)) problems.add(problem);
                    }
                    continue;
                }
                // Assertion nghiệp vụ có thể cố ý khác source để phát hiện lỗi; chỉ giữ kiểm tra bằng chứng fixture.
            }
        }
        return problems;
    }

    private Object returnedValue() {
        List<Object> results = new ArrayList<>();
        Map<String, Object> facts = rootFacts();
        for (var returned : target.findAll(ReturnStmt.class)) {
            Boolean reachable = reachable(returned, facts);
            if (Boolean.FALSE.equals(reachable)) continue;
            if (reachable == null) return UNKNOWN;
            if (returned.getExpression().isEmpty()) continue;
            Expression expression = returned.getExpression().get();
            var names = expression.findAll(NameExpr.class).stream().map(NameExpr::getNameAsString).toList();
            Map<String, Object> bindings = new HashMap<>();
            for (var variable : target.findAll(VariableDeclarator.class)) {
                if (!names.contains(variable.getNameAsString()) || variable.getInitializer().filter(Expression::isMethodCallExpr).isEmpty()) continue;
                String name = variable.getNameAsString();
                boolean mutates = target.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                        Set.of("reverse", "sort", "shuffle", "remove", "add", "clear").contains(call.getNameAsString())
                        && (call.getScope().map(Object::toString).filter(name::equals).isPresent()
                            || call.getArguments().stream().anyMatch(arg -> arg.toString().equals(name))));
                if (mutates) continue;
                Object result = stubValue(variable.getInitializer().get().asMethodCallExpr());
                if (result instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(Fixture.class::isInstance)) {
                    bindings.put(name, result);
                    Stub dependency = matchedStub(variable.getInitializer().get().asMethodCallExpr());
                    if (dependency != null) returnedDependencies.add(dependency);
                }
            }
            if (bindings.isEmpty()) return UNKNOWN;
            if (expression.isNameExpr()) results.add(bindings.getOrDefault(expression.toString(), UNKNOWN));
            else if (expression.isObjectCreationExpr()) {
                var creation = expression.asObjectCreationExpr();
                var type = type(creation.getType().getNameWithScope(), target.findCompilationUnit().orElseThrow());
                if (type == null) return UNKNOWN;
                var args = creation.getArguments().stream().map(arg -> bindings.getOrDefault(arg.toString(), UNKNOWN)).toList();
                results.add(construct(type, args));
            }
        }
        results.removeIf(item -> item == UNKNOWN);
        if (results.size() != 1) return UNKNOWN;
        Object result = results.get(0);
        // Chỉ gắn kết quả với đúng lời gọi SUT; không suy ra thứ tự từ tên method.
        for (var local : locals.entrySet()) {
            if (local.getValue().isMethodCallExpr() && isTargetCall(local.getValue().asMethodCallExpr())) values.put(local.getKey(), result);
        }
        return result;
    }

    private List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        Set<String> rootLists = new HashSet<>();
        target.findAll(ReturnStmt.class).forEach(r -> r.getExpression().ifPresent(e ->
                e.findAll(NameExpr.class).forEach(n -> rootLists.add(n.getNameAsString()))));
        var queue = new ArrayDeque<Visit>(); queue.add(new Visit(target, rootLists, rootFacts()));
        Set<MethodDeclaration> reached = new HashSet<>();
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            Visit visit = queue.remove();
            if (!visited.add(visit.method().getSignature() + visit.lists().toString())) continue;
            reached.add(visit.method());
            for (var call : visit.method().findAll(MethodCallExpr.class)) {
                if (Set.of("reverse", "sort", "shuffle", "remove", "add", "clear").contains(call.getNameAsString())
                        && (call.getScope().map(Object::toString).filter(visit.lists()::contains).isPresent()
                            || call.getArguments().stream().anyMatch(arg -> visit.lists().contains(arg.toString())))) return List.of();
                if (!Boolean.TRUE.equals(reachable(call, visit.facts()))) continue;
                if (call.getScope().isEmpty() || call.getScope().get().isThisExpr()) {
                    var helpers = owner.getMethodsByName(call.getNameAsString()).stream()
                            .filter(m -> m.getParameters().size() == call.getArguments().size()).toList();
                    if (helpers.size() == 1) {
                        Set<String> aliases = new HashSet<>();
                        for (int i = 0; i < call.getArguments().size(); i++) {
                            if (visit.lists().contains(call.getArgument(i).toString())) aliases.add(helpers.get(0).getParameter(i).getNameAsString());
                        }
                        Map<String, Object> parameters = new HashMap<>();
                        for (int i = 0; i < call.getArguments().size(); i++) parameters.put(helpers.get(0).getParameter(i).getNameAsString(),
                                sourceValue(call.getArgument(i), visit.facts()));
                        if (!aliases.isEmpty()) queue.add(new Visit(helpers.get(0), aliases, sourceFacts(helpers.get(0), parameters)));
                    }
                }
            }
            for (var branch : visit.method().findAll(IfStmt.class)) {
                if (!Boolean.TRUE.equals(reachable(branch, visit.facts()))) continue;
                if (branch.getElseStmt().isPresent() || !branch.getCondition().isMethodCallExpr()) continue;
                var contains = branch.getCondition().asMethodCallExpr();
                if (!contains.getNameAsString().equals("contains") || contains.getArguments().size() != 1
                        || !contains.getArgument(0).isMethodCallExpr() || contains.getScope().filter(Expression::isNameExpr).isEmpty()) continue;
                var loops = branch.stream(Node.TreeTraversal.PARENTS).filter(MethodCallExpr.class::isInstance)
                        .map(MethodCallExpr.class::cast).filter(c -> c.getNameAsString().equals("forEach")
                                && c.getScope().map(Object::toString).filter(visit.lists()::contains).isPresent()).toList();
                if (loops.size() != 1) continue;
                var writes = branch.getThenStmt().findAll(MethodCallExpr.class).stream()
                        .filter(c -> c.getNameAsString().startsWith("set") && c.getArguments().size() == 1
                                && c.getArgument(0).isBooleanLiteralExpr()).toList();
                if (writes.size() != 1) continue;
                var setter = writes.get(0);
                if (setter.findAncestor(IfStmt.class).filter(branch::equals).isEmpty()) continue;
                var lambda = branch.findAncestor(LambdaExpr.class);
                if (lambda.isEmpty() || lambda.get().getParameters().size() != 1 || setter.getScope().isEmpty()) continue;
                Expression root = setter.getScope().get();
                while (root.isMethodCallExpr() && root.asMethodCallExpr().getArguments().isEmpty()
                        && root.asMethodCallExpr().getScope().isPresent()) root = root.asMethodCallExpr().getScope().get();
                if (!root.isNameExpr() || !root.toString().equals(lambda.get().getParameter(0).getNameAsString())) continue;
                var key = contains.getArgument(0).asMethodCallExpr();
                if (key.getArguments().size() != 0 || !Objects.equals(key.getScope(), setter.getScope())) continue;
                var declarations = visit.method().findAll(VariableDeclarator.class).stream()
                        .filter(v -> v.getNameAsString().equals(contains.getScope().get().toString())
                                && v.getInitializer().filter(Expression::isMethodCallExpr).isPresent()).toList();
                if (declarations.size() != 1) continue;
                var selectedType = declarations.get(0).getType();
                if (!selectedType.isClassOrInterfaceType()) continue;
                String collectionName = selectedType.asClassOrInterfaceType().getNameWithScope();
                var sourceUnit = visit.method().findCompilationUnit().orElseThrow();
                if (!Set.of("java.util.Set", "java.util.List", "java.util.Collection").contains(collectionName)
                        && (type(collectionName, sourceUnit) != null || sourceUnit.getImports().stream().noneMatch(i -> !i.isStatic()
                            && (i.getNameAsString().equals("java.util." + collectionName)
                                || i.isAsterisk() && i.getNameAsString().equals("java.util"))))) continue;
                Object selected = stubValue(declarations.get(0).getInitializer().get().asMethodCallExpr());
                Stub dependency = matchedStub(declarations.get(0).getInitializer().get().asMethodCallExpr());
                Set<String> mutations = new HashSet<>();
                setter.getScope().get().findAll(MethodCallExpr.class).stream().filter(c -> c.getNameAsString().matches("(?:get|is)[A-Z].*"))
                        .forEach(c -> mutations.add("set" + c.getNameAsString().substring(c.getNameAsString().startsWith("get") ? 3 : 2)));
                if (key.getNameAsString().matches("(?:get|is)[A-Z].*")) mutations.add("set"
                        + key.getNameAsString().substring(key.getNameAsString().startsWith("get") ? 3 : 2));
                if (selected instanceof Collection<?> && dependency != null) rules.add(new Rule(setter.getNameAsString(), key.getNameAsString(), selected,
                        setter.getArgument(0).asBooleanLiteralExpr().getValue(), dependency, mutations));
            }
        }
        rules.removeIf(rule -> reached.stream().flatMap(m -> m.findAll(MethodCallExpr.class).stream())
                .filter(call -> call.getNameAsString().equals(rule.setter())).count() != 1);
        rules.removeIf(rule -> reached.stream().flatMap(m -> m.findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> rule.mutations().contains(call.getNameAsString())));
        return rules;
    }

    private Map<String, Object> rootFacts() {
        Map<String, Object> facts = new HashMap<>();
        var invocations = testMethod.findAll(MethodCallExpr.class).stream().filter(this::isTargetCall).toList();
        if (invocations.size() == 1 && invocations.get(0).getArguments().size() == target.getParameters().size()) {
            for (int i = 0; i < target.getParameters().size(); i++) facts.put(target.getParameter(i).getNameAsString(),
                    inputFact(invocations.get(0).getArgument(i), new HashSet<>()));
        }
        return sourceFacts(target, facts);
    }

    private Object inputFact(Expression expression, Set<String> seen) {
        if (expression.isNullLiteralExpr()) return NULL;
        if (expression.isObjectCreationExpr()) return NON_NULL;
        if (expression.isNameExpr() && seen.add(expression.toString()) && locals.containsKey(expression.toString()))
            return inputFact(locals.get(expression.toString()), seen);
        return value(expression, new HashSet<>());
    }

    private Map<String, Object> sourceFacts(MethodDeclaration method, Map<String, Object> parameters) {
        Map<String, Object> facts = new HashMap<>(parameters);
        for (var variable : method.findAll(VariableDeclarator.class)) {
            if (variable.getInitializer().filter(Expression::isMethodCallExpr).isPresent()) {
                Object result = stubValue(variable.getInitializer().get().asMethodCallExpr());
                if (result != UNKNOWN) facts.put(variable.getNameAsString(), result);
            }
        }
        return facts;
    }

    private Boolean reachable(Node node, Map<String, Object> facts) {
        for (Node parent : node.stream(Node.TreeTraversal.PARENTS).toList()) {
            if (parent instanceof MethodDeclaration declaration) {
                if (!owner.getMethods().contains(declaration)) return null;
                break;
            }
            if (parent instanceof LambdaExpr lambda) {
                var invocation = lambda.getParentNode().filter(MethodCallExpr.class::isInstance).map(MethodCallExpr.class::cast);
                if (invocation.isEmpty() || !invocation.get().getNameAsString().equals("forEach")
                        || invocation.get().getScope().map(e -> sourceValue(e, facts)).filter(Collection.class::isInstance).isEmpty()) return null;
            }
            if (parent instanceof ForStmt || parent instanceof WhileStmt || parent instanceof SwitchEntry) return null;
            if (!(parent instanceof IfStmt branch)) continue;
            Object condition = sourceValue(branch.getCondition(), facts);
            if (!(condition instanceof Boolean selected)) return null;
            boolean thenBranch = branch.getThenStmt().equals(node) || branch.getThenStmt().isAncestorOf(node);
            if (selected != thenBranch) return false;
        }
        return true;
    }

    private Object sourceValue(Expression expression, Map<String, Object> facts) {
        if (expression.isNameExpr()) return facts.getOrDefault(expression.toString(), UNKNOWN);
        if (expression.isNullLiteralExpr()) return NULL;
        if (expression.isBooleanLiteralExpr()) return expression.asBooleanLiteralExpr().getValue();
        if (expression.isIntegerLiteralExpr()) return expression.asIntegerLiteralExpr().asInt();
        if (expression.isEnclosedExpr()) return sourceValue(expression.asEnclosedExpr().getInner(), facts);
        if (expression.isMethodCallExpr()) {
            var call = expression.asMethodCallExpr();
            Object receiver = call.getScope().map(e -> sourceValue(e, facts)).orElse(UNKNOWN);
            if (receiver instanceof Collection<?> list && call.getArguments().isEmpty()) {
                if (call.getNameAsString().equals("size")) return list.size();
                if (call.getNameAsString().equals("isEmpty")) return list.isEmpty();
            }
        }
        if (expression.isBinaryExpr()) {
            var binary = expression.asBinaryExpr();
            Object left = sourceValue(binary.getLeft(), facts), right = sourceValue(binary.getRight(), facts);
            if (left == UNKNOWN || right == UNKNOWN) return UNKNOWN;
            return switch (binary.getOperator()) {
                case EQUALS, NOT_EQUALS -> {
                    if (left != NULL && right != NULL && !primitiveOperand(binary.getLeft()) && !primitiveOperand(binary.getRight())) yield UNKNOWN;
                    boolean equals = Objects.equals(left, right);
                    yield binary.getOperator() == BinaryExpr.Operator.EQUALS ? equals : !equals;
                }
                case AND -> left instanceof Boolean a && right instanceof Boolean b ? a && b : UNKNOWN;
                case OR -> left instanceof Boolean a && right instanceof Boolean b ? a || b : UNKNOWN;
                default -> UNKNOWN;
            };
        }
        return UNKNOWN;
    }

    private boolean primitiveOperand(Expression expression) {
        return expression.isIntegerLiteralExpr() || expression.isBooleanLiteralExpr()
                || expression.isMethodCallExpr() && expression.asMethodCallExpr().getNameAsString().equals("size");
    }

    private Object stubValue(MethodCallExpr production) {
        Stub stub = matchedStub(production);
        if (stub == null) return UNKNOWN;
        if (stub.arguments().isEmpty()) provenArguments.add(stub);
        else {
            boolean literal = true;
            for (int i = 0; i < stub.arguments().size(); i++) {
                Expression expected = stub.arguments().get(i);
                if (expected.isMethodCallExpr() && expected.asMethodCallExpr().getNameAsString().equals("eq")
                        && imported(expected.asMethodCallExpr(), Set.of("org.mockito.ArgumentMatchers", "org.mockito.Mockito"))
                        && expected.asMethodCallExpr().getArguments().size() == 1) expected = expected.asMethodCallExpr().getArgument(0);
                if (!production.getArgument(i).isLiteralExpr() || !expected.isLiteralExpr()) { literal = false; break; }
                if (!production.getArgument(i).equals(expected)) return UNKNOWN;
            }
            if (literal) provenArguments.add(stub);
            else if (!isMock(stub.scope()) || !uniqueProductionCall(stub) || hasDirectMockCall(stub)) return UNKNOWN;
        }
        return value(stub.value(), new HashSet<>());
    }

    private Stub matchedStub(MethodCallExpr production) {
        String scope = production.getScope().map(Object::toString).orElse("");
        var matches = stubs.stream().filter(stub -> stub.scope().equals(scope) && stub.method().equals(production.getNameAsString())
                && stub.arguments().size() == production.getArguments().size()).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private boolean uniqueProductionCall(Stub stub) {
        Set<MethodDeclaration> reached = new HashSet<>(); var queue = new ArrayDeque<MethodDeclaration>(); queue.add(target);
        Map<MethodDeclaration, Integer> callsites = new HashMap<>(); callsites.put(target, 1);
        while (!queue.isEmpty()) {
            var method = queue.remove(); if (!reached.add(method)) continue;
            for (var call : method.findAll(MethodCallExpr.class)) if (call.getScope().isEmpty() || call.getScope().get().isThisExpr()) {
                var helpers = owner.getMethodsByName(call.getNameAsString()).stream().filter(m -> m.getParameters().size() == call.getArguments().size()).toList();
                if (helpers.size() == 1) {
                    if (callsites.merge(helpers.get(0), 1, Integer::sum) > 1 || repeated(call)) return false;
                    queue.add(helpers.get(0));
                }
            }
        }
        if (reached.stream().flatMap(m -> m.findAll(MethodCallExpr.class).stream()).anyMatch(c -> c.getScope().map(Object::toString)
                .filter(stub.scope()::equals).isPresent() && c.getNameAsString().equals(stub.method()) && repeated(c))) return false;
        return reached.stream().flatMap(m -> m.findAll(MethodCallExpr.class).stream()).filter(c ->
                c.getScope().map(Object::toString).filter(stub.scope()::equals).isPresent()
                    && c.getNameAsString().equals(stub.method()) && c.getArguments().size() == stub.arguments().size()).count() == 1;
    }

    private boolean repeated(Node node) {
        return node.stream(Node.TreeTraversal.PARENTS).anyMatch(n -> n instanceof LambdaExpr || n instanceof ForStmt
                || n instanceof ForEachStmt || n instanceof WhileStmt || n instanceof DoStmt);
    }

    private boolean hasDirectMockCall(Stub stub) {
        return testMethod.findAll(MethodCallExpr.class).stream().filter(c -> c.getScope().map(Object::toString).filter(stub.scope()::equals).isPresent())
                .anyMatch(c -> c.findAncestor(MethodCallExpr.class).filter(parent -> parent.getNameAsString().equals("when")
                        && imported(parent, Set.of("org.mockito.Mockito")) && parent.getArguments().contains(c)).isEmpty());
    }

    private boolean isMock(String name) {
        return test.findAll(VariableDeclarator.class).stream().filter(v -> v.getNameAsString().equals(name)).anyMatch(variable ->
                variable.findAncestor(FieldDeclaration.class).filter(field -> field.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("org.mockito.Mock") || a.getNameAsString().equals("Mock")
                            && test.getImports().stream().anyMatch(i -> !i.isStatic() && i.getNameAsString().equals("org.mockito.Mock")))).isPresent()
                    || variable.getInitializer().filter(Expression::isMethodCallExpr).map(Expression::asMethodCallExpr)
                        .filter(call -> call.getNameAsString().equals("mock") && imported(call, Set.of("org.mockito.Mockito"))).isPresent());
    }

    private boolean verified(Stub stub) {
        var act = testMethod.findAll(MethodCallExpr.class).stream().filter(this::isTargetCall).findFirst();
        if (act.isEmpty()) return false;
        return testMethod.findAll(MethodCallExpr.class).stream().filter(call -> call.getNameAsString().equals(stub.method())
                && call.getArguments().stream().toList().equals(stub.arguments()) && call.getScope().filter(Expression::isMethodCallExpr).isPresent()
                && call.getParentNode().filter(ExpressionStmt.class::isInstance).map(ExpressionStmt.class::cast)
                    .filter(statement -> statement.getExpression().equals(call)
                        && statement.getParentNode().filter(testMethod.getBody().orElseThrow()::equals).isPresent()).isPresent()
                && call.getBegin().orElseThrow().isAfter(act.get().getBegin().orElseThrow())).anyMatch(call -> {
                    var verify = call.getScope().get().asMethodCallExpr();
                    if (!verify.getNameAsString().equals("verify") || !imported(verify, Set.of("org.mockito.Mockito"))
                            || verify.getArguments().isEmpty() || !verify.getArgument(0).toString().equals(stub.scope())) return false;
                    if (verify.getArguments().size() == 1) return true;
                    // never()/atMost()/times(0) không chứng minh stub đã được gọi.
                    if (verify.getArguments().size() != 2 || !verify.getArgument(1).isMethodCallExpr()) return false;
                    var mode = verify.getArgument(1).asMethodCallExpr();
                    return mode.getNameAsString().equals("times") && imported(mode, Set.of("org.mockito.Mockito"))
                            && mode.getArguments().size() == 1 && mode.getArgument(0).isIntegerLiteralExpr()
                            && mode.getArgument(0).asIntegerLiteralExpr().asInt() > 0;
                });
    }

    private Object value(Expression expression, Set<String> visiting) {
        if (expression.isEnclosedExpr()) return value(expression.asEnclosedExpr().getInner(), visiting);
        if (expression.isStringLiteralExpr()) return expression.asStringLiteralExpr().asString();
        if (expression.isBooleanLiteralExpr()) return expression.asBooleanLiteralExpr().getValue();
        if (expression.isIntegerLiteralExpr()) return expression.asIntegerLiteralExpr().asInt();
        if (expression.isNameExpr()) {
            String name = expression.toString();
            if (values.containsKey(name)) return values.get(name);
            if (!visiting.add(name) || !locals.containsKey(name)) return UNKNOWN;
            Object result = value(locals.get(name), visiting); visiting.remove(name); return result;
        }
        if (expression.isObjectCreationExpr()) {
            var creation = expression.asObjectCreationExpr();
            if (creation.getAnonymousClassBody().isPresent()) return UNKNOWN;
            if (jdkName(creation.getType().getNameWithScope(), "java.util.ArrayList")
                    && creation.getArguments().size() == 1) return value(creation.getArgument(0), visiting);
            var type = type(creation.getType().getNameWithScope(), test);
            return type == null ? UNKNOWN : construct(type, creation.getArguments().stream().map(arg -> value(arg, visiting)).toList());
        }
        if (!expression.isMethodCallExpr()) return UNKNOWN;
        var call = expression.asMethodCallExpr();
        String scope = call.getScope().map(Object::toString).orElse("");
        boolean factory = (jdkName(scope, "java.util.Arrays") && call.getNameAsString().equals("asList"))
                || ((jdkName(scope, "java.util.List") || jdkName(scope, "java.util.Set")) && call.getNameAsString().equals("of"))
                || (jdkName(scope, "java.util.Collections")
                    && Set.of("singleton", "singletonList", "emptyList", "emptySet").contains(call.getNameAsString()));
        if (factory) return call.getArguments().stream().map(arg -> value(arg, visiting)).toList();
        Object receiver = call.getScope().map(e -> value(e, visiting)).orElse(UNKNOWN);
        if (receiver instanceof List<?> list && call.getNameAsString().equals("get") && call.getArguments().size() == 1) {
            Object index = value(call.getArgument(0), visiting);
            return index instanceof Integer n && n >= 0 && n < list.size() ? list.get(n) : UNKNOWN;
        }
        if (receiver instanceof Fixture fixture && call.getArguments().isEmpty()) {
            String property = property(fixture.type(), call.getNameAsString());
            return property == null ? UNKNOWN : fixture.fields().getOrDefault(property, UNKNOWN);
        }
        return UNKNOWN;
    }

    private boolean jdkName(String name, String qualified) {
        if (name.equals(qualified)) return true;
        if (!name.equals(qualified.substring(qualified.lastIndexOf('.') + 1))) return false;
        if (test.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(name))
                || test.findAll(ClassOrInterfaceDeclaration.class).stream().anyMatch(t -> t.getNameAsString().equals(name))) return false;
        return test.getImports().stream().anyMatch(i -> !i.isStatic()
                && (i.getNameAsString().equals(qualified) || i.isAsterisk() && i.getNameAsString().equals("java.util")));
    }

    private Object construct(ClassOrInterfaceDeclaration type, List<Object> args) {
        if (!type.getExtendedTypes().isEmpty() || type.getMembers().stream().anyMatch(m -> m instanceof InitializerDeclaration init && !init.isStatic())) return UNKNOWN;
        Map<String, Object> fields = new LinkedHashMap<>();
        type.getFields().stream().filter(field -> !field.isStatic()).flatMap(field -> field.getVariables().stream())
                .forEach(field -> fields.put(field.getNameAsString(), field.getInitializer().isPresent()
                        ? field.getInitializer().filter(Expression::isBooleanLiteralExpr).<Object>map(e -> e.asBooleanLiteralExpr().getValue()).orElse(UNKNOWN)
                        : field.getType().asString().equals("boolean") ? false : UNKNOWN));
        var constructors = type.getConstructors().stream().filter(c -> c.getParameters().size() == args.size()).toList();
        if (constructors.size() == 1) {
            Map<String, Object> parameters = new HashMap<>();
            for (int i = 0; i < args.size(); i++) parameters.put(constructors.get(0).getParameter(i).getNameAsString(), args.get(i));
            var constructor = constructors.get(0);
            if (!constructor.findAll(MethodCallExpr.class).isEmpty() || !constructor.findAll(UnaryExpr.class).isEmpty()
                    || !constructor.findAll(ExplicitConstructorInvocationStmt.class).isEmpty()
                    || constructor.findAll(AssignExpr.class).stream().anyMatch(a -> a.getTarget().isNameExpr())) return UNKNOWN;
            for (String field : new ArrayList<>(fields.keySet())) {
                var assignments = constructor.findAll(AssignExpr.class).stream().filter(a -> a.getTarget().isFieldAccessExpr()
                        && a.getTarget().asFieldAccessExpr().getScope().isThisExpr()
                        && a.getTarget().asFieldAccessExpr().getNameAsString().equals(field)).toList();
                if (assignments.isEmpty()) continue;
                var assignment = assignments.get(0);
                boolean direct = assignments.size() == 1 && assignment.getOperator() == AssignExpr.Operator.ASSIGN && assignment.findAncestor(ExpressionStmt.class)
                        .flatMap(Node::getParentNode).filter(constructor.getBody()::equals).isPresent();
                fields.put(field, direct && assignment.getValue().isNameExpr()
                        ? parameters.getOrDefault(assignment.getValue().toString(), UNKNOWN) : UNKNOWN);
            }
            return new Fixture(type, fields);
        }
        if (!annotation(type, "AllArgsConstructor") && !annotation(type, "Value")) return UNKNOWN;
        var instance = type.getFields().stream().filter(f -> !f.isStatic()).flatMap(f -> f.getVariables().stream())
                .filter(v -> !v.findAncestor(FieldDeclaration.class).orElseThrow().isFinal() || v.getInitializer().isEmpty()).toList();
        if (instance.size() != args.size()) return UNKNOWN;
        for (int i = 0; i < args.size(); i++) fields.put(instance.get(i).getNameAsString(), args.get(i));
        return new Fixture(type, fields);
    }

    private boolean directSetter(ClassOrInterfaceDeclaration type, String property) {
        var setters = type.getMethodsByName("set" + upper(property));
        if (!setters.isEmpty()) {
            if (setters.size() != 1 || setters.get(0).getParameters().size() != 1
                    || setters.get(0).getBody().isEmpty() || setters.get(0).getBody().get().getStatements().size() != 1) return false;
            var statement = setters.get(0).getBody().get().getStatement(0);
            if (!statement.isExpressionStmt() || !statement.asExpressionStmt().getExpression().isAssignExpr()) return false;
            var assignment = statement.asExpressionStmt().getExpression().asAssignExpr();
            return assignment.getTarget().toString().equals("this." + property)
                    && assignment.getOperator() == AssignExpr.Operator.ASSIGN
                    && assignment.getValue().toString().equals(setters.get(0).getParameter(0).getNameAsString());
        }
        return annotation(type, "Data") || annotation(type, "Setter")
                || type.getFields().stream().filter(f -> annotation(f, "Setter"))
                    .anyMatch(f -> f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(property)));
    }

    private String property(ClassOrInterfaceDeclaration type, String getter) {
        var explicit = type.getMethodsByName(getter).stream().filter(m -> m.getParameters().isEmpty()).toList();
        if (!explicit.isEmpty()) {
            if (explicit.size() != 1 || explicit.get(0).getBody().isEmpty() || explicit.get(0).getBody().get().getStatements().size() != 1) return null;
            var returned = explicit.get(0).getBody().get().getStatement(0);
            if (!returned.isReturnStmt() || returned.asReturnStmt().getExpression().isEmpty()) return null;
            var value = returned.asReturnStmt().getExpression().get();
            return value.isNameExpr() ? value.toString() : value.isFieldAccessExpr() && value.asFieldAccessExpr().getScope().isThisExpr()
                    ? value.asFieldAccessExpr().getNameAsString() : null;
        }
        if (type.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("Accessors"))) return null;
        for (var field : type.getFields()) {
            if (field.isStatic() || (!annotation(type, "Data") && !annotation(type, "Value")
                    && !annotation(type, "Getter") && !annotation(field, "Getter"))) continue;
            for (var variable : field.getVariables()) {
                String name = variable.getNameAsString();
                String expected = variable.getType().asString().equals("boolean")
                        ? (name.matches("is[A-Z].*") ? name : "is" + upper(name)) : "get" + upper(name);
                if (getter.equals(expected)) return name;
            }
        }
        return null;
    }

    private ClassOrInterfaceDeclaration type(String name, CompilationUnit unit) {
        if (types.containsKey(name)) return types.get(name);
        var explicit = unit.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(name))
                .map(i -> types.get(i.getNameAsString())).filter(Objects::nonNull).toList();
        if (explicit.size() == 1) return explicit.get(0);
        String local = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("") + name;
        if (types.containsKey(local)) return types.get(local);
        return null;
    }

    private boolean isTargetCall(MethodCallExpr call) {
        if (!call.getNameAsString().equals(target.getNameAsString()) || call.getScope().filter(Expression::isNameExpr).isEmpty()) return false;
        String variable = call.getScope().get().toString();
        return test.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(variable)
                && v.getType().isClassOrInterfaceType() && type(v.getType().asClassOrInterfaceType().getNameWithScope(), test) == owner);
    }

    private boolean imported(MethodCallExpr call, Set<String> owners) {
        if (call.getScope().isPresent()) {
            String scope = call.getScope().get().toString();
            return owners.contains(scope) || test.getImports().stream().anyMatch(i -> !i.isStatic() && !i.isAsterisk()
                    && owners.contains(i.getNameAsString()) && i.getName().getIdentifier().equals(scope));
        }
        if (test.findAll(MethodDeclaration.class).stream().anyMatch(m -> m.getNameAsString().equals(call.getNameAsString()))) return false;
        return test.getImports().stream().anyMatch(i -> i.isStatic() && (i.isAsterisk() && owners.contains(i.getNameAsString())
                || !i.isAsterisk() && i.getName().getIdentifier().equals(call.getNameAsString())
                    && i.getName().getQualifier().map(Object::toString).filter(owners::contains).isPresent()));
    }

    private static boolean annotation(NodeWithAnnotations<?> node, String name) {
        var unit = ((Node) node).findCompilationUnit().orElseThrow();
        return node.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("lombok." + name)
                || a.getNameAsString().equals(name) && unit.getImports().stream().anyMatch(i -> !i.isStatic()
                    && (i.getNameAsString().equals("lombok." + name) || i.isAsterisk() && i.getNameAsString().equals("lombok"))));
    }

    private static String upper(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
