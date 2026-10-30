package com.greytest.service.agent;

import java.util.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.greytest.dto.agent.GenerationContextDtos.*;

/** Chặn assertion enrichment khi mock được inject nhưng chưa được cấu hình trên đường gọi helper. */
final class GeneratedCollaboratorStubValidator {
    private GeneratedCollaboratorStubValidator() { }

    static List<String> validate(UnitTestContextDto context, MethodContextDto target, CompilationUnit test) {
        if (target == null || target.classQualifiedName() == null) return List.of();
        var api = new GeneratedTestApiValidator(context);
        var owner = api.declaration(target.classQualifiedName());
        if (owner == null) return List.of();
        Set<String> problems = new LinkedHashSet<>();
        for (var method : test.findAll(MethodDeclaration.class)) {
            if (method.getAnnotations().stream().noneMatch(a -> a.getName().getIdentifier().equals("Test"))
                    || method.getBody().isEmpty() || conditional(method) || !method.findAll(LambdaExpr.class).isEmpty()) continue;
            for (var sut : method.findAll(VariableDeclarator.class)) {
                if (sut.getInitializer().filter(Expression::isObjectCreationExpr).isEmpty()) continue;
                var constructor = sut.getInitializer().get().asObjectCreationExpr();
                if (!target.classQualifiedName().equals(api.qualifiedType(constructor.getType(), test))) continue;
                if (reassigned(method, sut.getNameAsString())) continue;
                var mocks = unusedInjectedMocks(owner, constructor, method, test, api);
                if (mocks.isEmpty()) continue;
                for (var result : method.findAll(VariableDeclarator.class)) {
                    if (result.getInitializer().filter(Expression::isMethodCallExpr).isEmpty()) continue;
                    var selection = GeneratedActPath.select(result.getInitializer().get().asMethodCallExpr(), sut.getNameAsString());
                    if (selection == null) continue;
                    var act = selection.entry();
                    if (reassigned(method, result.getNameAsString())) continue;
                    if (act.getScope().map(Object::toString).filter(sut.getNameAsString()::equals).isEmpty()) continue;
                    if (test.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals(sut.getNameAsString()) && n != act.getScope().get())) continue;
                    if (method.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getArguments().stream()
                            .anyMatch(a -> a.isNameExpr() && a.asNameExpr().getNameAsString().equals(result.getNameAsString()))
                                    && api.junitAssertionsOwner(call, test) == null
                            || call.getScope().map(Object::toString).filter(result.getNameAsString()::equals).isPresent()
                                    && !call.getNameAsString().matches("(?:get|is)[A-Z].*"))) continue;
                    var returned = api.declaration(result.getType(), test);
                    var entry = uniqueMethod(owner, act);
                    if (entry == null || returned == null) continue;
                    var asserted = nonNullAssertions(method, result.getNameAsString(), test, api);
                    boolean needsElement = !asserted.isEmpty() || method.findAll(MethodCallExpr.class).stream().anyMatch(c ->
                            c.getNameAsString().equals("assertNotNull") && api.junitAssertionsOwner(c, test) != null
                            && c.getArguments().stream().anyMatch(a -> a.isNameExpr() && a.asNameExpr().getNameAsString().equals(result.getNameAsString())));
                    if (selection.firstElement() && needsElement) GeneratedActPath.missingCollectionStub(
                            GeneratedActPath.projection(entry, owner), owner, mocks, api).ifPresent(problems::add);
                    for (var reached : reachableHelpers(entry, owner)) {
                        if (conditional(reached)) continue;
                        for (var block : reached.findAll(TryStmt.class)) {
                            if (conditional(block.getTryBlock()) || block.getFinallyBlock().isPresent()
                                    || block.findAncestor(CatchClause.class).isPresent() || block.findAncestor(TryStmt.class).isPresent()
                                    || block.findAncestor(LambdaExpr.class).isPresent()
                                    || block.findAncestor(ClassOrInterfaceDeclaration.class).filter(owner::equals).isEmpty()) continue;
                            for (var dto : block.getTryBlock().findAll(VariableDeclarator.class)) {
                                if (reassigned(reached, dto.getNameAsString())) continue;
                                if (dto.getInitializer().filter(Expression::isMethodCallExpr).isEmpty()) continue;
                                var lookup = dto.getInitializer().get().asMethodCallExpr();
                                String field = lookup.getScope().map(Object::toString).orElse("");
                                if (!mocks.containsKey(field) || reached.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(field))
                                        || reached.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(field))) continue;
                                for (var ret : block.getTryBlock().findAll(ReturnStmt.class)) {
                                    if (ret.findAncestor(LambdaExpr.class).isPresent()) continue;
                                    if (ret.getExpression().filter(Expression::isMethodCallExpr).isEmpty()) continue;
                                    var call = ret.getExpression().get().asMethodCallExpr();
                                    var helper = uniqueMethod(owner, call);
                                    if (helper == null || conditional(helper) || !isThisCall(call)) continue;
                                    for (int index = 0; index < call.getArguments().size(); index++) {
                                        if (!call.getArgument(index).toString().equals(dto.getNameAsString())) continue;
                                        String parameter = helper.getParameter(index).getNameAsString();
                                        if (reassigned(helper, parameter)) continue;
                                        for (var setter : helper.findAll(MethodCallExpr.class)) {
                                            if (!setter.getNameAsString().matches("set[A-Z].*") || setter.getArguments().size() != 1
                                                    || !asserted.contains("get" + setter.getNameAsString().substring(3))
                                                    || !fallbackLeavesNull(block, returned, setter.getNameAsString().substring(3), api)
                                                    || setter.getScope().isEmpty()
                                                    || api.declaration(api.expressionType(setter.getScope().get(), owner.findCompilationUnit().orElseThrow()),
                                                            owner.findCompilationUnit().orElseThrow()) != returned) continue;
                                            var returns = helper.findAll(ReturnStmt.class);
                                            if (returns.size() != 1 || returns.get(0).getExpression().map(Object::toString)
                                                    .filter(setter.getScope().get().toString()::equals).isEmpty()) continue;
                                            if (setter.getArgument(0).findAll(MethodCallExpr.class).stream().anyMatch(getter ->
                                                    getter.getScope().map(Object::toString).filter(parameter::equals).isPresent())) {
                                                problems.add("Missing collaborator stub: " + mocks.get(field) + "." + lookup.getNameAsString()
                                                        + " returns null by default on the public-to-helper path, but the test asserts non-null enriched data. "
                                                        + "Stub this injected dependency with the approved DTO before Act; preserve approved inputs and assertions. "
                                                        + "Do not change assertions to fallback null values or disable strict stubbing.");
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(problems);
    }

    private static boolean reassigned(MethodDeclaration method, String name) {
        return method.findAll(AssignExpr.class).stream().anyMatch(a -> a.getTarget().toString().equals(name));
    }

    private static boolean fallbackLeavesNull(TryStmt block, ClassOrInterfaceDeclaration type, String suffix, GeneratedTestApiValidator api) {
        String property = Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
        if (!type.getExtendedTypes().isEmpty() || !type.getMembers().stream().filter(InitializerDeclaration.class::isInstance).toList().isEmpty()) return false;
        var field = type.getFieldByName(property).orElse(null);
        if (field == null || field.isStatic() || field.getElementType().isPrimitiveType()
                || field.getVariables().stream().anyMatch(v -> v.getInitializer().filter(e -> !e.isNullLiteralExpr()).isPresent())) return false;
        var unit = type.findCompilationUnit().orElseThrow();
        var getters = type.getMethodsByName("get" + suffix);
        if (getters.isEmpty()) {
            if (!api.lombok(type, unit, "Data") && !api.lombok(type, unit, "Getter") && !api.lombok(field, unit, "Getter")) return false;
        } else if (getters.size() != 1 || getters.get(0).getBody().isEmpty()
                || getters.get(0).getBody().get().getStatements().size() != 1
                || !Set.of("return " + property + ";", "return this." + property + ";")
                        .contains(getters.get(0).getBody().get().getStatement(0).toString())) return false;
        var returns = block.getCatchClauses().stream().flatMap(c -> c.findAll(ReturnStmt.class).stream()).toList();
        if (returns.isEmpty()) return false;
        for (var ret : returns) {
            if (ret.getExpression().filter(Expression::isObjectCreationExpr).isEmpty()) return false;
            var creation = ret.getExpression().get().asObjectCreationExpr();
            if (api.declaration(creation.getType(), creation.findCompilationUnit().orElseThrow()) != type) return false;
            var constructors = type.getConstructors().stream().filter(c -> c.getParameters().size() == creation.getArguments().size()).toList();
            if (constructors.size() != 1) return false;
            for (var statement : constructors.get(0).getBody().getStatements()) {
                if (!statement.isExpressionStmt() || !statement.asExpressionStmt().getExpression().isAssignExpr()) return false;
                var assignment = statement.asExpressionStmt().getExpression().asAssignExpr();
                if (Set.of(property, "this." + property).contains(assignment.getTarget().toString()) || assignment.getTarget().toString().contains("[")) return false;
                var value = assignment.getValue();
                if (!value.isLiteralExpr()) {
                    if (!value.isMethodCallExpr()) return false;
                    var getter = value.asMethodCallExpr();
                    if (!getter.getArguments().isEmpty() || !getter.getNameAsString().matches("(?:get|is)[A-Z].*")
                            || getter.getScope().filter(Expression::isNameExpr).isEmpty()
                            || constructors.get(0).getParameters().stream().noneMatch(p -> p.getNameAsString().equals(getter.getScope().get().toString()))) return false;
                }
            }
        }
        return true;
    }

    private static Map<String, String> unusedInjectedMocks(ClassOrInterfaceDeclaration owner, ObjectCreationExpr constructor,
            MethodDeclaration method, CompilationUnit test, GeneratedTestApiValidator api) {
        // shortcut: Chỉ kết luận khi mock local không được dùng ngoài constructor; setup/helper phức tạp cần bằng chứng riêng.
        List<String> fields = new ArrayList<>();
        var unit = owner.findCompilationUnit().orElseThrow();
        if (!owner.getConstructors().isEmpty() || !api.lombok(owner, unit, "RequiredArgsConstructor")) return Map.of();
        owner.getFields().stream().filter(f -> !f.isStatic() && f.isFinal())
                .flatMap(f -> f.getVariables().stream()).filter(v -> v.getInitializer().isEmpty()).forEach(v -> fields.add(v.getNameAsString()));
        if (fields.size() != constructor.getArguments().size()) return Map.of();
        Map<String, String> mocks = new LinkedHashMap<>();
        for (int index = 0; index < fields.size(); index++) {
            var argument = constructor.getArgument(index);
            if (!argument.isNameExpr()) continue;
            String name = argument.asNameExpr().getNameAsString();
            var variables = method.findAll(VariableDeclarator.class).stream().filter(v -> v.getNameAsString().equals(name)).toList();
            if (variables.size() != 1 || variables.get(0).getInitializer().filter(Expression::isMethodCallExpr).isEmpty()) continue;
            var mock = variables.get(0).getInitializer().get().asMethodCallExpr();
            if (!mock.getNameAsString().equals("mock") || mock.getArguments().size() != 1 || !mock.getArgument(0).isClassExpr()) continue;
            boolean mockito = mock.getScope().map(Object::toString).filter("org.mockito.Mockito"::equals).isPresent()
                    || mock.getScope().isEmpty() && test.getImports().stream().anyMatch(i -> i.isStatic()
                            && (i.getNameAsString().equals("org.mockito.Mockito.mock") || i.isAsterisk() && i.getNameAsString().equals("org.mockito.Mockito")))
                            && test.findAll(MethodDeclaration.class).stream().noneMatch(m -> m.getNameAsString().equals("mock"));
            if (!mockito || test.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals(name) && n != argument)) continue;
            var field = owner.getFieldByName(fields.get(index)).orElseThrow();
            var dependency = api.declaration(variables.get(0).getType(), test);
            if (dependency == null || dependency != api.declaration(field.getElementType(), unit)) continue;
            mocks.put(fields.get(index), name);
        }
        return mocks;
    }

    private static Set<String> nonNullAssertions(MethodDeclaration method, String result, CompilationUnit test, GeneratedTestApiValidator api) {
        Set<String> getters = new HashSet<>();
        for (var call : method.findAll(MethodCallExpr.class)) {
            String junit = api.junitAssertionsOwner(call, test);
            if (junit == null) continue;
            Expression actual = null;
            if (call.getNameAsString().equals("assertEquals") && call.getArguments().size() >= 2) {
                int expected = junit.equals("org.junit.Assert") ? call.getArguments().size() - 2 : 0;
                if (call.getArgument(expected).isLiteralExpr() && !call.getArgument(expected).isNullLiteralExpr()) actual = call.getArgument(expected + 1);
            } else if (call.getNameAsString().equals("assertNotNull") && !call.getArguments().isEmpty()) {
                actual = call.getArgument(junit.equals("org.junit.Assert") ? call.getArguments().size() - 1 : 0);
            }
            if (actual != null && actual.isMethodCallExpr()) {
                var getter = actual.asMethodCallExpr();
                if (getter.getScope().map(Object::toString).filter(result::equals).isPresent()) getters.add(getter.getNameAsString());
            }
        }
        return getters;
    }

    private static Set<MethodDeclaration> reachableHelpers(MethodDeclaration entry, ClassOrInterfaceDeclaration owner) {
        Set<MethodDeclaration> reached = new LinkedHashSet<>(); reached.add(entry);
        for (int depth = 0; depth < 3; depth++) for (var method : new ArrayList<>(reached)) {
            if (conditional(method)) continue;
            var projection = GeneratedActPath.projection(method, owner);
            if (projection != null) reached.add(projection.helper());
            for (var ret : method.findAll(ReturnStmt.class)) {
                if (ret.findAncestor(CatchClause.class).isPresent() || ret.findAncestor(LambdaExpr.class).isPresent()
                        || ret.getExpression().filter(Expression::isMethodCallExpr).isEmpty()) continue;
                var call = ret.getExpression().get().asMethodCallExpr();
                var helper = isThisCall(call) ? uniqueMethod(owner, call) : null;
                if (helper != null) reached.add(helper);
            }
        }
        return reached;
    }
    private static boolean isThisCall(MethodCallExpr call) { return call.getScope().isEmpty() || call.getScope().get().isThisExpr(); }
    private static MethodDeclaration uniqueMethod(ClassOrInterfaceDeclaration owner, MethodCallExpr call) {
        var matches = owner.getMethodsByName(call.getNameAsString()).stream().filter(m -> m.getParameters().size() == call.getArguments().size()).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }
    private static boolean conditional(Node node) {
        return node.findAll(Node.class).stream().anyMatch(n -> n instanceof IfStmt || n instanceof ConditionalExpr
                || n instanceof ForStmt || n instanceof ForEachStmt || n instanceof WhileStmt || n instanceof SwitchStmt
                || n instanceof TryStmt t && t.getFinallyBlock().isPresent());
    }
}
