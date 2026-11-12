package com.greytest.service.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.Type;

/**
 * Bắt hai lỗi chỉ lộ khi chạy thật mà đọc source là chứng minh được:
 * stub nằm sau nhánh collection rỗng, và cấu hình scalar mà @InjectMocks không gán.
 */
final class GeneratedFixtureReachabilityValidator {
    private static final Set<String> SCALAR_TYPES = Set.of("String", "Boolean", "Byte", "Character", "Double", "Float",
            "Integer", "Long", "Short", "boolean", "byte", "char", "double", "float", "int", "long", "short");
    private static final Set<String> EMPTY_FACTORIES = Set.of("emptyList", "emptySet", "emptyMap", "of", "asList");
    private static final Set<String> EMPTY_COLLECTIONS = Set.of("ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet", "HashMap", "LinkedHashMap", "TreeMap");

    private GeneratedFixtureReachabilityValidator() { }

    static List<String> validate(ClassOrInterfaceDeclaration owner, String targetMethod, CompilationUnit test, String testMethodName) {
        var method = test.findAll(MethodDeclaration.class).stream().filter(item -> item.getNameAsString().equals(testMethodName))
                .filter(item -> item.getBody().isPresent()).findFirst();
        if (method.isEmpty()) return List.of();
        List<String> problems = new ArrayList<>();
        addStubsOnUnusedLocalMocks(method.get(), problems);
        var acts =method.get().findAll(MethodCallExpr.class).stream().filter(call -> call.getNameAsString().equals(targetMethod)
                && call.getScope().filter(Expression::isNameExpr).filter(scope -> isSut(scope.asNameExpr().getNameAsString(), owner, test)).isPresent()).toList();
        // Nhiều lần gọi Act có thể đi các nhánh khác nhau, không kết luận được stub nào thừa.
        if (acts.size() == 1) addStubsBehindEmptyCollection(owner, acts.get(0), method.get(), problems);
        if (!acts.isEmpty()) addUnwiredConfiguration(owner, acts.get(0), test, problems);
        return problems;
    }

    /**
     * Field của kiểu đó mà method đang test có thể đổi qua method của chính object, kể cả trong helper nội bộ.
     * Trả về "*" khi không có source của kiểu nên không chứng minh được field chưa đổi.
     */
    static Set<String> fieldsWrittenThroughTarget(ClassOrInterfaceDeclaration owner, String targetMethod, String type,
            java.util.function.Function<String, ClassOrInterfaceDeclaration> declarations) {
        Set<String> written = new LinkedHashSet<>();
        Set<MethodDeclaration> reached = new LinkedHashSet<>(owner.getMethodsByName(targetMethod));
        var pending = new java.util.ArrayDeque<>(reached);
        while (!pending.isEmpty()) {
            for (var call : pending.poll().findAll(MethodCallExpr.class)) {
                if (call.getScope().isEmpty() || call.getScope().get().isThisExpr()) {
                    owner.getMethodsByName(call.getNameAsString()).stream().filter(reached::add).forEach(pending::add);
                    continue;
                }
                if (!call.getScope().get().isNameExpr() || isGetter(call.getNameAsString())) continue;
                var method = call.findAncestor(MethodDeclaration.class).orElseThrow();
                if (!declaredAs(method, call.getScope().get().asNameExpr().getNameAsString(), type)) continue;
                var declaration = declarations.apply(type);
                if (declaration != null) collectWrittenFields(declaration, call.getNameAsString(), written, new LinkedHashSet<>());
                else if (call.getNameAsString().matches("set[A-Z].*")) written.add(decapitalize(call.getNameAsString().substring(3)));
                else written.add("*");
            }
        }
        return written;
    }

    private static boolean isGetter(String name) { return name.matches("(?:get|is|has|equals|hashCode|toString)(?:[A-Z].*)?"); }

    private static String decapitalize(String name) { return Character.toLowerCase(name.charAt(0)) + name.substring(1); }

    private static boolean declaredAs(MethodDeclaration method, String name, String type) {
        return method.getParameterByName(name).filter(parameter -> simpleType(parameter.getType()).equals(type)).isPresent()
                || method.findAll(VariableDeclarator.class).stream().anyMatch(local -> local.getNameAsString().equals(name) && simpleType(local.getType()).equals(type));
    }

    private static String simpleType(Type type) {
        return type.isClassOrInterfaceType() ? type.asClassOrInterfaceType().getNameAsString() : type.asString();
    }

