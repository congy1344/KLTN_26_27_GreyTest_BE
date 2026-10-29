package com.greytest.service.agent;

import java.util.*;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.greytest.dto.agent.GenerationContextDtos.*;

/** Kiểm tra verify trên stream có fixture trùng; chỉ kết luận khi giải được toàn bộ argument. */
final class GeneratedStreamInvocationValidator {
    private record Value(String text, boolean string) { }
    private record Callback(Node body, String parameter, MethodDeclaration owner) { }
    private final UnitTestContextDto context;
    private final Map<String, VariableDeclarator> constants = new HashMap<>();
    private final List<CompilationUnit> sources = new ArrayList<>();

    private GeneratedStreamInvocationValidator(UnitTestContextDto context) { this.context = context; }

    static List<String> validate(UnitTestContextDto context, MethodContextDto target, Long caseId, CompilationUnit test) {
        if (target == null) return List.of();
        var approved = context.approvedTestCases().stream().filter(c -> Objects.equals(c.id(), caseId)).findFirst();
        // Số lần gọi do nghiệp vụ yêu cầu phải được giữ để test có thể phát hiện source sai.
        if (approved.isEmpty() || approvedContract(context, approved.get()).toLowerCase(Locale.ROOT)
                .matches("(?s).*(\\blần\\b(?!\\s+lượt\\b)|\\bonce\\b|\\btwice\\b|\\bexactly\\b|\\bnever\\b|\\btimes\\b|\\bcount\\b|không\\s+(?:được\\s+)?gọi).*$")) return List.of();
        var checker = new GeneratedStreamInvocationValidator(context);
        var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        context.classes().stream().map(ClassContextDto::validationSourceCode).filter(Objects::nonNull)
                .forEach(s -> parser.parse(s).getResult().ifPresent(checker.sources::add));
        checker.sources.forEach(unit -> unit.findAll(VariableDeclarator.class).stream()
                .filter(v -> Set.of("String", "java.lang.String").contains(v.getTypeAsString()))
                .filter(v -> v.getParentNode().filter(FieldDeclaration.class::isInstance).map(FieldDeclaration.class::cast)
                        .filter(f -> f.isStatic() && f.isFinal()).isPresent())
                .forEach(v -> v.findAncestor(TypeDeclaration.class).flatMap(TypeDeclaration::getFullyQualifiedName)
                        .ifPresent(owner -> checker.constants.put(owner + "." + v.getNameAsString(), v))));
        var methods = checker.sources.stream().flatMap(u -> u.findAll(MethodDeclaration.class).stream())
                .filter(m -> m.getNameAsString().equals(target.methodName()) && m.findAncestor(TypeDeclaration.class)
                        .flatMap(TypeDeclaration::getFullyQualifiedName).filter(target.classQualifiedName()::equals).isPresent()).toList();
        if (methods.size() != 1 || methods.get(0).findAll(com.github.javaparser.ast.stmt.IfStmt.class).size() > 0) return List.of();
        return checker.check(methods.get(0), test);
    }

    private static String approvedContract(UnitTestContextDto context, TestCaseContextItemDto item) {
        StringBuilder contract = new StringBuilder(item.toString());
        context.approvedTestPlans().stream().filter(p -> Objects.equals(p.id(), item.testPlanId())).forEach(p -> {
            contract.append(' ').append(p);
            context.approvedBusinessRules().stream().filter(r -> Objects.equals(r.id(), p.businessRuleId())
                    || p.coveredRuleIds().contains(r.id())).forEach(r -> contract.append(' ').append(r));
        });
        return contract.toString();
    }

