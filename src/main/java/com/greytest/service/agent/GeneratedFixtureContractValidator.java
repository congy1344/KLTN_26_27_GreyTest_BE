package com.greytest.service.agent;

import java.util.*;
import java.util.regex.Pattern;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;

/** Kiểm tra input và setup đã duyệt, tránh mock trả null và giữ oracle nghiệp vụ. */
final class GeneratedFixtureContractValidator {
    private GeneratedFixtureContractValidator() { }

    static List<String> validate(UnitTestContextDto context, MethodContextDto target, Long caseId, CompilationUnit test) {
        if (context == null || context.approvedTestCases() == null || context.classes() == null) return List.of();
        var approved = context.approvedTestCases().stream().filter(c -> Objects.equals(c.id(), caseId)).findFirst().orElse(null);
        if (approved == null) return List.of();
        List<String> problems = new ArrayList<>();
        var api = new GeneratedTestApiValidator(context);
        var parsedTarget = target == null || target.sourceCode() == null ? Optional.<MethodDeclaration>empty()
                : new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                        .parseMethodDeclaration(target.sourceCode()).getResult();
        if (parsedTarget.isPresent()) validateScenarioNulls(approved.testData(), target, parsedTarget.get(), test, api, problems);
        String preconditions = Objects.toString(approved.preconditions(), "");
        boolean injectedSut = target != null && target.classQualifiedName() != null && test.findAll(FieldDeclaration.class).stream()
                .filter(field -> field.getAnnotationByName("InjectMocks").isPresent() && field.getElementType().isClassOrInterfaceType())
                .anyMatch(field -> target.classQualifiedName().equals(api
                        .qualifiedType(field.getElementType().asClassOrInterfaceType(), test))
                        && field.getVariables().stream().allMatch(variable -> variable.getInitializer().isEmpty()
                                && test.findAll(AssignExpr.class).stream().noneMatch(assignment ->
                                        assignment.getTarget().toString().equals(variable.getNameAsString())
                                                || assignment.getTarget().toString().equals("this." + variable.getNameAsString()))));
        for (var field : test.findAll(FieldDeclaration.class)) {
            if (!field.getElementType().isClassOrInterfaceType() || !mockitoMock(field, test)) continue;
            boolean passedToSut = target != null && test.findAll(ObjectCreationExpr.class).stream()
                    .filter(c -> target.classQualifiedName().equals(api.qualifiedType(c.getType(), test)))
                    .anyMatch(c -> c.getArguments().stream().anyMatch(a -> field.getVariables().stream().anyMatch(v ->
                            api.expressionType(a, test) == v.getType())));
            if (!injectedSut && !passedToSut) continue;
            String type = field.getElementType().asClassOrInterfaceType().getNameAsString();
            var candidates = context.classes().stream().filter(c -> type.equals(c.className())).map(c -> c.qualifiedName()).distinct().toList();
            if (candidates.size() != 1 || !Objects.equals(candidates.get(0), api
                    .qualifiedType(field.getElementType().asClassOrInterfaceType(), test))) continue;
            if (injectedSut && parsedTarget.isPresent()) validateMockProjection(target, parsedTarget.get(), field, test, api, problems);
            var owner = target == null ? null : api.declaration(target.classQualifiedName());
            var mapperTypes = owner == null ? List.<String>of() : owner.getFields().stream()
                    .filter(f -> !f.isStatic() && f.getElementType().isClassOrInterfaceType()
                            && f.getElementType().asClassOrInterfaceType().getNameAsString().endsWith("Mapper"))
                    .map(f -> api.qualifiedType(f.getElementType().asClassOrInterfaceType(), owner.findCompilationUnit().orElseThrow())).distinct().toList();
            boolean soleMapperRole = mapperTypes.size() == 1 && Objects.equals(mapperTypes.get(0), candidates.get(0));
            if (requiresRealDependency(preconditions, type, soleMapperRole)) {
                problems.add((caseId == null ? "" : "Test case " + caseId + ": ") + "Approved fixture contract: " + type + " must be real. Use a source-supported real @Spy or explicit construction, "
                        + "not @Mock; preserve approved inputs and assertions.");
            }
        }
        String expected = Objects.toString(approved.expectedResult(), "").toLowerCase(Locale.ROOT);
        boolean approximate = expected.contains("approximately") || expected.contains("cho phép sai số") || expected.contains("xấp xỉ");
        if (approximate) validateJwtTimeBounds(test, problems);
        return problems;
    }

