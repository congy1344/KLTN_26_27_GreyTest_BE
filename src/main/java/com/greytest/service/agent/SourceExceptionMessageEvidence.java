package com.greytest.service.agent;

import java.util.*;
import java.util.regex.Pattern;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.Type;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.*;

/** Đối chiếu literal exception với biểu thức/constructor thật, không sửa chuỗi để khớp. */
final class SourceExceptionMessageEvidence {
    private static final Pattern MESSAGE = Pattern.compile("(?iuU)(?:thông\\s+(?:điệp|báo)|message)[^\"“`\\n]{0,80}[\"“`]([^\"”`]+)[\"”`]");
    private static final Set<String> STANDARD = Set.of("java.lang.Exception", "java.lang.RuntimeException", "java.lang.Throwable",
            "java.lang.IllegalArgumentException", "java.lang.IllegalStateException", "java.lang.NullPointerException",
            "java.lang.UnsupportedOperationException", "java.lang.ArithmeticException", "java.lang.Error");
    record Text(List<String> segments, String display, boolean string, int fixed) {
        static Text literal(String value, boolean string) { return new Text(List.of(value), value, string, value.length()); }
        static Text hole(String name, boolean string) { return new Text(Collections.singletonList(null), "<" + name + ">", string, 0); }
        Text plus(Text other) {
            var combined = new ArrayList<>(segments);
            for (String segment : other.segments) {
                int last = combined.size() - 1;
                if (last >= 0 && combined.get(last) != null && segment != null) combined.set(last, combined.get(last) + segment);
                else if (last < 0 || combined.get(last) != null || segment != null) combined.add(segment);
            }
            return new Text(Collections.unmodifiableList(combined), display + other.display, true, fixed + other.fixed);
        }
        boolean matches(String value) {
            int cursor = 0;
            boolean hole = false;
            for (int i = 0; i < segments.size(); i++) {
                String segment = segments.get(i);
                if (segment == null) { hole = true; continue; }
                // Dò literal từng đoạn, không tạo regex wildcard từ source không tin cậy.
                if (hole && i == segments.size() - 1) return value.endsWith(segment) && value.length() - segment.length() >= cursor;
                int found = hole ? value.indexOf(segment, cursor) : value.startsWith(segment, cursor) ? cursor : -1;
                if (found < 0) return false;
                cursor = found + segment.length(); hole = false;
            }
            return hole || cursor == value.length();
        }
        boolean containsLiteral(String value) { return segments.stream().filter(Objects::nonNull).anyMatch(s -> s.contains(value)); }
    }
    private final SourceEvidenceIndex index;
    SourceExceptionMessageEvidence(List<ClassContextDto> classes) { index = new SourceEvidenceIndex(classes); }

