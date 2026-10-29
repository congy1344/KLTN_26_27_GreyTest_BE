package com.greytest.service.agent;

import java.util.*;
import java.util.regex.Pattern;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.Type;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedTestCaseDto;

/** Kiểm chứng đường đi collection có dữ liệu cụ thể; gặp code chưa hỗ trợ thì dừng suy luận. */
final class SourceCollectionCallEvidence {
    private enum Unknown { VALUE }
    private enum Mutation { REMOVE, REVERSE }
    private static final class Sequence {
        final List<Object> items;
        final EnumSet<Mutation> history;
        Sequence(List<Object> items) { this(items, EnumSet.noneOf(Mutation.class)); }
        Sequence(List<Object> items, EnumSet<Mutation> history) { this.items = items; this.history = history.clone(); }
    }
    private record Fixture(Map<String, Object> properties, SourceEvidenceIndex.SourceType type) { }
    private record Dependency(String name, Type type) { }
    private record EnumValue(String type, String name) { }
    private record StreamValue(Sequence sequence, List<Expression> projections, Frame captured) { }
    private record Frame(SourceEvidenceIndex.SourceType owner, Map<String, Object> values, Map<String, Type> types) { }
    private static final class Stop extends RuntimeException { Stop() { super(null, null, false, false); } }
    private static final class Returned extends RuntimeException {
        final Object value; Returned(Object value) { super(null, null, false, false); this.value = value; }
    }
    private static final class Mismatch extends RuntimeException { Mismatch(String message) { super(message, null, false, false); } }
    private final SourceEvidenceIndex index;
    private final GeneratedTestCaseDto testCase;
    private final Map<String, List<Map<?, ?>>> mocks = new HashMap<>();
    private final Map<Object, Object> wrapped = new IdentityHashMap<>();
    private final Set<String> returnedMocks = new HashSet<>();
    private final String approvedInteractions;
    private int steps;

    SourceCollectionCallEvidence(SourceEvidenceIndex index, GeneratedTestCaseDto testCase) {
        this(index, testCase, "");
    }

    SourceCollectionCallEvidence(SourceEvidenceIndex index, GeneratedTestCaseDto testCase, String approvedInteractions) {
        this.index = index; this.testCase = testCase;
        this.approvedInteractions = approvedInteractions;
        Object setup = testCase.testData() == null ? null : testCase.testData().get("mocks");
        if (setup instanceof Map<?, ?> map) collectMocks(map, "");
    }

    Optional<String> validate(MethodContextDto target) {
        var method = index.method(target);
        var owner = target == null ? null : index.type(target.classQualifiedName());
        if (method == null || owner == null || method.getBody().isEmpty()) return Optional.empty();
        Map<String, Object> values = new HashMap<>(); Map<String, Type> types = new HashMap<>();
        for (var field : owner.node().getFields()) for (var variable : field.getVariables()) {
            values.put(variable.getNameAsString(), new Dependency(variable.getNameAsString(), field.getElementType()));
            types.put(variable.getNameAsString(), field.getElementType());
        }
        Object input = testCase.testData() == null ? null : testCase.testData().get("input");
        Map<?, ?> data = input instanceof Map<?, ?> map ? map : Map.of();
        for (var parameter : method.getParameters()) {
            String name = parameter.getNameAsString();
            values.put(name, data.containsKey(name) ? wrap(data.get(name), parameter.getType(), owner) : Unknown.VALUE);
            types.put(name, parameter.getType());
        }
        try { execute(method, new Frame(owner, values, types), 0); }
        catch (Mismatch mismatch) { return Optional.of("plan_id=" + testCase.planId() + ": " + mismatch.getMessage()); }
        catch (Stop unknown) { /* Không suy luận qua nhánh/helper/side effect chưa chứng minh được. */ }
        return Optional.empty();
    }

    private Object execute(MethodDeclaration method, Frame frame, int depth) {
        if (depth > 6 || method.getBody().isEmpty()) throw new Stop();
        try { statement(method.getBody().get(), frame, depth); }
        catch (Returned result) { return result.value; }
        return null;
    }