    private static final Pattern NEGATED_BEFORE = Pattern.compile("(?iuU)\\b(?:không|chưa|not|never|no|without)\\b");
    private static final Pattern NEGATED_AFTER = Pattern.compile("(?iuU)^\\s*(?:là\\s+)?(?:không|chưa|not|never|(?:is|are|was|were)\\s+not)\\b");
    private static final Pattern OPTIONAL_CHOICE = Pattern.compile("(?iuU)\\b(?:hoặc|hay|or|either)\\b");

    static boolean requiresRealDependency(String preconditions, String type, boolean soleMapperRole) {
        if (preconditions == null || preconditions.isBlank()) return false;
        Pattern explicitPattern = Pattern.compile("(?iuU)(?:\\b" + Pattern.quote(type)
                + "\\s+(?:(?:phải\\s+)?(?:dùng|sử\\s+dụng)\\s+)?(?:implementation\\s+)?(?:thật|thực(?!\\s+hiện\\b))\\b|\\breal\\s+" + Pattern.quote(type) + "\\b)");
        Pattern rolePattern = Pattern.compile("(?iuU)\\b(?:mapper\\s+(?:thật|thực(?!\\s+hiện\\b))|real\\s+mapper)\\b");
        for (String clause : preconditions.split("[.;\\n]")) {
            var explicit = explicitPattern.matcher(clause);
            var role = rolePattern.matcher(clause);
            var matched = explicit.find() ? explicit : (soleMapperRole && role.find() ? role : null);
            if (matched == null) continue;
            int commaBefore = clause.lastIndexOf(',', matched.start());
            int subStart = commaBefore >= 0 && !OPTIONAL_CHOICE.matcher(clause.substring(commaBefore + 1, matched.start())).find()
                    ? commaBefore + 1 : 0;
            int commaAfter = clause.indexOf(',', matched.end());
            int subEnd = commaAfter >= 0 && !Pattern.compile("(?iuU)^\\s*,\\s*(?:hoặc|hay|or)\\b").matcher(clause.substring(commaAfter)).find()
                    ? commaAfter : clause.length();
            String before = clause.substring(subStart, matched.end());
            String after = clause.substring(matched.end(), subEnd);
            if (NEGATED_BEFORE.matcher(before).find() || NEGATED_AFTER.matcher(after).find()
                    || OPTIONAL_CHOICE.matcher(clause.substring(subStart, subEnd)).find()) continue;
            return true;
        }
        return false;
    }

    private static List<MethodCallExpr> targetCalls(MethodDeclaration method, MethodContextDto target,
            CompilationUnit test, GeneratedTestApiValidator api) {
        var owner = api.declaration(target.classQualifiedName());
        if (owner == null || owner.getMethodsByName(target.methodName()).size() != 1) return List.of();
        return method.findAll(MethodCallExpr.class).stream().filter(c -> c.getNameAsString().equals(target.methodName()) && c.getScope().isPresent())
                .filter(c -> c.findAncestor(LambdaExpr.class).isEmpty())
                .filter(c -> { var type = api.expressionType(c.getScope().get(), test); return type != null && type.isClassOrInterfaceType()
                        && target.classQualifiedName().equals(api.qualifiedType(type.asClassOrInterfaceType(), test)); }).toList();
    }