    Optional<String> validate(List<ClassContextDto> classes, Long methodId, String description, Map<String, Object> input, boolean requireProof) {
        if (description == null) return Optional.empty();
        var dto = classes.stream().flatMap(c -> c.methods().stream()).filter(m -> Objects.equals(m.id(), methodId)).findFirst().orElse(null);
        if (dto == null) return Optional.empty();
        var method = index.method(dto);
        var owner = index.type(dto.classQualifiedName());
        if (method == null || owner == null) return Optional.empty();
        Map<String, Text> arguments = new HashMap<>();
        Set<String> written = new HashSet<>();
        method.findAll(AssignExpr.class).stream().map(AssignExpr::getTarget).filter(Expression::isNameExpr)
                .forEach(e -> written.add(e.asNameExpr().getNameAsString()));
        method.findAll(UnaryExpr.class).stream().filter(e -> Set.of(UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.POSTFIX_DECREMENT,
                UnaryExpr.Operator.PREFIX_INCREMENT, UnaryExpr.Operator.PREFIX_DECREMENT).contains(e.getOperator()))
                .map(UnaryExpr::getExpression).filter(Expression::isNameExpr).forEach(e -> written.add(e.asNameExpr().getNameAsString()));
        for (var parameter : method.getParameters()) {
            String name = parameter.getNameAsString();
            boolean string = "java.lang.String".equals(index.resolve(parameter.getType(), owner.unit()));
            Object value = input == null ? null : input.get(name);
            arguments.put(name, !written.contains(name) && input != null && input.containsKey(name) && !(value instanceof Map<?, ?>) && !(value instanceof List<?>)
                    ? Text.literal(String.valueOf(value), string) : Text.hole(name, string));
        }
        var matcher = MESSAGE.matcher(description);
        int previousEnd = 0;
        while (matcher.find()) {
            String claimed = matcher.group(1);
            String claimKind = matcher.group().substring(0, matcher.start(1) - matcher.start());
            boolean substringClaim = Pattern.compile("(?iuU)\\b(?:contains?|includes?|chứa)\\b").matcher(claimKind).find();
            boolean excludedFragment = substringClaim && Pattern.compile("(?iuU)(?:\\b(?:not|never)\\s+(?:contains?|includes?)\\b"
                    + "|\\bkhông\\s+(?:được\\s+)?chứa\\b|\\b(?:contains?|includes?)\\s+no\\s*$)").matcher(claimKind).find();
            boolean fragmentClaim = substringClaim || description.substring(matcher.end()).stripLeading().startsWith("+")
                    || Pattern.compile("(?iuU)(?:ghép\\s+từ|nối\\s+từ|concatenat|literal\\s+fragment)").matcher(claimKind).find();
            String prefix = description.substring(previousEnd, matcher.start());
            previousEnd = matcher.end();
            var named = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error))\\b").matcher(prefix);
            String associated = null;
            while (named.find()) associated = named.group(1);
            if (associated == null) continue;
            Map<String, List<Text>> evidence = new LinkedHashMap<>();
            Set<String> unknown = new HashSet<>();
            for (var creation : method.findAll(ObjectCreationExpr.class)) {
                // Chỉ xét exception nằm trong throw hoặc supplier của orElseThrow.
                boolean thrown = creation.getParentNode().filter(ThrowStmt.class::isInstance)
                        .map(ThrowStmt.class::cast).filter(t -> t.getExpression() == creation).isPresent()
                        || optionalSupplier(creation, owner);
                if (!thrown) continue;
                String simple = creation.getType().getNameAsString();
                if (!simple.equals(associated)) continue;
                String qualified = index.resolve(creation.getType(), owner.unit());
                var values = creation.getArguments().stream().map(e -> fragment(e, owner.unit(), arguments, 0)).toList();
                Text text = constructor(qualified, values, 0);
                if (text == null || text.fixed() == 0) unknown.add(simple);
                else evidence.computeIfAbsent(simple, ignored -> new ArrayList<>()).add(text);
            }
            boolean supported = evidence.values().stream().flatMap(List::stream)
                    .anyMatch(text -> excludedFragment ? text.segments().stream().allMatch(Objects::nonNull) && !text.containsLiteral(claimed)
                            : fragmentClaim ? text.containsLiteral(claimed) : text.matches(claimed));
            if (supported) continue;
            for (var entry : evidence.entrySet()) {
                if (unknown.contains(entry.getKey())) continue;
                boolean contradicted = excludedFragment ? entry.getValue().stream().allMatch(text -> text.containsLiteral(claimed))
                        : fragmentClaim ? entry.getValue().stream().allMatch(text -> text.segments().stream().allMatch(Objects::nonNull) && !text.containsLiteral(claimed))
                        : entry.getValue().stream().noneMatch(text -> text.matches(claimed));
                if (contradicted)
                    return Optional.of("method_id=" + methodId + ": source exception message for " + entry.getKey()
                            + " does not support quoted message \"" + claimed + "\". Source template(s): "
                            + entry.getValue().stream().map(Text::display).toList() + ". Preserve literal punctuation; do not invent a different message.");
            }
            if (requireProof && (!unknown.isEmpty() || evidence.isEmpty() || fragmentClaim)) return Optional.of("method_id=" + methodId
                    + ": cannot verify the exact source exception message for " + associated
                    + ". Keep the proven exception type and describe dynamic/unknown message data instead of inventing a quoted literal. "
                    + "For a locale-sensitive String.format expression, require the complete message computed with that same source formatter "
                    + "and unchanged inputs at test runtime, without quoting a rendered message or its format template. Do not reduce an approved message requirement to exception type alone.");
        }
        return Optional.empty();
    }

    private boolean optionalSupplier(ObjectCreationExpr creation, SourceEvidenceIndex.SourceType owner) {
        var lambda = creation.findAncestor(LambdaExpr.class).orElse(null);
        if (lambda == null || !lambda.getParameters().isEmpty()) return false;
        boolean direct = lambda.getExpressionBody().map(e -> e == creation).orElse(false)
                || lambda.getBody().isBlockStmt() && lambda.getBody().asBlockStmt().getStatements().size() == 1
                && lambda.getBody().asBlockStmt().getStatement(0).isReturnStmt()
                && lambda.getBody().asBlockStmt().getStatement(0).asReturnStmt().getExpression().filter(e -> e == creation).isPresent();
        if (!direct || !(lambda.getParentNode().orElse(null) instanceof MethodCallExpr call)
                || !call.getNameAsString().equals("orElseThrow") || call.getArguments().size() != 1
                || call.getScope().isEmpty()) return false;
        var method = creation.findAncestor(MethodDeclaration.class).orElse(null);
        return optionalReceiver(call.getScope().get(), owner, method);
    }

    private boolean optionalReceiver(Expression receiver, SourceEvidenceIndex.SourceType owner, MethodDeclaration method) {
        if (receiver.isNameExpr()) {
            var type = variableType(receiver.asNameExpr().getNameAsString(), receiver, owner, method);
            return type != null && "java.util.Optional".equals(index.resolve(type, owner.unit()));
        }
        if (receiver.isFieldAccessExpr() && receiver.asFieldAccessExpr().getScope().isThisExpr()) {
            var type = index.propertyType(owner, receiver.asFieldAccessExpr().getNameAsString());
            return type != null && "java.util.Optional".equals(index.resolve(type, owner.unit()));
        }
        if (!receiver.isMethodCallExpr()) return false;
        var lookup = receiver.asMethodCallExpr();
        if (lookup.getScope().isEmpty()) return false;
        if (Set.of("map", "flatMap", "filter").contains(lookup.getNameAsString()) && lookup.getArguments().size() == 1
                && optionalReceiver(lookup.getScope().get(), owner, method)) return true;
        var scope = lookup.getScope().get();
        var type = scope.isNameExpr() ? variableType(scope.asNameExpr().getNameAsString(), scope, owner, method)
                : scope.isFieldAccessExpr() && scope.asFieldAccessExpr().getScope().isThisExpr()
                        ? index.propertyType(owner, scope.asFieldAccessExpr().getNameAsString()) : null;
        var dependency = type == null ? null : index.type(index.resolve(type, owner.unit()));
        if (dependency == null) return false;
        var methods = dependency.node().getMethodsByName(lookup.getNameAsString()).stream()
                .filter(m -> m.getParameters().size() == lookup.getArguments().size()).toList();
        if (!methods.isEmpty()) return methods.size() == 1 && "java.util.Optional".equals(index.resolve(methods.get(0).getType(), dependency.unit()));
        if (!lookup.getNameAsString().equals("findById") || lookup.getArguments().size() != 1
                || !dependency.node().isClassOrInterfaceDeclaration()) return false;
        var declaration = dependency.node().asClassOrInterfaceDeclaration();
        // CrudRepository.findById(ID) trả Optional<T>; chỉ dùng khi source xác nhận đúng interface framework.
        return declaration.isInterface() && declaration.getExtendedTypes().size() == 1
                && Set.of("org.springframework.data.jpa.repository.JpaRepository", "org.springframework.data.repository.CrudRepository")
                        .contains(Objects.toString(index.resolve(declaration.getExtendedTypes().get(0), dependency.unit()), ""));
    }

    private Type variableType(String name, Expression use, SourceEvidenceIndex.SourceType owner, MethodDeclaration method) {
        if (method != null) {
            var parameters = method.getParameters().stream().filter(p -> p.getNameAsString().equals(name)).toList();
            if (!parameters.isEmpty()) return parameters.size() == 1 ? parameters.get(0).getType() : null;
            if (method.findAll(Parameter.class).stream().anyMatch(p -> p.getNameAsString().equals(name))) return null;
            var locals = method.findAll(VariableDeclarator.class).stream().filter(v -> v.getNameAsString().equals(name)).toList();
            if (!locals.isEmpty()) {
                if (locals.size() != 1) return null;
                var local = locals.get(0);
                var declaration = local.getParentNode().filter(VariableDeclarationExpr.class::isInstance).orElse(null);
                var statement = declaration == null ? null : declaration.getParentNode().filter(ExpressionStmt.class::isInstance).map(ExpressionStmt.class::cast).orElse(null);
                var block = statement == null ? null : statement.getParentNode().filter(BlockStmt.class::isInstance).map(BlockStmt.class::cast).orElse(null);
                // Local phải được khai báo trước điểm dùng và nằm trong block bao điểm dùng đó.
                if (block == null || !block.isAncestorOf(use) || local.getRange().isEmpty() || use.getRange().isEmpty()
                        || !local.getRange().get().end.isBefore(use.getRange().get().begin)) return null;
                return local.getType();
            }
        }
        return index.propertyType(owner, name);
    }

    private Text constructor(String qualified, List<Text> args, int depth) {
        if (qualified == null || depth > 5 || args.stream().anyMatch(Objects::isNull)) return null;
        if (STANDARD.contains(qualified)) return args.size() == 1 && args.get(0).string() ? args.get(0) : null;
        var source = index.type(qualified);
        if (source == null || !source.node().isClassOrInterfaceDeclaration()) return null;
        var declaration = source.node().asClassOrInterfaceDeclaration();
        if (!declaration.getMethodsByName("getMessage").isEmpty()) return null;
        var candidates = declaration.getConstructors().stream().filter(c -> c.getParameters().size() == args.size()).toList();
        // String cụ thể hơn Throwable/Object; không chọn overload chỉ theo số đối số.
        if (candidates.size() > 1 && args.stream().allMatch(Text::string)) candidates = candidates.stream()
                .filter(c -> c.getParameters().stream().allMatch(p -> "java.lang.String".equals(index.resolve(p.getType(), source.unit())))).toList();
        if (candidates.size() != 1) return null;
        var constructor = candidates.get(0);
        if (constructor.getBody().getStatements().size() != 1 || !constructor.getBody().getStatement(0).isExplicitConstructorInvocationStmt()) return null;
        var invocation = constructor.getBody().getStatement(0).asExplicitConstructorInvocationStmt();
        Map<String, Text> env = new HashMap<>();
        for (int i = 0; i < args.size(); i++) {
            var parameter = constructor.getParameter(i);
            var value = args.get(i);
            env.put(parameter.getNameAsString(), new Text(value.segments(), value.display(),
                    "java.lang.String".equals(index.resolve(parameter.getType(), source.unit())), value.fixed()));
        }
        var delegated = invocation.getArguments().stream().map(e -> fragment(e, source.unit(), env, depth + 1)).toList();
        String parent = invocation.isThis() ? qualified : declaration.getExtendedTypes().size() == 1
                ? index.resolve(declaration.getExtendedTypes().get(0), source.unit()) : null;
        return constructor(parent, delegated, depth + 1);
    }

    private Text fragment(Expression expression, CompilationUnit unit, Map<String, Text> env, int depth) {
        if (depth > 8) return null;
        if (expression.isEnclosedExpr()) return fragment(expression.asEnclosedExpr().getInner(), unit, env, depth + 1);
        if (expression.isStringLiteralExpr()) return Text.literal(expression.asStringLiteralExpr().asString(), true);
        if (expression.isIntegerLiteralExpr()) return Text.literal(expression.asIntegerLiteralExpr().asNumber().toString(), false);
        if (expression.isLongLiteralExpr()) return Text.literal(expression.asLongLiteralExpr().asNumber().toString(), false);
        if (expression.isNameExpr()) return env.get(expression.asNameExpr().getNameAsString());
        if (expression.isBinaryExpr() && expression.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            var binary = expression.asBinaryExpr();
            Text left = fragment(binary.getLeft(), unit, env, depth + 1), right = fragment(binary.getRight(), unit, env, depth + 1);
            return left != null && right != null && (left.string() || right.string()) ? left.plus(right) : null;
        }
        if (expression.isMethodCallExpr()) {
            var call = expression.asMethodCallExpr();
            if (!call.getNameAsString().equals("format") || call.getScope().isEmpty()
                    || call.getScope().get().findAll(NameExpr.class).stream().anyMatch(e -> env.containsKey(e.getNameAsString()))
                    || !"java.lang.String".equals(index.resolve(call.getScope().get().toString(), unit))
                    || call.getArguments().isEmpty() || !call.getArgument(0).isStringLiteralExpr()) return Text.hole(expression.toString(), false);
            String format = call.getArgument(0).asStringLiteralExpr().asString();
            Text result = Text.literal("", true);
            int argument = 1;
            for (int cursor = 0; cursor < format.length();) {
                int marker = format.indexOf('%', cursor);
                if (marker < 0) return argument == call.getArguments().size() ? result.plus(Text.literal(format.substring(cursor), true)) : null;
                result = result.plus(Text.literal(format.substring(cursor, marker), true));
                if (marker + 1 >= format.length()) return null;
                char token = format.charAt(marker + 1);
                if (token == '%') result = result.plus(Text.literal("%", true));
                else if (token == 's' && argument < call.getArguments().size()) {
                    var value = fragment(call.getArgument(argument++), unit, env, depth + 1);
                    if (value == null) return null;
                    result = result.plus(value);
                } else return null;
                cursor = marker + 2;
                if (cursor == format.length()) return argument == call.getArguments().size() ? result : null;
            }
        }
        // Biểu thức còn lại là giá trị runtime, không đoán kết quả helper/getter.
        return expression.isMethodCallExpr() || expression.isFieldAccessExpr() ? Text.hole(expression.toString(), false) : null;
    }
}