    private void statement(Statement statement, Frame frame, int depth) {
        if (++steps > 500) throw new Stop();
        if (statement.isBlockStmt()) { for (var child : statement.asBlockStmt().getStatements()) statement(child, frame, depth); return; }
        if (statement.isIfStmt()) {
            var branch = statement.asIfStmt(); Object condition = value(branch.getCondition(), frame, depth);
            if (!(condition instanceof Boolean)) throw new Stop();
            if (Boolean.TRUE.equals(condition)) statement(branch.getThenStmt(), frame, depth);
            else if (branch.getElseStmt().isPresent()) statement(branch.getElseStmt().get(), frame, depth);
            return;
        }
        if (statement.isReturnStmt()) throw new Returned(statement.asReturnStmt().getExpression().map(e -> value(e, frame, depth)).orElse(null));
        if (!statement.isExpressionStmt()) throw new Stop();
        var expression = statement.asExpressionStmt().getExpression();
        if (expression.isVariableDeclarationExpr()) {
            for (var variable : expression.asVariableDeclarationExpr().getVariables()) {
                frame.values().put(variable.getNameAsString(), variable.getInitializer().map(e -> value(e, frame, depth)).orElse(Unknown.VALUE));
                frame.types().put(variable.getNameAsString(), variable.getType());
            }
        } else if (expression.isAssignExpr() && expression.asAssignExpr().getOperator() == AssignExpr.Operator.ASSIGN
                && expression.asAssignExpr().getTarget().isNameExpr()) {
            var assignment = expression.asAssignExpr();
            frame.values().put(assignment.getTarget().asNameExpr().getNameAsString(), value(assignment.getValue(), frame, depth));
        } else if (value(expression, frame, depth) == Unknown.VALUE) throw new Stop();
    }

    private Object value(Expression expression, Frame frame, int depth) {
        if (++steps > 500 || depth > 12) throw new Stop();
        if (expression.isEnclosedExpr()) return value(expression.asEnclosedExpr().getInner(), frame, depth + 1);
        if (expression.isNullLiteralExpr()) return null;
        if (expression.isStringLiteralExpr()) return expression.asStringLiteralExpr().asString();
        if (expression.isBooleanLiteralExpr()) return expression.asBooleanLiteralExpr().getValue();
        if (expression.isIntegerLiteralExpr()) return expression.asIntegerLiteralExpr().asNumber().intValue();
        if (expression.isLongLiteralExpr()) return expression.asLongLiteralExpr().asNumber().longValue();
        if (expression.isNameExpr()) return frame.values().getOrDefault(expression.asNameExpr().getNameAsString(), Unknown.VALUE);
        if (expression.isUnaryExpr() && expression.asUnaryExpr().getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            Object inner = value(expression.asUnaryExpr().getExpression(), frame, depth + 1);
            return inner instanceof Boolean bool ? !bool : Unknown.VALUE;
        }
        if (expression.isFieldAccessExpr()) {
            var field = expression.asFieldAccessExpr();
            if (field.getScope().isThisExpr()) return frame.values().getOrDefault(field.getNameAsString(), Unknown.VALUE);
            var enumType = index.type(index.resolve(field.getScope().toString(), frame.owner().unit()));
            if (enumType != null && enumType.node().isEnumDeclaration() && enumType.node().asEnumDeclaration().getEntries().stream()
                    .anyMatch(e -> e.getNameAsString().equals(field.getNameAsString()))) return new EnumValue(enumType.name(), field.getNameAsString());
            return Unknown.VALUE;
        }
        if (expression.isBinaryExpr()) {
            var binary = expression.asBinaryExpr(); Object left = value(binary.getLeft(), frame, depth + 1);
            if (binary.getOperator() == BinaryExpr.Operator.AND && Boolean.FALSE.equals(left)) return false;
            if (binary.getOperator() == BinaryExpr.Operator.OR && Boolean.TRUE.equals(left)) return true;
            Object right = value(binary.getRight(), frame, depth + 1);
            if (left == Unknown.VALUE || right == Unknown.VALUE) return Unknown.VALUE;
            if ((binary.getOperator() == BinaryExpr.Operator.EQUALS || binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS)
                    && left != null && right != null && !(left instanceof EnumValue && right instanceof EnumValue)
                    && !primitive(binary.getLeft(), frame) && !primitive(binary.getRight(), frame)) return Unknown.VALUE;
            return switch (binary.getOperator()) {
                case EQUALS -> equal(left, right); case NOT_EQUALS -> !equal(left, right);
                case AND -> left instanceof Boolean a && right instanceof Boolean b ? a && b : Unknown.VALUE;
                case OR -> left instanceof Boolean a && right instanceof Boolean b ? a || b : Unknown.VALUE;
                case GREATER, GREATER_EQUALS, LESS, LESS_EQUALS -> left instanceof Number a && right instanceof Number b
                        ? compare(a, b, binary.getOperator()) : Unknown.VALUE;
                default -> Unknown.VALUE;
            };
        }
        if (expression.isMethodCallExpr()) {
            Object result = call(expression.asMethodCallExpr(), frame, depth + 1);
            if (result == Unknown.VALUE) throw new Stop();
            return result;
        }
        throw new Stop();
    }