    private static void validateScenarioNulls(Map<String, Object> data, MethodContextDto target, MethodDeclaration production,
            CompilationUnit test, GeneratedTestApiValidator api, List<String> problems) {
        // shortcut: Chỉ chứng minh constructor trực tiếp; mở rộng factory/setter khi có lỗi runtime làm bằng chứng.
        if (data == null || !(data.get("input") instanceof Map<?, ?> input) || !(input.get("cases") instanceof List<?> scenarios)
                || scenarios.isEmpty() || scenarios.stream().anyMatch(s -> !(s instanceof Map<?, ?>))) return;
        for (var method : test.findAll(MethodDeclaration.class)) {
            if (method.getAnnotations().stream().noneMatch(a -> a.getName().getIdentifier().equals("Test"))) continue;
            var calls = targetCalls(method, target, test, api);
            if (calls.size() != scenarios.size()) continue;
            for (int index = 0; index < production.getParameters().size(); index++) {
                String parameter = production.getParameter(index).getNameAsString();
                for (var scenario : scenarios) {
                    if (!(((Map<?, ?>) scenario).get(parameter) instanceof Map<?, ?> fixture)
                            || !(fixture.get("arguments") instanceof List<?> expected) || !(fixture.get("constructor") instanceof String descriptor)) continue;
                    String type = descriptor.split("\\(", 2)[0].trim(); type = type.substring(type.lastIndexOf('.') + 1);
                    final int argument = index;
                    final String constructorType = type;
                    boolean possible = calls.stream().anyMatch(call -> {
                        if (call.getArguments().size() <= argument) return true;
                        var constructor = constructorAt(call.getArgument(argument), call, method);
                        if (constructor == null || !constructor.getType().getNameAsString().equals(constructorType)
                                || constructor.getArguments().size() != expected.size()) return true;
                        for (int slot = 0; slot < expected.size(); slot++) {
                            Expression value = constructor.getArgument(slot);
                            while (value.isEnclosedExpr()) value = value.asEnclosedExpr().getInner();
                            if (expected.get(slot) == null && (value.isLiteralExpr() && !value.isNullLiteralExpr() || value.isObjectCreationExpr())) return false;
                            if (expected.get(slot) != null && value.isNullLiteralExpr()) return false;
                        }
                        return true;
                    });
                    if (!possible) problems.add("Approved input constructor null positions changed for parameter '" + parameter
                            + "'. Preserve every approved scenario; do not populate null patch fields from the existing entity or expected response.");
                }
            }
        }
    }

    private static ObjectCreationExpr constructorAt(Expression argument, MethodCallExpr call, MethodDeclaration method) {
        if (argument.isObjectCreationExpr()) return argument.asObjectCreationExpr();
        if (!argument.isNameExpr()) return null;
        String name = argument.asNameExpr().getNameAsString();
        var variable = precedingLocal(name, call, method);
        if (variable == null) return null;
        // Lời gọi/setter/alias trước Act có thể đổi DTO; không suy null từ constructor trong trường hợp đó.
        if (method.findAll(NameExpr.class).stream().anyMatch(ref -> ref.getNameAsString().equals(name) && ref.getRange().isPresent()
                && ref.getRange().get().begin.isBefore(call.getRange().get().begin))) return null;
        return variable.getInitializer().filter(Expression::isObjectCreationExpr).map(Expression::asObjectCreationExpr).orElse(null);
    }

    private static VariableDeclarator precedingLocal(String name, Node use, MethodDeclaration method) {
        var variables = method.findAll(VariableDeclarator.class).stream().filter(v -> v.getNameAsString().equals(name)).toList();
        if (variables.size() != 1) return null;
        var variable = variables.get(0);
        var statement = variable.getParentNode().flatMap(Node::getParentNode).filter(ExpressionStmt.class::isInstance).orElse(null);
        var block = statement == null ? null : statement.getParentNode().filter(BlockStmt.class::isInstance).map(BlockStmt.class::cast).orElse(null);
        if (block == null || !block.isAncestorOf(use) || variable.getRange().isEmpty() || use.getRange().isEmpty()
                || !variable.getRange().get().end.isBefore(use.getRange().get().begin)) return null;
        return variable;
    }