    private List<String> check(MethodDeclaration source, CompilationUnit test) {
        List<String> problems = new ArrayList<>();
        for (var map : source.findAll(MethodCallExpr.class)) {
            if (!map.getNameAsString().equals("map") || map.getArguments().size() != 1 || !fullyTraversed(map)) continue;
            var callback = callback(map.getArgument(0), source);
            if (callback == null) continue;
            var chain = chainBefore(map);
            if (chain.isEmpty()) continue;
            var lookup = chain.get(chain.size() - 1);
            for (var method : test.findAll(MethodDeclaration.class)) {
                if (uncertainFlow(method) || method.findAll(MethodCallExpr.class).stream()
                        .anyMatch(c -> Set.of("reset", "clearInvocations", "thenThrow", "doThrow", "thenAnswer", "doAnswer").contains(c.getNameAsString()))) continue;
                long candidates = method.findAll(MethodCallExpr.class).stream().filter(w -> mockito(w, "when", test)
                        && w.getArguments().size() == 1 && w.getArgument(0).isMethodCallExpr())
                        .map(w -> w.getArgument(0).asMethodCallExpr()).filter(c -> c.getNameAsString().equals(lookup.getNameAsString())
                                && scope(c).equals(scope(lookup))).count();
                if (candidates != 1) continue;
                for (var stub : method.findAll(MethodCallExpr.class)) {
                    if (!stub.getNameAsString().equals("thenReturn") || stub.getArguments().size() != 1
                            || stub.getScope().filter(Expression::isMethodCallExpr).isEmpty()) continue;
                    var when = stub.getScope().orElseThrow().asMethodCallExpr();
                    if (!mockito(when, "when", test) || when.getArguments().size() != 1 || !when.getArgument(0).isMethodCallExpr()) continue;
                    var call = when.getArgument(0).asMethodCallExpr();
                    if (!call.getNameAsString().equals(lookup.getNameAsString())
                            || !scope(call).equals(scope(lookup))) continue;
                    var list = stub.getArgument(0);
                    if (list.isNameExpr()) list = variable(list.toString(), method).flatMap(VariableDeclarator::getInitializer).orElse(list);
                    if (!list.isMethodCallExpr()) continue;
                    var factory = list.asMethodCallExpr();
                    if (!Set.of("Arrays.asList", "List.of", "java.util.Arrays.asList", "java.util.List.of")
                            .contains(factory.getScope().map(Object::toString).orElse("") + "." + factory.getNameAsString())
                            || factory.getArguments().size() < 2 || !stableFixtures(factory, method)) continue;
                    checkVerifications(source, callback, chain, method, factory.getArguments(), lookup, test, problems);
                }
            }
        }
        return problems;
    }

    private void checkVerifications(MethodDeclaration source, Callback callback, List<MethodCallExpr> chain,
            MethodDeclaration method, NodeList<Expression> elements, MethodCallExpr lookup, CompilationUnit test, List<String> problems) {
        var acts = method.findAll(MethodCallExpr.class).stream().filter(c -> c.getNameAsString().equals(source.getNameAsString())
                && !scope(c).equals(scope(lookup))
                && !c.findAncestor(LambdaExpr.class).isPresent() && !c.getArguments().stream().anyMatch(Expression::isClassExpr)).toList();
        if (acts.size() != 1) return; // Nhiều Act cần theo dõi lịch sử riêng.
        for (var verified : method.findAll(MethodCallExpr.class)) {
            if (verified.getScope().filter(Expression::isMethodCallExpr).isEmpty()) continue;
            var verify = verified.getScope().orElseThrow().asMethodCallExpr();
            if (!mockito(verify, "verify", test) || verify.getArguments().isEmpty()) continue;
            Integer count = verificationCount(verify);
            if (count == null) continue;
            int matching = matchingInvocations(callback, chain, method, elements, verified, verify);
            if (matching >= 0 && matching != count) problems.add("Mockito invocation count: " + verified
                    + " verifies " + count + " calls, but " + matching + " matching calls occur during stream traversal. "
                    + "Repeated default verify calls do not consume interactions. Use one verification with the proven count; preserve approved fixtures and assertions.");
        }
    }