    /** Đi theo các method nội bộ của object; setter do Lombok sinh không có thân nên suy ra field từ tên. */
    private static void collectWrittenFields(ClassOrInterfaceDeclaration type, String method, Set<String> written, Set<String> visited) {
        if (!visited.add(method)) return;
        var declared = type.getMethodsByName(method);
        if (declared.isEmpty() && method.matches("set[A-Z].*")) written.add(decapitalize(method.substring(3)));
        for (var declaration : declared) {
            for (var assignment : declaration.findAll(AssignExpr.class)) {
                var target = assignment.getTarget();
                if (target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr()) written.add(target.asFieldAccessExpr().getNameAsString());
                else if (target.isNameExpr() && type.getFieldByName(target.asNameExpr().getNameAsString()).isPresent()
                        && declaration.getParameterByName(target.asNameExpr().getNameAsString()).isEmpty()) written.add(target.asNameExpr().getNameAsString());
            }
            for (var call : declaration.findAll(MethodCallExpr.class)) {
                if (call.getScope().isEmpty() || call.getScope().get().isThisExpr()) collectWrittenFields(type, call.getNameAsString(), written, visited);
            }
        }
    }

    /** Mock cục bộ chỉ xuất hiện trong when(...) thì không code nào gọi được nó, stub chắc chắn thừa. */
    private static void addStubsOnUnusedLocalMocks(MethodDeclaration testMethod, List<String> problems) {
        for (var local : testMethod.findAll(VariableDeclarator.class)) {
            boolean mock = local.getInitializer().filter(Expression::isMethodCallExpr).map(Expression::asMethodCallExpr)
                    .filter(call -> call.getNameAsString().equals("mock")).isPresent();
            if (!mock) continue;
            String name = local.getNameAsString();
            var uses = testMethod.findAll(NameExpr.class).stream().filter(use -> use.getNameAsString().equals(name)).toList();
            if (uses.isEmpty() || !uses.stream().allMatch(GeneratedFixtureReachabilityValidator::isStubbedReceiver)) continue;
            problems.add("UnnecessaryStubbingException: mock '" + name + "' is only stubbed and never passed to the code under test, so stub "
                    + uses.get(0).getParentNode().orElseThrow() + " is never used. Pass the mock to the approved Act if the scenario needs it, otherwise remove the mock and its stubs.");
        }
    }

    private static boolean isStubbedReceiver(NameExpr use) {
        return use.getParentNode().filter(MethodCallExpr.class::isInstance).map(MethodCallExpr.class::cast)
                .filter(call -> call.getScope().filter(scope -> scope == use).isPresent())
                .flatMap(Node::getParentNode).filter(MethodCallExpr.class::isInstance).map(MethodCallExpr.class::cast)
                .filter(wrapper -> wrapper.getNameAsString().equals("when") && wrapper.getScope().isEmpty()).isPresent();
    }

    private static boolean isSut(String name, ClassOrInterfaceDeclaration owner, CompilationUnit test) {
        String type = owner.getNameAsString();
        return test.findAll(FieldDeclaration.class).stream().anyMatch(field -> field.getElementType().asString().endsWith(type)
                && field.getVariables().stream().anyMatch(variable -> variable.getNameAsString().equals(name)))
                || test.findAll(VariableDeclarator.class).stream().anyMatch(variable -> variable.getNameAsString().equals(name)
                        && variable.getType().asString().endsWith(type));
    }

    /** Fixture chỉ được source đọc sau khi collection có phần tử thì stub của nó thừa khi collaborator trả về rỗng. */
    private static void addStubsBehindEmptyCollection(ClassOrInterfaceDeclaration owner, MethodCallExpr act, MethodDeclaration testMethod, List<String> problems) {
        var production = owner.getMethodsByName(act.getNameAsString()).stream()
                .filter(item -> item.getParameters().size() == act.getArguments().size() && item.getBody().isPresent()).toList();
        if (production.size() != 1) return;
        var body = production.get(0).getBody().orElseThrow();
        for (Statement statement : body.getStatements()) {
            if (!statement.isExpressionStmt() || !statement.asExpressionStmt().getExpression().isVariableDeclarationExpr()) continue;
            for (var local : statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables()) {
                var source = local.getInitializer().filter(Expression::isMethodCallExpr).map(Expression::asMethodCallExpr)
                        .filter(call -> call.getScope().filter(Expression::isNameExpr).isPresent()).orElse(null);
                if (source == null || !stubbedEmpty(source, owner, testMethod)) continue;
                List<Node> reachableOnlyWithElements = guardedRegion(body.getStatements(), statement, local.getNameAsString());
                if (reachableOnlyWithElements.isEmpty()) continue;
                for (int index = 0; index < production.get(0).getParameters().size(); index++) {
                    if (!act.getArgument(index).isNameExpr()) continue;
                    addUnusedStubs(production.get(0).getParameter(index), act.getArgument(index).asNameExpr(), body, reachableOnlyWithElements,
                            source, testMethod, problems);
                }
            }
        }
    }