    private static void validateMockProjection(MethodContextDto target, MethodDeclaration production, FieldDeclaration mock,
            CompilationUnit test, GeneratedTestApiValidator api, List<String> problems) {
        // shortcut: Chỉ xét stream().map(methodRef).toList(); pipeline khác cần bằng chứng riêng trước khi mở rộng.
        if (mock.getVariables().size() != 1 || mock.getVariable(0).getInitializer().isPresent()
                || mock.getAnnotations().stream().anyMatch(a -> a.toString().contains("answer"))
                || test.findAll(ClassOrInterfaceDeclaration.class).stream().anyMatch(c -> !c.getExtendedTypes().isEmpty())
                || test.findAll(MethodDeclaration.class).stream().anyMatch(m -> m.getAnnotations().stream()
                        .anyMatch(a -> Set.of("Before", "BeforeEach", "BeforeAll", "BeforeClass").contains(a.getName().getIdentifier())))) return;
        String mapper = mock.getVariable(0).getNameAsString();
        if (test.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals(mapper))) return;
        var owner = api.declaration(target.classQualifiedName());
        if (owner == null) return;
        var sourceUnit = owner.findCompilationUnit().orElseThrow();
        var returns = production.findAll(ReturnStmt.class);
        if (returns.size() != 1 || returns.get(0).getExpression().filter(Expression::isMethodCallExpr).isEmpty()) return;
        var terminal = returns.get(0).getExpression().get().asMethodCallExpr();
        if (!terminal.getNameAsString().equals("toList") || !terminal.getArguments().isEmpty() || terminal.getScope().filter(Expression::isMethodCallExpr).isEmpty()) return;
        var map = terminal.getScope().get().asMethodCallExpr();
        if (!map.getNameAsString().equals("map") || map.getArguments().size() != 1 || !map.getArgument(0).isMethodReferenceExpr()
                || map.getScope().filter(Expression::isMethodCallExpr).isEmpty()) return;
        var reference = map.getArgument(0).asMethodReferenceExpr();
        if (!Set.of(mapper, "this." + mapper).contains(reference.getScope().toString())) return;
        // Chỉ suy mock khi field được Lombok inject; tên trùng local không chứng minh cùng đối tượng.
        var sourceField = owner.getFieldByName(mapper).orElse(null);
        if (sourceField == null || !sourceField.getElementType().isClassOrInterfaceType() || !sourceField.isFinal() || sourceField.isStatic()
                || sourceField.getVariables().size() != 1 || sourceField.getVariable(0).getInitializer().isPresent()
                || !Objects.equals(api.qualifiedType(sourceField.getElementType().asClassOrInterfaceType(), sourceUnit),
                        api.qualifiedType(mock.getElementType().asClassOrInterfaceType(), test))
                || !owner.getConstructors().isEmpty()
                || owner.getAnnotations().stream().noneMatch(a -> Set.of("lombok.RequiredArgsConstructor", "lombok.AllArgsConstructor").contains(a.getNameAsString())
                        || Set.of("RequiredArgsConstructor", "AllArgsConstructor").contains(a.getNameAsString())
                        && sourceUnit.getImports().stream().anyMatch(i -> !i.isStatic() && (i.getNameAsString().equals("lombok." + a.getNameAsString())
                                || i.isAsterisk() && i.getNameAsString().equals("lombok"))))
                || reference.getScope().toString().equals(mapper) && (production.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(mapper))
                        || production.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(mapper)))) return;
        var stream = map.getScope().get().asMethodCallExpr();
        if (!stream.getNameAsString().equals("stream") || stream.getScope().filter(Expression::isNameExpr).isEmpty()) return;
        String collection = stream.getScope().get().asNameExpr().getNameAsString();
        var collectionVariable = precedingLocal(collection, stream, production);
        if (collectionVariable == null || !collectionVariable.getType().isClassOrInterfaceType()
                || !Set.of("java.util.List", "java.util.Set", "java.util.Collection").contains(api.qualifiedType(collectionVariable.getType().asClassOrInterfaceType(), sourceUnit))) return;
        var mapperType = api.declaration(mock.getElementType(), test);
        if (mapperType == null) return;
        var projections = mapperType.getMethodsByName(reference.getIdentifier());
        if (projections.size() != 1 || api.declaration(projections.get(0).getType(), mapperType.findCompilationUnit().orElseThrow()) == null) return;
        for (var method : test.findAll(MethodDeclaration.class)) for (var act : targetCalls(method, target, test, api)) {
            var result = act.getParentNode().filter(VariableDeclarator.class::isInstance).map(VariableDeclarator.class::cast).orElse(null);
            if (result == null) continue;
            for (var get : method.findAll(MethodCallExpr.class)) {
                if (!get.getNameAsString().equals("get") || get.getScope().filter(Expression::isNameExpr)
                        .filter(s -> s.asNameExpr().getNameAsString().equals(result.getNameAsString())).isEmpty()
                        || precedingLocal(result.getNameAsString(), get, method) != result) continue;
                var element = get.getParentNode().filter(VariableDeclarator.class::isInstance).map(VariableDeclarator.class::cast).orElse(null);
                if (element == null) continue;
                boolean dereferenced = method.findAll(MethodCallExpr.class).stream().anyMatch(read -> read.getScope().filter(Expression::isNameExpr)
                        .filter(s -> s.asNameExpr().getNameAsString().equals(element.getNameAsString())).isPresent()
                        && precedingLocal(element.getNameAsString(), read, method) == element
                        && method.findAll(AssignExpr.class).stream().noneMatch(a -> a.getTarget().isNameExpr()
                                && Set.of(result.getNameAsString(), element.getNameAsString()).contains(a.getTarget().asNameExpr().getNameAsString()))
                        && read.findAncestor(LambdaExpr.class).isEmpty() && read.findAncestor(ConditionalExpr.class).isEmpty()
                        && read.stream(Node.TreeTraversal.PARENTS).noneMatch(n -> n instanceof BinaryExpr b
                                && Set.of(BinaryExpr.Operator.AND, BinaryExpr.Operator.OR).contains(b.getOperator()))
                        && read.findAncestor(ExpressionStmt.class).flatMap(Node::getParentNode).flatMap(Node::getParentNode).filter(method::equals).isPresent());
                if (dereferenced) { problems.add("Unstubbed mock projection: " + mapper + "." + reference.getIdentifier()
                        + " returns null by default, but the test dereferences its mapped DTO. Use a verified real @Spy or stub the mapper with DTOs preserving every approved field."); return; }
            }
        }
    }

    private static boolean mockitoMock(FieldDeclaration field, CompilationUnit test) {
        if (field.getAnnotationByName("org.mockito.Mock").isPresent()) return true;
        return field.getAnnotationByName("Mock").isPresent()
                && test.findAll(AnnotationDeclaration.class).stream().noneMatch(a -> a.getNameAsString().equals("Mock"))
                && test.getImports().stream().anyMatch(i -> !i.isStatic() && (i.getNameAsString().equals("org.mockito.Mock")
                        || i.isAsterisk() && i.getNameAsString().equals("org.mockito")));
    }

    private static void validateJwtTimeBounds(CompilationUnit test, List<String> problems) {
        for (var method : test.findAll(MethodDeclaration.class)) {
            if (method.getAnnotationByName("Test").isEmpty()) continue;
            Set<String> jwt = new HashSet<>(), expiration = new HashSet<>(), clocks = new HashSet<>();
            for (var variable : method.findAll(VariableDeclarator.class)) {
                if (variable.getType().isClassOrInterfaceType()) {
                    var type = variable.getType().asClassOrInterfaceType();
                    if (officialType(type.getNameWithScope(), "Jws", test)
                            && type.getTypeArguments().stream().flatMap(Collection::stream)
                                    .anyMatch(argument -> officialType(argument.asString(), "Claims", test))) jwt.add(variable.getNameAsString());
                }
                var initializer = variable.getInitializer().filter(Expression::isMethodCallExpr).map(Expression::asMethodCallExpr).orElse(null);
                if (initializer == null) continue;
                if (initializer.getNameAsString().equals("currentTimeMillis") && initializer.getArguments().isEmpty()
                        && initializer.getScope().map(Object::toString).filter(s -> s.equals("System") || s.equals("java.lang.System")).isPresent()
                        && test.findAll(ClassOrInterfaceDeclaration.class).stream().noneMatch(t -> t.getNameAsString().equals("System"))) clocks.add(variable.getNameAsString());
                if (!initializer.getNameAsString().equals("getExpiration") || !initializer.getArguments().isEmpty()) continue;
                var body = initializer.getScope().filter(Expression::isMethodCallExpr).map(Expression::asMethodCallExpr).orElse(null);
                if (body != null && Set.of("getBody", "getPayload").contains(body.getNameAsString()) && body.getArguments().isEmpty()
                        && body.getScope().filter(Expression::isNameExpr).map(e -> e.asNameExpr().getNameAsString()).filter(jwt::contains).isPresent())
                    expiration.add(variable.getNameAsString());
            }
            clocks.removeIf(name -> method.findAll(AssignExpr.class).stream().anyMatch(a -> a.getTarget().toString().equals(name))
                    || method.findAll(UnaryExpr.class).stream().anyMatch(u -> u.getExpression().toString().equals(name)
                            && Set.of(UnaryExpr.Operator.PREFIX_INCREMENT, UnaryExpr.Operator.POSTFIX_INCREMENT,
                                    UnaryExpr.Operator.PREFIX_DECREMENT, UnaryExpr.Operator.POSTFIX_DECREMENT).contains(u.getOperator())));
            for (var comparison : method.findAll(BinaryExpr.class)) {
                if (!Set.of(BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS).contains(comparison.getOperator())) continue;
                if (!positivelyAsserted(comparison, test)) continue;
                var left = comparison.getLeft();
                if (!left.isMethodCallExpr()) continue;
                var call = left.asMethodCallExpr();
                if (call.getNameAsString().equals("getTime") && call.getArguments().isEmpty()
                        && call.getScope().filter(Expression::isNameExpr).map(e -> e.asNameExpr().getNameAsString()).filter(expiration::contains).isPresent()
                        && rawMillisClock(comparison.getRight(), clocks)) {
                    problems.add("JWT expiration has whole-seconds precision. This approved case allows approximate time, but its lower bound uses raw milliseconds. "
                            + "Compare epoch seconds or allow sub-second rounding tolerance; preserve sessionTime and the approved expected interval.");
                    return;
                }
            }
        }
    }

    private static boolean positivelyAsserted(BinaryExpr comparison, CompilationUnit test) {
        com.github.javaparser.ast.Node argument = comparison;
        while (argument.getParentNode().filter(EnclosedExpr.class::isInstance).isPresent()) argument = argument.getParentNode().orElseThrow();
        var assertion = argument.getParentNode().filter(MethodCallExpr.class::isInstance).map(MethodCallExpr.class::cast).orElse(null);
        if (assertion == null || !assertion.getNameAsString().equals("assertTrue") || !assertion.getArguments().contains(argument)) return false;
        if (test.findAll(MethodDeclaration.class).stream().anyMatch(m -> m.getNameAsString().equals("assertTrue"))) return false;
        if (assertion.getScope().isPresent()) {
            String scope = assertion.getScope().orElseThrow().toString();
            if (Set.of("org.junit.jupiter.api.Assertions", "org.junit.Assert").contains(scope)) return true;
            return Set.of("Assertions", "Assert").contains(scope)
                    && test.findAll(ClassOrInterfaceDeclaration.class).stream().noneMatch(c -> c.getNameAsString().equals(scope))
                    && test.getImports().stream().anyMatch(i -> !i.isStatic()
                            && Set.of("org.junit.jupiter.api.Assertions", "org.junit.Assert").contains(i.getNameAsString()));
        }
        return test.getImports().stream().anyMatch(i -> i.isStatic() && (i.isAsterisk()
                && Set.of("org.junit.jupiter.api.Assertions", "org.junit.Assert").contains(i.getNameAsString())
                || Set.of("org.junit.jupiter.api.Assertions.assertTrue", "org.junit.Assert.assertTrue").contains(i.getNameAsString())));
    }

    private static boolean officialType(String name, String simple, CompilationUnit test) {
        if (name.equals("io.jsonwebtoken." + simple)) return true;
        return name.equals(simple) && test.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream()
                .noneMatch(t -> t.getNameAsString().equals(simple))
                && test.getImports().stream().anyMatch(i -> !i.isStatic() && (i.getNameAsString().equals("io.jsonwebtoken." + simple)
                        || i.isAsterisk() && i.getNameAsString().equals("io.jsonwebtoken")));
    }

    private static boolean rawMillisClock(Expression value, Set<String> clocks) {
        if (value.isEnclosedExpr()) return rawMillisClock(value.asEnclosedExpr().getInner(), clocks);
        if (value.isNameExpr()) return clocks.contains(value.asNameExpr().getNameAsString());
        if (!value.isBinaryExpr()) return false;
        var binary = value.asBinaryExpr();
        if (!Set.of(BinaryExpr.Operator.PLUS, BinaryExpr.Operator.MINUS, BinaryExpr.Operator.MULTIPLY).contains(binary.getOperator())) return false;
        if (binary.getOperator() == BinaryExpr.Operator.MINUS) {
            try {
                long margin = binary.getRight().isLongLiteralExpr() ? binary.getRight().asLongLiteralExpr().asNumber().longValue()
                        : binary.getRight().asIntegerLiteralExpr().asNumber().longValue();
                if (margin >= 999) return false;
            } catch (RuntimeException uncertainMargin) { return false; }
        }
        return rawMillisClock(binary.getLeft(), clocks) || rawMillisClock(binary.getRight(), clocks);
    }
}
