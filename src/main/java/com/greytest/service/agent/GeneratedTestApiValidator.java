package com.greytest.service.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;

/** Kiểm tra constructor, generic và kiểu giá trị từ khai báo source, không đoán API còn thiếu. */
final class GeneratedTestApiValidator {
    private static final Set<String> JAVA_LANG_TYPES = Set.of(
            "Object", "String", "Boolean", "Byte", "Character", "Double", "Float", "Integer", "Long", "Short");
    private static final Set<String> FINAL_JDK_TYPES = Set.of(
            "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Character",
            "java.lang.Double", "java.lang.Float", "java.lang.Integer", "java.lang.Long", "java.lang.Short",
            "java.math.BigDecimal", "java.math.BigInteger",
            "java.time.LocalDate", "java.time.LocalTime", "java.time.LocalDateTime", "java.time.Instant",
            "java.util.UUID", "java.util.Optional");
    private final Map<String, ClassOrInterfaceDeclaration> declarations = new HashMap<>();
    private final Map<String, String> sourcePaths = new HashMap<>();
    private final List<com.greytest.dto.agent.GenerationContextDtos.ResourceEvidenceDto> resources;
    private final List<String> contextWarnings;
    private final UnitTestContextDto context;

    GeneratedTestApiValidator(UnitTestContextDto context) {
        this.context = context;
        resources = context.supportingResources() == null ? List.of() : context.supportingResources();
        contextWarnings = context.contextWarnings() == null ? List.of() : context.contextWarnings();
        if (context.classes() == null) return;
        for (var clazz : context.classes()) {
            if (clazz.qualifiedName() != null && clazz.filePath() != null) sourcePaths.put(clazz.qualifiedName(), clazz.filePath().replace('\\', '/'));
            if (clazz.validationSourceCode() == null || clazz.qualifiedName() == null) continue;
            var parsed = new JavaParser(new ParserConfiguration().setLanguageLevel(
                    ParserConfiguration.LanguageLevel.JAVA_21)).parse(clazz.validationSourceCode());
            if (!parsed.isSuccessful()) continue;
            parsed.getResult().orElseThrow().findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(type -> type.getFullyQualifiedName().filter(clazz.qualifiedName()::equals).isPresent())
                    .findFirst().ifPresent(type -> declarations.put(clazz.qualifiedName(), type));
        }
    }

    static List<String> validate(UnitTestContextDto context, CompilationUnit test) {
        return validate(context, test, null);
    }

    static List<String> validate(UnitTestContextDto context, CompilationUnit test, Long caseId) {
        var validator = new GeneratedTestApiValidator(context);
        List<String> problems = new ArrayList<>();
        validator.validateMissingProjectImports(test, problems);
        validator.validateUninitializedSpies(test, problems);
        test.findAll(ObjectCreationExpr.class).forEach(creation -> validator.validateConstructor(creation, test, problems));
        test.findAll(com.github.javaparser.ast.expr.BinaryExpr.class)
                .forEach(binary -> validator.validateBinaryComparison(binary, test, problems));
        test.findAll(MethodCallExpr.class).forEach(call -> {
            validator.validateCall(call, test, problems);
            validator.validateNullAssertion(call, test, problems);
            validator.validateBigDecimalAssertion(call, test, caseId, problems);
            validator.validateUncapturedRead(call, test, problems);
            validator.validateReferenceIdentity(call, test, problems);
        });
        return problems;
    }

