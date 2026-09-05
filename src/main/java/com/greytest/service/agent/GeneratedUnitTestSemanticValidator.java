package com.greytest.service.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.service.agent.ProjectJavaVersionDetector.TestFramework;

/**
 * Kiểm tra các lỗi ngữ nghĩa có thể chứng minh trực tiếp từ source production và test được sinh.
 */
public final class GeneratedUnitTestSemanticValidator {

    private GeneratedUnitTestSemanticValidator() {
    }

    public static Optional<String> validate(UnitTestContextDto context, UnitTestResponseDto response) {
        return validate(context, response, null);
    }

    public static Optional<String> validate(
            UnitTestContextDto context,
            UnitTestResponseDto response,
            TestFramework framework) {
        if (context == null || response == null || response.unitTests() == null) return Optional.empty();
        Map<Long, MethodContextDto> methodsByCaseId = methodsByCaseId(context);
        List<String> problems = new ArrayList<>();
        for (GeneratedUnitTestDto test : response.unitTests()) {
            if (test == null || test.sourceCode() == null) continue;
            MethodContextDto productionMethod = methodsByCaseId.get(test.caseId());
            validateFrameworkCompatibility(test, framework, problems);
            validateMockedEnums(context, test, problems);
            validateContentCast(test, problems);
            validateMultipartAssumption(productionMethod, test, problems);
            validateNullSwitchExpectation(productionMethod, test, problems);
        }
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join(" ", problems));
    }

    private static void validateMockedEnums(
            UnitTestContextDto context,
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        String source = generatedTest.sourceCode();
        context.classes().stream()
                .filter(javaClass -> "ENUM".equalsIgnoreCase(javaClass.classType()))
                .map(javaClass -> javaClass.className())
                .filter(name -> source.matches("(?s).*\\bmock\\s*\\(\\s*(?:[A-Za-z_$][\\w$]*\\.)*"
                        + java.util.regex.Pattern.quote(name) + "\\s*\\.class\\s*\\).*"))
                .findFirst()
                .ifPresent(name -> problems.add("Do not mock enum " + name
                        + "; enum values are final and an exhaustive switch default may be unreachable."));
    }

    private static void validateContentCast(GeneratedUnitTestDto generatedTest, List<String> problems) {
        String source = generatedTest.sourceCode();
        boolean usesJavaMailPart = source.matches(
                "(?s).*(?:javax|jakarta)\\.mail\\.(?:BodyPart|Part|internet\\.MimeBodyPart).*")
                || source.matches("(?s).*import\\s+(?:javax|jakarta)\\.mail(?:\\.internet)?\\.\\*\\s*;.*"
                        + "\\b(?:BodyPart|Part|MimeBodyPart)\\s+[A-Za-z_$][\\w$]*.*");
        if (usesJavaMailPart
                && source.matches("(?s).*\\(\\s*byte\\s*\\[\\s*]\\s*\\)\\s*[^;]*\\.getContent\\s*\\(\\s*\\).*")) {
            problems.add("JavaMail Part.getContent() may return InputStream; read part.getInputStream() into bytes "
                    + "instead of casting getContent() to byte[].");
        }
    }

    private static void validateFrameworkCompatibility(
            GeneratedUnitTestDto generatedTest,
            TestFramework framework,
            List<String> problems) {
        String source = generatedTest.sourceCode();
        if (source.matches("(?s).*\\bisNull\\s*\\(\\s*[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*\\.class\\s*\\).*")) {
            problems.add("Mockito ArgumentMatchers.isNull(Class) is not available in modern Mockito; use isNull() or ArgumentMatchers.<Type>isNull().");
        }
        if (framework == null) return;
        if (framework == TestFramework.JUNIT4 && source.contains("org.junit.jupiter")) {
            problems.add("The project uses JUnit 4; remove every org.junit.jupiter import and use JUnit 4 consistently.");
        } else if (framework == TestFramework.JUNIT5
                && java.util.regex.Pattern.compile("org\\.junit\\.(?!jupiter(?:\\.|;)|platform(?:\\.|;))")
                        .matcher(source).find()) {
            problems.add("The project uses JUnit 5; remove JUnit 4 test/lifecycle imports and use org.junit.jupiter consistently.");
        }
        if (framework != TestFramework.JUNIT4) return;
        Optional<CompilationUnit> parsed = parseCompilationUnit(source);
        if (parsed.isEmpty()) return;
        boolean importsJunit4Assert = parsed.get().getImports().stream()
                .anyMatch(value -> value.getNameAsString().startsWith("org.junit.Assert"))
                || source.contains("org.junit.Assert.");
        boolean usesJunit5MessageOrder = importsJunit4Assert
                && parsed.get().findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().startsWith("assert"))
                .anyMatch(GeneratedUnitTestSemanticValidator::hasTrailingJunit5Message);
        if (usesJunit5MessageOrder) {
            problems.add("JUnit 4 assertion messages must be the first argument, not the last argument.");
        }
    }

    private static boolean hasTrailingJunit5Message(MethodCallExpr call) {
        if (!java.util.Set.of(
                "assertEquals", "assertNotEquals", "assertSame", "assertNotSame", "assertArrayEquals",
                "assertTrue", "assertFalse", "assertNull", "assertNotNull").contains(call.getNameAsString())) {
            return false;
        }
        int size = call.getArguments().size();
        if (size == 0 || !call.getArgument(size - 1).isStringLiteralExpr()) return false;
        if (size >= 3) return true;
        return size == 2 && switch (call.getNameAsString()) {
            case "assertTrue", "assertFalse", "assertNull", "assertNotNull" -> true;
            default -> false;
        };
    }

    private static void validateMultipartAssumption(
            MethodContextDto productionMethod,
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        if (productionMethod == null || productionMethod.sourceCode() == null) return;
        Optional<MethodDeclaration> parsedProduction = parseMethod(productionMethod.sourceCode());
        Optional<CompilationUnit> parsedTest = parseCompilationUnit(generatedTest.sourceCode());
        if (parsedProduction.isEmpty() || parsedTest.isEmpty()
                || !usesMultipartHelper(parsedProduction.get())) return;
        Optional<MethodDeclaration> targetTest = findTestMethod(parsedTest.get(), generatedTest.testMethodName());
        if (targetTest.isEmpty()) return;

        boolean assumesPlainStringContent = targetTest.get().findAll(InstanceOfExpr.class).stream()
                .filter(expression -> "String".equals(expression.getType().asString()))
                .map(InstanceOfExpr::getExpression)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(call -> "getContent".equals(call.getNameAsString()))
                .anyMatch(call -> call.getScope()
                        .map(scope -> scope.isNameExpr()
                                && isVariableOfType(targetTest.get(), scope.asNameExpr().getNameAsString(), "MimeMessage"))
                        .orElse(false));
        if (assumesPlainStringContent) {
            problems.add("Production creates MimeMessageHelper with multipart=true; inspect attachment disposition "
                    + "instead of expecting MimeMessage.getContent() to be String.");
        }
    }

    private static boolean usesMultipartHelper(MethodDeclaration method) {
        List<ObjectCreationExpr> helpers = method.findAll(ObjectCreationExpr.class).stream()
                .filter(creation -> creation.getType().getNameAsString().equals("MimeMessageHelper"))
                .toList();
        return !helpers.isEmpty() && helpers.stream().allMatch(creation ->
                creation.getArguments().size() >= 2
                        && creation.getArgument(1).isBooleanLiteralExpr()
                        && creation.getArgument(1).asBooleanLiteralExpr().getValue());
    }

    private static void validateNullSwitchExpectation(
            MethodContextDto productionMethod,
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        if (productionMethod == null || productionMethod.sourceCode() == null) return;
        Optional<MethodDeclaration> parsedProduction = parseMethod(productionMethod.sourceCode());
        Optional<CompilationUnit> parsedTest = parseCompilationUnit(generatedTest.sourceCode());
        if (parsedProduction.isEmpty() || parsedTest.isEmpty()) return;
        Optional<MethodDeclaration> targetTest = findTestMethod(parsedTest.get(), generatedTest.testMethodName());
        if (targetTest.isEmpty()) return;

        List<SwitchTarget> switches = new ArrayList<>();
        parsedProduction.get().findAll(SwitchStmt.class).forEach(statement -> switches.add(
                new SwitchTarget(statement, statement.getSelector(), statement.getEntries())));
        parsedProduction.get().findAll(com.github.javaparser.ast.expr.SwitchExpr.class).forEach(expression -> switches.add(
                new SwitchTarget(expression, expression.getSelector(), expression.getEntries())));

        for (SwitchTarget switchTarget : switches) {
            String selector = switchTarget.selector().toString();
            int parameterIndex = parameterIndex(parsedProduction.get(), selector);
            if (parameterIndex < 0
                    || handlesNullBeforeSwitch(switchTarget.node(), selector)
                    || hasNullCase(switchTarget.entries())) continue;
            if (expectsIllegalArgumentForNullCall(
                    targetTest.get(), productionMethod, parameterIndex)) {
                problems.add("Calling " + productionMethod.methodName()
                        + " with null reaches a switch selector and throws NullPointerException before default; "
                        + "do not expect IllegalArgumentException.");
                return;
            }
        }
    }

    private static boolean expectsIllegalArgumentForNullCall(
            MethodDeclaration testMethod,
            MethodContextDto productionMethod,
            int parameterIndex) {
        boolean junit4Expected = testMethod.getAnnotations().stream()
                .filter(NormalAnnotationExpr.class::isInstance)
                .map(NormalAnnotationExpr.class::cast)
                .filter(annotation -> "Test".equals(annotation.getName().getIdentifier()))
                .flatMap(annotation -> annotation.getPairs().stream())
                .anyMatch(pair -> "expected".equals(pair.getNameAsString())
                        && pair.getValue().toString().contains("IllegalArgumentException.class"));
        if (junit4Expected && containsTargetNullCall(testMethod, testMethod, productionMethod, parameterIndex)) {
            return true;
        }

        return testMethod.findAll(MethodCallExpr.class).stream()
                .filter(call -> "assertThrows".equals(call.getNameAsString()) && call.getArguments().size() >= 2)
                .filter(call -> call.getArgument(0).toString().contains("IllegalArgumentException.class"))
                .anyMatch(assertion -> containsTargetNullCall(
                        testMethod, assertion.getArgument(1), productionMethod, parameterIndex));
    }

    private static boolean containsTargetNullCall(
            MethodDeclaration testMethod,
            Node assertionBody,
            MethodContextDto productionMethod,
            int parameterIndex) {
        return assertionBody.findAll(MethodCallExpr.class).stream()
                .filter(call -> productionMethod.methodName().equals(call.getNameAsString()))
                .filter(call -> call.getArguments().size() > parameterIndex)
                .filter(call -> call.getArgument(parameterIndex).isNullLiteralExpr())
                .anyMatch(call -> isProductionReceiver(testMethod, call, productionMethod.classQualifiedName()));
    }

    private static boolean isProductionReceiver(
            MethodDeclaration testMethod,
            MethodCallExpr call,
            String classQualifiedName) {
        if (call.getScope().isEmpty() || classQualifiedName == null) return false;
        String expectedSimpleName = classQualifiedName.substring(classQualifiedName.lastIndexOf('.') + 1);
        Expression scope = call.getScope().get();
        if (scope.isObjectCreationExpr()) {
            return expectedSimpleName.equals(scope.asObjectCreationExpr().getType().getNameAsString());
        }
        if (scope.isNameExpr()) {
            String name = scope.asNameExpr().getNameAsString();
            return expectedSimpleName.equals(name)
                    || isVariableOfType(testMethod, name, expectedSimpleName);
        }
        return scope.isFieldAccessExpr()
                && scope.asFieldAccessExpr().getScope().isThisExpr()
                && isVariableOfType(testMethod, scope.asFieldAccessExpr().getNameAsString(), expectedSimpleName);
    }

    private static boolean isVariableOfType(
            MethodDeclaration testMethod,
            String variableName,
            String expectedSimpleName) {
        boolean local = testMethod.findAll(VariableDeclarator.class).stream()
                .filter(variable -> variableName.equals(variable.getNameAsString()))
                .map(variable -> variable.getType().asString())
                .map(type -> type.substring(type.lastIndexOf('.') + 1))
                .anyMatch(expectedSimpleName::equals);
        if (local) return true;
        return testMethod.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .stream()
                .flatMap(type -> type.getFields().stream())
                .flatMap(field -> field.getVariables().stream())
                .filter(variable -> variableName.equals(variable.getNameAsString()))
                .map(variable -> variable.getType().asString())
                .map(type -> type.substring(type.lastIndexOf('.') + 1))
                .anyMatch(expectedSimpleName::equals);
    }

    private static boolean hasNullCase(List<SwitchEntry> entries) {
        return entries.stream().flatMap(entry -> entry.getLabels().stream())
                .anyMatch(NullLiteralExpr.class::isInstance);
    }

    private static Map<Long, MethodContextDto> methodsByCaseId(UnitTestContextDto context) {
        Map<Long, MethodContextDto> methodsById = new HashMap<>();
        context.classes().forEach(javaClass -> javaClass.methods()
                .forEach(method -> methodsById.put(method.id(), method)));
        Map<Long, BusinessRuleContextDto> rulesById = new HashMap<>();
        context.approvedBusinessRules().forEach(rule -> rulesById.put(rule.id(), rule));
        Map<Long, TestPlanContextItemDto> plansById = new HashMap<>();
        context.approvedTestPlans().forEach(plan -> plansById.put(plan.id(), plan));
        Map<Long, MethodContextDto> result = new HashMap<>();
        context.approvedTestCases().forEach(testCase -> {
            TestPlanContextItemDto plan = plansById.get(testCase.testPlanId());
            BusinessRuleContextDto rule = plan == null ? null : rulesById.get(plan.businessRuleId());
            MethodContextDto method = rule == null ? null : methodsById.get(rule.methodId());
            if (method != null) result.put(testCase.id(), method);
        });
        return result;
    }

    private static Optional<MethodDeclaration> findTestMethod(CompilationUnit source, String methodName) {
        if (methodName == null) return Optional.empty();
        return source.findAll(MethodDeclaration.class).stream()
                .filter(method -> methodName.equals(method.getNameAsString()))
                .findFirst();
    }

    private static int parameterIndex(MethodDeclaration method, String selector) {
        for (int index = 0; index < method.getParameters().size(); index++) {
            if (method.getParameter(index).getNameAsString().equals(selector)) return index;
        }
        return -1;
    }

    private static boolean handlesNullBeforeSwitch(Node switchNode, String selector) {
        List<com.github.javaparser.ast.stmt.Statement> preceding = precedingSiblingStatements(switchNode);
        boolean explicitGuard = preceding.stream()
                .filter(IfStmt.class::isInstance)
                .map(IfStmt.class::cast)
                .filter(statement -> isExactNullCondition(statement.getCondition(), selector))
                .anyMatch(statement -> exitsBeforeSwitch(statement.getThenStmt()));
        boolean nullCheckCall = preceding.stream()
                .filter(com.github.javaparser.ast.stmt.ExpressionStmt.class::isInstance)
                .map(com.github.javaparser.ast.stmt.ExpressionStmt.class::cast)
                .map(com.github.javaparser.ast.stmt.ExpressionStmt::getExpression)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(call -> "notNull".equals(call.getNameAsString()))
                .filter(call -> call.getScope().map(scope -> "Assert".equals(scope.toString())
                        || "org.springframework.util.Assert".equals(scope.toString())).orElse(false))
                .anyMatch(call -> !call.getArguments().isEmpty()
                        && selector.equals(call.getArgument(0).toString()));
        return explicitGuard || nullCheckCall;
    }

    private static List<com.github.javaparser.ast.stmt.Statement> precedingSiblingStatements(Node switchNode) {
        List<com.github.javaparser.ast.stmt.Statement> preceding = new ArrayList<>();
        Node container = switchNode;
        while (container.getParentNode().isPresent()
                && !(container instanceof MethodDeclaration)) {
            Node parent = container.getParentNode().get();
            if (parent instanceof com.github.javaparser.ast.stmt.BlockStmt block
                    && container instanceof com.github.javaparser.ast.stmt.Statement statement) {
                int index = block.getStatements().indexOf(statement);
                if (index > 0) preceding.addAll(block.getStatements().subList(0, index));
            }
            container = parent;
        }
        return preceding;
    }

    private static boolean exitsBeforeSwitch(Node statement) {
        if (statement instanceof com.github.javaparser.ast.stmt.ReturnStmt
                || statement instanceof com.github.javaparser.ast.stmt.ThrowStmt) return true;
        if (!(statement instanceof com.github.javaparser.ast.stmt.BlockStmt block)
                || block.getStatements().isEmpty()) return false;
        Node last = block.getStatement(block.getStatements().size() - 1);
        return last instanceof com.github.javaparser.ast.stmt.ReturnStmt
                || last instanceof com.github.javaparser.ast.stmt.ThrowStmt;
    }

    private static boolean isExactNullCondition(Expression condition, String selector) {
        Expression unwrapped = condition;
        while (unwrapped.isEnclosedExpr()) unwrapped = unwrapped.asEnclosedExpr().getInner();
        if (!unwrapped.isBinaryExpr()
                || unwrapped.asBinaryExpr().getOperator()
                        != com.github.javaparser.ast.expr.BinaryExpr.Operator.EQUALS) return false;
        Expression left = unwrapped.asBinaryExpr().getLeft();
        Expression right = unwrapped.asBinaryExpr().getRight();
        return isSelector(left, selector) && right.isNullLiteralExpr()
                || left.isNullLiteralExpr() && isSelector(right, selector);
    }

    private static boolean isSelector(Expression expression, String selector) {
        Expression unwrapped = expression;
        while (unwrapped.isEnclosedExpr()) unwrapped = unwrapped.asEnclosedExpr().getInner();
        return unwrapped.isNameExpr() && selector.equals(unwrapped.asNameExpr().getNameAsString());
    }

    private static Optional<CompilationUnit> parseCompilationUnit(String source) {
        var result = javaParser().parse(source);
        return result.isSuccessful() ? result.getResult() : Optional.empty();
    }

    private static Optional<MethodDeclaration> parseMethod(String source) {
        var result = javaParser().parseMethodDeclaration(source);
        return result.isSuccessful() ? result.getResult() : Optional.empty();
    }

    private static JavaParser javaParser() {
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    }

    private record SwitchTarget(Node node, Expression selector, List<SwitchEntry> entries) {
    }
}