    private int matchingInvocations(Callback callback, List<MethodCallExpr> chain,
            MethodDeclaration method, NodeList<Expression> elements, MethodCallExpr verified, MethodCallExpr verify) {
        int matching = 0;
        var calls = callback.body().findAll(MethodCallExpr.class).stream().filter(c -> c.getNameAsString().equals(verified.getNameAsString())
                && scope(c).equals(verify.getArgument(0).toString()) && c.getArguments().size() == verified.getArguments().size()).toList();
        if (calls.isEmpty()) return -1;
        for (var invoked : calls) {
            if (invoked.findAncestor(CatchClause.class).isPresent()) return -1;
            var fields = requiredFields(invoked, callback.parameter());
            if (fields.isEmpty() || !preservedByMappings(chain, fields) || callback.body().findAll(MethodCallExpr.class).stream()
                    .anyMatch(c -> fields.stream().anyMatch(f -> c.getNameAsString().equals("set" + Character.toUpperCase(f.charAt(0)) + f.substring(1))))) return -1;
            for (var element : elements) {
                var values = fixtureFields(element, method);
                if (!values.keySet().containsAll(fields)) return -1;
                boolean matches = true;
                for (int i = 0; i < invoked.getArguments().size(); i++) {
                    Value actual = value(invoked.getArgument(i), callback.owner(), values, 0);
                    Value expected = value(verified.getArgument(i), method, Map.of(), 0);
                    if (actual == null || expected == null) return -1;
                    matches &= actual.equals(expected);
                }
                if (matches) matching++;
            }
        }
        return matching;
    }

    private static Callback callback(Expression expression, MethodDeclaration source) {
        if (expression.isLambdaExpr()) {
            var lambda = expression.asLambdaExpr();
            return lambda.getParameters().size() == 1 && !uncertainFlow(lambda)
                    ? new Callback(lambda.getBody(), lambda.getParameter(0).getNameAsString(), source) : null;
        }
        if (!expression.isMethodReferenceExpr() || !expression.asMethodReferenceExpr().getScope().isThisExpr()) return null;
        var ref = expression.asMethodReferenceExpr();
        var methods = source.findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow().getMethodsByName(ref.getIdentifier())
                .stream().filter(m -> m.isPrivate() && m.getParameters().size() == 1 && m.getBody().isPresent()).toList();
        if (methods.size() != 1 || uncertainFlow(methods.get(0))) return null;
        var helper = methods.get(0);
        return new Callback(helper.getBody().orElseThrow(), helper.getParameter(0).getNameAsString(), helper);
    }

    private static boolean uncertainFlow(Node body) {
        return body.findAll(Node.class).stream().anyMatch(n -> n instanceof IfStmt || n instanceof ForStmt || n instanceof ForEachStmt
                || n instanceof WhileStmt || n instanceof DoStmt || n instanceof SwitchStmt || n instanceof ConditionalExpr);
    }

    private static boolean fullyTraversed(MethodCallExpr map) {
        MethodCallExpr current = map;
        while (current.getParentNode().orElse(null) instanceof MethodCallExpr next
                && next.getScope().filter(current::equals).isPresent()) {
            if (next.getNameAsString().equals("toList") && next.getArguments().isEmpty()) return true;
            if (next.getNameAsString().equals("collect") && next.getArguments().size() == 1
                    && Set.of("Collectors.toList()", "Collectors.toSet()", "Collectors.toUnmodifiableList()",
                            "java.util.stream.Collectors.toList()", "java.util.stream.Collectors.toUnmodifiableList()")
                            .contains(next.getArgument(0).toString())) return true;
            if (!next.getNameAsString().equals("distinct")) return false;
            current = next;
        }
        return false;
    }