    private Object call(MethodCallExpr call, Frame frame, int depth) {
        String name = call.getNameAsString();
        if (name.equals("reverse") && call.getArguments().size() == 1 && call.getScope().isPresent()
                && !scopeIsValue(call.getScope().get(), frame)
                && "java.util.Collections".equals(index.resolve(call.getScope().get().toString(), frame.owner().unit()))) {
            Object argument = value(call.getArgument(0), frame, depth);
            if (!(argument instanceof Sequence sequence)) return Unknown.VALUE;
            Collections.reverse(sequence.items); sequence.history.add(Mutation.REVERSE); return null;
        }
        if (call.getScope().isEmpty() || call.getScope().get().isThisExpr()) {
            if (frame.owner().node().isClassOrInterfaceDeclaration()) {
                var declaration = frame.owner().node().asClassOrInterfaceDeclaration();
                if (!declaration.getExtendedTypes().isEmpty() || !declaration.getImplementedTypes().isEmpty()) return Unknown.VALUE;
            }
            var helpers = frame.owner().node().getMethodsByName(name).stream()
                    .filter(m -> m.getParameters().size() == call.getArguments().size()).toList();
            if (helpers.size() != 1 || !helpers.get(0).isPrivate()) return Unknown.VALUE;
            var helper = helpers.get(0); var next = new Frame(frame.owner(), new HashMap<>(frame.values()), new HashMap<>(frame.types()));
            for (int i = 0; i < call.getArguments().size(); i++) {
                next.values().put(helper.getParameter(i).getNameAsString(), value(call.getArgument(i), frame, depth));
                next.types().put(helper.getParameter(i).getNameAsString(), helper.getParameter(i).getType());
            }
            return execute(helper, next, depth + 1);
        }
        Object receiver = value(call.getScope().get(), frame, depth);
        if (receiver instanceof Dependency dependency) {
            var arguments = call.getArguments().stream().map(e -> value(e, frame, depth)).toList();
            var candidates = mocks.getOrDefault(dependency.name() + "." + name, List.of());
            Map<?, ?> setup = candidates.size() == 1 ? candidates.get(0) : Map.of();
            Object expected = setup.get("arguments");
            for (int i = 0; i < arguments.size(); i++) if (!approvedInteractions.contains(dependency.name() + "." + name)
                    && arguments.get(i) instanceof Sequence sequence && scalar(sequence.items)) {
                if (beforeMutationClaim(name, sequence.history)) throw new Mismatch("source calls " + dependency.name() + "." + name
                        + " with " + sequence.items + " after list mutation, but expected_result claims before mutation. Keep approved business outcomes; do not invent an interaction order.");
                if (expected instanceof List<?> expectedArgs && i < expectedArgs.size() && expectedArgs.get(i) instanceof List<?> list
                        && scalar(list) && !equalList(list, sequence.items)) throw new Mismatch("source argument " + (i + 1) + " of "
                                + dependency.name() + "." + name + " is " + sequence.items + ", not mock arguments " + list
                                + ". Trace source trim/reverse before building mock setup; preserve approved input and output order.");
            }
            if (!setup.containsKey("returns")) return Unknown.VALUE;
            // Lần gọi lặp lại có thể dùng answer/return khác; không tự đoán danh tính object.
            if (!returnedMocks.add(dependency.name() + "." + name)) throw new Stop();
            var owner = index.type(index.resolve(dependency.type(), frame.owner().unit()));
            var methods = owner == null ? List.<MethodDeclaration>of() : owner.node().getMethodsByName(name).stream()
                    .filter(m -> m.getParameters().size() == arguments.size()).toList();
            return wrap(setup.get("returns"), methods.size() == 1 ? methods.get(0).getType() : null, owner);
        }
        if (receiver instanceof Fixture fixture) return getter(fixture, name, call.getArguments().size(), depth);
        if (receiver instanceof Sequence sequence) {
            if (call.getArguments().isEmpty()) return switch (name) { case "size" -> sequence.items.size(); case "isEmpty" -> sequence.items.isEmpty();
                case "stream" -> new StreamValue(sequence, List.of(), frame); default -> Unknown.VALUE; };
            if (name.equals("remove") && call.getArguments().size() == 1 && intArgument(call.getArgument(0), frame, depth)) {
                Object argument = value(call.getArgument(0), frame, depth);
                if (!(argument instanceof Integer i) || i < 0 || i >= sequence.items.size()) throw new Stop();
                sequence.history.add(Mutation.REMOVE); return sequence.items.remove(i.intValue());
            }
        }
        if (receiver instanceof StreamValue stream) {
            if (name.equals("map") && call.getArguments().size() == 1) {
                var projections = new ArrayList<>(stream.projections()); projections.add(call.getArgument(0));
                return new StreamValue(stream.sequence(), projections, stream.captured());
            }
            if (name.equals("collect") && call.getArguments().size() == 1 && toListCollector(call.getArgument(0), frame)
                    || name.equals("toList") && call.getArguments().isEmpty()) {
                List<Object> mapped = new ArrayList<>();
                for (Object item : stream.sequence().items) {
                    Object result = item;
                    for (var mapper : stream.projections()) result = projection(mapper, result, stream.captured(), depth);
                    if (result == Unknown.VALUE) throw new Stop();
                    mapped.add(result);
                }
                return new Sequence(mapped, stream.sequence().history);
            }
        }
        return Unknown.VALUE;
    }