    private static void addUnusedStubs(Parameter parameter, NameExpr fixture, Node body, List<Node> guarded, MethodCallExpr source,
            MethodDeclaration testMethod, List<String> problems) {
        String name = parameter.getNameAsString();
        // Điều kiện chỉ so null thì không gọi method nào của tham số; lời gọi ngoài vùng được bảo vệ thì stub vẫn có thể được dùng.
        boolean usedOutside = body.findAll(NameExpr.class).stream().filter(use -> use.getNameAsString().equals(name))
                .filter(use -> guarded.stream().noneMatch(region -> region == use || region.isAncestorOf(use)))
                .anyMatch(use -> !comparedWithNull(use));
        if (usedOutside) return;
        for (var stub : testMethod.findAll(MethodCallExpr.class)) {
            if (!stub.getNameAsString().equals("when") || stub.getArguments().size() != 1 || !stub.getArgument(0).isMethodCallExpr()) continue;
            var invocation = stub.getArgument(0).asMethodCallExpr();
            if (invocation.getScope().filter(scope -> scope.isNameExpr() && scope.asNameExpr().getNameAsString().equals(fixture.getNameAsString())).isEmpty()) continue;
            boolean consumedByTest = testMethod.findAll(NameExpr.class).stream().filter(use -> use.getNameAsString().equals(fixture.getNameAsString()))
                    .anyMatch(use -> use != fixture && !stub.isAncestorOf(use));
            if (consumedByTest) continue;
            problems.add("UnnecessaryStubbingException: stub " + invocation + " is never used because the source only reads '" + name
                    + "' after " + source + " returns elements, and this test stubs that call to return an empty collection. "
                    + "Remove only this stub; keep the approved scenario and assertions.");
        }
    }

    /** Vùng chỉ chạy khi collection có phần tử: phần thân if không rỗng, hoặc các lệnh sau lệnh return sớm khi rỗng. */
    private static List<Node> guardedRegion(List<Statement> statements, Statement declaration, String collection) {
        List<Node> region = new ArrayList<>();
        int start = statements.indexOf(declaration);
        for (int index = start + 1; index < statements.size(); index++) {
            Statement statement = statements.get(index);
            if (!statement.isIfStmt()) continue;
            IfStmt branch = statement.asIfStmt();
            if (requiresElements(branch.getCondition(), collection)) {
                region.add(branch.getThenStmt());
                // Điều kiện kết hợp bằng && chỉ đánh giá vế sau khi vế trước đúng.
                conjunctsAfterGuard(branch.getCondition(), collection, region);
                return region;
            }
            if (isEmptyCheck(branch.getCondition(), collection) && branch.getElseStmt().isEmpty() && exits(branch.getThenStmt())) {
                for (int after = index + 1; after < statements.size(); after++) region.add(statements.get(after));
                return region;
            }
        }
        return List.of();
    }

    private static void conjunctsAfterGuard(Expression condition, String collection, List<Node> region) {
        if (!condition.isBinaryExpr() || condition.asBinaryExpr().getOperator() != BinaryExpr.Operator.AND) return;
        if (hasElementsCheck(condition.asBinaryExpr().getLeft(), collection)) region.add(condition.asBinaryExpr().getRight());
    }

    private static boolean requiresElements(Expression condition, String collection) {
        if (hasElementsCheck(condition, collection)) return true;
        return condition.isBinaryExpr() && condition.asBinaryExpr().getOperator() == BinaryExpr.Operator.AND
                && requiresElements(condition.asBinaryExpr().getLeft(), collection);
    }

    private static boolean hasElementsCheck(Expression condition, String collection) {
        if (condition.isEnclosedExpr()) return hasElementsCheck(condition.asEnclosedExpr().getInner(), collection);
        if (condition.isUnaryExpr() && condition.asUnaryExpr().getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            return isEmptyCheck(condition.asUnaryExpr().getExpression(), collection);
        }
        if (condition.isBinaryExpr()) {
            var binary = condition.asBinaryExpr();
            if (!sizeCall(binary.getLeft(), collection) || !binary.getRight().isIntegerLiteralExpr()) return false;
            int value = binary.getRight().asIntegerLiteralExpr().asNumber().intValue();
            return value == 0 && (binary.getOperator() == BinaryExpr.Operator.GREATER || binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS);
        }
        return utilityCheck(condition, collection, Set.of("isNotEmpty"));
    }

