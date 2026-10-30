package com.greytest.service.agent;

import java.util.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.greytest.dto.agent.GenerationContextDtos.*;

/** Đối chiếu fixture save và trạng thái ban đầu với setup đã duyệt; không suy oracle từ kết quả source. */
final class GeneratedRepositoryFixtureValidator {
    private record Stub(MethodCallExpr registration, MethodCallExpr invocation, Expression returned) { }
    private record Fixture(String name, ClassOrInterfaceDeclaration type) { }

    private final UnitTestContextDto context;
    private final GeneratedTestApiValidator source;
    private final CompilationUnit test;

    private GeneratedRepositoryFixtureValidator(UnitTestContextDto context, CompilationUnit test) {
        this.context = context;
        this.source = new GeneratedTestApiValidator(context);
        this.test = test;
    }

    static List<String> validate(UnitTestContextDto context, MethodContextDto target, Long caseId, CompilationUnit test) {
        if (context.approvedTestCases() == null) return List.of();
        var approved = context.approvedTestCases().stream().filter(item -> Objects.equals(item.id(), caseId)).findFirst().orElse(null);
        if (approved == null || approved.testData() == null) return List.of();
        var checker = new GeneratedRepositoryFixtureValidator(context, test);
        List<String> problems = new ArrayList<>();
        for (var method : test.findAll(MethodDeclaration.class)) {
            if (method.getAnnotations().stream().noneMatch(a -> a.getName().getIdentifier().equals("Test")) || !straightLine(method)) continue;
            List<Stub> stubs = checker.stubs(method);
            checker.validateSaveReturns(approved, method, stubs, problems);
            checker.validateMappingState(approved, target, method, stubs, problems);
            checker.validateUnapprovedEnrichedFieldNullAssertions(approved, target, method, stubs, problems);
        }
        return problems;
    }

    private static boolean straightLine(MethodDeclaration method) {
        // shortcut: Không diễn giải nhánh, vòng lặp hoặc lifecycle/helper; chỉ kiểm chứng Arrange/Act/Assert trực tiếp.
        return method.findAll(Node.class).stream().noneMatch(node -> node instanceof IfStmt || node instanceof ForStmt
                || node instanceof ForEachStmt || node instanceof WhileStmt || node instanceof DoStmt
                || node instanceof SwitchStmt || node instanceof TryStmt || node instanceof ConditionalExpr);
    }