    private Object getter(Fixture fixture, String name, int arity, int depth) {
        if (fixture.type() == null || arity != 0) return Unknown.VALUE;
        var methods = fixture.type().node().getMethodsByName(name).stream().filter(m -> m.getParameters().isEmpty()).toList();
        if (methods.size() == 1 && methods.get(0).getBody().filter(b -> b.getStatements().size() == 1 && b.getStatement(0).isReturnStmt()).isPresent()) {
            Map<String, Object> fields = new HashMap<>(); Map<String, Type> types = new HashMap<>();
            for (String field : fixture.properties().keySet()) {
                var type = index.propertyType(fixture.type(), field);
                fields.put(field, wrap(fixture.properties().get(field), type, fixture.type())); types.put(field, type);
            }
            return execute(methods.get(0), new Frame(fixture.type(), fields, types), depth + 1);
        }
        if (!methods.isEmpty() || !index.getterGenerated(fixture.type())) return Unknown.VALUE;
        String property = fixture.type().node().isRecordDeclaration() ? name
                : name.matches("(?:get|is)[A-Z].*") ? name.substring(name.startsWith("get") ? 3 : 2) : "";
        if (property.isEmpty()) return Unknown.VALUE;
        property = Character.toLowerCase(property.charAt(0)) + property.substring(1);
        var type = index.propertyType(fixture.type(), property);
        return type != null && fixture.properties().containsKey(property) ? wrap(fixture.properties().get(property), type, fixture.type()) : Unknown.VALUE;
    }

    private Object projection(Expression mapper, Object item, Frame frame, int depth) {
        if (mapper.isMethodReferenceExpr() && item instanceof Fixture fixture && fixture.type() != null) {
            var reference = mapper.asMethodReferenceExpr();
            if (fixture.type().name().equals(index.resolve(reference.getScope().toString(), frame.owner().unit())))
                return getter(fixture, reference.getIdentifier(), 0, depth + 1);
        }
        if (mapper.isLambdaExpr() && mapper.asLambdaExpr().getParameters().size() == 1 && mapper.asLambdaExpr().getExpressionBody().isPresent()) {
            var lambda = mapper.asLambdaExpr(); var next = new Frame(frame.owner(), new HashMap<>(frame.values()), new HashMap<>(frame.types()));
            next.values().put(lambda.getParameter(0).getNameAsString(), item);
            var body = lambda.getExpressionBody().get();
            // Chỉ project getter của chính phần tử; không chạy side effect trong lambda tùy ý.
            if (body.isMethodCallExpr() && body.asMethodCallExpr().getScope().filter(Expression::isNameExpr)
                    .map(e -> e.asNameExpr().getNameAsString().equals(lambda.getParameter(0).getNameAsString())).orElse(false)
                    && item instanceof Fixture fixture)
                return getter(fixture, body.asMethodCallExpr().getNameAsString(), body.asMethodCallExpr().getArguments().size(), depth + 1);
            return Unknown.VALUE;
        }
        return Unknown.VALUE;
    }