    private static boolean isEmptyCheck(Expression condition, String collection) {
        if (condition.isEnclosedExpr()) return isEmptyCheck(condition.asEnclosedExpr().getInner(), collection);
        if (condition.isMethodCallExpr()) {
            var call = condition.asMethodCallExpr();
            if (call.getNameAsString().equals("isEmpty") && call.getArguments().isEmpty()
                    && call.getScope().filter(scope -> scope.isNameExpr() && scope.asNameExpr().getNameAsString().equals(collection)).isPresent()) return true;
            return utilityCheck(condition, collection, Set.of("isEmpty"));
        }
        if (condition.isBinaryExpr()) {
            var binary = condition.asBinaryExpr();
            return sizeCall(binary.getLeft(), collection) && binary.getOperator() == BinaryExpr.Operator.EQUALS
                    && binary.getRight().isIntegerLiteralExpr() && binary.getRight().asIntegerLiteralExpr().asNumber().intValue() == 0;
        }
        return false;
    }

    /** Dạng CollectionUtils.isEmpty(list) của các thư viện, nhận diện theo tên method và đối số duy nhất. */
    private static boolean utilityCheck(Expression condition, String collection, Set<String> names) {
        return condition.isMethodCallExpr() && names.contains(condition.asMethodCallExpr().getNameAsString())
                && condition.asMethodCallExpr().getArguments().size() == 1 && condition.asMethodCallExpr().getArgument(0).isNameExpr()
                && condition.asMethodCallExpr().getArgument(0).asNameExpr().getNameAsString().equals(collection);
    }

    private static boolean sizeCall(Expression expression, String collection) {
        return expression.isMethodCallExpr() && expression.asMethodCallExpr().getNameAsString().equals("size")
                && expression.asMethodCallExpr().getScope().filter(scope -> scope.isNameExpr() && scope.asNameExpr().getNameAsString().equals(collection)).isPresent();
    }

    private static boolean exits(Statement statement) {
        if (statement instanceof ReturnStmt || statement instanceof ThrowStmt) return true;
        if (!statement.isBlockStmt() || statement.asBlockStmt().getStatements().isEmpty()) return false;
        var last = statement.asBlockStmt().getStatements().getLast().orElseThrow();
        return last instanceof ReturnStmt || last instanceof ThrowStmt;
    }

    private static boolean comparedWithNull(NameExpr use) {
        return use.getParentNode().filter(BinaryExpr.class::isInstance).map(BinaryExpr.class::cast)
                .filter(binary -> binary.getLeft().isNullLiteralExpr() || binary.getRight().isNullLiteralExpr()).isPresent();
    }

