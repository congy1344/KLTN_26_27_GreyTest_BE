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
    private static final Pattern MESSAGE = Pattern.compile("(?iuU)(?:thông\\s+(?:điệp|báo)|message|"
            + ExceptionMessageFormatClaim.HEADER + ")[^\"“`\\n]{0,160}[\"“`]([^\"”`]+)[\"”`]");
    private static final Pattern CONSTRUCTOR = Pattern.compile(
            "`?\\b(?:new\\s+)?((?:[\\w$]+\\.)*[\\w$]*(?:Exception|Error))\\s*\\((?:\"(?:\\\\.|[^\"\\\\])*\"|[^()\"\\r\\n])*\\)`?");
    private static final Set<String> INTEGRAL_TYPES = Set.of("byte", "short", "int", "long", "java.lang.Byte",
            "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.math.BigInteger");
    private static final Set<String> STANDARD = Set.of("java.lang.Exception", "java.lang.RuntimeException", "java.lang.Throwable",
            "java.lang.IllegalArgumentException", "java.lang.IllegalStateException", "java.lang.NullPointerException",
            "java.lang.UnsupportedOperationException", "java.lang.ArithmeticException", "java.lang.Error");
    record FormatTemplate(String value, List<String> arguments, List<String> decimalArguments) { }
    record Text(List<String> segments, String display, boolean string, int fixed, FormatTemplate format, String sourceArgument) {
        Text(List<String> segments, String display, boolean string, int fixed, FormatTemplate format) { this(segments, display, string, fixed, format, null); }
        Text(List<String> segments, String display, boolean string, int fixed) { this(segments, display, string, fixed, null); }
        static Text literal(String value, boolean string) { return new Text(List.of(value), value, string, value.length(), null, string ? new StringLiteralExpr().setString(value).toString() : value); }
        static Text hole(String name, boolean string) { return new Text(Collections.singletonList(null), "<" + name + ">", string, 0, null, name); }
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
        description = messageNarrative(description);
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
            Text text = !written.contains(name) && input != null && input.containsKey(name) && !(value instanceof Map<?, ?>) && !(value instanceof List<?>)
                    ? Text.literal(String.valueOf(value), string) : Text.hole(name, string);
            arguments.put(name, new Text(text.segments(), text.display(), text.string(), text.fixed(), text.format(), name));
        }
        // Biến cục bộ là giá trị runtime: chỉ đối chiếu phần literal ghép cùng nó, không đoán giá trị.
        method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).forEach(variable -> arguments.putIfAbsent(
                variable.getNameAsString(), Text.hole(variable.getNameAsString(),
                        "java.lang.String".equals(index.resolve(variable.getType(), owner.unit())))));
        var matcher = MESSAGE.matcher(description);
        int previousEnd = 0;
        while (matcher.find()) {
            String claimed = matcher.group(1);
            String claimKind = matcher.group().substring(0, matcher.start(1) - matcher.start());
            boolean substringClaim = Pattern.compile("(?iuU)\\b(?:contains?|includes?|chứa|bao\\s+gồm)\\b").matcher(claimKind).find();
            boolean excludedFragment = substringClaim && Pattern.compile("(?iuU)(?:\\b(?:not|never)\\s+(?:contains?|includes?)\\b"
                    + "|\\bkhông\\s+(?:được\\s+)?(?:chứa|bao\\s+gồm)\\b|\\b(?:contains?|includes?)\\s+no\\s*$)").matcher(claimKind).find();
            String afterClaim = description.substring(matcher.end()).stripLeading();
            boolean fragmentClaim = substringClaim || afterClaim.startsWith("+")
                    // "followed by" là cách AI viết tiếng Anh cho "nối với" khi mô tả message ghép chuỗi.
                    || Pattern.compile("(?iuU)^(?:,\\s*)?(?:(?:nối|ghép|cộng)(?:\\s+chuỗi)?\\s+(?:với|cùng|và)|(?:với|cùng|và)\\s+(?:giá\\s+trị|tham\\s+số|biến|ID|id|mã)\\b|followed\\s+by\\b)").matcher(afterClaim).find()
                    || Pattern.compile("(?iuU)(?:(?<!\\bkết\\s)(?:(?:ghép|nối)\\s+chuỗi|(?:được|bằng(?:\\s+cách)?)\\s+(?:ghép|nối)|(?:ghép|nối)\\s+(?:từ|bởi|với|cùng|các\\s+phần))\\b|gồm\\s+(?:các\\s+phần|phần|chuỗi)|\\b(?:tiền\\s+tố|hậu\\s+tố|prefix|suffix|bắt\\s+đầu\\s+(?:bằng|với)|kết\\s+thúc\\s+(?:bằng|với)|starts?\\s+with|ends?\\s+with)\\b|concatenat|literal\\s+fragment)").matcher(claimKind).find();
            String prefix = description.substring(previousEnd, matcher.start());
            String beforeLiteral = description.substring(previousEnd, matcher.start(1) - 1);
            previousEnd = matcher.end();
            var named = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error))\\b").matcher(prefix);
            String associated = null;
            while (named.find()) associated = named.group(1);
            if (associated == null) continue;
            Map<String, List<Text>> evidence = new LinkedHashMap<>();
            List<FormatTemplate> formats = new ArrayList<>();
            Set<String> unknown = new HashSet<>();
            List<Expression> messageArguments = new ArrayList<>();
            for (var creation : method.findAll(ObjectCreationExpr.class)) {
                // Không lấy exception trong method/class lồng nhau làm bằng chứng của method hiện tại.
                if (creation.findAncestor(CallableDeclaration.class).orElse(null) != method
                        || creation.findAncestor(TypeDeclaration.class).isPresent()
                        || creation.getAnonymousClassBody().isPresent()
                        || creation.findAncestor(ObjectCreationExpr.class).filter(c -> c.getAnonymousClassBody().isPresent()).isPresent()) continue;
                // Chỉ xét exception nằm trong throw hoặc supplier của orElseThrow.
                boolean thrown = creation.getParentNode().filter(ThrowStmt.class::isInstance)
                        .map(ThrowStmt.class::cast).filter(t -> t.getExpression() == creation).isPresent()
                        || optionalSupplier(creation, owner);
                if (!thrown) continue;
                String simple = creation.getType().getNameAsString();
                if (!simple.equals(associated)) continue;
                messageArguments.addAll(creation.getArguments());
                String qualified = index.resolve(creation.getType(), owner.unit());
                var values = creation.getArguments().stream().map(e -> fragment(e, owner.unit(), arguments, 0)).toList();
                Text text = constructor(qualified, values, 0);
                if (text != null && text.format() != null) formats.add(text.format());
                if (text == null || text.fixed() == 0) unknown.add(simple);
                else evidence.computeIfAbsent(simple, ignored -> new ArrayList<>()).add(text);
            }
            var helperThrows = new HelperThrows();
            for (var call : method.findAll(MethodCallExpr.class)) {
                if (call.findAncestor(CallableDeclaration.class).orElse(null) != method
                        || call.findAncestor(TypeDeclaration.class).isPresent()
                        || call.findAncestor(LambdaExpr.class).isPresent()) continue;
                Text text = helperMessage(call, owner, method, arguments, associated, 0);
                if (text == null && call.getScope().isEmpty()) readHelperThrows(call, owner, arguments, associated, helperThrows, new HashSet<>());
                if (text == null || text.fixed() == 0) continue;
                if (text.format() != null) formats.add(text.format());
                evidence.computeIfAbsent(associated, ignored -> new ArrayList<>()).add(text);
            }
            if (!helperThrows.messages.isEmpty()) evidence.computeIfAbsent(associated, ignored -> new ArrayList<>()).addAll(helperThrows.messages);
            // Chỉ gợi ý format template khi source thật sự dùng formatter; message ghép chuỗi phải trích như message thường.
            boolean usesFormatter = !formats.isEmpty() || messageArguments.stream()
                    .anyMatch(argument -> usesMessageFormatter(argument, method));
            var sourceMessages = evidence.values().stream().flatMap(List::stream).map(Text::display).toList();
            var formatClaim = ExceptionMessageFormatClaim.parse(beforeLiteral, claimed, description.substring(matcher.end()));
            if (!substringClaim && formatClaim.template()) {
                Set<String> parameters = method.getParameters().stream().map(Parameter::getNameAsString)
                        .collect(java.util.stream.Collectors.toSet());
                boolean proven = formatClaim.valid() && formats.stream().anyMatch(format -> format.value().equals(claimed)
                        && format.arguments().equals(formatClaim.arguments()) && format.arguments().stream().allMatch(arg -> arg != null && (arg.startsWith("\"") || parameters.contains(rootParameter(arg))))
                        && format.arguments().stream().filter(arg -> arg != null && !arg.startsWith("\"")).map(SourceExceptionMessageEvidence::rootParameter).noneMatch(written::contains)
                        && format.decimalArguments().stream().allMatch(name -> isIntegralArgument(name, method, owner)
                                || method.getParameters().stream()
                                .anyMatch(parameter -> parameter.getNameAsString().equals(name) && !parameter.isVarArgs()
                                        && INTEGRAL_TYPES.contains(Objects.toString(index.resolve(parameter.getType(), owner.unit()), "")))));
                if (proven) continue;
                if (requireProof || !formats.isEmpty() || !evidence.isEmpty()) return Optional.of(usesFormatter
                        ? "method_id=" + methodId
                        + ": cannot verify source exception format template and unchanged arguments for " + associated
                        + ". Source formatter evidence (template, arguments bound at the calling method): " + formats
                        + ". Quote only the exact source format template and list its arguments in source order "
                        + "(e.g., from template \"<template>\" using <arguments> / từ mẫu \"<template>\" với các tham số <arguments>); "
                        + "require the complete formatted message at test runtime."
                        : "method_id=" + methodId + ": source does not use a formatter for the " + associated
                        + " message, so do not label it as a template or format. Source message(s): " + sourceMessages
                        + ". Quote one listed message unchanged as a plain message, keeping <...> placeholders for runtime values; "
                        + "if none is listed, describe the message without quoting it.");
                continue;
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
                // Chưa đọc được hết nơi ném lỗi thì không đủ bằng chứng để kết luận phát biểu là sai.
                if (contradicted && !helperThrows.unread)
                    return Optional.of("method_id=" + methodId + ": source exception message for " + entry.getKey()
                            + " does not support quoted message \"" + claimed + "\". Source message(s): "
                            + entry.getValue().stream().map(Text::display).toList()
                            + ". Preserve literal punctuation and <...> placeholders for runtime values; do not invent a different message.");
            }
            if (helperThrows.unread) {
                com.greytest.service.GenerationJobContext.log("Cần người duyệt kiểm tra: chưa kiểm chứng được thông báo \"" + claimed
                        + "\" của " + associated + " vì method gọi helper chưa đọc được message (method_id=" + methodId + ").");
                continue;
            }
            if (requireProof && (!unknown.isEmpty() || evidence.isEmpty() || fragmentClaim)) return Optional.of("method_id=" + methodId
                    + ": cannot verify the exact source exception message for " + associated
                    + ". Keep the proven exception type and describe dynamic/unknown message data instead of inventing a quoted literal. "
                    + (usesFormatter ? "For a locale-sensitive String.format expression, require the complete message computed with that same source formatter "
                    + "and unchanged inputs at test runtime. Do not quote a guessed rendered message; identify a quoted format template "
                    + "explicitly and list its unchanged source arguments in order. " : "")
                    + "Do not reduce an approved message requirement to exception type alone.");
        }
        return Optional.empty();
    }

    private static final class HelperThrows {
        private final List<Text> messages = new ArrayList<>();
        private boolean unread;
    }

    /** Đọc throw trong helper cùng class mà helperMessage không lần theo được, kể cả helper nhiều lệnh và helper lồng nhau. */
    private void readHelperThrows(MethodCallExpr call, SourceEvidenceIndex.SourceType owner, Map<String, Text> env,
            String exception, HelperThrows found, Set<MethodDeclaration> visited) {
        if (!owner.node().isClassOrInterfaceDeclaration()) return;
        var declaration = owner.node().asClassOrInterfaceDeclaration();
        // Lớp cha chưa đọc có thể có overload khác, không dùng tập candidate thiếu làm bằng chứng.
        if (declaration.getExtendedTypes().stream().anyMatch(t -> !"java.lang.Object".equals(index.resolve(t, owner.unit())))) return;
        var candidates = owner.node().getMethodsByName(call.getNameAsString()).stream()
                .filter(m -> m.getParameters().size() == call.getArguments().size()).toList();
        var values = call.getArguments().stream().map(e -> fragment(e, owner.unit(), env, 0)).toList();
        if (candidates.size() > 1 && values.stream().allMatch(v -> v != null && v.string())) candidates = candidates.stream()
                .filter(m -> m.getParameters().stream().allMatch(p -> "java.lang.String".equals(index.resolve(p.getType(), owner.unit())))).toList();
        for (var helper : candidates) {
            if (!visited.add(helper)) continue;
            Map<String, Text> locals = new HashMap<>();
            helper.findAll(NameExpr.class).forEach(name -> locals.put(name.getNameAsString(), Text.hole(name.getNameAsString(), false)));
            for (var creation : helper.findAll(ObjectCreationExpr.class)) {
                if (!creation.getType().getNameAsString().equals(exception)
                        || creation.getParentNode().filter(ThrowStmt.class::isInstance).isEmpty()) continue;
                Text text = creation.getAnonymousClassBody().isPresent() ? null : constructor(index.resolve(creation.getType(), owner.unit()),
                        creation.getArguments().stream().map(e -> fragment(e, owner.unit(), locals, 0)).toList(), 0);
                if (text == null || text.fixed() == 0) found.unread = true;
                else found.messages.add(text);
            }
            helper.findAll(MethodCallExpr.class).stream().filter(nested -> nested.getScope().isEmpty())
                    .forEach(nested -> readHelperThrows(nested, owner, locals, exception, found, visited));
        }
    }

    private static String messageNarrative(String description) {
        var claim = MESSAGE.matcher(description);
        while (claim.find()) {
            var constructor = CONSTRUCTOR.matcher(description);
            // Chỉ bỏ tham số khi quote bị bắt nằm trong constructor, không sửa literal message thực tế.
            if (constructor.find(claim.start()) && constructor.start() < claim.start(1)
                    && constructor.end() > claim.start(1)) {
                description = description.substring(0, constructor.start()) + constructor.group(1)
                        + description.substring(constructor.end());
                claim = MESSAGE.matcher(description);
            }
        }
        return description;
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
        // Chỉ dùng hợp đồng framework đã biết khi project không khai báo đè hoặc làm mơ hồ kiểu đó.
        if ("org.springframework.security.core.userdetails.UsernameNotFoundException".equals(qualified)
                && !index.declares(qualified)) {
            return args.size() == 1 && args.get(0).string() ? args.get(0) : null;
        }
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
                    "java.lang.String".equals(index.resolve(parameter.getType(), source.unit())), value.fixed(), value.format(), value.sourceArgument()));
        }
        var delegated = invocation.getArguments().stream().map(e -> fragment(e, source.unit(), env, depth + 1)).toList();
        String parent = invocation.isThis() ? qualified : declaration.getExtendedTypes().size() == 1
                ? index.resolve(declaration.getExtendedTypes().get(0), source.unit()) : null;
        return constructor(parent, delegated, depth + 1);
    }

    /** Chỉ lần theo helper một lệnh, có đích gọi và overload xác định từ source. */
    private Text helperMessage(MethodCallExpr call, SourceEvidenceIndex.SourceType owner,
            MethodDeclaration caller, Map<String, Text> env, String exception, int depth) {
        if (depth > 5) return null;
        var target = owner;
        boolean classCall = call.getScope().isPresent() && !call.getScope().get().isThisExpr();
        if (classCall) {
            Expression scope = call.getScope().get();
            var root = scope.findFirst(NameExpr.class);
            if (root.isPresent() && variableType(root.get().getNameAsString(), call, owner, caller) != null) return null;
            target = index.type(index.resolve(scope.toString(), owner.unit()));
        }
        if (target == null) return null;
        // Chưa suy giải overload kế thừa: không dùng một tập candidate bị thiếu làm bằng chứng.
        if (!target.node().isClassOrInterfaceDeclaration()) return null;
        var declaration = target.node().asClassOrInterfaceDeclaration();
        var targetUnit = target.unit();
        if (!declaration.getImplementedTypes().isEmpty() || declaration.getExtendedTypes().stream()
                .anyMatch(t -> {
                    String parent = index.resolve(t, targetUnit);
                    // Exception chuẩn của JDK không có static helper, nên lời gọi Class.helper() không thể là overload kế thừa.
                    return !"java.lang.Object".equals(parent) && !(classCall && STANDARD.contains(parent));
                })) return null;
        var values = call.getArguments().stream().map(e -> fragment(e, owner.unit(), env, 0)).toList();
        if (values.stream().anyMatch(Objects::isNull)) return null;
        var candidates = target.node().getMethodsByName(call.getNameAsString()).stream()
                .filter(m -> m.getParameters().size() == values.size()).toList();
        if (candidates.size() > 1 && values.stream().allMatch(Text::string)) {
            var unit = target.unit();
            candidates = candidates.stream().filter(m -> m.getParameters().stream()
                    .allMatch(p -> "java.lang.String".equals(index.resolve(p.getType(), unit)))).toList();
        }
        if (candidates.size() != 1) return null;
        var helper = candidates.get(0);
        if (helper.getParameters().stream().anyMatch(Parameter::isVarArgs)) return null;
        // Chọn overload trước khi xét dispatch; không bỏ overload thực rồi nhận nhầm một overload khác.
        if (classCall ? !helper.isStatic() : !helper.isStatic() && !helper.isPrivate() && !helper.isFinal()
                && !target.node().hasModifier(com.github.javaparser.ast.Modifier.Keyword.FINAL)) return null;
        if (helper.getBody().isEmpty() || helper.getBody().get().getStatements().size() != 1) return null;
        Map<String, Text> bindings = new HashMap<>();
        for (int i = 0; i < values.size(); i++) {
            var value = values.get(i);
            var parameter = helper.getParameter(i);
            bindings.put(parameter.getNameAsString(), new Text(value.segments(), value.display(),
                    "java.lang.String".equals(index.resolve(parameter.getType(), target.unit())),
                    value.fixed(), value.format(), value.sourceArgument()));
        }
        var statement = helper.getBody().get().getStatement(0);
        if (statement.isThrowStmt() && statement.asThrowStmt().getExpression().isObjectCreationExpr()) {
            var creation = statement.asThrowStmt().getExpression().asObjectCreationExpr();
            if (creation.getAnonymousClassBody().isPresent()) return null;
            if (!creation.getType().getNameAsString().equals(exception)) return null;
            var helperOwner = target;
            var args = creation.getArguments().stream().map(e -> fragment(e, helperOwner.unit(), bindings, 0)).toList();
            return constructor(index.resolve(creation.getType(), target.unit()), args, 0);
        }
        if (statement.isExpressionStmt() && statement.asExpressionStmt().getExpression().isMethodCallExpr()) {
            return helperMessage(statement.asExpressionStmt().getExpression().asMethodCallExpr(), target,
                    helper, bindings, exception, depth + 1);
        }
        return null;
    }

    private Text fragment(Expression expression, CompilationUnit unit, Map<String, Text> env, int depth) {
        if (depth > 8) return null;
        if (expression.isEnclosedExpr()) return fragment(expression.asEnclosedExpr().getInner(), unit, env, depth + 1);
        if (expression.isStringLiteralExpr()) return Text.literal(expression.asStringLiteralExpr().asString(), true);
        if (expression.isIntegerLiteralExpr()) return Text.literal(expression.asIntegerLiteralExpr().asNumber().toString(), false);
        if (expression.isLongLiteralExpr()) return Text.literal(expression.asLongLiteralExpr().asNumber().toString(), false);
        if (expression.isNameExpr()) {
            String name = expression.asNameExpr().getNameAsString();
            // Tham số/biến cục bộ che field cùng tên nên phải tra env trước.
            return env.containsKey(name) ? env.get(name) : constantValue(name, unit, depth);
        }
        if (expression.isBinaryExpr() && expression.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            var binary = expression.asBinaryExpr();
            Text left = fragment(binary.getLeft(), unit, env, depth + 1), right = fragment(binary.getRight(), unit, env, depth + 1);
            return left != null && right != null && (left.string() || right.string()) ? left.plus(right) : null;
        }
        if (expression.isMethodCallExpr()) {
            var call = expression.asMethodCallExpr();
            Text enumValue = enumGetterValue(call, unit, env);
            if (enumValue != null) return enumValue;
            if (!call.getNameAsString().equals("format") || call.getScope().isEmpty()
                    || call.getScope().get().findAll(NameExpr.class).stream().anyMatch(e -> env.containsKey(e.getNameAsString()))
                    || !"java.lang.String".equals(index.resolve(call.getScope().get().toString(), unit))
                    || call.getArguments().isEmpty() || !call.getArgument(0).isStringLiteralExpr()) return Text.hole(expression.toString(), false);
            String format = call.getArgument(0).asStringLiteralExpr().asString();
            Text result = Text.literal("", true);
            boolean localeSensitive = false;
            List<String> decimalArguments = new ArrayList<>();
            int argument = 1;
            for (int cursor = 0; cursor < format.length();) {
                int marker = format.indexOf('%', cursor);
                if (marker < 0) { result = result.plus(Text.literal(format.substring(cursor), true)); break; }
                result = result.plus(Text.literal(format.substring(cursor, marker), true));
                if (marker + 1 >= format.length()) return null;
                char token = format.charAt(marker + 1);
                if (token == '%') result = result.plus(Text.literal("%", true));
                else if (token == 's' && argument < call.getArguments().size()) {
                    var value = fragment(call.getArgument(argument++), unit, env, depth + 1);
                    if (value == null) return null;
                    result = result.plus(value);
                } else if (token == 'd' && argument < call.getArguments().size()) {
                    var value = fragment(call.getArgument(argument++), unit, env, depth + 1);
                    if (value == null || value.sourceArgument() == null) return null;
                    decimalArguments.add(value.sourceArgument());
                    localeSensitive = true;
                } else return null;
                cursor = marker + 2;
            }
            if (argument != call.getArguments().size()) return null;
            // Giữ đối số tại method gọi qua constructor để không nhầm tên tham số trùng nhau.
            var boundArguments = call.getArguments().subList(1, call.getArguments().size()).stream()
                    .map(e -> fragment(e, unit, env, depth + 1))
                    .map(value -> value == null ? "" : Objects.toString(value.sourceArgument(), "")).toList();
            var template = new FormatTemplate(format, boundArguments, List.copyOf(decimalArguments));
            // %d phụ thuộc locale: giữ bằng chứng mẫu/tham số, không đoán chuỗi message đã format.
            return localeSensitive ? new Text(Collections.singletonList(null), call.toString(), true, 0, template)
                    : new Text(result.segments(), result.display(), true, result.fixed(), template);
        }
        // Biểu thức còn lại là giá trị runtime, không đoán kết quả helper/getter.
        return expression.isMethodCallExpr() || expression.isFieldAccessExpr() ? Text.hole(expression.toString(), false) : null;
    }

    /**
     * Đọc hằng static final khai báo đúng một lần trong file khi initializer là literal hoặc ghép literal đúng kiểu.
     * Hằng còn lại là giá trị runtime: chỉ đối chiếu phần literal ghép cùng nó, không đoán giá trị.
     */
    // ponytail: hằng kế thừa và static import vẫn chưa xác định; mở rộng khi source thực tế cần.
    private Text constantValue(String name, CompilationUnit unit, int depth) {
        var variables = unit.findAll(FieldDeclaration.class).stream().flatMap(field -> field.getVariables().stream())
                .filter(variable -> variable.getNameAsString().equals(name)).toList();
        if (variables.size() != 1 || !(variables.get(0).getParentNode().orElse(null) instanceof FieldDeclaration field)
                || !field.isStatic() || !field.isFinal()) return null;
        var variable = variables.get(0);
        String type = index.resolve(variable.getType(), unit);
        boolean string = "java.lang.String".equals(type);
        Text value = variable.getInitializer().map(initializer -> fragment(initializer, unit, Map.of(), depth + 1)).orElse(null);
        // Literal chỉ trùng chuỗi Java in ra khi cùng nhóm kiểu: double = 4 in "4.0", char = 65 in "A".
        boolean exact = value != null && value.segments().stream().allMatch(Objects::nonNull)
                && (value.string() ? string : INTEGRAL_TYPES.contains(Objects.toString(type, "")));
        return exact ? value : Text.hole(name, string);
    }

    /** Message có dựng từ formatter chuỗi không, tính cả biến cục bộ được truyền vào exception. */
    // ponytail: chỉ xét initializer của biến cục bộ; gán lại sau khai báo thì không nhận ra.
    private static boolean usesMessageFormatter(Expression message, MethodDeclaration method) {
        Set<String> names = new HashSet<>();
        message.findAll(NameExpr.class).forEach(name -> names.add(name.getNameAsString()));
        return hasMessageFormatter(message) || method.findAll(VariableDeclarator.class).stream()
                .filter(variable -> names.contains(variable.getNameAsString()))
                .flatMap(variable -> variable.getInitializer().stream())
                .anyMatch(SourceExceptionMessageEvidence::hasMessageFormatter);
    }

    private static boolean hasMessageFormatter(Expression expression) {
        return expression.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getNameAsString().equals("formatted")
                || call.getNameAsString().equals("format") && call.getScope().map(Expression::toString)
                        .map(Set.of("String", "java.lang.String", "MessageFormat", "java.text.MessageFormat")::contains)
                        .orElse(call.getArguments().isNonEmpty() && call.getArgument(0).isStringLiteralExpr()));
    }

    /**
     * Đọc giá trị của Enum.CONST.getter() khi getter chỉ trả field và field nhận literal qua constructor.
     * Trường hợp khác trả null để giữ nguyên cách xử lý giá trị runtime, không đoán chuỗi.
     */
    private Text enumGetterValue(MethodCallExpr call, CompilationUnit unit, Map<String, Text> env) {
        if (!call.getArguments().isEmpty() || call.getScope().isEmpty() || !call.getScope().get().isFieldAccessExpr()) return null;
        var reference = call.getScope().get().asFieldAccessExpr();
        if (reference.getScope().findAll(NameExpr.class).stream().anyMatch(name -> env.containsKey(name.getNameAsString()))) return null;
        var type = index.type(index.resolve(reference.getScope().toString(), unit));
        if (type == null || !type.node().isEnumDeclaration()) return null;
        var declaration = type.node().asEnumDeclaration();
        var constant = declaration.getEntries().stream()
                .filter(entry -> entry.getNameAsString().equals(reference.getNameAsString())).findFirst().orElse(null);
        if (constant == null || !constant.getClassBody().isEmpty()) return null;
        var getters = declaration.getMethodsByName(call.getNameAsString()).stream().filter(m -> m.getParameters().isEmpty()).toList();
        if (getters.size() != 1 || getters.get(0).getBody().isEmpty() || getters.get(0).getBody().get().getStatements().size() != 1) return null;
        var statement = getters.get(0).getBody().get().getStatement(0);
        if (!statement.isReturnStmt() || statement.asReturnStmt().getExpression().isEmpty()) return null;
        String field = fieldName(statement.asReturnStmt().getExpression().get());
        if (field == null || declaration.getFieldByName(field).isEmpty()) return null;
        var constructors = declaration.getConstructors().stream()
                .filter(c -> c.getParameters().size() == constant.getArguments().size()).toList();
        if (constructors.size() != 1) return null;
        var constructor = constructors.get(0);
        // Field chỉ được phép đổi qua method có tên; method đó có lời gọi trong source thì giá trị không còn cố định.
        for (var assign : declaration.findAll(AssignExpr.class)) {
            if (!field.equals(fieldName(assign.getTarget()))) continue;
            if (assign.findAncestor(ConstructorDeclaration.class).isPresent()) continue;
            var writer = assign.findAncestor(MethodDeclaration.class).orElse(null);
            if (writer == null || index.callsMethodOn(writer.getNameAsString(), declaration.getNameAsString())
                    || declaration.findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getNameAsString().equals(writer.getNameAsString()))) return null;
        }
        var assignments = constructor.findAll(AssignExpr.class).stream().filter(a -> field.equals(fieldName(a.getTarget()))).toList();
        if (assignments.size() != 1 || !assignments.get(0).getValue().isNameExpr()) return null;
        String parameter = assignments.get(0).getValue().asNameExpr().getNameAsString();
        for (int i = 0; i < constructor.getParameters().size(); i++) {
            if (!constructor.getParameter(i).getNameAsString().equals(parameter)) continue;
            var argument = constant.getArgument(i);
            return argument.isStringLiteralExpr() ? Text.literal(argument.asStringLiteralExpr().asString(), true) : null;
        }
        return null;
    }

    private static String fieldName(Expression expression) {
        if (expression.isNameExpr()) return expression.asNameExpr().getNameAsString();
        return expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getScope().isThisExpr()
                ? expression.asFieldAccessExpr().getNameAsString() : null;
    }

    private static String rootParameter(String argument) {
        int dot = argument.indexOf('.');
        return dot >= 0 ? argument.substring(0, dot) : argument;
    }

    private boolean isIntegralArgument(String argument, MethodDeclaration method, SourceEvidenceIndex.SourceType owner) {
        String root = rootParameter(argument);
        var parameter = method.getParameters().stream().filter(p -> p.getNameAsString().equals(root) && !p.isVarArgs()).findFirst().orElse(null);
        if (parameter == null) return false;
        String currentType = index.resolve(parameter.getType(), owner.unit());
        if (!argument.contains(".")) return INTEGRAL_TYPES.contains(Objects.toString(currentType, ""));
        String[] parts = argument.split("\\.");
        for (int i = 1; i < parts.length; i++) {
            var type = currentType == null ? null : index.type(currentType);
            if (type == null) return false;
            String part = parts[i];
            if (part.endsWith("()")) {
                String methodName = part.substring(0, part.length() - 2);
                var methods = type.node().getMethodsByName(methodName).stream().filter(m -> m.getParameters().isEmpty()).toList();
                if (methods.size() == 1) currentType = index.resolve(methods.get(0).getType(), type.unit());
                else if (methods.isEmpty() && index.getterGenerated(type)) {
                    String prop = type.node().isRecordDeclaration() ? methodName
                            : methodName.startsWith("get") && methodName.length() > 3
                                    ? Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4) : null;
                    var propType = prop == null ? null : index.propertyType(type, prop);
                    currentType = propType == null ? null : index.resolve(propType, type.unit());
                } else return false;
            } else {
                var propType = index.propertyType(type, part);
                currentType = propType == null ? null : index.resolve(propType, type.unit());
            }
        }
        return INTEGRAL_TYPES.contains(Objects.toString(currentType, ""));
    }
}