    private boolean intArgument(Expression expression, Frame frame, int depth) {
        if (expression.isIntegerLiteralExpr()) return true;
        if (expression.isNameExpr()) return Optional.ofNullable(frame.types().get(expression.asNameExpr().getNameAsString())).map(Type::asString).filter("int"::equals).isPresent();
        if (expression.isMethodCallExpr()) {
            var call = expression.asMethodCallExpr(); Object receiver = receiver(call, frame);
            if (receiver instanceof Fixture fixture && fixture.type() != null) {
                var declared = fixture.type().node().getMethodsByName(call.getNameAsString()).stream().filter(m -> m.getParameters().isEmpty()).toList();
                if (declared.size() == 1) return declared.get(0).getType().asString().equals("int");
                String name = call.getNameAsString();
                if (name.matches("get[A-Z].*") && index.getterGenerated(fixture.type())) {
                    String property = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                    return Optional.ofNullable(index.propertyType(fixture.type(), property)).map(Type::asString).filter("int"::equals).isPresent();
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Object wrap(Object raw, Type type, SourceEvidenceIndex.SourceType owner) {
        String numberType = type == null ? "" : type.asString();
        if (Set.of("float", "double", "Float", "Double", "java.lang.Float", "java.lang.Double").contains(numberType)) return Unknown.VALUE;
        if (raw instanceof Number number && Set.of("int", "long", "Integer", "Long", "java.lang.Integer", "java.lang.Long").contains(numberType)) {
            try {
                var decimal = new java.math.BigDecimal(number.toString());
                if (Set.of("int", "Integer", "java.lang.Integer").contains(numberType)) return decimal.intValueExact();
                return decimal.longValueExact();
            } catch (ArithmeticException invalid) { return Unknown.VALUE; }
        }
        if (raw instanceof List<?> list) {
            String qualified = type != null && owner != null ? index.resolve(type, owner.unit()) : null;
            if (!Set.of("java.util.List", "java.util.ArrayList", "java.util.LinkedList").contains(Objects.toString(qualified, ""))) return Unknown.VALUE;
            if (wrapped.containsKey(raw)) return wrapped.get(raw);
            Type element = type != null && type.isClassOrInterfaceType() ? type.asClassOrInterfaceType().getTypeArguments()
                    .filter(a -> a.size() == 1).map(a -> a.get(0)).orElse(null) : null;
            List<Object> result = new ArrayList<>(); var sequence = new Sequence(result); wrapped.put(raw, sequence);
            for (Object item : list) result.add(wrap(item, element, owner)); return sequence;
        }
        var declared = type != null && owner != null ? index.type(index.resolve(type, owner.unit())) : null;
        if (raw instanceof Map<?, ?> map) return new Fixture((Map<String, Object>) map, declared);
        if (declared != null && declared.node().isEnumDeclaration() && raw instanceof String name) return declared.node().asEnumDeclaration().getEntries().stream()
                .anyMatch(e -> e.getNameAsString().equals(name)) ? new EnumValue(declared.name(), name) : Unknown.VALUE;
        return raw;
    }

    private Object receiver(MethodCallExpr call, Frame frame) {
        if (call.getScope().isEmpty()) return Unknown.VALUE;
        var scope = call.getScope().get();
        if (scope.isNameExpr()) return frame.values().getOrDefault(scope.asNameExpr().getNameAsString(), Unknown.VALUE);
        if (scope.isFieldAccessExpr() && scope.asFieldAccessExpr().getScope().isThisExpr())
            return frame.values().getOrDefault(scope.asFieldAccessExpr().getNameAsString(), Unknown.VALUE);
        return Unknown.VALUE;
    }

    private boolean scopeIsValue(Expression scope, Frame frame) {
        return scope.findAll(NameExpr.class).stream().anyMatch(e -> frame.values().containsKey(e.getNameAsString()));
    }

    private boolean primitive(Expression expression, Frame frame) {
        if (expression.isIntegerLiteralExpr() || expression.isLongLiteralExpr() || expression.isBooleanLiteralExpr()) return true;
        if (expression.isNameExpr()) return Optional.ofNullable(frame.types().get(expression.asNameExpr().getNameAsString())).map(Type::isPrimitiveType).orElse(false);
        if (expression.isMethodCallExpr()) {
            var call = expression.asMethodCallExpr(); var receiver = receiver(call, frame);
            if (receiver instanceof Sequence && Set.of("size", "isEmpty").contains(call.getNameAsString()) && call.getArguments().isEmpty()) return true;
            if (receiver instanceof Fixture fixture && fixture.type() != null) {
                var methods = fixture.type().node().getMethodsByName(call.getNameAsString()).stream().filter(m -> m.getParameters().isEmpty()).toList();
                if (methods.size() == 1) return methods.get(0).getType().isPrimitiveType();
            }
        }
        return false;
    }

    private boolean beforeMutationClaim(String callee, Set<Mutation> history) {
        for (String sentence : Objects.toString(testCase.expectedResult(), "").split("[.\\n]")) {
            if (Pattern.compile("(?iuU)\\b(?:không|not|never)\\b").matcher(sentence).find()) continue;
            if (!Pattern.compile("(?iuU)(?:dependency\\s+bổ\\s+sung|dependencies|downstream|enrich|làm\\s+giàu|" + Pattern.quote(callee) + ")").matcher(sentence).find()) continue;
            if (history.contains(Mutation.REVERSE) && Pattern.compile("(?iu)(?:trước khi|before).{0,90}(?:đảo|revers)").matcher(sentence).find()) return true;
            if (history.contains(Mutation.REMOVE) && Pattern.compile("(?iu)(?:trước khi|before).{0,90}(?:loại|remov|trim)").matcher(sentence).find()) return true;
        }
        return false;
    }
    private boolean toListCollector(Expression expression, Frame frame) {
        if (!expression.isMethodCallExpr()) return false;
        var call = expression.asMethodCallExpr(); if (!call.getNameAsString().equals("toList") || !call.getArguments().isEmpty()) return false;
        return call.getScope().isPresent() ? !scopeIsValue(call.getScope().get(), frame)
                && "java.util.stream.Collectors".equals(index.resolve(call.getScope().get().toString(), frame.owner().unit()))
                : frame.owner().node().getMethodsByName("toList").isEmpty()
                && frame.owner().unit().getImports().stream().anyMatch(i -> i.isStatic() && (i.getNameAsString().equals("java.util.stream.Collectors.toList")
                        || i.isAsterisk() && i.getNameAsString().equals("java.util.stream.Collectors")));
    }
    private void collectMocks(Map<?, ?> map, String prefix) {
        map.forEach((key, value) -> { if (key instanceof String name && value instanceof Map<?, ?> nested) {
            String path = prefix + name;
            if (nested.containsKey("returns") || nested.containsKey("arguments")) mocks.computeIfAbsent(path.split("\\(", 2)[0], ignored -> new ArrayList<>()).add(nested);
            else collectMocks(nested, path + ".");
        }});
    }
    private static boolean scalar(List<?> list) { return list.stream().allMatch(v -> v == null || v instanceof String || v instanceof Number || v instanceof Boolean); }
    private static boolean equalList(List<?> a, List<?> b) { if (a.size() != b.size()) return false; for (int i = 0; i < a.size(); i++) if (!equal(a.get(i), b.get(i))) return false; return true; }
    private static boolean equal(Object a, Object b) { return a instanceof Number x && b instanceof Number y ? new java.math.BigDecimal(x.toString()).compareTo(new java.math.BigDecimal(y.toString())) == 0 : Objects.equals(a, b); }
    private static boolean compare(Number a, Number b, BinaryExpr.Operator op) {
        int c = new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()));
        return switch (op) { case GREATER -> c > 0; case GREATER_EQUALS -> c >= 0; case LESS -> c < 0; case LESS_EQUALS -> c <= 0; default -> false; };
    }
}