    private static boolean stubbedEmpty(MethodCallExpr source, ClassOrInterfaceDeclaration owner, MethodDeclaration testMethod) {
        String field = source.getScope().orElseThrow().asNameExpr().getNameAsString();
        Optional<String> dependency = owner.getFieldByName(field).map(FieldDeclaration::getElementType).map(Type::asString);
        var test = testMethod.findCompilationUnit().orElseThrow();
        for (var call : testMethod.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("thenReturn") || call.getArguments().size() != 1 || !emptyCollection(call.getArgument(0))) continue;
            var when = call.getScope().filter(Expression::isMethodCallExpr).map(Expression::asMethodCallExpr)
                    .filter(item -> item.getNameAsString().equals("when") && item.getArguments().size() == 1 && item.getArgument(0).isMethodCallExpr());
            if (when.isEmpty()) continue;
            var stubbed = when.get().getArgument(0).asMethodCallExpr();
            if (!stubbed.getNameAsString().equals(source.getNameAsString()) || stubbed.getScope().filter(Expression::isNameExpr).isEmpty()) continue;
            String mock = stubbed.getScope().orElseThrow().asNameExpr().getNameAsString();
            boolean sameType = dependency.isPresent() && test.findAll(VariableDeclarator.class).stream().anyMatch(variable -> variable.getNameAsString().equals(mock)
                    && variable.getType().asString().equals(dependency.get()));
            if (mock.equals(field) || sameType) return true;
        }
        return false;
    }

    private static boolean emptyCollection(Expression value) {
        if (value.isObjectCreationExpr()) {
            return EMPTY_COLLECTIONS.contains(value.asObjectCreationExpr().getType().getNameAsString()) && value.asObjectCreationExpr().getArguments().isEmpty();
        }
        return value.isMethodCallExpr() && value.asMethodCallExpr().getArguments().isEmpty()
                && EMPTY_FACTORIES.contains(value.asMethodCallExpr().getNameAsString());
    }

    /** @InjectMocks không mock được kiểu scalar, nên cấu hình @Value hoặc tham số scalar của constructor vẫn là null hoặc 0. */
    private static void addUnwiredConfiguration(ClassOrInterfaceDeclaration owner, MethodCallExpr act, CompilationUnit test, List<String> problems) {
        var sut = act.getScope().orElseThrow().asNameExpr().getNameAsString();
        boolean injected = test.findAll(FieldDeclaration.class).stream().anyMatch(field -> field.getAnnotationByName("InjectMocks").isPresent()
                && field.getVariables().stream().anyMatch(variable -> variable.getNameAsString().equals(sut)));
        if (!injected) return;
        var production = owner.getMethodsByName(act.getNameAsString()).stream()
                .filter(item -> item.getParameters().size() == act.getArguments().size() && item.getBody().isPresent()).toList();
        if (production.size() != 1) return;
        Set<String> wired = new LinkedHashSet<>();
        test.findAll(MethodCallExpr.class).stream().filter(call -> call.getNameAsString().equals("setField") && call.getArguments().size() >= 2
                && call.getArgument(1).isStringLiteralExpr()).forEach(call -> wired.add(call.getArgument(1).asStringLiteralExpr().getValue()));
        for (String field : scalarConfiguration(owner)) {
            if (wired.contains(field) || !readUnconditionally(production.get(0), field)) continue;
            problems.add("Configuration field '" + field + "' stays null or zero under @InjectMocks because Mockito does not resolve @Value or mock scalar types, and "
                    + act.getNameAsString() + " reads it on every path. Add ReflectionTestUtils.setField(" + sut + ", \"" + field + "\", value) in Arrange, or construct the service explicitly with the value.");
        }
    }

    private static Set<String> scalarConfiguration(ClassOrInterfaceDeclaration owner) {
        Set<String> fields = new LinkedHashSet<>();
        for (FieldDeclaration field : owner.getFields()) {
            if (field.isStatic() || field.getAnnotationByName("Value").isEmpty()) continue;
            field.getVariables().stream().filter(variable -> variable.getInitializer().isEmpty()).forEach(variable -> fields.add(variable.getNameAsString()));
        }
        owner.getConstructors().stream().max(java.util.Comparator.comparingInt(constructor -> constructor.getParameters().size())).ifPresent(constructor -> {
            for (Parameter parameter : constructor.getParameters()) {
                if (parameter.getAnnotationByName("Value").isEmpty() && !SCALAR_TYPES.contains(parameter.getType().asString())) continue;
                for (AssignExpr assignment : constructor.findAll(AssignExpr.class)) {
                    if (assignment.getValue().isNameExpr() && assignment.getValue().asNameExpr().getNameAsString().equals(parameter.getNameAsString())
                            && assignment.getTarget() instanceof FieldAccessExpr target) fields.add(target.getNameAsString());
                }
            }
        });
        return fields;
    }

    private static boolean readUnconditionally(MethodDeclaration method, String field) {
        boolean shadowed = method.getParameterByName(field).isPresent() || method.findAll(VariableDeclarator.class).stream().anyMatch(local -> local.getNameAsString().equals(field));
        if (shadowed) return false;
        List<Node> reads = new ArrayList<>(method.findAll(NameExpr.class).stream().filter(name -> name.getNameAsString().equals(field)).toList());
        reads.addAll(method.findAll(FieldAccessExpr.class).stream().filter(access -> access.getNameAsString().equals(field) && access.getScope().isThisExpr()).toList());
        return reads.stream().anyMatch(read -> read.findAncestor(IfStmt.class).isEmpty()
                && read.findAncestor(com.github.javaparser.ast.stmt.ForStmt.class).isEmpty()
                && read.findAncestor(com.github.javaparser.ast.stmt.ForEachStmt.class).isEmpty()
                && read.findAncestor(com.github.javaparser.ast.stmt.WhileStmt.class).isEmpty()
                && read.findAncestor(com.github.javaparser.ast.stmt.TryStmt.class).isEmpty()
                && read.findAncestor(com.github.javaparser.ast.expr.LambdaExpr.class).isEmpty()
                && read.findAncestor(com.github.javaparser.ast.expr.ConditionalExpr.class).isEmpty()
                && read.getParentNode().filter(parent -> parent instanceof AssignExpr assign && assign.getTarget() == read).isEmpty());
    }
}