    private List<Stub> stubs(MethodDeclaration method) {
        List<Stub> result = new ArrayList<>();
        for (var call : method.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("thenReturn") || call.getArguments().size() != 1
                    || call.getScope().filter(Expression::isMethodCallExpr).isEmpty()) continue;
            var when = call.getScope().get().asMethodCallExpr();
            if (!isMockitoWhen(when) || when.getArguments().size() != 1 || !when.getArgument(0).isMethodCallExpr()) continue;
            result.add(new Stub(call, when.getArgument(0).asMethodCallExpr(), call.getArgument(0)));
        }
        return result;
    }

    private boolean isMockitoWhen(MethodCallExpr call) {
        if (!call.getNameAsString().equals("when")) return false;
        if (call.getScope().isPresent()) {
            String owner = call.getScope().get().toString();
            return owner.equals("org.mockito.Mockito") || owner.equals("Mockito") && test.getImports().stream()
                    .anyMatch(i -> !i.isStatic() && i.getNameAsString().equals("org.mockito.Mockito"));
        }
        return test.findAll(MethodDeclaration.class).stream().noneMatch(m -> m.getNameAsString().equals("when"))
                && test.getImports().stream().anyMatch(i -> i.isStatic() && (i.isAsterisk()
                        && i.getNameAsString().equals("org.mockito.Mockito") || i.getNameAsString().equals("org.mockito.Mockito.when")));
    }

    private void validateSaveReturns(TestCaseContextItemDto approved, MethodDeclaration method, List<Stub> stubs, List<String> problems) {
        Set<String> inputFields = new LinkedHashSet<>();
        Map<String, Integer> occurrences = new HashMap<>();
        collectInputFields(approved.testData().get("input"), inputFields, occurrences);
        inputFields.removeIf(name -> occurrences.getOrDefault(name, 0) != 1);
        for (var stub : stubs) {
            if (!stub.invocation().getNameAsString().equals("save") || stub.invocation().getScope().isEmpty()) continue;
            String description = mockDescription(approved.testData().get("mocks"), "", stub.invocation());
            if (!description.toLowerCase(Locale.ROOT).matches("(?s).*(retain.*fields?|preserv.*fields?|giữ.*(?:trường|ánh xạ)|chính.*(?:truyền|cập nhật)|submitted entity|same entity).*$")) continue;
            Fixture fixture = fixture(stub.returned(), method);
            if (fixture == null || stubs.stream().anyMatch(other -> other != stub && mentions(other.returned(), fixture.name()))
                    || uncertainSetup(fixture, method, stub.registration())) continue;
            Set<String> populated = populatedFields(fixture, method);
            Set<String> assertedNonNull = assertedReturnedFields(fixture, method);
            List<String> missing = new ArrayList<>();
            for (var field : fixture.type().getFields()) {
                if (field.isStatic() || field.getElementType().isPrimitiveType()) continue;
                for (var variable : field.getVariables()) {
                    String property = variable.getNameAsString();
                    if (!inputFields.contains(property) || !assertedNonNull.contains(property) || populated.contains(property)
                            || !directGetter(fixture.type(), property)) continue;
                    if (variable.getInitializer().isEmpty() || variable.getInitializer().get().isNullLiteralExpr()) missing.add(property);
                }
            }
            if (!missing.isEmpty()) problems.add("Approved save-return fixture '" + fixture.name() + "' does not retain required non-null input fields "
                    + missing + ". Mockito returns this separate object without copying the submitted entity. Complete the returned fixture through verified APIs "
                    + "or use a source-compatible thenAnswer returning invocation.getArgument(0), assigning only simulated persistence fields. "
                    + "Preserve approved inputs, assertions and deliberately empty-return scenarios; do not change production or expected results.");
        }
    }

    private Set<String> assertedReturnedFields(Fixture fixture, MethodDeclaration method) {
        Set<String> result = new HashSet<>();
        for (var call : method.findAll(MethodCallExpr.class)) {
            String junit = source.junitAssertionsOwner(call, test);
            if (junit == null) continue;
            Expression actual = null;
            int count = call.getArguments().size();
            int first = junit.equals("org.junit.Assert") ? count - 2 : 0;
            if (call.getNameAsString().equals("assertEquals") && (count == 2 || count == 3)) {
                var expected = call.getArgument(first);
                if (expected.isStringLiteralExpr() || expected.isIntegerLiteralExpr() || expected.isLongLiteralExpr()
                        || expected.isDoubleLiteralExpr() || expected.isBooleanLiteralExpr() || expected.isCharLiteralExpr()
                        || expected.isObjectCreationExpr()) actual = call.getArgument(first + 1);
            } else if (call.getNameAsString().equals("assertNotNull") && (count == 1 || count == 2)) {
                actual = call.getArgument(junit.equals("org.junit.Assert") ? count - 1 : 0);
            }
            if (actual == null || !actual.isMethodCallExpr()) continue;
            var getter = actual.asMethodCallExpr();
            if (!getter.getNameAsString().matches("get[A-Z].*") || !getter.getArguments().isEmpty() || getter.getScope().isEmpty()) continue;
            String property = property(getter.getNameAsString());
            var returnedType = source.declaration(source.expressionType(getter.getScope().get(), test), test);
            if (returnedType != null && directGetter(returnedType, property) && copiesField(returnedType, fixture.type(), property)) result.add(property);
        }
        return result;
    }

    private boolean copiesField(ClassOrInterfaceDeclaration returned, ClassOrInterfaceDeclaration entity, String property) {
        if (returned == entity) return true;
        var unit = returned.findCompilationUnit().orElseThrow();
        for (var constructor : returned.getConstructors()) {
            if (constructor.getParameters().size() != 1 || source.declaration(constructor.getParameter(0).getType(), unit) != entity) continue;
            var assignments = constructor.findAll(AssignExpr.class).stream().filter(a -> a.getTarget().toString().equals("this." + property)
                    || a.getTarget().toString().equals(property)).toList();
            if (assignments.size() != 1) continue;
            var assignment = assignments.get(0);
            if (assignment.getOperator() != AssignExpr.Operator.ASSIGN || !assignment.getValue().isMethodCallExpr()
                    || assignment.getParentNode().flatMap(Node::getParentNode).filter(constructor.getBody()::equals).isEmpty()) continue;
            var getter = assignment.getValue().asMethodCallExpr();
            if (getter.getArguments().isEmpty() && getter.getNameAsString().equals("get" + upper(property))
                    && getter.getScope().map(Object::toString).filter(constructor.getParameter(0).getNameAsString()::equals).isPresent()) return true;
        }
        return false;
    }

    private static void collectInputFields(Object value, Set<String> fields, Map<String, Integer> occurrences) {
        if (!(value instanceof Map<?, ?> map)) return;
        for (var item : map.entrySet()) {
            if (item.getKey() instanceof String key && !(item.getValue() instanceof Map<?, ?>)) {
                occurrences.merge(key, 1, Integer::sum);
                if (item.getValue() != null) fields.add(key);
            }
            collectInputFields(item.getValue(), fields, occurrences);
        }
    }

    private String mockDescription(Object value, String prefix, MethodCallExpr invocation) {
        if (!(value instanceof Map<?, ?> map)) return "";
        String path = invocation.getScope().map(Object::toString).orElse("") + "." + invocation.getNameAsString();
        for (var entry : map.entrySet()) {
            String key = prefix + entry.getKey();
            if (key.equals(path) || key.startsWith(path + "(")) return Objects.toString(entry.getValue(), "");
            String nested = mockDescription(entry.getValue(), key + ".", invocation);
            if (!nested.isEmpty()) return nested;
        }
        return "";
    }

    private Fixture fixture(Expression value, MethodDeclaration method) {
        if (!value.isNameExpr()) return null;
        String name = value.asNameExpr().getNameAsString();
        var locals = method.findAll(VariableDeclarator.class).stream().filter(v -> v.getNameAsString().equals(name)).toList();
        if (locals.size() != 1 || locals.get(0).getInitializer().filter(Expression::isObjectCreationExpr).isEmpty()) return null;
        var creation = locals.get(0).getInitializer().get().asObjectCreationExpr();
        var type = source.declaration(creation.getType(), test);
        if (!creation.getArguments().isEmpty() || creation.getAnonymousClassBody().isPresent() || type == null
                || !type.getExtendedTypes().isEmpty() || !type.getImplementedTypes().isEmpty()
                || type.getMembers().stream().anyMatch(InitializerDeclaration.class::isInstance)
                || type.getConstructors().stream().anyMatch(c -> c.getParameters().isEmpty() && !c.getBody().getStatements().isEmpty())) return null;
        return new Fixture(name, type);
    }

    private boolean uncertainSetup(Fixture fixture, MethodDeclaration method, MethodCallExpr registration) {
        if (method.findAll(AssignExpr.class).stream().anyMatch(a -> a.getTarget().isNameExpr() && a.getTarget().toString().equals(fixture.name()))) return true;
        if (method.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getInitializer().filter(e -> e.isNameExpr()
                && e.toString().equals(fixture.name())).isPresent())) return true;
        for (var call : method.findAll(MethodCallExpr.class)) {
            if (call != registration && call.getArguments().stream().anyMatch(e -> e.isNameExpr() && e.toString().equals(fixture.name()))) return true;
            if (call.getScope().map(Object::toString).filter(fixture.name()::equals).isPresent()
                    && (!before(call, registration) || deferred(call, method) || !call.getNameAsString().matches("set[A-Z].*") || call.getArguments().size() != 1
                        || !directSetter(fixture.type(), property(call.getNameAsString())))) return true;
        }
        return false;
    }

    private Set<String> populatedFields(Fixture fixture, MethodDeclaration method) {
        Set<String> fields = new HashSet<>();
        for (var call : method.findAll(MethodCallExpr.class)) {
            if (call.getScope().map(Object::toString).filter(fixture.name()::equals).isEmpty()
                    || !call.getNameAsString().matches("set[A-Z].*") || call.getArguments().size() != 1) continue;
            if (call.getArgument(0).isNullLiteralExpr()) fields.remove(property(call.getNameAsString()));
            else fields.add(property(call.getNameAsString()));
        }
        return fields;
    }

    private boolean directSetter(ClassOrInterfaceDeclaration type, String property) {
        var methods = type.getMethodsByName("set" + upper(property));
        if (!methods.isEmpty()) {
            if (methods.size() != 1 || methods.get(0).getParameters().size() != 1 || methods.get(0).getBody().isEmpty()) return false;
            var body = methods.get(0).getBody().get().getStatements();
            if (body.size() != 1 || !body.get(0).isExpressionStmt() || !body.get(0).asExpressionStmt().getExpression().isAssignExpr()) return false;
            var assignment = body.get(0).asExpressionStmt().getExpression().asAssignExpr();
            String parameter = methods.get(0).getParameter(0).getNameAsString();
            return assignment.getOperator() == AssignExpr.Operator.ASSIGN && assignment.getValue().toString().equals(parameter)
                    && (assignment.getTarget().toString().equals("this." + property)
                        || !parameter.equals(property) && assignment.getTarget().toString().equals(property));
        }
        return lombokAccessor(type, property, "Setter");
    }

    static boolean hasDirectProperty(UnitTestContextDto context, CompilationUnit test,
            com.github.javaparser.ast.type.Type entityType, String property) {
        var checker = new GeneratedRepositoryFixtureValidator(context, test);
        var declaration = checker.source.declaration(entityType, test);
        return declaration != null && checker.directSetter(declaration, property) && checker.directGetter(declaration, property);
    }

    private boolean directGetter(ClassOrInterfaceDeclaration type, String property) {
        var methods = type.getMethodsByName("get" + upper(property));
        if (!methods.isEmpty()) {
            return methods.size() == 1 && methods.get(0).getParameters().isEmpty() && methods.get(0).getBody().isPresent()
                    && methods.get(0).getBody().get().getStatements().size() == 1
                    && methods.get(0).getBody().get().getStatement(0).isReturnStmt()
                    && methods.get(0).getBody().get().getStatement(0).asReturnStmt().getExpression()
                        .map(e -> e.toString().equals(property) || e.toString().equals("this." + property)).orElse(false);
        }
        return lombokAccessor(type, property, "Getter");
    }

    private boolean lombokAccessor(ClassOrInterfaceDeclaration type, String property, String annotation) {
        var unit = type.findCompilationUnit().orElseThrow();
        if (type.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("Accessors"))) return false;
        var field = type.getFieldByName(property);
        if (field.isEmpty() || field.get().getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("Accessors")
                || a.getName().getIdentifier().equals(annotation) && a.toString().contains("NONE"))
                || type.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals(annotation) && a.toString().contains("NONE"))) return false;
        return source.lombok(field.get(), unit, annotation) || source.lombok(type, unit, annotation) || source.lombok(type, unit, "Data")
                || annotation.equals("Getter") && source.lombok(type, unit, "Value");
    }

    private void validateMappingState(TestCaseContextItemDto approved, MethodContextDto target, MethodDeclaration method, List<Stub> stubs, List<String> problems) {
        if (target == null || target.sourceCode() == null || context.approvedTestPlans() == null || context.approvedBusinessRules() == null) return;
        String description = Objects.toString(approved.description(), "").toLowerCase(Locale.ROOT);
        if (description.matches("(?s).*(idempotent|unchanged|same value|already|giữ nguyên|không thay đổi).*$")) return;
        var parsed = new JavaParser().parseMethodDeclaration(target.sourceCode()).getResult();
        var owner = source.declaration(target.classQualifiedName());
        if (parsed.isEmpty() || parsed.get().getBody().isEmpty() || owner == null) return;
        var statements = parsed.get().getBody().get().getStatements();
        if (statements.stream().anyMatch(s -> !s.isExpressionStmt() || !s.asExpressionStmt().getExpression().isMethodCallExpr())) return;
        Set<Long> ruleIds = new HashSet<>();
        context.approvedTestPlans().stream().filter(p -> Objects.equals(p.id(), approved.testPlanId())).forEach(p -> {
            ruleIds.add(p.businessRuleId()); if (p.coveredRuleIds() != null) ruleIds.addAll(p.coveredRuleIds());
        });
        for (var rule : context.approvedBusinessRules()) {
            if (!ruleIds.contains(rule.id()) || !Objects.equals(rule.methodId(), target.id()) || rule.sourceBranchId() == null
                    || !rule.sourceBranchId().matches("STMT-[1-9][0-9]{0,3}")) continue;
            int index = Integer.parseInt(rule.sourceBranchId().substring(5)) - 1;
            if (index >= statements.size()) continue;
            var setter = statements.get(index).asExpressionStmt().getExpression().asMethodCallExpr();
            validateMappingAssignment(approved, method, stubs, parsed.get(), owner, setter, problems);
        }
    }

    private void validateMappingAssignment(TestCaseContextItemDto approved, MethodDeclaration testMethod, List<Stub> stubs,
            MethodDeclaration mapping, ClassOrInterfaceDeclaration owner, MethodCallExpr setter, List<String> problems) {
        if (!setter.getNameAsString().matches("set[A-Z].*") || setter.getScope().isEmpty() || setter.getArguments().size() != 1
                || !setter.getArgument(0).isMethodCallExpr()) return;
        var getter = setter.getArgument(0).asMethodCallExpr();
        if (!getter.getArguments().isEmpty() || getter.getScope().isEmpty()) return;
        String property = property(setter.getNameAsString());
        String preconditions = Objects.toString(approved.preconditions(), "").toLowerCase(Locale.ROOT);
        if (preconditions.contains(property.toLowerCase(Locale.ROOT)) || preconditions.matches("(?s).*(\\btrue\\b|\\bfalse\\b|khả dụng|availability).*$")) return;
        var entity = parameterType(mapping, owner, setter.getScope().get());
        var request = parameterType(mapping, owner, getter.getScope().get());
        if (entity == null || request == null || !getter.getNameAsString().equals("get" + upper(property))
                || !directSetter(entity, property) || !directSetter(request, property) || !directGetter(request, property)) return;
        for (var stub : stubs) {
            if (!stub.returned().isMethodCallExpr()) continue;
            var optional = stub.returned().asMethodCallExpr();
            if (!optional.getNameAsString().equals("of") || optional.getArguments().size() != 1) continue;
            Fixture existing = fixture(optional.getArgument(0), testMethod);
            if (existing == null || existing.type() != entity) continue;
            String lookup = mockDescription(approved.testData().get("mocks"), "", stub.invocation()).toLowerCase(Locale.ROOT);
            if (lookup.contains(property.toLowerCase(Locale.ROOT)) || lookup.matches("(?s).*(\\btrue\\b|\\bfalse\\b).*$")) continue;
            for (var variable : testMethod.findAll(VariableDeclarator.class)) {
                var fixture = fixture(new NameExpr(variable.getNameAsString()), testMethod);
                if (fixture == null || fixture.type() != request || !unfixedInitialInput(approved, fixture, property)) continue;
                var act = sutCall(fixture, owner, testMethod);
                if (act == null) continue;
                Boolean input = booleanValue(fixture, property, testMethod, act);
                var approvedInput = (Map<?, ?>) approved.testData().get("input");
                var approvedRequest = (Map<?, ?>) approvedInput.get(fixture.name());
                if (input != null && !input.equals(approvedRequest.get(property))) {
                    problems.add("Preserve the approved request field '" + property + "' with value " + approvedRequest.get(property)
                            + ". Do not change request data to evade the mapping fixture check; adjust only an unspecified initial entity state.");
                    continue;
                }
                Boolean initial = booleanValue(existing, property, testMethod, act);
                if (input != null && input.equals(initial)) problems.add("Non-discriminating mapping fixture for '" + property
                        + "': the initial entity value already equals the request value " + input + ". The selected approved statement tests request-to-entity mapping. "
                        + "Keep the approved request and assertion; when initial state is unspecified, initialize the existing entity to a distinct value through its verified setter. "
                        + "Never change an initial state fixed by approved preconditions or an idempotence scenario.");
            }
        }
    }

    private ClassOrInterfaceDeclaration parameterType(MethodDeclaration method, ClassOrInterfaceDeclaration owner, Expression name) {
        return method.getParameters().stream().filter(p -> p.getNameAsString().equals(name.toString()))
                .map(p -> source.declaration(p.getType(), owner.findCompilationUnit().orElseThrow())).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Boolean booleanValue(Fixture fixture, String property, MethodDeclaration method, MethodCallExpr act) {
        if (method.findAll(VariableDeclarator.class).stream().anyMatch(v -> before(v, act)
                && v.getInitializer().filter(e -> e.isNameExpr() && e.toString().equals(fixture.name())).isPresent())
                || method.findAll(AssignExpr.class).stream().anyMatch(a -> before(a, act) && a.getTarget().toString().equals(fixture.name()))) return null;
        var field = fixture.type().getFieldByName(property);
        if (field.isEmpty()) return null;
        var variable = field.get().getVariables().stream().filter(v -> v.getNameAsString().equals(property)).findFirst().orElseThrow();
        Boolean value = variable.getInitializer().filter(Expression::isBooleanLiteralExpr).map(e -> e.asBooleanLiteralExpr().getValue())
                .orElse(variable.getType().asString().equals("boolean") ? Boolean.FALSE : null);
        for (var call : method.findAll(MethodCallExpr.class)) {
            if (!before(call, act)) continue;
            if (deferred(call, method) && mentions(call, fixture.name())) return null;
            if (call.getArguments().stream().anyMatch(e -> e.isNameExpr() && e.toString().equals(fixture.name()))
                    && !optionalOf(call) && !mockStubbingUse(call, method)) return null;
            if (call.getScope().map(Object::toString).filter(fixture.name()::equals).isPresent()) {
                if (!call.getNameAsString().matches("set[A-Z].*") || call.getArguments().size() != 1
                        || !directSetter(fixture.type(), property(call.getNameAsString()))) return null;
                if (call.getNameAsString().equals("set" + upper(property)))
                    value = call.getArgument(0).isBooleanLiteralExpr() ? call.getArgument(0).asBooleanLiteralExpr().getValue() : null;
            }
        }
        return value;
    }

    private MethodCallExpr sutCall(Fixture fixture, ClassOrInterfaceDeclaration owner, MethodDeclaration method) {
        var calls = method.findAll(MethodCallExpr.class).stream().filter(call -> !deferred(call, method) && call.getScope().isPresent()
                && source.declaration(source.expressionType(call.getScope().get(), test), test) == owner
                && call.getArguments().stream().anyMatch(e -> e.isNameExpr() && e.toString().equals(fixture.name()))).toList();
        return calls.size() == 1 ? calls.get(0) : null;
    }

    private boolean unfixedInitialInput(TestCaseContextItemDto approved, Fixture request, String property) {
        if (!(approved.testData().get("input") instanceof Map<?, ?> input)
                || !(input.get(request.name()) instanceof Map<?, ?> values) || !(values.get(property) instanceof Boolean)) return false;
        Set<String> fields = new HashSet<>();
        Map<String, Integer> occurrences = new HashMap<>();
        collectInputFields(input, fields, occurrences);
        return occurrences.getOrDefault(property, 0) == 1;
    }

    private boolean optionalOf(MethodCallExpr call) {
        if (!call.getNameAsString().equals("of") || call.getScope().isEmpty()) return false;
        String scope = call.getScope().get().toString();
        return scope.equals("java.util.Optional") || scope.equals("Optional") && source.expressionType(call.getScope().get(), test) == null
                && test.getImports().stream().anyMatch(i -> !i.isStatic() && (i.getNameAsString().equals("java.util.Optional")
                        || i.isAsterisk() && i.getNameAsString().equals("java.util")));
    }

    private boolean mockStubbingUse(MethodCallExpr call, MethodDeclaration method) {
        for (var stub : stubs(method)) {
            if (stub.registration() != call && stub.invocation() != call || stub.invocation().getScope().filter(Expression::isNameExpr).isEmpty()) continue;
            String name = stub.invocation().getScope().get().toString();
            if (method.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))
                    || method.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(name))
                    || method.findAll(AssignExpr.class).stream().anyMatch(a -> a.getTarget().toString().equals(name)
                        || a.getTarget().toString().equals("this." + name))) return false;
            boolean imported = test.getImports().stream().anyMatch(i -> !i.isStatic() && (i.getNameAsString().equals("org.mockito.Mock")
                    || i.isAsterisk() && i.getNameAsString().equals("org.mockito")));
            return call.findAncestor(ClassOrInterfaceDeclaration.class).stream().flatMap(owner -> owner.getFields().stream())
                    .anyMatch(field -> field.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(name))
                            && field.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("org.mockito.Mock")
                                || imported && a.getNameAsString().equals("Mock")));
        }
        return false;
    }

    private static boolean before(Node first, Node second) {
        return first.getBegin().isPresent() && second.getBegin().isPresent() && first.getBegin().get().isBefore(second.getBegin().get());
    }

    private static boolean deferred(Node node, MethodDeclaration method) {
        return node.stream(Node.TreeTraversal.PARENTS).takeWhile(parent -> parent != method)
                .anyMatch(parent -> parent instanceof LambdaExpr || parent instanceof MethodDeclaration || parent instanceof ClassOrInterfaceDeclaration);
    }

    private static boolean mentions(Node expression, String name) {
        return expression.findAll(NameExpr.class).stream().anyMatch(e -> e.getNameAsString().equals(name));
    }

    private static String property(String setter) {
        return setter.length() > 3 ? Character.toLowerCase(setter.charAt(3)) + setter.substring(4) : "";
    }

    private static String upper(String property) {
        return Character.toUpperCase(property.charAt(0)) + property.substring(1);
    }

    /** Chặn assertion null tự suy diễn trên trường DTO đã được helper/SUT làm giàu khi TC không yêu cầu null. */
    private void validateUnapprovedEnrichedFieldNullAssertions(
            TestCaseContextItemDto approved, MethodContextDto target, MethodDeclaration method, List<Stub> stubs, List<String> problems) {
        if (target == null || target.sourceCode() == null || target.classQualifiedName() == null) return;
        boolean hasFailureOrNullStub = method.findAll(MethodCallExpr.class).stream()
                .anyMatch(c -> Set.of("doThrow", "thenThrow").contains(c.getNameAsString())
                        || c.getNameAsString().equals("thenReturn") && c.getArguments().size() == 1
                                && c.getArgument(0).isNullLiteralExpr());
        if (hasFailureOrNullStub) return;
        var parsedTarget = new JavaParser().parseMethodDeclaration(target.sourceCode()).getResult();
        var owner = source.declaration(target.classQualifiedName());
        if (parsedTarget.isEmpty() || owner == null) return;

        for (var variable : method.findAll(VariableDeclarator.class)) {
            if (variable.getInitializer().filter(Expression::isMethodCallExpr).isEmpty()) continue;
            var act = variable.getInitializer().get().asMethodCallExpr();
            if (!act.getNameAsString().equals(target.methodName()) || deferred(act, method) || act.getScope().isEmpty()
                    || source.declaration(source.expressionType(act.getScope().get(), test), test) != owner) continue;
            var returnedType = source.declaration(variable.getType(), test);
            if (returnedType == null) continue;
            Set<String> enriched = unconditionallyPopulatedProperties(parsedTarget.get(), owner, returnedType, stubs);
            if (enriched.isEmpty()) continue;
            String resultName = variable.getNameAsString();
            for (var call : method.findAll(MethodCallExpr.class)) {
                String junit = source.junitAssertionsOwner(call, test);
                if (junit == null) continue;
                Expression nullTarget = nullAssertedExpression(call, junit);
                if (nullTarget == null || !nullTarget.isMethodCallExpr()) continue;
                var getter = nullTarget.asMethodCallExpr();
                if (!getter.getNameAsString().matches("get[A-Z].*") || !getter.getArguments().isEmpty()
                        || getter.getScope().filter(Expression::isNameExpr)
                                .filter(s -> s.asNameExpr().getNameAsString().equals(resultName)).isEmpty()) continue;
                String prop = property(getter.getNameAsString());
                if (enriched.contains(prop) && !isPropertyNullApproved(approved, prop)) {
                    problems.add("Unapproved null assertion on '" + nullTarget + "': Test Case " + approved.id()
                            + " does not expect '" + prop + "' to be null, and " + target.methodName()
                            + " populates set" + upper(prop) + "(...) from the stubbed collaborators. "
                            + "Remove the unapproved null assertion or assert the enriched value from the approved mock fixtures.");
                }
            }
        }
    }

    private Set<String> unconditionallyPopulatedProperties(
            MethodDeclaration targetMethod, ClassOrInterfaceDeclaration owner, ClassOrInterfaceDeclaration returnedType, List<Stub> stubs) {
        List<MethodDeclaration> candidates = new ArrayList<>();
        candidates.add(targetMethod);
        for (int depth = 0; depth < 2; depth++) {
            for (var current : new ArrayList<>(candidates)) {
                if (!tryBlockCollaboratorsStubbed(current, owner, stubs)) continue;
                for (var ret : current.findAll(ReturnStmt.class)) {
                    if (ret.findAncestor(CatchClause.class).isPresent()
                            || ret.getExpression().filter(Expression::isMethodCallExpr).isEmpty()) continue;
                    var helperCall = ret.getExpression().get().asMethodCallExpr();
                    if (helperCall.getScope().isPresent() && !helperCall.getScope().get().isThisExpr()) continue;
                    owner.getMethodsByName(helperCall.getNameAsString()).stream()
                            .filter(m -> m.getParameters().size() == helperCall.getArguments().size())
                            .filter(m -> !candidates.contains(m))
                            .forEach(candidates::add);
                }
            }
        }
        Set<String> populated = new HashSet<>();
        var ownerUnit = owner.findCompilationUnit().orElseThrow();
        for (var candidate : candidates) {
            if (!tryBlockCollaboratorsStubbed(candidate, owner, stubs)) continue;
            Set<String> dtoVars = new HashSet<>();
            for (var v : candidate.findAll(VariableDeclarator.class)) {
                if (source.declaration(v.getType(), ownerUnit) == returnedType) dtoVars.add(v.getNameAsString());
            }
            if (dtoVars.isEmpty()) continue;
            for (var call : candidate.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().matches("set[A-Z].*") || call.getArguments().size() != 1
                        || call.findAncestor(IfStmt.class).isPresent() || call.findAncestor(LambdaExpr.class).isPresent()
                        || call.getArgument(0).isNullLiteralExpr()
                        || call.getScope().filter(Expression::isNameExpr)
                                .filter(s -> dtoVars.contains(s.asNameExpr().getNameAsString())).isEmpty()) continue;
                String prop = property(call.getNameAsString());
                if (directGetter(returnedType, prop) && directSetter(returnedType, prop)) populated.add(prop);
            }
        }
        return populated;
    }

    private static boolean tryBlockCollaboratorsStubbed(
            MethodDeclaration method, ClassOrInterfaceDeclaration owner, List<Stub> stubs) {
        var tries = method.findAll(TryStmt.class);
        if (tries.isEmpty()) return true;
        Set<String> fieldNames = new HashSet<>();
        owner.getFields().stream().filter(f -> !f.isStatic())
                .flatMap(f -> f.getVariables().stream()).forEach(v -> fieldNames.add(v.getNameAsString()));
        for (var tryStmt : tries) {
            for (var call : tryStmt.getTryBlock().findAll(MethodCallExpr.class)) {
                if (call.getScope().filter(Expression::isNameExpr).isEmpty()) continue;
                String receiver = call.getScope().get().asNameExpr().getNameAsString();
                if (!fieldNames.contains(receiver)) continue;
                boolean stubbed = stubs.stream().anyMatch(s -> s.invocation().getScope().map(Object::toString)
                        .filter(receiver::equals).isPresent()
                        && s.invocation().getNameAsString().equals(call.getNameAsString())
                        && !s.returned().isNullLiteralExpr());
                if (!stubbed) return false;
            }
        }
        return true;
    }

    private static Expression nullAssertedExpression(MethodCallExpr call, String junit) {
        String name = call.getNameAsString();
        int size = call.getArguments().size();
        if (name.equals("assertNull") && (size == 1 || size == 2)) {
            return unwrapExpr(call.getArgument(junit.equals("org.junit.Assert") ? size - 1 : 0));
        }
        if (name.equals("assertEquals") && (size == 2 || size == 3)) {
            int first = junit.equals("org.junit.Assert") ? size - 2 : 0;
            Expression left = unwrapExpr(call.getArgument(first));
            Expression right = unwrapExpr(call.getArgument(first + 1));
            if (left.isNullLiteralExpr()) return right;
            if (right.isNullLiteralExpr()) return left;
        }
        if ((name.equals("assertTrue") || name.equals("assertFalse")) && (size == 1 || size == 2)) {
            Expression cond = unwrapExpr(call.getArgument(junit.equals("org.junit.Assert") ? size - 1 : 0));
            if (!cond.isBinaryExpr()) return null;
            var binary = cond.asBinaryExpr();
            var expectedOp = name.equals("assertTrue") ? BinaryExpr.Operator.EQUALS : BinaryExpr.Operator.NOT_EQUALS;
            if (binary.getOperator() != expectedOp) return null;
            Expression left = unwrapExpr(binary.getLeft());
            Expression right = unwrapExpr(binary.getRight());
            if (left.isNullLiteralExpr()) return right;
            if (right.isNullLiteralExpr()) return left;
        }
        return null;
    }

    private static Expression unwrapExpr(Expression expression) {
        Expression current = expression;
        while (current != null && current.isEnclosedExpr()) current = current.asEnclosedExpr().getInner();
        return current;
    }

    private static boolean isPropertyNullApproved(TestCaseContextItemDto approved, String prop) {
        if (hasNullValueForKey(approved.testData(), prop)) return true;
        String text = (Objects.toString(approved.description(), "") + " "
                + Objects.toString(approved.expectedResult(), "")).toLowerCase(Locale.ROOT);
        String spaced = prop.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ").toLowerCase(Locale.ROOT);
        return (text.contains(prop.toLowerCase(Locale.ROOT)) || text.contains(spaced))
                && text.matches("(?s).*(\\bnull\\b|rỗng|trống|không có|chưa có).*");
    }

    private static boolean hasNullValueForKey(Object data, String prop) {
        if (data instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof String key && key.equalsIgnoreCase(prop)
                        && (entry.getValue() == null || "null".equalsIgnoreCase(Objects.toString(entry.getValue(), "")))) {
                    return true;
                }
                if (hasNullValueForKey(entry.getValue(), prop)) return true;
            }
        } else if (data instanceof Collection<?> list) {
            for (var item : list) if (hasNullValueForKey(item, prop)) return true;
        }
        return false;
    }
}