    private void validateUninitializedSpies(CompilationUnit test, List<String> problems) {
        for (var field : test.findAll(com.github.javaparser.ast.body.FieldDeclaration.class)) {
            boolean spy = Boolean.TRUE.equals(GeneratedUnitTestSemanticValidator.isMockitoSpyDependency(field, test));
            if (!spy || !field.getElementType().isClassOrInterfaceType()
                    || field.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("InjectMocks"))) continue;
            for (var variable : field.getVariables()) {
                if (!variable.getType().isClassOrInterfaceType()
                        || variable.getInitializer().filter(e -> !e.isNullLiteralExpr()).isPresent()) continue;
                var owner = field.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
                if (owner == null || !owner.getExtendedTypes().isEmpty()) continue;
                // Constructor/instance initializer chạy trước MockitoExtension, khác với BeforeEach.
                boolean assignedBeforeExtension = owner.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                        .filter(a -> a.findAncestor(ClassOrInterfaceDeclaration.class).filter(owner::equals).isPresent()
                                && a.findAncestor(com.github.javaparser.ast.expr.LambdaExpr.class).isEmpty())
                        .filter(a -> a.findAncestor(com.github.javaparser.ast.body.ConstructorDeclaration.class).isPresent()
                                || a.findAncestor(com.github.javaparser.ast.body.InitializerDeclaration.class).filter(i -> !i.isStatic()).isPresent())
                        .anyMatch(a -> Set.of(variable.getNameAsString(), "this." + variable.getNameAsString()).contains(a.getTarget().toString())
                                && !a.getValue().isNullLiteralExpr());
                if (assignedBeforeExtension) continue;
                List<String> errors = new ArrayList<>();
                validateConstructor(new ObjectCreationExpr().setType(variable.getType().asClassOrInterfaceType().clone()), test, errors);
                errors.forEach(error -> problems.add("Uninitialized @Spy '" + variable.getNameAsString() + "': " + error
                        + " Mockito constructs this field before @BeforeEach. Construct the real collaborator explicitly with its verified dependencies."));
            }
        }
    }

    private void validateReferenceIdentity(MethodCallExpr call, CompilationUnit test, List<String> problems) {
        if (!Set.of("assertSame", "assertNotSame").contains(call.getNameAsString())
                || call.getArguments().size() < 2 || call.getArguments().size() > 3) return;
        String junit = junitAssertionsOwner(call, test);
        if (junit == null) return;
        int first = junit.equals("org.junit.Assert") ? call.getArguments().size() - 2 : 0;
        Expression leftExpr = unwrap(call.getArgument(first));
        Expression rightExpr = unwrap(call.getArgument(first + 1));
        if (call.getNameAsString().equals("assertSame") && isTautologicalSameExpression(leftExpr, rightExpr, call)) {
            problems.add("Tautological identity assertion '" + call + "': both arguments evaluate to the same test variable or captor read. "
                    + "Remove the tautological assertSame or verify the approved repository/service interaction instead.");
            return;
        }
        var left = declaredValueType(leftExpr, test);
        var right = declaredValueType(rightExpr, test);
        if (left == null || right == null) return;
        // Hai class cụ thể độc lập hoặc entity với kiểu vô hướng final không thể là cùng một instance.
        if (incomparableTypes(left, right)) {
            problems.add("Impossible reference identity: " + left.type() + " and " + right.type()
                    + " are unrelated concrete types. Preserve approved mapped DTO fields; do not assert entity-to-DTO or entity-to-scalar identity.");
        }
    }

    /** Chặn phép so sánh == / != giữa hai kiểu không tương thích gây lỗi biên dịch javac (incomparable types). */
    private void validateBinaryComparison(com.github.javaparser.ast.expr.BinaryExpr binary, CompilationUnit test, List<String> problems) {
        if (binary.getOperator() != com.github.javaparser.ast.expr.BinaryExpr.Operator.EQUALS
                && binary.getOperator() != com.github.javaparser.ast.expr.BinaryExpr.Operator.NOT_EQUALS) return;
        Expression leftExpr = unwrap(binary.getLeft());
        Expression rightExpr = unwrap(binary.getRight());
        if (leftExpr.isNullLiteralExpr() || rightExpr.isNullLiteralExpr()) {
            Expression nonNull = leftExpr.isNullLiteralExpr() ? rightExpr : leftExpr;
            SourceValueType resolved = declaredValueType(nonNull, test);
            if (resolved != null && resolved.type().isPrimitiveType()) {
                problems.add("Source value contract: " + nonNull + " has primitive type " + resolved.type()
                        + " and cannot be compared with null.");
            }
            return;
        }
        var left = declaredValueType(leftExpr, test);
        var right = declaredValueType(rightExpr, test);
        if (left == null || right == null) return;
        if (incomparableTypes(left, right)) {
            problems.add("Incompatible type comparison '" + binary + "': " + left.type() + " and " + right.type()
                    + " are incomparable types and will not compile. Compare compatible fields (such as saved.getId() and result.getId() with assertEquals) instead of comparing an entity/DTO reference with an unrelated type.");
        }
    }

    private boolean incomparableTypes(SourceValueType left, SourceValueType right) {
        var a = declaration(left.type(), left.unit());
        var b = declaration(right.type(), right.unit());
        boolean concreteA = a != null && !a.isInterface() && a.getExtendedTypes().isEmpty();
        boolean concreteB = b != null && !b.isInterface() && b.getExtendedTypes().isEmpty();
        if (concreteA && concreteB) return a != b;
        String qLeft = left.type().isClassOrInterfaceType() ? qualifiedType(left.type().asClassOrInterfaceType(), left.unit()) : null;
        String qRight = right.type().isClassOrInterfaceType() ? qualifiedType(right.type().asClassOrInterfaceType(), right.unit()) : null;
        boolean finalLeft = qLeft != null && FINAL_JDK_TYPES.contains(qLeft);
        boolean finalRight = qRight != null && FINAL_JDK_TYPES.contains(qRight);
        if (finalLeft && finalRight) return !qLeft.equals(qRight);
        if (concreteA && (right.type().isPrimitiveType() || finalRight)) return true;
        if (concreteB && (left.type().isPrimitiveType() || finalLeft)) return true;
        return false;
    }

    private static boolean isTautologicalSameExpression(Expression left, Expression right, Node contextNode) {
        if ((left.isNameExpr() || left.isFieldAccessExpr()
                || left.isMethodCallExpr() && left.asMethodCallExpr().getNameAsString().equals("getValue"))
                && left.equals(right)) return true;
        return sameInitializerRead(left, right, contextNode) || sameInitializerRead(right, left, contextNode);
    }

    private static boolean sameInitializerRead(Expression candidateName, Expression other, Node contextNode) {
        if (!candidateName.isNameExpr()) return false;
        String name = candidateName.asNameExpr().getNameAsString();
        var method = contextNode.findAncestor(MethodDeclaration.class).orElse(null);
        if (method == null) return false;
        var locals = method.findAll(VariableDeclarator.class).stream()
                .filter(v -> v.getNameAsString().equals(name)).toList();
        if (locals.size() != 1 || locals.get(0).getInitializer().isEmpty()) return false;
        boolean reassigned = method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                .anyMatch(a -> a.getTarget().isNameExpr() && a.getTarget().asNameExpr().getNameAsString().equals(name));
        return !reassigned && unwrap(locals.get(0).getInitializer().get()).equals(other);
    }

    private static Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current.isEnclosedExpr()) current = current.asEnclosedExpr().getInner();
        return current;
    }

    private void validateMissingProjectImports(CompilationUnit test, List<String> problems) {
        if (context.classes() == null || test.getPackageDeclaration().isEmpty() || test.findAll(ClassOrInterfaceDeclaration.class).stream()
                .anyMatch(type -> !type.getExtendedTypes().isEmpty() || !type.getImplementedTypes().isEmpty())) return;
        // Wildcard chưa resolve có thể cung cấp kiểu lồng; không kết luận thiếu import từ tên đơn.
        if (test.getImports().stream().anyMatch(item -> item.isAsterisk() && (!item.isStatic()
                || !Set.of("org.mockito.Mockito", "org.mockito.ArgumentMatchers", "org.junit.Assert", "org.junit.jupiter.api.Assertions")
                        .contains(item.getNameAsString())))) return;
        Set<String> reported = new java.util.HashSet<>();
        for (var reference : test.findAll(ClassOrInterfaceType.class)) {
            String name = reference.getNameWithScope();
            if (reference.getScope().isPresent() || qualifiedType(reference, test) != null
                    || test.findAll(com.github.javaparser.ast.type.TypeParameter.class).stream().anyMatch(type -> type.getNameAsString().equals(name))
                    || test.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream().anyMatch(type -> type.getNameAsString().equals(name))
                    || test.getImports().stream().anyMatch(item -> !item.isAsterisk() && item.getName().getIdentifier().equals(name))) continue;
            String local = test.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("") + name;
            var candidates = context.classes().stream().filter(type -> name.equals(type.className()) && declarations.containsKey(type.qualifiedName()))
                    .map(type -> type.qualifiedName()).distinct().toList();
            if (!candidates.isEmpty() && !candidates.contains(local) && reported.add(name)) {
                problems.add("Missing project type import for '" + name + "'. The supplied source declares " + candidates
                        + ". Use the exact source-supported import or fully qualified name in this complete test file; do not rely on imports from other generated cases.");
            }
        }
    }

    ClassOrInterfaceDeclaration declaration(String qualifiedName) {
        return declarations.get(qualifiedName);
    }

    ClassOrInterfaceDeclaration declaration(Type type, CompilationUnit unit) {
        return type != null && type.isClassOrInterfaceType() ? declarations.get(qualifiedType(type.asClassOrInterfaceType(), unit)) : null;
    }

    private void validateBigDecimalAssertion(MethodCallExpr call, CompilationUnit test, Long caseId, List<String> problems) {
        if (!call.getNameAsString().equals("assertEquals") || call.getArguments().size() < 2
                || call.getArguments().size() > 3) return;
        String junit = junitAssertionsOwner(call, test);
        if (junit == null) return;
        int first = junit.equals("org.junit.Assert") ? call.getArguments().size() - 2 : 0;
        Expression expected = call.getArgument(first);
        Expression actual = call.getArgument(first + 1);
        boolean stringRepresentation = expected.isStringLiteralExpr() && actual.isMethodCallExpr()
                && Set.of("toString", "toPlainString", "toEngineeringString").contains(actual.asMethodCallExpr().getNameAsString())
                && actual.asMethodCallExpr().getArguments().isEmpty() && actual.asMethodCallExpr().getScope().isPresent();
        if (stringRepresentation) actual = actual.asMethodCallExpr().getScope().orElseThrow();
        SourceValueType actualType = declaredValueType(actual, test);
        if (!(stringRepresentation || isBigDecimalLiteral(expected, test)) || actualType == null || !actualType.type().isClassOrInterfaceType()
                || !"java.math.BigDecimal".equals(qualifiedType(actualType.type().asClassOrInterfaceType(), actualType.unit()))) return;
        if (requiresExactDecimalComparison(caseId, actual)) return;
        // Không thêm ràng buộc scale vào oracle chỉ yêu cầu giá trị số, dù source hiện tại có thể tình cờ khớp.
        problems.add("BigDecimal numeric assertion contract for Test Case " + caseId + ": " + call
                + " compares both value and scale, but its approved contract has no explicit decimal representation requirement. "
                + "Preserve the approved input and expected numeric value; use assertNotNull(actual) and "
                + "assertEquals(0, expected.compareTo(actual)) instead. Do not change fixtures, production or the business expectation to make the test pass.");
    }

    private boolean isBigDecimalLiteral(Expression value, CompilationUnit test) {
        if (value.isEnclosedExpr()) return isBigDecimalLiteral(value.asEnclosedExpr().getInner(), test);
        if (value.isObjectCreationExpr()) {
            return "java.math.BigDecimal".equals(qualifiedType(value.asObjectCreationExpr().getType(), test));
        }
        Expression owner;
        if (value.isFieldAccessExpr() && Set.of("ZERO", "ONE", "TEN").contains(value.asFieldAccessExpr().getNameAsString())) {
            owner = value.asFieldAccessExpr().getScope();
        } else if (value.isMethodCallExpr() && value.asMethodCallExpr().getNameAsString().equals("valueOf")
                && value.asMethodCallExpr().getScope().isPresent()) {
            owner = value.asMethodCallExpr().getScope().get();
        } else return false;
        if (expressionType(owner, test) != null) return false;
        var parsed = new JavaParser().parseClassOrInterfaceType(owner.toString()).getResult();
        return parsed.isPresent() && "java.math.BigDecimal".equals(qualifiedType(parsed.get(), test));
    }

    private boolean requiresExactDecimalComparison(Long caseId, Expression actual) {
        if (caseId == null || context.approvedTestCases() == null) return true;
        var approved = context.approvedTestCases().stream().filter(item -> caseId.equals(item.id())).findFirst().orElse(null);
        if (approved == null || approved.expectedResult() == null || approved.expectedResult().isBlank()) return true;
        List<String> contract = new ArrayList<>();
        contract.add(approved.description());
        contract.add(approved.preconditions());
        contract.add(approved.expectedResult());
        if (approved.testData() != null) contract.add(approved.testData().toString());
        Set<Long> ruleIds = new java.util.HashSet<>();
        if (context.approvedTestPlans() != null) {
            context.approvedTestPlans().stream().filter(plan -> java.util.Objects.equals(approved.testPlanId(), plan.id()))
                    .forEach(plan -> {
                        contract.add(plan.title());
                        contract.add(plan.description());
                        ruleIds.add(plan.businessRuleId());
                        if (plan.coveredRuleIds() != null) ruleIds.addAll(plan.coveredRuleIds());
                    });
        }
        if (context.approvedBusinessRules() != null) {
            context.approvedBusinessRules().stream().filter(rule -> ruleIds.contains(rule.id()))
                    .forEach(rule -> contract.add(rule.description()));
        }
        // shortcut: Chỉ nhận dấu hiệu biểu diễn rõ trong hợp đồng đã duyệt; không suy scale từ ký hiệu 0.00 hay @Column.
        Expression subjectValue = actual;
        if (actual.isNameExpr()) {
            var method = actual.findAncestor(MethodDeclaration.class).orElse(null);
            if (method != null) {
                String alias = actual.asNameExpr().getNameAsString();
                var locals = method.findAll(VariableDeclarator.class).stream().filter(v -> v.getNameAsString().equals(alias)).toList();
                if (locals.size() == 1) {
                    var local = locals.get(0);
                    var statement = local.getParentNode().flatMap(Node::getParentNode).filter(ExpressionStmt.class::isInstance).orElse(null);
                    var block = statement == null ? null : statement.getParentNode().filter(BlockStmt.class::isInstance).map(BlockStmt.class::cast).orElse(null);
                    boolean reassigned = method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                            .anyMatch(assignment -> assignment.getTarget().isNameExpr() && assignment.getTarget().asNameExpr().getNameAsString().equals(alias));
                    if (!reassigned && block != null && block.isAncestorOf(actual) && local.getRange().isPresent() && actual.getRange().isPresent()
                            && local.getRange().get().end.isBefore(actual.getRange().get().begin) && local.getInitializer().isPresent()) {
                        subjectValue = local.getInitializer().get();
                    }
                }
            }
        }
        boolean subjectKnown = subjectValue.isFieldAccessExpr() || subjectValue.isMethodCallExpr()
                && subjectValue.asMethodCallExpr().getArguments().isEmpty()
                && subjectValue.asMethodCallExpr().getNameAsString().startsWith("get");
        String name = subjectValue.isMethodCallExpr() ? subjectValue.asMethodCallExpr().getNameAsString()
                : subjectValue.isNameExpr() ? subjectValue.asNameExpr().getNameAsString()
                : subjectValue.isFieldAccessExpr() ? subjectValue.asFieldAccessExpr().getNameAsString() : "";
        if (name.startsWith("get") && name.length() > 3) name = name.substring(3);
        String field = name.isEmpty() ? "(?!)" : java.util.Arrays.stream(name.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ").split("[\\s_]+"))
                .map(part -> java.util.regex.Pattern.quote(part.toLowerCase(Locale.ROOT))).collect(java.util.stream.Collectors.joining("\\s*"));
        String subject = "(?:\\b" + field + "\\b|\\bamount\\b|\\bmoney\\b|\\bcurrency\\b|số\\s+tiền)";
        String modifiers = "(?:(?:must|should|shall|is|be|have|has|the|a|exactly|always|textual|text|string|decimal|được|phải)\\s+){0,8}";
        String representation = "(?:representation|format(?:ted|ting)?|biểu\\s+diễn|định\\s+dạng)";
        var fieldRepresentation = java.util.regex.Pattern.compile(subject + "(?:['’]s)?\\s+" + modifiers + representation
                + "|(?:(?:textual|text|string|decimal)\\s+)?" + representation + "\\s+(?:of|for|của|cho)\\s+" + subject);
        return contract.stream().filter(java.util.Objects::nonNull).map(text -> text.toLowerCase(Locale.ROOT))
                .anyMatch(text -> text.matches("(?s).*(\\bscale\\b|\\bprecision\\b|\\bround(?:ing|ed)?\\b|decimal places?|decimal digits?"
                        + "|thập phân|làm tròn|chữ\\s+số\\s+sau\\s+dấu\\s+(?:phẩy|chấm)|decimal\\s+(?:representation|format|text)"
                        + "|bigdecimal\\.(?:zero|one|ten)|bigdecimal\\s*\\(|setscale|striptrailingzeros).*$")
                        || fieldRepresentation.matcher(text).find()
                        // Chưa gắn được giá trị với property thì không ép bỏ một oracle biểu diễn đã duyệt.
                        || !subjectKnown && text.matches("(?s).*(representation|format(?:ted|ting)?|biểu diễn|định dạng).*$"));
    }

    private void validateUncapturedRead(MethodCallExpr call, CompilationUnit test, List<String> problems) {
        if (!call.getNameAsString().equals("getValue") || !call.getArguments().isEmpty()
                || call.getScope().filter(Expression::isNameExpr).isEmpty()
                || call.findAncestor(com.github.javaparser.ast.expr.LambdaExpr.class).isPresent()) return;
        Expression scope = call.getScope().orElseThrow();
        var type = expressionType(scope, test);
        if (type == null || !type.isClassOrInterfaceType()
                || !"org.mockito.ArgumentCaptor".equals(qualifiedType(type.asClassOrInterfaceType(), test))) return;
        var method = call.findAncestor(MethodDeclaration.class).orElse(null);
        if (method == null) return;
        String name = scope.asNameExpr().getNameAsString();
        var locals = method.findAll(VariableDeclarator.class).stream().filter(v -> v.getNameAsString().equals(name)).toList();
        if (locals.size() != 1 || locals.get(0).getInitializer().filter(Expression::isMethodCallExpr).isEmpty()) return;
        var local = locals.get(0);
        var statement = local.getParentNode().flatMap(Node::getParentNode).filter(ExpressionStmt.class::isInstance).orElse(null);
        var block = statement == null ? null : statement.getParentNode().filter(BlockStmt.class::isInstance).map(BlockStmt.class::cast).orElse(null);
        if (block == null || !block.isAncestorOf(call) || local.getRange().isEmpty() || call.getRange().isEmpty()
                || !local.getRange().get().end.isBefore(call.getRange().get().begin)) return;
        var factory = locals.get(0).getInitializer().orElseThrow().asMethodCallExpr();
        if (!factory.getNameAsString().equals("forClass") || factory.getScope().isEmpty()
                || expressionType(factory.getScope().get(), test) != null) return;
        var owner = new JavaParser().parseClassOrInterfaceType(factory.getScope().get().toString()).getResult();
        if (owner.isEmpty() || !"org.mockito.ArgumentCaptor".equals(qualifiedType(owner.get(), test))) return;
        // Chỉ kết luận với captor mới tạo, không truyền hoặc alias sang code khác có thể capture.
        boolean readsOnly = method.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                .filter(ref -> ref.getNameAsString().equals(name)).allMatch(ref -> ref.getParentNode()
                        .filter(MethodCallExpr.class::isInstance).map(MethodCallExpr.class::cast)
                        .filter(read -> read.getScope().filter(ref::equals).isPresent()
                                && Set.of("getValue", "getAllValues").contains(read.getNameAsString())).isPresent());
        if (readsOnly) problems.add("ArgumentCaptor '" + name + "' is read by getValue() but never captures an argument. "
                + "Use verify(mock).method(" + name + ".capture()) before reading, or use same(result) for an approved identity requirement. Preserve the approved assertions.");
    }

    private void validateNullAssertion(MethodCallExpr call, CompilationUnit test, List<String> problems) {
        String name = call.getNameAsString();
        if (!Set.of("assertNull", "assertEquals").contains(name)) return;
        String junit = junitAssertionsOwner(call, test);
        if (junit == null) return;
        Expression value = null;
        int size = call.getArguments().size();
        if (name.equals("assertNull") && (size == 1 || size == 2)) {
            value = call.getArgument(junit.equals("org.junit.Assert") ? size - 1 : 0);
        } else if (name.equals("assertEquals") && (size == 2 || size == 3)) {
            int first = junit.equals("org.junit.Assert") ? size - 2 : 0;
            Expression left = call.getArgument(first);
            Expression right = call.getArgument(first + 1);
            if (left.isNullLiteralExpr()) value = right;
            else if (right.isNullLiteralExpr()) value = left;
        }
        SourceValueType resolved = value == null ? null : declaredValueType(value, test);
        Type type = resolved == null ? null : resolved.type();
        if (type != null && type.isPrimitiveType()) {
            problems.add("Source value contract: " + value + " has primitive type " + type
                    + " and cannot be null. Follow the source-defined return, fallback or exception; "
                    + "preserve the approved expected result and report a contradiction if it requires null.");
        }
    }

    String junitAssertionsOwner(MethodCallExpr call, CompilationUnit test) {
        Set<String> owners = Set.of("org.junit.Assert", "org.junit.jupiter.api.Assertions");
        if (call.getScope().isPresent()) {
            String scope = call.getScope().get().toString();
            if (owners.contains(scope)) return scope;
            if (!call.getScope().get().isNameExpr() || expressionType(call.getScope().get(), test) != null
                    || test.findAll(ClassOrInterfaceDeclaration.class).stream()
                            .anyMatch(type -> type.getNameAsString().equals(scope))) return null;
            return test.getImports().stream().filter(imported -> !imported.isStatic() && !imported.isAsterisk())
                    .map(imported -> imported.getNameAsString()).filter(owners::contains)
                    .filter(owner -> owner.endsWith("." + scope)).findFirst().orElse(null);
        }
        if (test.findAll(MethodDeclaration.class).stream()
                .anyMatch(method -> method.getNameAsString().equals(call.getNameAsString()))) return null;
        if (insideAnonymousClass(call) || call.stream(Node.TreeTraversal.PARENTS)
                .filter(ClassOrInterfaceDeclaration.class::isInstance).map(ClassOrInterfaceDeclaration.class::cast)
                .anyMatch(owner -> !owner.getExtendedTypes().isEmpty() || !owner.getImplementedTypes().isEmpty())) return null;
        var importedOwners = test.getImports().stream().filter(imported -> imported.isStatic())
                // Mockito không khai báo assertion JUnit; wildcard này không tạo xung đột assertEquals/assertNull.
                .filter(imported -> !imported.isAsterisk() || !imported.getNameAsString().equals("org.mockito.Mockito"))
                .filter(imported -> imported.isAsterisk() || imported.getName().getIdentifier().equals(call.getNameAsString()))
                .map(imported -> imported.isAsterisk() ? imported.getNameAsString()
                        : imported.getName().getQualifier().map(Object::toString).orElse(""))
                .distinct().toList();
        return importedOwners.size() == 1 && owners.contains(importedOwners.get(0)) ? importedOwners.get(0) : null;
    }

    private record SourceValueType(Type type, CompilationUnit unit) {}

    private SourceValueType declaredValueType(Expression value, CompilationUnit test) {
        if (value.isEnclosedExpr()) return declaredValueType(value.asEnclosedExpr().getInner(), test);
        if (!value.isMethodCallExpr()) {
            Type type = expressionType(value, test);
            return type == null ? null : new SourceValueType(type, test);
        }
        MethodCallExpr getter = value.asMethodCallExpr();
        SourceValueType receiver = getter.getScope().map(scope -> declaredValueType(scope, test)).orElse(null);
        if (receiver == null || !receiver.type().isClassOrInterfaceType()) return null;
        ClassOrInterfaceType receiverType = receiver.type().asClassOrInterfaceType();
        String qualified = qualifiedType(receiverType, receiver.unit());
        SourceValueType element = collectionElementType(getter, receiverType, qualified, receiver.unit());
        if (element != null) return element;
        ClassOrInterfaceDeclaration owner = declarations.get(qualified);
        if (owner == null) return null;
        // Không suy ra overload theo số tham số: chỉ getter không đối số có kiểu trả về chắc chắn.
        if (!getter.getArguments().isEmpty()) return null;
        var methods = owner.getMethodsByName(getter.getNameAsString()).stream()
                .filter(method -> method.getParameters().isEmpty()).toList();
        CompilationUnit production = owner.findCompilationUnit().orElseThrow();
        if (methods.size() == 1 && methods.get(0).getTypeParameters().isEmpty()) {
            return resolveMemberType(methods.get(0).getType(), owner, production, receiverType, receiver.unit());
        }
        if (!methods.isEmpty()) return null;
        // Chỉ suy ra getter Lombok khi annotation xác nhận, ưu tiên method viết tay ở trên.
        if (owner.getAnnotations().stream().anyMatch(annotation -> annotation.getName().getIdentifier().equals("Accessors"))) return null;
        for (var field : owner.getFields()) {
            if (field.isStatic() || field.getAnnotations().stream()
                    .anyMatch(annotation -> annotation.getName().getIdentifier().equals("Accessors")
                            || annotation.getName().getIdentifier().equals("Getter") && annotation.toString().contains("NONE"))) continue;
            if (!lombok(field, production, "Getter") && !lombok(owner, production, "Getter")
                    && !lombok(owner, production, "Data") && !lombok(owner, production, "Value")) continue;
            for (var variable : field.getVariables()) {
                String expected = expectedGetter(owner, variable);
                if (expected != null && expected.equals(getter.getNameAsString())) return resolveMemberType(variable.getType(), owner, production, receiverType, receiver.unit());
            }
        }
        return null;
    }

    private SourceValueType resolveMemberType(Type member, ClassOrInterfaceDeclaration owner, CompilationUnit unit,
            ClassOrInterfaceType receiver, CompilationUnit receiverUnit) {
        if (owner.getTypeParameters().isEmpty()) return declaredMemberType(member, owner, unit);
        var actual = receiver.getTypeArguments();
        if (actual.isEmpty() || actual.get().size() != owner.getTypeParameters().size()) return null;
        Type resolved = member.clone();
        for (int index = 0; index < owner.getTypeParameters().size(); index++) {
            String parameter = owner.getTypeParameter(index).getNameAsString();
            Type argument = actual.get().get(index);
            if (!argument.isClassOrInterfaceType()) return null;
            String qualified = concreteType(argument, receiverUnit);
            if (qualified == null) return null;
            Type replacement = new JavaParser().parseType(qualified).getResult().orElse(null);
            if (replacement == null) return null;
            if (resolved.asString().equals(parameter)) resolved = replacement;
            else for (var nested : new ArrayList<>(resolved.findAll(ClassOrInterfaceType.class))) {
                if (nested.getNameWithScope().equals(parameter)) nested.replace(replacement.clone());
            }
        }
        return declaredMemberType(resolved, owner, unit);
    }

    private SourceValueType declaredMemberType(Type type, ClassOrInterfaceDeclaration owner, CompilationUnit unit) {
        // Chỉ resolve kiểu tham chiếu khi đã có đủ khai báo tổ tiên để loại trừ kiểu lồng che khuất.
        if (!type.isPrimitiveType() && inheritedTypeIsUnknown(owner, type, new java.util.HashSet<>())) return null;
        return new SourceValueType(type, unit);
    }

    private boolean inheritedTypeIsUnknown(ClassOrInterfaceDeclaration owner, Type member, java.util.Set<String> visited) {
        if (!visited.add(owner.getFullyQualifiedName().orElse(owner.getNameAsString()))) return true;
        for (var parent : java.util.stream.Stream.concat(owner.getExtendedTypes().stream(), owner.getImplementedTypes().stream()).toList()) {
            String qualified = qualifiedType(parent, owner.findCompilationUnit().orElseThrow());
            var declaration = declarations.get(qualified);
            if (declaration == null || declaration.getMembers().stream().filter(com.github.javaparser.ast.body.TypeDeclaration.class::isInstance)
                    .map(nested -> (com.github.javaparser.ast.body.TypeDeclaration<?>) nested).anyMatch(nested -> member.findAll(ClassOrInterfaceType.class).stream()
                            .anyMatch(reference -> reference.getNameWithScope().equals(nested.getNameAsString())))
                    || inheritedTypeIsUnknown(declaration, member, new java.util.HashSet<>(visited))) return true;
        }
        return false;
    }

    private boolean insideAnonymousClass(Node node) {
        return node.stream(Node.TreeTraversal.PARENTS).anyMatch(parent -> parent instanceof ObjectCreationExpr creation
                && creation.getAnonymousClassBody().isPresent());
    }

    private SourceValueType collectionElementType(MethodCallExpr call, ClassOrInterfaceType receiver,
            String qualified, CompilationUnit unit) {
        if (!call.getNameAsString().equals("get") || receiver.getTypeArguments().isEmpty()) return null;
        var arguments = receiver.getTypeArguments().orElseThrow();
        if ("java.util.Optional".equals(qualified) && arguments.size() == 1 && call.getArguments().isEmpty()) {
            return new SourceValueType(arguments.get(0), unit);
        }
        if (call.getArguments().size() != 1) return null;
        // Chỉ dùng generic của collection JDK đã resolve; không đoán API của class cùng tên.
        if ("java.util.List".equals(qualified) && arguments.size() == 1) {
            return new SourceValueType(arguments.get(0), unit);
        }
        if ("java.util.Map".equals(qualified) && arguments.size() == 2) {
            return new SourceValueType(arguments.get(1), unit);
        }
        return null;
    }

    private void validateConstructor(ObjectCreationExpr creation, CompilationUnit test, List<String> problems) {
        String fqn = qualifiedType(creation.getType(), test);
        ClassOrInterfaceDeclaration type = declarations.get(fqn);
        if (type == null || type.isInterface() || creation.getAnonymousClassBody().isPresent()) return;
        CompilationUnit production = type.findCompilationUnit().orElseThrow();
        // Builder có thể sinh constructor riêng; chỉ chặn khi danh sách constructor được biết chắc.
        if (lombok(type, production, "Builder") || lombok(type, production, "SuperBuilder")) return;
        if (type.getConstructors().stream().anyMatch(constructor -> lombok(constructor, production, "Tolerate"))) return;
        boolean value = lombok(type, production, "Value");
        if (value && type.getFields().stream().filter(field -> !field.isStatic())
                .flatMap(field -> field.getVariables().stream()).anyMatch(variable -> variable.getInitializer().isPresent())) return;
        List<Integer> arities = new ArrayList<>();
        for (var constructor : type.getConstructors()) {
            int count = constructor.getParameters().size();
            if (count > 0 && constructor.getParameter(count - 1).isVarArgs()
                    && creation.getArguments().size() >= count - 1) return;
            arities.add(count);
        }
        if (lombok(type, production, "NoArgsConstructor")) arities.add(0);
        boolean noArgs = lombok(type, production, "NoArgsConstructor");
        boolean allArgs = lombok(type, production, "AllArgsConstructor");
        boolean requiredArgs = lombok(type, production, "RequiredArgsConstructor");
        if (allArgs || value && type.getConstructors().isEmpty()) {
            arities.add((int) type.getFields().stream().filter(field -> !field.isStatic())
                    .flatMap(field -> field.getVariables().stream())
                    .filter(variable -> !variable.findAncestor(com.github.javaparser.ast.body.FieldDeclaration.class)
                            .orElseThrow().isFinal() || variable.getInitializer().isEmpty()).count());
        }
        if (requiredArgs || lombok(type, production, "Data") && type.getConstructors().isEmpty() && !noArgs && !allArgs) {
            arities.add((int) type.getFields().stream().filter(field -> !field.isStatic())
                    .filter(field -> field.isFinal() || lombok(field, production, "NonNull"))
                    .flatMap(field -> field.getVariables().stream()).filter(variable -> variable.getInitializer().isEmpty()).count());
        }
        if (arities.isEmpty() && type.getConstructors().isEmpty()) arities.add(0);
        if (!arities.contains(creation.getArguments().size())) {
            problems.add("Source constructor contract: " + fqn + " has no constructor with "
                    + creation.getArguments().size() + " arguments; declared/generated arities are " + arities
                    + ". Use the exact production constructor or confirmed setters.");
        }
    }

    boolean lombok(NodeWithAnnotations<?> type, CompilationUnit unit, String name) {
        return type.getAnnotations().stream().anyMatch(annotation -> {
            String declared = annotation.getNameAsString();
            return declared.equals("lombok." + name) || declared.equals("lombok.experimental." + name)
                    || declared.equals(name) && unit.getImports().stream().anyMatch(imported -> !imported.isStatic()
                    && (imported.getNameAsString().equals("lombok." + name)
                    || imported.getNameAsString().equals("lombok.experimental." + name)
                    || imported.isAsterisk() && Set.of("lombok", "lombok.experimental").contains(imported.getNameAsString())));
        });
    }

    private void validateCall(MethodCallExpr call, CompilationUnit test, List<String> problems) {
        validateAccessor(call, test, problems);
        Type receiver = call.getScope().map(scope -> expressionType(scope, test)).orElse(null);
        if (receiver == null || !receiver.isClassOrInterfaceType()) return;
        ClassOrInterfaceDeclaration owner = declarations.get(qualifiedType(receiver.asClassOrInterfaceType(), test));
        // Không đoán substitution, overload kế thừa hoặc method generic chưa resolve được.
        if (owner == null || !owner.getTypeParameters().isEmpty() || !owner.getExtendedTypes().isEmpty()
                || !owner.getImplementedTypes().isEmpty()) return;
        var namedMethods = owner.getMethodsByName(call.getNameAsString());
        if (namedMethods.stream().anyMatch(method -> method.getParameters().stream().anyMatch(parameter -> parameter.isVarArgs())
                && call.getArguments().size() >= method.getParameters().size() - 1)) return;
        var methods = namedMethods.stream()
                .filter(method -> method.getParameters().size() == call.getArguments().size())
                .filter(method -> method.getParameters().stream().noneMatch(parameter -> parameter.isVarArgs())).toList();
        if (methods.size() != 1 || !methods.get(0).getTypeParameters().isEmpty()) return;
        MethodDeclaration method = methods.get(0);
        CompilationUnit production = owner.findCompilationUnit().orElseThrow();
        for (int i = 0; i < call.getArguments().size(); i++) {
            Type actual = expressionType(call.getArgument(i), test);
            reportMismatch(call.getNameAsString(), method.getParameter(i).getType(), production, actual, test, problems);
        }
        call.getParentNode().filter(VariableDeclarator.class::isInstance).map(VariableDeclarator.class::cast)
                .filter(variable -> variable.getInitializer().filter(call::equals).isPresent())
                .ifPresent(variable -> reportMismatch(call.getNameAsString(), variable.getType(), test,
                        method.getType(), production, problems));
    }

    private void validateAccessor(MethodCallExpr call, CompilationUnit test, List<String> problems) {
        if (!call.getArguments().isEmpty() || !call.getNameAsString().matches("(?:get|is)[A-Z].*")) return;
        SourceValueType receiver = call.getScope().map(scope -> declaredValueType(scope, test)).orElse(null);
        if (receiver == null || !receiver.type().isClassOrInterfaceType()) return;
        var owner = declarations.get(qualifiedType(receiver.type().asClassOrInterfaceType(), receiver.unit()));
        if (owner == null || !owner.getExtendedTypes().isEmpty() || !owner.getImplementedTypes().isEmpty()
                || !owner.getMethodsByName(call.getNameAsString()).isEmpty()
                || owner.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("Accessors")
                        || a.getName().getIdentifier().equals("Getter") && a.toString().contains("NONE"))) return;
        var unit = owner.findCompilationUnit().orElseThrow();
        for (var field : owner.getFields()) {
            if (field.isStatic() || field.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("Accessors"))
                    || field.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("Getter") && a.toString().contains("NONE"))) continue;
            if (!lombok(field, unit, "Getter") && !lombok(owner, unit, "Data")
                    && !lombok(owner, unit, "Getter") && !lombok(owner, unit, "Value")) continue;
            for (var variable : field.getVariables()) {
                String name = variable.getNameAsString();
                String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                String expected = expectedGetter(owner, variable);
                if (expected == null) continue;
                if ((call.getNameAsString().equals("get" + suffix) || call.getNameAsString().equals("is" + suffix))
                        && !call.getNameAsString().equals(expected)) {
                    problems.add("Source accessor contract: " + owner.getNameAsString() + "." + call.getNameAsString()
                            + "() is not declared; Lombok field " + name + " (" + variable.getType()
                            + ") exposes " + expected + "(). Use the source-proven accessor.");
                }
            }
        }
    }

    private String expectedGetter(ClassOrInterfaceDeclaration owner, VariableDeclarator variable) {
        if (contextWarnings.stream().anyMatch(w -> w.toLowerCase(java.util.Locale.ROOT).contains("lombok"))) return null;
        String path = sourcePaths.get(owner.getFullyQualifiedName().orElse(""));
        Map<String, String> settings = new java.util.LinkedHashMap<>();
        if (path != null) {
            var configs = resources.stream().filter(resource -> resource.kind().equals("LOMBOK_CONFIGURATION"))
                    .filter(resource -> path.startsWith(resource.filePath().substring(0,
                            resource.filePath().length() - "lombok.config".length())))
                    .sorted(java.util.Comparator.comparingInt((com.greytest.dto.agent.GenerationContextDtos.ResourceEvidenceDto r)
                            -> r.filePath().length()).reversed()).toList();
            List<com.greytest.dto.agent.GenerationContextDtos.ResourceEvidenceDto> applicable = new ArrayList<>();
            for (var config : configs) {
                applicable.add(config);
                if (config.content().lines().anyMatch(line -> line.matches("config\\.stopBubbling\\s*=\\s*true"))) break;
            }
            java.util.Collections.reverse(applicable);
            for (var config : applicable) config.content().lines().forEach(line -> {
                if (line.startsWith("clear ")) settings.remove(line.substring(6).trim());
                else if (line.contains("=")) {
                    String[] pair = line.split("=", 2); settings.put(pair[0].trim(), pair[1].trim());
                }
            });
        }
        // Accessors tùy biến cần contract riêng; không đoán tên từ convention mặc định.
        if (settings.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith("lombok.accessors.")
                && !entry.getKey().equals("lombok.accessors.chain"))) return null;
        String name = variable.getNameAsString();
        boolean bool = variable.getType().asString().equals("boolean")
                && !"true".equalsIgnoreCase(settings.get("lombok.getter.noIsPrefix"));
        return bool && name.matches("is[A-Z].*") ? name
                : (bool ? "is" : "get") + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private void reportMismatch(String method, Type expected, CompilationUnit expectedUnit,
            Type actual, CompilationUnit actualUnit, List<String> problems) {
        if (actual == null || !actual.isClassOrInterfaceType() || !expected.isClassOrInterfaceType()) return;
        var expectedClass = expected.asClassOrInterfaceType();
        var actualClass = actual.asClassOrInterfaceType();
        String expectedName = qualifiedType(expectedClass, expectedUnit);
        String actualName = qualifiedType(actualClass, actualUnit);
        var expectedDeclaration = declarations.get(expectedName);
        var actualDeclaration = declarations.get(actualName);
        // Hai class source khác nhau và actual chỉ kế thừa Object thì không thể gán cho nhau.
        if (expectedDeclaration != null && actualDeclaration != null && !expectedName.equals(actualName)
                && !expectedDeclaration.isInterface() && !actualDeclaration.isInterface()
                && actualDeclaration.getExtendedTypes().isEmpty()) {
            problems.add("Source type contract for " + method + ": expected " + expectedName + ", got " + actualName
                    + ". Use the declared production parameter/return type; these source classes are not assignable.");
            return;
        }
        if (expectedClass.getTypeArguments().filter(arguments -> !arguments.isEmpty()).isEmpty()
                || actualClass.getTypeArguments().filter(arguments -> !arguments.isEmpty()).isEmpty()) return;
        String raw = qualifiedType(expectedClass, expectedUnit);
        if (raw == null || !raw.equals(qualifiedType(actualClass, actualUnit))) return;
        String expectedType = concreteType(expected, expectedUnit);
        String actualType = concreteType(actual, actualUnit);
        if (expectedType != null && actualType != null && !expectedType.equals(actualType)) {
            problems.add("Source generic contract for " + method + ": expected " + expectedType + ", got "
                    + actualType + ". Concrete generic arguments are invariant; copy exact types from production.");
        }
    }

    private String concreteType(Type type, CompilationUnit unit) {
        if (!type.isClassOrInterfaceType()) return null;
        var classType = type.asClassOrInterfaceType();
        String raw = qualifiedType(classType, unit);
        if (raw == null) return null;
        if (classType.getTypeArguments().isEmpty()) return raw;
        List<String> arguments = new ArrayList<>();
        for (Type argument : classType.getTypeArguments().orElseThrow()) {
            String resolved = concreteType(argument, unit);
            if (resolved == null) return null;
            arguments.add(resolved);
        }
        return raw + "<" + String.join(",", arguments) + ">";
    }

    Type expressionType(Expression expression, CompilationUnit test) {
        if (insideAnonymousClass(expression)) return null;
        if (expression.isObjectCreationExpr()) return expression.asObjectCreationExpr().getType();
        if (expression.isEnclosedExpr()) return expressionType(expression.asEnclosedExpr().getInner(), test);
        String name;
        if (expression.isNameExpr()) name = expression.asNameExpr().getNameAsString();
        else if (expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getScope().isThisExpr()) {
            if (expression.asFieldAccessExpr().getScope().asThisExpr().getTypeName().isPresent()) return null;
            name = expression.asFieldAccessExpr().getNameAsString();
        } else return null;
        var method = expression.findAncestor(MethodDeclaration.class);
        if (method.isPresent() && !expression.isFieldAccessExpr()) {
            var namedVariables = method.get().findAll(VariableDeclarator.class).stream()
                    .filter(variable -> variable.getNameAsString().equals(name))
                    .filter(variable -> variable.findAncestor(MethodDeclaration.class).equals(method)).toList();
            // Scope for/try/lambda cần phân giải đầy đủ; bỏ qua thay vì gán nhầm type của field.
            if (namedVariables.stream().anyMatch(variable -> variable.getParentNode()
                    .flatMap(parent -> parent.getParentNode()).filter(ExpressionStmt.class::isInstance).isEmpty())) return null;
            if (method.get().findAll(Parameter.class).stream().anyMatch(parameter -> parameter.getNameAsString().equals(name)
                    && !method.get().getParameters().contains(parameter))) return null;
            var locals = namedVariables.stream()
                    .filter(variable -> variable.getBegin().isPresent() && expression.getBegin().isPresent()
                            && variable.getBegin().orElseThrow().isBefore(expression.getBegin().orElseThrow()))
                    .filter(variable -> variable.findAncestor(BlockStmt.class)
                            .filter(block -> block.isAncestorOf(expression)).isPresent()).toList();
            if (locals.size() == 1) return locals.get(0).getType();
            if (locals.size() > 1) return null;
            var parameter = method.get().getParameters().stream().filter(p -> p.getNameAsString().equals(name)).findFirst();
            if (parameter.isPresent()) return parameter.get().getType();
        }
        return expression.findAncestor(ClassOrInterfaceDeclaration.class).stream()
                .flatMap(owner -> owner.getFields().stream()).flatMap(field -> field.getVariables().stream())
                .filter(variable -> variable.getNameAsString().equals(name))
                .map(VariableDeclarator::getType).findFirst().orElse(null);
    }

    String qualifiedType(ClassOrInterfaceType type, CompilationUnit unit) {
        String name = type.getNameWithScope();
        if (name.contains(".") && Character.isLowerCase(name.charAt(0))) return name;
        if (unit.findAll(com.github.javaparser.ast.type.TypeParameter.class).stream()
                .anyMatch(parameter -> parameter.getNameAsString().equals(name))) return null;
        if (unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .anyMatch(local -> local.getNameAsString().equals(name)
                        && (local.getParentNode().filter(CompilationUnit.class::isInstance).isEmpty()
                        || local.getFullyQualifiedName().filter(declarations::containsKey).isEmpty()))) return null;
        var explicit = unit.getImports().stream().filter(imported -> !imported.isStatic() && !imported.isAsterisk())
                .map(imported -> imported.getNameAsString()).filter(imported -> imported.endsWith("." + name)).toList();
        if (explicit.size() == 1) return explicit.get(0);
        String pkg = unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString() + ".").orElse("");
        if (declarations.containsKey(pkg + name)) return pkg + name;
        if (JAVA_LANG_TYPES.contains(name)) return "java.lang." + name;
        var wildcard = unit.getImports().stream().filter(imported -> !imported.isStatic() && imported.isAsterisk())
                .map(imported -> imported.getNameAsString() + "." + name).filter(declarations::containsKey).toList();
        return wildcard.size() == 1 ? wildcard.get(0) : null;
    }
}