    private static boolean stableFixtures(MethodCallExpr factory, MethodDeclaration method) {
        for (var element : factory.getArguments()) {
            if (!element.isNameExpr()) continue;
            String name = element.asNameExpr().getNameAsString();
            var declaration = variable(name, method);
            if (declaration.isEmpty()) return false;
            boolean mutable = declaration.get().getInitializer().filter(Expression::isObjectCreationExpr)
                    .map(e -> e.asObjectCreationExpr().getArguments().isEmpty()).orElse(false);
            if (method.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals(name)
                    && factory.getArguments().stream().noneMatch(e -> e == n) && !(mutable && n.getParentNode().filter(MethodCallExpr.class::isInstance)
                            .map(MethodCallExpr.class::cast).filter(c -> c.getScope().filter(e -> e == n).isPresent()
                                    && c.getNameAsString().startsWith("set") && c.getArguments().size() == 1
                                    && c.getParentNode().filter(ExpressionStmt.class::isInstance)
                                            .flatMap(Node::getParentNode).filter(parent -> parent == method.getBody().orElse(null)).isPresent()
                                    && c.getBegin().orElseThrow().isBefore(factory.getBegin().orElseThrow())).isPresent()))) return false;
        }
        return true;
    }

    private List<MethodCallExpr> chainBefore(MethodCallExpr map) {
        List<MethodCallExpr> chain = new ArrayList<>();
        Expression scope = map.getScope().orElse(null);
        while (scope instanceof MethodCallExpr call) {
            chain.add(call);
            if (!Set.of("stream", "map").contains(call.getNameAsString())) return chain;
            if (call.getNameAsString().equals("map") && (call.getArguments().size() != 1 || !call.getArgument(0).isMethodReferenceExpr())) return List.of();
            scope = call.getScope().orElse(null);
        }
        return List.of();
    }

    private Set<String> requiredFields(MethodCallExpr call, String parameter) {
        Set<String> fields = new HashSet<>();
        for (var argument : call.getArguments()) for (var getter : argument.findAll(MethodCallExpr.class)) {
            if (!getter.getScope().map(Object::toString).orElse("").equals(parameter) || !getter.getNameAsString().startsWith("get")) return Set.of();
            String suffix = getter.getNameAsString().substring(3);
            if (suffix.isEmpty()) return Set.of();
            fields.add(Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1));
        }
        return fields;
    }

    private boolean preservedByMappings(List<MethodCallExpr> chain, Set<String> fields) {
        for (var map : chain) {
            if (!map.getNameAsString().equals("map")) continue;
            var ref = map.getArgument(0).asMethodReferenceExpr();
            var mappings = sources.stream().flatMap(u -> u.findAll(MethodDeclaration.class).stream())
                    .filter(m -> m.getNameAsString().equals(ref.getIdentifier()) && m.findAncestor(TypeDeclaration.class)
                            .map(t -> t.getNameAsString().equals(ref.getScope().toString())).orElse(false)).toList();
            if (mappings.isEmpty()) return false;
            for (var mapping : mappings) for (String field : fields) {
                String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
                if (mapping.getBody().isEmpty() || mapping.getBody().orElseThrow().getStatements().size() != 1
                        || !mapping.getBody().orElseThrow().getStatement(0).isReturnStmt()) return false;
                var writes = mapping.findAll(MethodCallExpr.class).stream().filter(c -> c.getNameAsString().equals(field)).toList();
                if (writes.isEmpty() || writes.stream().anyMatch(c -> c.getArguments().size() != 1 || !c.getArgument(0).isMethodCallExpr()
                        || !c.getArgument(0).asMethodCallExpr().getNameAsString().equals(getter))) return false;
            }
        }
        return true;
    }

    private Map<String, Value> fixtureFields(Expression element, MethodDeclaration method) {
        Expression initial = element.isNameExpr() ? variable(element.toString(), method)
                .flatMap(VariableDeclarator::getInitializer).orElse(element) : element;
        if (initial.isObjectCreationExpr() && element.isNameExpr()) return setterFields(element.toString(), method);
        if (!initial.isMethodCallExpr() || !initial.asMethodCallExpr().getNameAsString().equals("build")) return Map.of();
        Map<String, Value> values = new HashMap<>();
        for (var setter : initial.findAll(MethodCallExpr.class)) if (setter.getArguments().size() == 1) {
            Value value = value(setter.getArgument(0), method, Map.of(), 0);
            if (value != null) values.put(setter.getNameAsString(), value);
        }
        return values;
    }

    private Map<String, Value> setterFields(String name, MethodDeclaration method) {
        var entity = variable(name, method).orElseThrow();
        Map<String, Value> values = new HashMap<>();
        Set<String> assigned = new HashSet<>();
        for (var setter : method.findAll(MethodCallExpr.class)) {
            if (!scope(setter).equals(name) || !setter.getNameAsString().matches("set[A-Z].*") || setter.getArguments().size() != 1) continue;
            String suffix = setter.getNameAsString().substring(3);
            String field = Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
            if (!assigned.add(field) || !GeneratedRepositoryFixtureValidator.hasDirectProperty(context,
                    method.findCompilationUnit().orElseThrow(), entity.getType(), field)) return Map.of();
            Value value = value(setter.getArgument(0), method, Map.of(), 0);
            if (value != null) values.put(field, value);
        }
        return values;
    }

    private Value value(Expression expression, MethodDeclaration method, Map<String, Value> fields, int depth) {
        if (depth > 10) return null;
        if (expression.isEnclosedExpr()) return value(expression.asEnclosedExpr().getInner(), method, fields, depth + 1);
        if (expression.isStringLiteralExpr()) return new Value(expression.asStringLiteralExpr().asString(), true);
        if (expression.isIntegerLiteralExpr()) {
            try { return new Value(expression.asIntegerLiteralExpr().asNumber().toString(), false); }
            catch (RuntimeException ignored) { return null; }
        }
        if (expression.isLongLiteralExpr()) {
            try { return new Value(expression.asLongLiteralExpr().asNumber().toString(), false); }
            catch (RuntimeException ignored) { return null; }
        }
        if (expression.isClassExpr()) return new Value(qualified(expression.asClassExpr().getTypeAsString(), method.findCompilationUnit().orElseThrow()), false);
        if (expression.isBinaryExpr() && expression.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            var binary = expression.asBinaryExpr(); Value left = value(binary.getLeft(), method, fields, depth + 1);
            Value right = value(binary.getRight(), method, fields, depth + 1);
            return left != null && right != null && (left.string() || right.string()) ? new Value(left.text() + right.text(), true) : null;
        }
        if (expression.isNameExpr()) {
            String name = expression.toString();
            if (method.findAll(AssignExpr.class).stream().anyMatch(a -> a.getTarget().toString().equals(name))
                    || method.findAll(UnaryExpr.class).stream().anyMatch(u -> u.getExpression().toString().equals(name))) return null;
            return variable(name, method).flatMap(VariableDeclarator::getInitializer)
                    .map(e -> value(e, method, fields, depth + 1)).orElse(null);
        }
        if (expression.isFieldAccessExpr()) {
            String text = expression.toString(); int dot = text.indexOf('.');
            String fqn = qualified(text.substring(0, dot), method.findCompilationUnit().orElseThrow()) + text.substring(dot);
            var constant = constants.get(fqn);
            if (constant != null && constant.getInitializer().isPresent()) {
                var e = constant.getInitializer().orElseThrow();
                return e.isStringLiteralExpr() ? new Value(e.asStringLiteralExpr().asString(), true) : null;
            }
        }
        if (expression.isMethodCallExpr()) {
            var call = expression.asMethodCallExpr();
            if (call.getNameAsString().equals("eq") && call.getArguments().size() == 1) return value(call.getArgument(0), method, fields, depth + 1);
            if (call.getNameAsString().startsWith("get") && call.getNameAsString().length() > 3 && call.getArguments().isEmpty()) {
                String suffix = call.getNameAsString().substring(3);
                return fields.get(Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1));
            }
        }
        return null;
    }

    private static Optional<VariableDeclarator> variable(String name, MethodDeclaration method) {
        var matches = method.findAll(VariableDeclarator.class).stream().filter(v -> v.getNameAsString().equals(name)).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }
    private static String scope(MethodCallExpr call) { return call.getScope().map(Object::toString).orElse("").replaceFirst("^this\\.", ""); }
    private static String qualified(String type, CompilationUnit unit) {
        return unit.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk() && i.getNameAsString().endsWith("." + type))
                .map(i -> i.getNameAsString()).findFirst().orElse(type);
    }
    private static boolean mockito(MethodCallExpr call, String name, CompilationUnit test) {
        return call.getNameAsString().equals(name) && test.findAll(MethodDeclaration.class).stream().noneMatch(m -> m.getNameAsString().equals(name))
                && (call.getScope().map(Object::toString).filter("org.mockito.Mockito"::equals).isPresent()
                || call.getScope().isEmpty() && test.getImports().stream().anyMatch(i -> i.isStatic()
                        && i.getNameAsString().equals("org.mockito.Mockito" + (i.isAsterisk() ? "" : "." + name))));
    }
    private static Integer verificationCount(MethodCallExpr verify) {
        if (verify.getArguments().size() == 1) return 1;
        if (verify.getArguments().size() == 2 && verify.getArgument(1).isMethodCallExpr()) {
            var mode = verify.getArgument(1).asMethodCallExpr();
            if (mode.getNameAsString().equals("times") && mode.getArguments().size() == 1 && mode.getArgument(0).isIntegerLiteralExpr()) {
                try { return mode.getArgument(0).asIntegerLiteralExpr().asNumber().intValue(); } catch (RuntimeException ignored) { return null; }
            }
        }
        return null;
    }
}
