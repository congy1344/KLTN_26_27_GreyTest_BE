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
import com.github.javaparser.ast.comments.Comment;
import java.util.HashSet;
import java.util.Set;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.greytest.dto.agent.GenerationContextDtos.BusinessRuleContextDto;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.dto.agent.GenerationContextDtos.TestPlanContextItemDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.service.agent.ProjectJavaVersionDetector.TestFramework;
import com.greytest.service.agent.ProjectJavaVersionDetector.MockitoVersionInfo;

/**
 * Kiểm tra tính hợp lệ kỹ thuật của test; không dùng hành vi source làm kỳ vọng nghiệp vụ.
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
        return validate(context, response, framework, null);
    }

    public static Optional<String> validate(UnitTestContextDto context, UnitTestResponseDto response,
            TestFramework framework, MockitoVersionInfo mockitoVersion) {
        if (context == null || response == null) return Optional.empty();
        Map<Long, MethodContextDto> methodsByCaseId = methodsByCaseId(context);
        List<String> problems = new ArrayList<>();
        validateContext(context).ifPresent(problems::add);
        validateResponseCoverage(context, response, problems);
        if (response.unitTests() == null) {
            return problems.isEmpty() ? Optional.empty() : Optional.of(String.join(" ", problems));
        }
        for (GeneratedUnitTestDto test : response.unitTests()) {
            if (test == null) continue;
            if (test.sourceCode() == null) {
                problems.add("Test case " + test.caseId() + " must contain valid Java test source.");
                continue;
            }
            var parsedSource = javaParser().parse(test.sourceCode());
            if (!parsedSource.isSuccessful() || parsedSource.getResult().isEmpty()) {
                String detail = parsedSource.getProblems().stream().limit(2)
                        .map(problem -> problem.getVerboseMessage()).collect(java.util.stream.Collectors.joining("; "));
                problems.add("Test case " + test.caseId() + " must contain valid Java test source. "
                        + detail.substring(0, Math.min(detail.length(), 1_000)));
                continue;
            }
            MethodContextDto productionMethod = methodsByCaseId.get(test.caseId());
            validateFrameworkCompatibility(test, framework, problems);
            validateZeroSkippingPolicy(test, problems);
            validatePlaceholderHelpers(test, problems);
            validateJavaAssertions(test, problems);
            validateDateTimeMethods(context, test, problems);
            validateUnsupportedTestInfrastructure(test, problems);
            validateNoLenientStubbing(test, problems);
            validateUnusedFixtureStubs(context, productionMethod, test, problems);
            validateRemovedMockitoApis(test, mockitoVersion, problems);
            validateMockitoRunnerOrExtension(test, framework, problems);
            validateJUnit5AssertionOrder(test, framework, problems);
            parseCompilationUnit(test.sourceCode()).ifPresent(source ->
                    problems.addAll(GeneratedMockitoImportValidator.validate(source)));
            validateAssertionImports(test, framework, problems);
            validateMockitoVerifyNoInteractions(test, problems);
            validateApprovedNegativeInteractions(context, test, parsedSource.getResult().orElseThrow(), problems);
            validateMockitoMatcherConsistency(test, problems);
            validateRepositorySaveMatcherType(test, problems);
            validateMockitoSettingsSyntax(test, problems);
            parseCompilationUnit(test.sourceCode()).ifPresent(source ->
                    problems.addAll(GeneratedTestApiValidator.validate(context, source, test.caseId())));
            parseCompilationUnit(test.sourceCode()).ifPresent(source ->
                    problems.addAll(GeneratedFixtureMutationValidator.validate(context, productionMethod, source)));
            parseCompilationUnit(test.sourceCode()).ifPresent(source ->
                    problems.addAll(GeneratedStreamInvocationValidator.validate(context, productionMethod, test.caseId(), source)));
            parseCompilationUnit(test.sourceCode()).ifPresent(source ->
                    problems.addAll(GeneratedRepositoryFixtureValidator.validate(context, productionMethod, test.caseId(), source)));
            parseCompilationUnit(test.sourceCode()).ifPresent(source ->
                    problems.addAll(GeneratedFixtureContractValidator.validate(context, productionMethod, test.caseId(), source)));
            if (productionMethod != null) parseCompilationUnit(test.sourceCode()).ifPresent(source ->
                    problems.addAll(GeneratedBooleanAssertionValidator.validate(context,
                            productionMethod.classQualifiedName(), productionMethod.methodName(), source)));
            validateImportsFromContext(context, productionMethod, test, problems);
            validateMockedEnums(context, test, problems);
            validateEnumReferences(context, test, problems);
            validateEnumFixtureValues(context, productionMethod, test, problems);
            validateProductionMethodCalls(context, productionMethod, test, problems);
            validateInjectMocksStubbing(test, problems);
            validateSutDependencyMocks(context, productionMethod, test, problems);
            validatePrivateMethodCalls(context, test, problems);
            validateAnonymousSutSubclassOverrides(productionMethod, test, problems);
            validateContentCast(test, problems);
            validateTransactionTemplateStubbing(productionMethod, test, problems);
            validateExceptionVerificationPlacement(productionMethod, test, problems);
        }
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join(" ", problems));
    }

    /** Chặn Test Case đã duyệt trỏ tới method không còn trong source trước khi gọi LLM. */
    public static Optional<String> validateContext(UnitTestContextDto context) {
        if (context == null || context.classes() == null || context.approvedTestCases() == null) {
            return Optional.empty();
        }
        Map<Long, MethodContextDto> methods = methodsByCaseId(context);
        for (var testCase : context.approvedTestCases()) {
            MethodContextDto method = methods.get(testCase.id());
            if (method == null) {
                return Optional.of("The approved Test Case " + testCase.id()
                        + " has no matching production method in the current generation context. "
                        + "Refresh its Business Rule, Test Plan and Test Case before generation.");
            }
            ClassContextDto javaClass = context.classes().stream()
                    .filter(clazz -> method.classQualifiedName().equals(clazz.qualifiedName()))
                    .findFirst().orElse(null);
            if (javaClass == null) continue;
            Optional<ClassOrInterfaceDeclaration> declaration = serviceDeclaration(javaClass);
            if (declaration.isPresent() && declaration.get().getExtendedTypes().isEmpty()
                    && declaration.get().getMethods().stream()
                    .noneMatch(candidate -> candidate.getNameAsString().equals(method.methodName()))) {
                return Optional.of("The approved Test Case " + testCase.id() + " references '"
                        + method.methodName() + "', which does not exist in the current production source of "
                        + javaClass.className() + ". Refresh its Business Rule, Test Plan and Test Case before generation.");
            }
        }
        return Optional.empty();
    }

    private static void validateResponseCoverage(
            UnitTestContextDto context,
            UnitTestResponseDto response,
            List<String> problems) {
        if (context.approvedTestCases() == null) return;

        Set<Long> expected = new HashSet<>();
        context.approvedTestCases().stream()
                .filter(java.util.Objects::nonNull)
                .map(testCase -> testCase.id())
                .forEach(expected::add);

        Set<Long> actual = new HashSet<>();
        Set<Long> duplicates = new HashSet<>();
        if (response.unitTests() != null) {
            response.unitTests().forEach(test -> {
                if (test == null) {
                    problems.add("Unit Test response must not contain null items.");
                    return;
                }
                if (test.caseId() == null) {
                    problems.add("Unit Test response items must contain a non-null case_id.");
                    return;
                }
                if (!actual.add(test.caseId())) duplicates.add(test.caseId());
            });
        }

        if (response.unitTests() == null && !expected.isEmpty()) {
            problems.add("Unit Test response must contain exactly one generated test for every approved Test Case; response unit_tests is missing.");
            return;
        }
        if (response.unitTests() != null && response.unitTests().size() != expected.size()) {
            problems.add("Unit Test response must contain exactly one generated test for every approved Test Case; expected "
                    + expected.size() + " item(s) but received " + response.unitTests().size() + ".");
        }
        if (!duplicates.isEmpty()) {
            problems.add("Unit Test response contains duplicate case IDs: " + duplicates + ".");
        }
        Set<Long> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            problems.add("Unit Test response is missing case IDs: " + missing
                    + "; it must contain exactly one generated test for every approved Test Case.");
        }
        Set<Long> unexpected = new HashSet<>(actual);
        unexpected.removeAll(expected);
        if (!unexpected.isEmpty()) {
            problems.add("Unit Test response contains unexpected case IDs: " + unexpected + ".");
        }
    }

    private static void validateJavaAssertions(
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        Optional<CompilationUnit> parsed = parseCompilationUnit(generatedTest.sourceCode());
        if (parsed.isPresent() && !parsed.get().findAll(AssertStmt.class).isEmpty()) {
            problems.add("Do not use the Java assert statement; use standard JUnit assertions instead.");
        }
    }

    private static void validateAssertionImports(GeneratedUnitTestDto test, TestFramework framework, List<String> problems) {
        if (framework == null) return;
        parseCompilationUnit(test.sourceCode()).ifPresent(source -> {
            Set<String> assertions = Set.of("assertTrue", "assertFalse", "assertEquals", "assertNotEquals", "assertNull", "assertNotNull",
                    "assertSame", "assertNotSame", "assertArrayEquals", "assertThrows", "assertThrowsExactly", "assertDoesNotThrow",
                    "assertInstanceOf", "assertIterableEquals", "assertLinesMatch", "assertAll", "assertTimeout", "assertTimeoutPreemptively", "fail");
            Set<String> missing = new java.util.TreeSet<>();
            for (MethodCallExpr call : source.findAll(MethodCallExpr.class)) {
                String name = call.getNameAsString();
                if (call.getScope().isPresent() || !assertions.contains(name)) continue;
                boolean imported = source.getImports().stream().anyMatch(item -> item.isStatic()
                        && (item.isAsterisk() || item.getName().getIdentifier().equals(name)));
                if (imported) continue;
                Node node = call;
                boolean locallyAvailable = false;
                while (node.getParentNode().isPresent()) {
                    node = node.getParentNode().get();
                    if (node instanceof ClassOrInterfaceDeclaration owner
                            && (!owner.getMethodsByName(name).isEmpty() || !owner.getExtendedTypes().isEmpty())) {
                        locallyAvailable = true;
                        break;
                    }
                }
                // Không đoán API kế thừa hoặc wildcard thư viện; chỉ chặn lời gọi chắc chắn chưa có binding.
                if (!locallyAvailable) missing.add(name);
            }
            missing.forEach(name -> problems.add("Unqualified assertion '" + name + "' has no static import or declared/inherited helper. "
                    + "Add its correct static import or use a qualified API supported by the detected " + framework + " version; preserve the expected result."));
        });
    }

    private static void validateDateTimeMethods(
            UnitTestContextDto context, GeneratedUnitTestDto test, List<String> problems) {
        parseCompilationUnit(test.sourceCode()).ifPresent(source -> source.findAll(MethodCallExpr.class).stream()
                .filter(call -> "atStartOfDay".equals(call.getNameAsString()))
                .filter(call -> call.getScope()
                        .map(scope -> isLocalDateTimeExpression(scope, source, context)).orElse(false))
                .forEach(call -> problems.add("LocalDateTime has no atStartOfDay() method. "
                        + "Use LocalDate.atStartOfDay() or compare with LocalDateTime.now().")));
    }

    private static boolean isLocalDateTimeExpression(
            Expression expression, CompilationUnit source, UnitTestContextDto context) {
        if (expression.isNameExpr()) {
            String name = expression.asNameExpr().getNameAsString();
            return source.findAll(VariableDeclarator.class).stream()
                    .anyMatch(variable -> name.equals(variable.getNameAsString())
                            && "LocalDateTime".equals(simpleType(variable.getType().asString())));
        }
        if (expression.isFieldAccessExpr()) {
            FieldAccessExpr field = expression.asFieldAccessExpr();
            if ("this".equals(field.getScope().toString())) {
                return source.findAll(VariableDeclarator.class).stream()
                        .anyMatch(variable -> field.getNameAsString().equals(variable.getNameAsString())
                                && "LocalDateTime".equals(simpleType(variable.getType().asString())));
            }
            return isLocalDateTimeMember(field.getScope(), field.getNameAsString(), source, context);
        }
        if (!expression.isMethodCallExpr()) return false;
        MethodCallExpr call = expression.asMethodCallExpr();
        if ("atDate".equals(call.getNameAsString())) return true;
        if (call.getNameAsString().startsWith("get") && call.getScope().isPresent()
                && isLocalDateTimeMember(call.getScope().get(), call.getNameAsString(), source, context)) return true;
        if (Set.of("now", "of", "parse").contains(call.getNameAsString())
                && call.getScope().map(scope -> "LocalDateTime".equals(simpleType(scope.toString())))
                        .orElse(false)) return true;
        return Set.of("plusDays", "minusDays", "plusHours", "minusHours", "plusMinutes", "minusMinutes",
                "plusSeconds", "minusSeconds", "plusNanos", "minusNanos", "withYear", "withMonth",
                "withDayOfMonth", "withHour", "withMinute", "withSecond", "withNano")
                .contains(call.getNameAsString())
                && call.getScope().map(scope -> isLocalDateTimeExpression(scope, source, context)).orElse(false);
    }

    private static boolean isLocalDateTimeMember(
            Expression receiver, String member, CompilationUnit testSource, UnitTestContextDto context) {
        if (!receiver.isNameExpr() || context.classes() == null) return false;
        String receiverName = receiver.asNameExpr().getNameAsString();
        String declaredType = testSource.findAll(VariableDeclarator.class).stream()
                .filter(variable -> receiverName.equals(variable.getNameAsString()))
                .map(variable -> variable.getType().asString()).findFirst().orElse(null);
        if (declaredType == null) return false;
        List<ClassContextDto> matchingClasses = context.classes().stream()
                .filter(clazz -> declaredType.equals(clazz.qualifiedName())
                        || declaredType.equals(clazz.className()))
                .toList();
        if (matchingClasses.size() != 1 || matchingClasses.get(0).validationSourceCode() == null) return false;
        return parseCompilationUnit(matchingClasses.get(0).validationSourceCode())
                .map(declaration -> declaration.findAll(MethodDeclaration.class).stream()
                        .anyMatch(method -> member.equals(method.getNameAsString())
                                && "LocalDateTime".equals(simpleType(method.getType().asString())))
                        || declaration.findAll(VariableDeclarator.class).stream()
                                .anyMatch(field -> (member.equals(field.getNameAsString())
                                        || member.startsWith("get") && member.length() > 3
                                        && (Character.toLowerCase(member.charAt(3)) + member.substring(4))
                                                .equals(field.getNameAsString()))
                                        && "LocalDateTime".equals(simpleType(field.getType().asString()))))
                .orElse(false);
    }

    /** Chỉ kiểm tra vị trí verify trong test; không dùng interaction của source làm oracle. */
    private static void validateExceptionVerificationPlacement(MethodContextDto target, GeneratedUnitTestDto test, List<String> problems) {
        if (target == null) return;
        var source = parseCompilationUnit(test.sourceCode());
        if (source.isEmpty()) return;
        findTestMethod(source.get(), test.testMethodName()).ifPresent(method -> {
            for (MethodCallExpr act : method.findAll(MethodCallExpr.class)) {
                if (!target.methodName().equals(act.getNameAsString()) || !isProductionReceiver(method, act, target.classQualifiedName())) continue;
                boolean expectedAnnotation = method.getAnnotations().stream().filter(NormalAnnotationExpr.class::isInstance)
                        .map(NormalAnnotationExpr.class::cast).filter(a -> a.getName().getIdentifier().equals("Test"))
                        .flatMap(a -> a.getPairs().stream()).anyMatch(pair -> pair.getNameAsString().equals("expected"));
                boolean assertion = act.findAncestor(LambdaExpr.class).flatMap(lambda -> lambda.findAncestor(MethodCallExpr.class))
                        .map(call -> Set.of("assertThrows", "assertThrowsExactly").contains(call.getNameAsString())).orElse(false);
                boolean caught = act.findAncestor(com.github.javaparser.ast.stmt.TryStmt.class)
                        .filter(statement -> statement.getTryBlock().isAncestorOf(act) && !statement.getCatchClauses().isEmpty())
                        .map(statement -> statement.getTryBlock().findAll(MethodCallExpr.class).stream()
                                .anyMatch(call -> endsBefore(act, call) && isJUnitFailCall(call, source.get()))).orElse(false);
                if (!expectedAnnotation && !assertion && !caught) continue;
                for (var verification : method.findAll(MethodCallExpr.class)) {
                    var verify = verification.getScope().filter(Expression::isMethodCallExpr).map(Expression::asMethodCallExpr).orElse(null);
                    boolean chained = verify != null && isMockitoApiCall(verify, "verify", source.get());
                    boolean standalone = Set.of("verifyNoInteractions", "verifyZeroInteractions", "verifyNoMoreInteractions")
                            .contains(verification.getNameAsString())
                            && isMockitoApiCall(verification, verification.getNameAsString(), source.get());
                    if ((!chained && !standalone) || !endsBefore(act, verification)) continue;
                    if (!exceptionVerificationIsReachable(act, verification, method, expectedAnnotation)) {
                        problems.add("Verification " + verification + " cannot run after the expected exception; put the approved verify "
                                + "after assertThrows, in catch, or in a reachable finally block.");
                    }
                }
            }
        });
    }

    private static boolean isJUnitFailCall(MethodCallExpr call, CompilationUnit source) {
        if (!"fail".equals(call.getNameAsString())) return false;
        Set<String> owners = Set.of("org.junit.Assert", "org.junit.jupiter.api.Assertions");
        if (call.getScope().isPresent()) {
            String scope = call.getScope().get().toString();
            return owners.contains(scope) || source.getImports().stream().anyMatch(imported -> !imported.isStatic()
                    && owners.contains(imported.getNameAsString()) && imported.getName().getIdentifier().equals(scope));
        }
        return source.getImports().stream().anyMatch(imported -> imported.isStatic()
                && (owners.contains(imported.getNameAsString()) && imported.isAsterisk()
                    || owners.stream().anyMatch(owner -> (owner + ".fail").equals(imported.getNameAsString()))));
    }

    private static boolean exceptionVerificationIsReachable(MethodCallExpr act, MethodCallExpr verification,
            MethodDeclaration method, boolean expectedAnnotation) {
        if (verification.findAncestor(LambdaExpr.class).isPresent()) return false;
        var assertion = act.findAncestor(LambdaExpr.class).flatMap(lambda -> lambda.findAncestor(MethodCallExpr.class))
                .filter(call -> Set.of("assertThrows", "assertThrowsExactly").contains(call.getNameAsString()));
        if (assertion.isPresent()) return topLevelStatement(verification, method).isPresent()
                && endsBefore(assertion.get(), verification);
        var attempt = act.findAncestor(com.github.javaparser.ast.stmt.TryStmt.class)
                .filter(statement -> statement.getTryBlock().isAncestorOf(act));
        if (attempt.isEmpty()) return false;
        var statement = attempt.get();
        if (statement.getFinallyBlock().filter(block -> block.isAncestorOf(verification)
                && verification.findAncestor(BlockStmt.class).filter(block::equals).isPresent()).isPresent()) return true;
        if (expectedAnnotation) return false;
        return statement.getCatchClauses().stream().anyMatch(clause -> clause.getBody().isAncestorOf(verification)
                && verification.findAncestor(BlockStmt.class).filter(clause.getBody()::equals).isPresent())
                || topLevelStatement(verification, method).isPresent() && endsBefore(statement, verification);
    }

    private static boolean endsBefore(Node first, Node second) {
        return first.getRange().flatMap(firstRange -> second.getRange().map(secondRange ->
                firstRange.end.isBefore(secondRange.begin))).orElse(false);
    }

    /** Chỉ xác nhận lookup không có nhánh/side effect chưa biết đứng trước; không suy diễn luồng thiếu bằng chứng. */
    private static void validateUnsupportedTestInfrastructure(
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        Optional<CompilationUnit> parsed = parseCompilationUnit(generatedTest.sourceCode());
        if (parsed.isEmpty()) return;
        CompilationUnit cu = parsed.get();
        if (cu.findAll(AnnotationExpr.class).stream()
                .anyMatch(annotation -> "SpringBootTest".equals(annotation.getName().getIdentifier()))) {
            problems.add("Do not use @SpringBootTest for generated unit tests; use Mockito unit-test wiring.");
        }
        if (cu.findAll(AnnotationExpr.class).stream()
                .anyMatch(annotation -> "Autowired".equals(annotation.getName().getIdentifier()))) {
            problems.add("Do not use @Autowired in generated unit tests; declare collaborators with @Mock.");
        }
        boolean hasMockitoStaticImport = cu.getImports().stream()
                .filter(imported -> imported.isStatic())
                .anyMatch(imported -> "org.mockito.Mockito.mockStatic".equals(imported.getNameAsString())
                        || imported.isAsterisk() && "org.mockito.Mockito".equals(imported.getNameAsString()));
        boolean usesMockitoStaticMock = cu.findAll(MethodCallExpr.class).stream()
                .filter(call -> "mockStatic".equals(call.getNameAsString()))
                .anyMatch(call -> call.getScope().map(scope ->
                        "Mockito".equals(scope.toString()) || "org.mockito.Mockito".equals(scope.toString()))
                        .orElse(hasMockitoStaticImport));
        boolean importsMockitoMockedStatic = cu.getImports().stream()
                .filter(imported -> !imported.isStatic())
                .anyMatch(imported -> "org.mockito.MockedStatic".equals(imported.getNameAsString())
                        || imported.isAsterisk() && "org.mockito".equals(imported.getNameAsString()));
        boolean usesMockitoMockedStatic = cu.findAll(ClassOrInterfaceType.class).stream()
                .anyMatch(type -> "org.mockito.MockedStatic".equals(type.getNameWithScope())
                        || "MockedStatic".equals(type.getNameAsString()) && importsMockitoMockedStatic);
        if (usesMockitoStaticMock || usesMockitoMockedStatic) {
            problems.add("Do not use static mocking in generated unit tests; execute static helpers with real fixtures.");
        }
        boolean mocksPageType = cu.findAll(MethodCallExpr.class).stream()
                .filter(call -> "mock".equals(call.getNameAsString()) && !call.getArguments().isEmpty())
                .anyMatch(call -> call.getArgument(0).toString()
                        .matches("(?:[A-Za-z_$][\\w$]*\\.)*(?:Page|Pageable)\\.class"));
        boolean declaresPageMock = cu.findAll(FieldDeclaration.class).stream()
                .filter(field -> field.getAnnotationByName("Mock").isPresent())
                .map(field -> simpleType(field.getElementType().asString()))
                .anyMatch(GeneratedUnitTestSemanticValidator::isPageType);
        boolean declaresPageParameter = cu.findAll(Parameter.class).stream()
                .filter(parameter -> parameter.getAnnotationByName("Mock").isPresent())
                .map(parameter -> simpleType(parameter.getType().asString()))
                .anyMatch(GeneratedUnitTestSemanticValidator::isPageType);
        if (mocksPageType || declaresPageMock || declaresPageParameter) {
            problems.add("Do not mock Page/Pageable; use a real PageImpl and PageRequest fixture.");
        }
    }

    private static boolean isPageType(String type) {
        return "Page".equals(type) || "Pageable".equals(type);
    }

    private static void validateInjectMocksStubbing(
            GeneratedUnitTestDto generatedTest, List<String> problems) {
        if (generatedTest == null || generatedTest.sourceCode() == null) return;
        Optional<CompilationUnit> parsed = parseCompilationUnit(generatedTest.sourceCode());
        if (parsed.isEmpty()) {
            problems.add("Test case " + generatedTest.caseId() + " must contain valid Java test source.");
            return;
        }
        CompilationUnit cu = parsed.get();

        Set<String> injectMockFieldNames = new HashSet<>();
        for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
            if (field.getAnnotationByName("InjectMocks").isPresent()) {
                for (VariableDeclarator var : field.getVariables()) {
                    injectMockFieldNames.add(var.getNameAsString());
                }
            }
        }
        if (injectMockFieldNames.isEmpty()) return;

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if ("when".equals(call.getNameAsString()) && !call.getArguments().isEmpty()) {
                Expression arg = call.getArgument(0);
                if (arg.isMethodCallExpr()) {
                    arg.asMethodCallExpr().getScope().ifPresent(scope -> {
                        if (scope.isNameExpr() && injectMockFieldNames.contains(scope.asNameExpr().getNameAsString())) {
                            problems.add("Do not stub methods on the @InjectMocks instance '"
                                    + scope.asNameExpr().getNameAsString()
                                    + "'; @InjectMocks is the real class under test, only @Mock dependencies can be stubbed.");
                        }
                    });
                }
            }
            if ("verify".equals(call.getNameAsString()) && !call.getArguments().isEmpty()) {
                Expression arg = call.getArgument(0);
                if (arg.isNameExpr() && injectMockFieldNames.contains(arg.asNameExpr().getNameAsString())) {
                    problems.add("Do not verify methods on the @InjectMocks instance '"
                            + arg.asNameExpr().getNameAsString()
                            + "'; only @Mock dependencies can be verified.");
                }
            }
        }
    }

    private static void validatePrivateMethodCalls(
            UnitTestContextDto context,
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        if (context == null || context.classes() == null || generatedTest == null || generatedTest.sourceCode() == null) return;
        Optional<CompilationUnit> parsed = parseCompilationUnit(generatedTest.sourceCode());
        if (parsed.isEmpty()) return;
        CompilationUnit cu = parsed.get();

        Map<String, Set<String>> privateMethodsByClass = privateMethodsByClass(context);
        if (privateMethodsByClass.isEmpty()) return;

        Map<String, String> sutFieldToType = new HashMap<>();
        for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
            if (field.getAnnotationByName("InjectMocks").isPresent()) {
                String typeName = field.getElementType().asString();
                typeName = typeName.substring(typeName.lastIndexOf('.') + 1);
                for (VariableDeclarator var : field.getVariables()) {
                    sutFieldToType.put(var.getNameAsString(), typeName);
                }
            }
        }

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String methodName = call.getNameAsString();
            call.getScope().ifPresent(scope -> {
                if (scope.isNameExpr()) {
                    String varName = scope.asNameExpr().getNameAsString();
                    String typeName = sutFieldToType.get(varName);
                    if (typeName != null) {
                        Set<String> privateMethods = privateMethodsByClass.get(typeName);
                        if (privateMethods != null && privateMethods.contains(methodName)) {
                            problems.add("Method '" + methodName + "' is private in " + typeName
                                    + " and cannot be invoked directly in a unit test. Test it indirectly through public methods.");
                        }
                    }
                }
            });
        }
    }

    private static void validateAnonymousSutSubclassOverrides(
            MethodContextDto productionMethod,
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        if (productionMethod == null || productionMethod.classQualifiedName() == null
                || generatedTest == null || generatedTest.sourceCode() == null) return;
        Optional<CompilationUnit> parsed = parseCompilationUnit(generatedTest.sourceCode());
        if (parsed.isEmpty()) return;
        String qualifiedSutType = productionMethod.classQualifiedName();
        String sutType = qualifiedSutType.substring(qualifiedSutType.lastIndexOf('.') + 1);

        for (ObjectCreationExpr creation : parsed.get().findAll(ObjectCreationExpr.class)) {
            if (!isSutCreation(creation, parsed.get(), qualifiedSutType) || creation.getAnonymousClassBody().isEmpty()) continue;
            creation.getAnonymousClassBody().get().stream()
                    .filter(MethodDeclaration.class::isInstance)
                    .map(MethodDeclaration.class::cast)
                    .filter(method -> method.getAnnotationByName("Override").isPresent())
                    .forEach(method -> problems.add("Do not use an anonymous subclass of " + sutType
                            + " to override '" + method.getNameAsString()
                            + "'. It bypasses the real System Under Test and cannot override private methods. "
                            + "Mock injected dependencies and call the public method instead."));
        }
    }

    private static boolean isSutCreation(
            ObjectCreationExpr creation,
            CompilationUnit testSource,
            String qualifiedSutType) {
        String creationType = creation.getType().getNameWithScope();
        if (creationType.equals(qualifiedSutType)) return true;
        if (creationType.contains(".")) return false;

        String sutType = qualifiedSutType.substring(qualifiedSutType.lastIndexOf('.') + 1);
        if (!creationType.equals(sutType)) return false;
        String sutPackage = qualifiedSutType.substring(0, qualifiedSutType.lastIndexOf('.'));
        return testSource.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString().equals(sutPackage))
                .orElse(false)
                || testSource.getImports().stream().anyMatch(imported -> !imported.isStatic()
                        && (imported.getNameAsString().equals(qualifiedSutType)
                        || imported.isAsterisk() && imported.getNameAsString().equals(sutPackage)));
    }

    private static Map<String, Set<String>> privateMethodsByClass(UnitTestContextDto context) {
        Map<String, Set<String>> privateMethodsByClass = new HashMap<>();
        if (context == null || context.classes() == null) return privateMethodsByClass;
        for (ClassContextDto clazz : context.classes()) {
            if (clazz.methods() == null) continue;
            Set<String> privateMethods = new HashSet<>();
            clazz.methods().stream()
                    .filter(method -> "PRIVATE".equalsIgnoreCase(method.visibility()))
                    .map(MethodContextDto::methodName)
                    .forEach(privateMethods::add);
            if (privateMethods.isEmpty()) continue;
            privateMethodsByClass.put(clazz.className(), privateMethods);
            if (clazz.qualifiedName() != null) {
                privateMethodsByClass.put(clazz.qualifiedName(), privateMethods);
            }
        }
        return privateMethodsByClass;
    }

    private static void validateRemovedMockitoApis(
            GeneratedUnitTestDto generatedTest, MockitoVersionInfo mockitoVersion, List<String> problems) {
        CompilationUnit source = parseCompilationUnit(generatedTest.sourceCode()).orElseThrow();
        for (String api : List.of("verifyNoInteractions", "verifyZeroInteractions")) {
            boolean used = source.findAll(MethodCallExpr.class).stream().anyMatch(call -> api.equals(call.getNameAsString()))
                    || source.getImports().stream().anyMatch(value -> value.isStatic()
                            && value.getNameAsString().equals("org.mockito.Mockito." + api));
            if (!used) continue;
            if (api.equals("verifyNoInteractions") && (mockitoVersion == null || !mockitoVersion.supportsNoInteractions())) {
                problems.add("verifyNoInteractions requires confirmed Mockito 3.0.1+. For Mockito 2 / 3.0.0 use verifyZeroInteractions "
                        + "with its matching static import, or use portable verifyNoMoreInteractions.");
            }
            if (api.equals("verifyZeroInteractions") && (mockitoVersion == null || mockitoVersion.major() >= 4)) {
                problems.add("verifyZeroInteractions is unavailable in Mockito 4+. Use verifyNoInteractions only with "
                        + "confirmed Mockito 3+, or portable verifyNoMoreInteractions when the version is unknown.");
            }
        }
    }

    private static void validateZeroSkippingPolicy(
            GeneratedUnitTestDto generatedTest, List<String> problems) {
        Optional<CompilationUnit> parsed = parseCompilationUnit(generatedTest.sourceCode());
        if (parsed.isEmpty()) return;
        // Mỗi test case đã duyệt phải có một test JUnit thực thi được để lượt retry của AI sửa đúng phản hồi.
        boolean hasSkippedComment = parsed.get().getAllContainedComments().stream()
                .map(Comment::getContent)
                .anyMatch(comment -> comment.trim().matches("(?is)^skipped\\b.*")
                        || comment.lines().map(String::trim).anyMatch(line -> line.matches(
                                "(?i)\\*?\\s*@(?:(?:org\\.junit(?:\\.jupiter\\.api)?\\.)?"
                                        + "(?:Test|TestFactory|TestTemplate|Disabled|Ignore))\\b.*")));
        boolean hasDisabledAnnotation = parsed.get().findAll(AnnotationExpr.class).stream()
                .map(annotation -> annotation.getNameAsString())
                .anyMatch(name -> name.equals("Disabled") || name.endsWith(".Disabled")
                        || name.equals("Ignore") || name.endsWith(".Ignore"));
        boolean hasRunnableTest = findTestMethod(parsed.get(), generatedTest.testMethodName())
                .map(method -> method.getAnnotations().stream().anyMatch(
                        annotation -> isTestMethodAnnotation(annotation.getName().getIdentifier())))
                .orElse(false);
        if (hasSkippedComment || hasDisabledAnnotation) {
            problems.add("Test case " + generatedTest.caseId()
                    + " must not skip, disable, or comment out its generated test.");
        }
        if (!hasRunnableTest) {
            problems.add("Test case " + generatedTest.caseId() + " must declare @Test on generated method '"
                    + generatedTest.testMethodName() + "'.");
        }
    }

    private static void validatePlaceholderHelpers(GeneratedUnitTestDto test, List<String> problems) {
        parseCompilationUnit(test.sourceCode()).flatMap(source -> findTestMethod(source, test.testMethodName()))
                .ifPresent(method -> {
                    boolean expectsException = method.getAnnotations().stream()
                            .filter(AnnotationExpr::isNormalAnnotationExpr)
                            .flatMap(annotation -> annotation.asNormalAnnotationExpr().getPairs().stream())
                            .anyMatch(pair -> "expected".equals(pair.getNameAsString()));
                    if (expectsException) return;
                    method.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(owner -> {
                        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                            if (call.getScope().filter(scope -> !scope.isThisExpr()).isPresent()
                                    || call.findAncestor(LambdaExpr.class).isPresent()
                                    || call.findAncestor(com.github.javaparser.ast.stmt.TryStmt.class).isPresent()) continue;
                            var helpers = owner.getMethodsByName(call.getNameAsString()).stream()
                                    .filter(helper -> helper.getParameters().size() == call.getArguments().size()).toList();
                            // Chỉ chặn khi xác định được helper; tên và số tham số chưa đủ chọn overload.
                            if (helpers.size() != 1) continue;
                            for (MethodDeclaration helper : helpers) {
                                if (helper.getBody().isEmpty()) continue;
                                var statements = helper.getBody().get().getStatements();
                                if (nullPlaceholderStubbing(call, statements, owner)) {
                                    problems.add("Test case " + test.caseId() + " uses placeholder helper '" + helper.getNameAsString()
                                            + "' returning null inside Mockito.when. Stub a real @Mock collaborator and implement the approved Act/assertions instead of a test-local placeholder.");
                                    continue;
                                }
                                if (statements.size() != 1 || !statements.get(0).isThrowStmt()) continue;
                                Expression thrown = statements.get(0).asThrowStmt().getExpression();
                                if (thrown.isObjectCreationExpr() && "UnsupportedOperationException".equals(
                                        simpleType(thrown.asObjectCreationExpr().getType().asString()))) {
                                    problems.add("Test case " + test.caseId() + " calls placeholder helper '"
                                            + helper.getNameAsString() + "' that always throws UnsupportedOperationException. "
                                            + "Use real production DTOs and their declared generic types instead.");
                                }
                            }
                        }
                    });
                });
    }

    private static boolean nullPlaceholderStubbing(MethodCallExpr call,
            List<? extends com.github.javaparser.ast.stmt.Statement> statements, ClassOrInterfaceDeclaration owner) {
        if (statements.size() != 1 || !statements.get(0).isReturnStmt()
                || statements.get(0).asReturnStmt().getExpression().filter(Expression::isNullLiteralExpr).isEmpty()) return false;
        var parent = call.getParentNode().filter(MethodCallExpr.class::isInstance).map(MethodCallExpr.class::cast);
        if (parent.isEmpty() || parent.get().getArguments().size() != 1 || parent.get().getArgument(0) != call) return false;
        if (parent.get().getScope().isEmpty() && (!owner.getExtendedTypes().isEmpty() || !owner.getImplementedTypes().isEmpty())) return false;
        return GeneratedFixtureMutationValidator.isMockitoCall(parent.get(), "when", owner.findCompilationUnit().orElseThrow());
    }

    private static boolean isTestMethodAnnotation(String annotationName) {
        return "Test".equals(annotationName);
    }

    private static void validateNoLenientStubbing(
            GeneratedUnitTestDto generatedTest, List<String> problems) {
        Optional<CompilationUnit> parsed = parseCompilationUnit(generatedTest.sourceCode());
        if (parsed.isEmpty()) return;
        boolean importsMockitoLenient = parsed.get().getImports().stream()
                .anyMatch(imported -> imported.isStatic()
                        && (imported.getNameAsString().equals("org.mockito.Mockito.lenient")
                        || imported.isAsterisk() && imported.getNameAsString().equals("org.mockito.Mockito")));
        boolean importsMockitoWithSettings = parsed.get().getImports().stream()
                .anyMatch(imported -> imported.isStatic()
                        && (imported.getNameAsString().equals("org.mockito.Mockito.withSettings")
                        || imported.isAsterisk() && imported.getNameAsString().equals("org.mockito.Mockito")));
        boolean importsMockitoSession = parsed.get().getImports().stream()
                .anyMatch(imported -> imported.isStatic()
                        && (imported.getNameAsString().equals("org.mockito.Mockito.mockitoSession")
                        || imported.isAsterisk() && imported.getNameAsString().equals("org.mockito.Mockito")));
        boolean importsMockitoStrictness = parsed.get().getImports().stream()
                .anyMatch(imported -> (!imported.isStatic()
                        && imported.getNameAsString().equals("org.mockito.quality.Strictness"))
                        || (imported.isStatic() && (imported.getNameAsString()
                        .startsWith("org.mockito.quality.Strictness.")
                        || (imported.isAsterisk() && imported.getNameAsString()
                        .equals("org.mockito.quality.Strictness")))));
        boolean usesLenientStubbing = parsed.get().findAll(MethodCallExpr.class).stream()
                .filter(call -> "lenient".equals(call.getNameAsString()))
                .anyMatch(call -> isMockitoLenientCall(
                        call, importsMockitoLenient, importsMockitoWithSettings));
        boolean disablesStrictStubbing = parsed.get().findAll(AnnotationExpr.class).stream()
                .anyMatch(annotation -> disablesStrictStubbing(annotation, importsMockitoStrictness));
        boolean usesNonStrictness = parsed.get().findAll(FieldAccessExpr.class).stream()
                .anyMatch(expression -> isMockitoNonStrictness(expression, importsMockitoStrictness))
                || parsed.get().findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> isMockitoStrictnessCall(
                        call, importsMockitoWithSettings, importsMockitoSession)
                        && isMockitoNonStrictness(call.getArgument(0), importsMockitoStrictness));
        if (usesLenientStubbing || disablesStrictStubbing || usesNonStrictness) {
            problems.add("Do not use lenient or non-STRICT_STUBS Mockito settings; keep strict stubbing enabled.");
        }
    }

    /** Chỉ chặn stub fixture khi lookup null chứng minh method trả về trước khi đọc tham số đó. */
    private static void validateUnusedFixtureStubs(UnitTestContextDto context, MethodContextDto target,
            GeneratedUnitTestDto test, List<String> problems) {
        if (target == null) return;
        var source = context.classes().stream().filter(c -> target.classQualifiedName().equals(c.qualifiedName()))
                .findFirst().flatMap(GeneratedUnitTestSemanticValidator::serviceDeclaration);
        var parsed = parseCompilationUnit(test.sourceCode());
        if (source.isEmpty() || parsed.isEmpty()) return;
        var methods = source.get().getMethodsByName(target.methodName());
        if (methods.size() != 1 || !source.get().getConstructors().isEmpty()
                || source.get().getAnnotationByName("AllArgsConstructor").isEmpty()) return;
        var production = methods.get(0);
        var lookup = nullGuardLookup(production);
        var method = findTestMethod(parsed.get(), test.testMethodName());
        if (lookup == null || method.isEmpty() || method.get().getBody().isEmpty()
                || method.get().findAll(LambdaExpr.class).size() > 0) return;
        var testMethod = method.get();
        var owner = testMethod.findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow();
        var acts = testMethod.findAll(MethodCallExpr.class).stream()
                .filter(call -> target.methodName().equals(call.getNameAsString())
                        && isProductionReceiver(testMethod, call, target.classQualifiedName())).toList();
        if (acts.size() != 1) return;
        var act = acts.get(0);
        if (topLevelStatement(act, testMethod).isEmpty() || act.getArguments().size() != production.getParameters().size()
                || act.getArguments().stream().anyMatch(argument -> !argument.isNameExpr() && !argument.isLiteralExpr())
                || act.getScope().filter(Expression::isNameExpr).isEmpty()) return;
        String sut = act.getScope().orElseThrow().toString();
        String dependency = lookup.getScope().orElseThrow().toString();
        if (sut.equals(dependency) || production.getParameters().stream()
                .anyMatch(parameter -> parameter.getNameAsString().equals(dependency))) return;
        if (!hasFixtureLookupBinding(source.get(), owner, testMethod, target.classQualifiedName(), sut, dependency)) return;
        var stubs = confirmedNullLookupStubs(production, testMethod, act, lookup, parsed.get());
        addUnusedFixtureProblems(production, testMethod, act, stubs, test.caseId(), problems);
    }

    private static boolean hasFixtureLookupBinding(ClassOrInterfaceDeclaration source, ClassOrInterfaceDeclaration owner,
            MethodDeclaration testMethod, String qualifiedName, String sut, String dependency) {
        var injected = owner.getFields().stream().filter(field -> field.getAnnotationByName("InjectMocks").isPresent())
                .flatMap(field -> field.getVariables().stream()).filter(variable -> variable.getNameAsString().equals(sut)
                        && variable.getInitializer().isEmpty()
                        && qualifiedName.equals(qualifiedFixtureType(variable))).toList();
        if (injected.size() != 1 || testMethod.findAll(VariableDeclarator.class).stream()
                .anyMatch(variable -> Set.of(sut, dependency).contains(variable.getNameAsString()))
                || owner.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                .anyMatch(assignment -> Set.of(sut, dependency, "this." + sut, "this." + dependency)
                        .contains(assignment.getTarget().toString()))) return false;
        var productionFields = source.getFields().stream().flatMap(field -> field.getVariables().stream()).toList();
        var dependencyField = productionFields.stream().filter(field -> field.getNameAsString().equals(dependency)).findFirst();
        if (dependencyField.isEmpty() || dependencyField.get().getInitializer().isPresent()
                || dependencyField.get().findAncestor(FieldDeclaration.class).filter(FieldDeclaration::isStatic).isPresent()) return false;
        String dependencyType = qualifiedFixtureType(dependencyField.get());
        var mocks = owner.getFields().stream().filter(field -> field.getAnnotationByName("Mock").isPresent())
                .flatMap(field -> field.getVariables().stream()).filter(field -> dependencyType != null
                        && dependencyType.equals(qualifiedFixtureType(field))).toList();
        return mocks.size() == 1 && mocks.get(0).getNameAsString().equals(dependency);
    }

    private static List<MethodCallExpr> confirmedNullLookupStubs(MethodDeclaration production, MethodDeclaration testMethod,
            MethodCallExpr act, MethodCallExpr lookup, CompilationUnit parsed) {
        String dependency = lookup.getScope().orElseThrow().toString();
        var stubs = testMethod.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals("thenReturn") && call.getArguments().size() == 1
                        && call.getParentNode().filter(MethodCallExpr.class::isInstance).isEmpty()
                        && topLevelStatement(call, testMethod).filter(statement -> statement.getExpression() == call).isPresent()
                        && appearsBefore(call, act)
                        && call.getScope().filter(Expression::isMethodCallExpr).isPresent())
                .filter(call -> isMockitoWhenCall(call.getScope().orElseThrow().asMethodCallExpr(), parsed))
                .filter(call -> call.getScope().orElseThrow().asMethodCallExpr().getArguments().size() == 1
                        && call.getScope().orElseThrow().asMethodCallExpr().getArgument(0).isMethodCallExpr()).toList();
        var nullStubs = stubs.stream().filter(stub -> stub.getArgument(0).isNullLiteralExpr())
                .map(stub -> stub.getScope().orElseThrow().asMethodCallExpr().getArgument(0).asMethodCallExpr())
                .filter(call -> call.getScope().map(Object::toString).filter(dependency::equals).isPresent()
                        && call.getNameAsString().equals(lookup.getNameAsString())
                        && call.getArguments().size() == lookup.getArguments().size())
                .filter(call -> sameLookupArguments(lookup, call, production, act)).toList();
        if (nullStubs.size() != 1) return List.of();
        var nullStatement = topLevelStatement(nullStubs.get(0), testMethod);
        var actStatement = topLevelStatement(act, testMethod);
        if (nullStatement.isEmpty() || actStatement.isEmpty()) return List.of();
        var statements = testMethod.getBody().orElseThrow().getStatements();
        if (statements.indexOf(nullStatement.get()) + 1 != statements.indexOf(actStatement.get())) return List.of();
        var actExpression = actStatement.get().getExpression();
        if (actExpression != act && !(actExpression.isVariableDeclarationExpr()
                && actExpression.asVariableDeclarationExpr().getVariables().size() == 1
                && actExpression.asVariableDeclarationExpr().getVariable(0).getInitializer().filter(value -> value == act).isPresent())) return List.of();
        if (stubs.stream().map(stub -> stub.getScope().orElseThrow().asMethodCallExpr().getArgument(0).asMethodCallExpr())
                .filter(call -> call.getScope().map(Object::toString).filter(dependency::equals).isPresent()
                        && call.getNameAsString().equals(lookup.getNameAsString())).count() != 1) return List.of();
        return stubs;
    }

    private static void addUnusedFixtureProblems(MethodDeclaration production, MethodDeclaration testMethod,
            MethodCallExpr act, List<MethodCallExpr> stubs, Long caseId, List<String> problems) {
        // shortcut: Chỉ chứng minh null guard với một Act đơn giản; mở rộng khi có lỗi thực tế từ helper/lifecycle.
        var parsed = testMethod.findCompilationUnit().orElseThrow();
        for (var stub : stubs) {
            if (!stub.getArgument(0).isLiteralExpr()) continue;
            var invocation = stub.getScope().orElseThrow().asMethodCallExpr().getArgument(0).asMethodCallExpr();
            if (invocation.getScope().filter(Expression::isNameExpr).isEmpty()) continue;
            String fixture = invocation.getScope().orElseThrow().toString();
            var locals = testMethod.findAll(VariableDeclarator.class).stream()
                    .filter(variable -> variable.getNameAsString().equals(fixture)).toList();
            if (locals.size() != 1 || locals.get(0).getInitializer().filter(Expression::isMethodCallExpr)
                    .map(Expression::asMethodCallExpr).filter(call -> isMockitoApiCall(call, "mock", parsed)).isEmpty()) continue;
            for (int index = 0; index < production.getParameters().size(); index++) {
                if (!act.getArgument(index).toString().equals(fixture)) continue;
                Expression fixtureArgument = act.getArgument(index);
                String parameter = production.getParameter(index).getNameAsString();
                var body = production.getBody().orElseThrow();
                var branch = body.getStatement(1).asIfStmt();
                boolean readBeforeReturn = List.<Node>of(body.getStatement(0), branch.getCondition(), branch.getThenStmt()).stream()
                        .anyMatch(statement -> statement.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                                .anyMatch(name -> name.getNameAsString().equals(parameter)));
                if (readBeforeReturn) continue;
                boolean escapes = testMethod.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                        .filter(name -> name.getNameAsString().equals(fixture))
                        .anyMatch(name -> !stub.isAncestorOf(name) && name != fixtureArgument);
                if (!escapes) problems.add("Test case " + caseId + ": UnnecessaryStubbingException: stub "
                        + invocation + " is unused on the confirmed null-lookup early return in " + production.getNameAsString()
                        + ". Remove only this stub; keep the non-null fixture, approved scenario, assertions and STRICT_STUBS. "
                        + "Do not add dummy getter calls, lenient(), or change the input to null to consume/bypass the stub.");
            }
        }
    }

    private static MethodCallExpr nullGuardLookup(MethodDeclaration method) {
        if (method.getBody().isEmpty() || method.getBody().get().getStatements().size() < 2) return null;
        var first = method.getBody().get().getStatement(0);
        var second = method.getBody().get().getStatement(1);
        if (!first.isExpressionStmt() || !first.asExpressionStmt().getExpression().isVariableDeclarationExpr()
                || !second.isIfStmt()) return null;
        var variables = first.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables();
        if (variables.size() != 1 || variables.get(0).getInitializer().filter(Expression::isMethodCallExpr).isEmpty()) return null;
        var variable = variables.get(0);
        var lookup = variable.getInitializer().orElseThrow().asMethodCallExpr();
        if (lookup.getScope().filter(Expression::isNameExpr).isEmpty()
                || lookup.getArguments().stream().anyMatch(arg -> !arg.isNameExpr() && !arg.isLiteralExpr())) return null;
        var branch = second.asIfStmt();
        if (!branch.getCondition().isBinaryExpr()) return null;
        var condition = branch.getCondition().asBinaryExpr();
        if (condition.getOperator() != BinaryExpr.Operator.EQUALS
                || !(condition.getLeft().toString().equals(variable.getNameAsString()) && condition.getRight().isNullLiteralExpr()
                || condition.getRight().toString().equals(variable.getNameAsString()) && condition.getLeft().isNullLiteralExpr())) return null;
        var returned = branch.getThenStmt();
        if (returned.isBlockStmt() && returned.asBlockStmt().getStatements().size() == 1)
            returned = returned.asBlockStmt().getStatement(0);
        return returned.isReturnStmt() ? lookup : null;
    }

    private static boolean sameLookupArguments(MethodCallExpr lookup, MethodCallExpr stub,
            MethodDeclaration production, MethodCallExpr act) {
        for (int index = 0; index < lookup.getArguments().size(); index++) {
            var expected = lookup.getArgument(index);
            if (expected.isNameExpr()) {
                int parameter = parameterIndex(production, expected.toString());
                if (parameter < 0) return false;
                expected = act.getArgument(parameter);
            }
            if (!expected.isLiteralExpr() || !expected.equals(stub.getArgument(index))) return false;
        }
        return true;
    }

    private static int parameterIndex(MethodDeclaration method, String selector) {
        for (int index = 0; index < method.getParameters().size(); index++) {
            if (method.getParameter(index).getNameAsString().equals(selector)) return index;
        }
        return -1;
    }

    private static String qualifiedFixtureType(VariableDeclarator field) {
        if (!field.getType().isClassOrInterfaceType()) return null;
        String type = field.getType().asClassOrInterfaceType().getNameWithScope();
        if (type.contains(".")) return type;
        var unit = field.findCompilationUnit().orElseThrow();
        var imports = unit.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk()
                && i.getName().getIdentifier().equals(type)).toList();
        if (imports.size() == 1) return imports.get(0).getNameAsString();
        if (!imports.isEmpty() || unit.getImports().stream().anyMatch(i -> !i.isStatic() && i.isAsterisk())) return null;
        return unit.getPackageDeclaration().map(p -> p.getNameAsString() + "." + type).orElse(type);
    }

    private static boolean isMockitoScope(Expression scope) {
        String name = scope.toString();
        return "Mockito".equals(name) || "org.mockito.Mockito".equals(name)
                || "BDDMockito".equals(name) || "org.mockito.BDDMockito".equals(name);
    }

    private static boolean isMockitoLenientCall(
            MethodCallExpr call, boolean importsMockitoLenient, boolean importsMockitoWithSettings) {
        return call.getScope().map(scope -> isMockitoScope(scope)
                || (scope.isMethodCallExpr() && isMockitoWithSettingsCall(
                scope.asMethodCallExpr(), importsMockitoWithSettings))).orElse(importsMockitoLenient);
    }

    private static boolean isMockitoWithSettingsCall(MethodCallExpr call, boolean importsMockitoWithSettings) {
        if (!"withSettings".equals(call.getNameAsString())) return false;
        return call.getScope().map(GeneratedUnitTestSemanticValidator::isMockitoScope)
                .orElse(importsMockitoWithSettings);
    }

    private static boolean isMockitoStrictnessCall(
            MethodCallExpr call, boolean importsMockitoWithSettings, boolean importsMockitoSession) {
        if (!"strictness".equals(call.getNameAsString()) || call.getArguments().size() != 1
                || call.getScope().isEmpty() || !call.getScope().get().isMethodCallExpr()) return false;
        MethodCallExpr configuredMock = call.getScope().get().asMethodCallExpr();
        return isMockitoWithSettingsCall(configuredMock, importsMockitoWithSettings)
                || isMockitoSessionCall(configuredMock, importsMockitoSession);
    }

    private static boolean isMockitoSessionCall(MethodCallExpr call, boolean importsMockitoSession) {
        if (!"mockitoSession".equals(call.getNameAsString())) return false;
        return call.getScope().map(GeneratedUnitTestSemanticValidator::isMockitoScope)
                .orElse(importsMockitoSession);
    }

    private static boolean disablesStrictStubbing(AnnotationExpr annotation, boolean importsMockitoStrictness) {
        if (!(annotation instanceof NormalAnnotationExpr normal)) return false;
        String name = annotation.getName().getIdentifier();
        if ("Mock".equals(name)) {
            return normal.getPairs().stream().anyMatch(pair ->
                    ("lenient".equals(pair.getNameAsString())
                            && pair.getValue().isBooleanLiteralExpr()
                            && pair.getValue().asBooleanLiteralExpr().getValue())
                            || ("strictness".equals(pair.getNameAsString())
                            && isMockitoNonStrictness(pair.getValue(), importsMockitoStrictness)));
        }
        return "MockitoSettings".equals(name) && normal.getPairs().stream()
                .anyMatch(pair -> "strictness".equals(pair.getNameAsString())
                        && isMockitoNonStrictness(pair.getValue(), importsMockitoStrictness));
    }

    private static boolean isMockitoNonStrictness(Expression value, boolean importsMockitoStrictness) {
        if (value.isFieldAccessExpr()) {
            FieldAccessExpr strictness = value.asFieldAccessExpr();
            return !"STRICT_STUBS".equals(strictness.getNameAsString())
                    && ("org.mockito.quality.Strictness".equals(strictness.getScope().toString())
                    || "Strictness".equals(strictness.getScope().toString()) && importsMockitoStrictness);
        }
        return value.isNameExpr() && importsMockitoStrictness
                && !"STRICT_STUBS".equals(value.asNameExpr().getNameAsString());
    }

    private static void validateMockitoRunnerOrExtension(
            GeneratedUnitTestDto generatedTest,
            TestFramework framework,
            List<String> problems) {
        if (generatedTest == null || generatedTest.sourceCode() == null) return;
        String source = generatedTest.sourceCode();
        boolean hasMockAnnotation = source.contains("@Mock") || source.contains("@InjectMocks")
                || source.contains("@Spy");
        if (!hasMockAnnotation) return;

        Optional<CompilationUnit> parsed = parseCompilationUnit(source);
        if (parsed.isEmpty()) return;
        CompilationUnit cu = parsed.get();

        boolean hasMockFields = cu.findAll(FieldDeclaration.class).stream()
                .anyMatch(f -> f.getAnnotationByName("Mock").isPresent()
                        || f.getAnnotationByName("InjectMocks").isPresent()
                        || f.getAnnotationByName("Spy").isPresent());
        if (!hasMockFields) return;

        boolean hasOpenMocks = source.contains("openMocks(") || source.contains("initMocks(");
        if (hasOpenMocks) return;

        boolean hasExtendWithMockito = source.matches("(?s).*@ExtendWith\\s*\\(\\s*(?:[A-Za-z_$][\\w$]*\\.)*MockitoExtension\\.class\\s*\\).*");
        boolean hasRunWithMockito = source.matches("(?s).*@RunWith\\s*\\(\\s*(?:[A-Za-z_$][\\w$]*\\.)*MockitoJUnitRunner(?:\\.[A-Za-z_$][\\w$]*)*\\.class\\s*\\).*");
        boolean hasSilentMockitoRunner = source.matches("(?s).*@RunWith\\s*\\(\\s*(?:[A-Za-z_$][\\w$]*\\.)*MockitoJUnitRunner\\.Silent\\.class\\s*\\).*");

        if (hasSilentMockitoRunner) {
            problems.add("MockitoJUnitRunner.Silent disables strict stubbing; use MockitoJUnitRunner.class instead.");
        }

        if (framework == TestFramework.JUNIT4) {
            if (!hasRunWithMockito) {
                problems.add("When using @Mock or @InjectMocks in JUnit 4, the test class MUST be annotated with @RunWith(MockitoJUnitRunner.class) and import org.junit.runner.RunWith and org.mockito.junit.MockitoJUnitRunner.");
            }
        } else {
            // Mặc định hoặc JUNIT5
            if (!hasExtendWithMockito && !hasRunWithMockito) {
                problems.add("When using @Mock or @InjectMocks in JUnit 5, the test class MUST be annotated with @ExtendWith(MockitoExtension.class) and import org.junit.jupiter.api.extension.ExtendWith and org.mockito.junit.jupiter.MockitoExtension.");
            }
        }
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

    private static void validateEnumReferences(
            UnitTestContextDto context,
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        if (context.classes() == null) return;
        Optional<CompilationUnit> parsed = parseCompilationUnit(generatedTest.sourceCode());
        if (parsed.isEmpty()) return;
        CompilationUnit testSource = parsed.get();
        Map<String, String> enumNames = new HashMap<>();
        Map<String, Set<String>> enumConstants = new HashMap<>();
        Map<String, String> enumPackages = new HashMap<>();
        for (ClassContextDto javaClass : context.classes()) {
            if (!"ENUM".equalsIgnoreCase(javaClass.classType()) || javaClass.qualifiedName() == null
                    || javaClass.validationSourceCode() == null) continue;
            parseCompilationUnit(javaClass.validationSourceCode()).ifPresent(source -> source.findAll(EnumDeclaration.class)
                    .stream().filter(declaration -> declaration.getFullyQualifiedName()
                            .filter(javaClass.qualifiedName()::equals).isPresent())
                    .findFirst().ifPresent(declaration -> {
                        enumNames.put(javaClass.className(), javaClass.qualifiedName());
                        enumPackages.put(javaClass.qualifiedName(), source.getPackageDeclaration()
                                .map(declarationPackage -> declarationPackage.getNameAsString()).orElse(""));
                        enumConstants.put(javaClass.qualifiedName(), declaration.getEntries().stream()
                                .map(entry -> entry.getNameAsString()).collect(java.util.stream.Collectors.toSet()));
                    }));
        }
        testSource.getImports().stream().filter(imported -> !imported.isStatic() && !imported.isAsterisk())
                .map(imported -> imported.getNameAsString())
                .filter(enumConstants::containsKey)
                .forEach(qualifiedName -> enumNames.put(
                        qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1), qualifiedName));
        for (EnumDeclaration declaration : testSource.findAll(EnumDeclaration.class)) {
            enumNames.put(declaration.getNameAsString(), declaration.getNameAsString());
        }

        for (ObjectCreationExpr creation : testSource.findAll(ObjectCreationExpr.class)) {
            String type = creation.getType().getNameWithScope();
            if (enumNames.containsKey(creation.getType().getNameAsString())) {
                problems.add("Do not instantiate enum " + type + "; use a declared constant directly.");
            }
        }
        for (ClassOrInterfaceType type : testSource.findAll(ClassOrInterfaceType.class)) {
            String expected = enumNames.get(type.getNameAsString());
            if (expected != null && !type.getNameWithScope().equals(type.getNameAsString())
                    && resolveEnumReference(type.getNameWithScope(), testSource, enumConstants, enumPackages) == null) {
                problems.add("Enum type '" + type.getNameWithScope() + "' is not declared in context; use "
                        + expected + ".");
            }
        }
        for (FieldAccessExpr access : testSource.findAll(FieldAccessExpr.class)) {
            String scope = access.getScope().toString();
            int dot = scope.lastIndexOf('.');
            String simple = scope.substring(dot + 1);
            String resolved = resolveEnumReference(scope, testSource, enumConstants, enumPackages);
            String expected = resolved == null ? enumNames.get(simple) : resolved;
            if (expected == null) continue;
            if (dot >= 0 && resolved == null) {
                problems.add("Enum type '" + scope + "' is not declared in context; use " + expected + ".");
            }
            if (enumConstants.containsKey(expected)
                    && !enumConstants.get(expected).contains(access.getNameAsString())) {
                problems.add("Enum constant '" + access.getNameAsString() + "' does not exist in " + expected + ".");
            }
        }
        for (MethodCallExpr call : testSource.findAll(MethodCallExpr.class)) {
            if (!"valueOf".equals(call.getNameAsString()) || call.getArguments().size() != 1
                    || !call.getArgument(0).isStringLiteralExpr() || call.getScope().isEmpty()) continue;
            String scope = call.getScope().get().toString();
            String resolved = resolveEnumReference(scope, testSource, enumConstants, enumPackages);
            String expected = resolved == null ? enumNames.get(scope.substring(scope.lastIndexOf('.') + 1)) : resolved;
            if (expected != null && scope.contains(".") && resolved == null) {
                problems.add("Enum type '" + scope + "' is not declared in context; use " + expected + ".");
            }
            if (expected != null && enumConstants.containsKey(expected)
                    && !enumConstants.get(expected).contains(call.getArgument(0).asStringLiteralExpr().asString())) {
                problems.add("Enum constant '" + call.getArgument(0).asStringLiteralExpr().asString()
                        + "' does not exist in " + expected + ".");
            }
        }
        Set<String> simpleReferences = new HashSet<>();
        testSource.findAll(ClassOrInterfaceType.class).stream()
                .filter(type -> type.getNameWithScope().equals(type.getNameAsString()))
                .map(ClassOrInterfaceType::getNameAsString).forEach(simpleReferences::add);
        testSource.findAll(FieldAccessExpr.class).stream()
                .map(FieldAccessExpr::getScope).filter(Expression::isNameExpr)
                .map(scope -> scope.asNameExpr().getNameAsString()).forEach(simpleReferences::add);
        testSource.findAll(MethodCallExpr.class).stream()
                .flatMap(call -> call.getScope().stream()).filter(Expression::isNameExpr)
                .map(scope -> scope.asNameExpr().getNameAsString()).forEach(simpleReferences::add);
        for (String name : simpleReferences) {
            if (resolveEnumReference(name, testSource, enumConstants, enumPackages) != null) continue;
            String expected = enumNames.get(name);
            if (expected == null || !enumConstants.containsKey(expected)) continue;
            problems.add("Enum " + name + " requires import " + expected + ".");
        }
    }

    private static String resolveEnumReference(String reference, CompilationUnit testSource,
            Map<String, Set<String>> enumConstants, Map<String, String> enumPackages) {
        if (enumConstants.containsKey(reference)) return reference;
        int dot = reference.indexOf('.');
        String root = dot < 0 ? reference : reference.substring(0, dot);
        String suffix = dot < 0 ? "" : reference.substring(dot);
        // Import class ngoài cho phép dùng Outer.Enum; import tường minh ưu tiên hơn kiểu cùng package.
        List<String> imports = testSource.getImports().stream().filter(imported -> !imported.isAsterisk())
                .filter(imported -> imported.getName().getIdentifier().equals(root))
                .map(imported -> imported.getNameAsString() + suffix).distinct().toList();
        if (!imports.isEmpty()) {
            return imports.size() == 1 && enumConstants.containsKey(imports.get(0)) ? imports.get(0) : null;
        }
        String testPackage = testSource.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
        String samePackage = testPackage.isEmpty() ? reference : testPackage + "." + reference;
        if (enumConstants.containsKey(samePackage) && testPackage.equals(enumPackages.get(samePackage))) return samePackage;
        return null;
    }

    private static void validateEnumFixtureValues(
            UnitTestContextDto context, MethodContextDto productionMethod,
            GeneratedUnitTestDto test, List<String> problems) {
        CompilationUnit source = parseCompilationUnit(test.sourceCode()).orElse(null);
        if (source == null) return;
        Set<String> enumTypes = new HashSet<>(Set.of("Enum"));
        context.classes().stream().filter(clazz -> "ENUM".equalsIgnoreCase(clazz.classType()))
                .map(ClassContextDto::className).forEach(enumTypes::add);
        List<Expression> fixtures = new ArrayList<>();
        for (VariableDeclarator variable : source.findAll(VariableDeclarator.class)) {
            if (!enumTypes.contains(simpleType(variable.getType().asString()))) continue;
            variable.getInitializer().ifPresent(fixtures::add);
            MethodDeclaration owner = variable.findAncestor(MethodDeclaration.class).orElse(null);
            source.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                    .filter(assignment -> mockReference(assignment.getTarget())
                            .map(variable.getNameAsString()::equals).orElse(false))
                    .filter(assignment -> owner != null
                            ? assignment.findAncestor(MethodDeclaration.class).orElse(null) == owner
                            : assignment.getTarget().isFieldAccessExpr()
                                    || assignment.findAncestor(MethodDeclaration.class)
                                            .map(method -> method.findAll(VariableDeclarator.class).stream()
                                                    .noneMatch(local -> local.getNameAsString()
                                                            .equals(variable.getNameAsString())))
                                            .orElse(true))
                    .map(assignment -> assignment.getValue()).forEach(fixtures::add);
        }
        if (productionMethod != null && productionMethod.sourceCode() != null) {
            parseMethod(productionMethod.sourceCode()).ifPresent(method -> {
                for (MethodDeclaration testMethod : source.findAll(MethodDeclaration.class)) {
                    for (MethodCallExpr call : testMethod.findAll(MethodCallExpr.class)) {
                        if (!method.getNameAsString().equals(call.getNameAsString())
                                || call.getArguments().size() != method.getParameters().size()
                                || !isProductionReceiver(testMethod, call, productionMethod.classQualifiedName())) continue;
                        for (int index = 0; index < method.getParameters().size(); index++) {
                            if (enumTypes.contains(simpleType(method.getParameter(index).getType().asString()))) {
                                fixtures.add(call.getArgument(index));
                            }
                        }
                    }
                }
            });
        }
        Set<String> declaredFixtureTypes = context.classes().stream()
                .map(ClassContextDto::qualifiedName).collect(java.util.stream.Collectors.toSet());
        source.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).forEach(raw -> {
            com.github.javaparser.ast.body.TypeDeclaration<?> type = raw;
            type.getFullyQualifiedName().ifPresent(declaredFixtureTypes::add);
        });
        for (var fixture : fixtures) {
            if (!fixture.isFieldAccessExpr()) continue;
            String scope = fixture.asFieldAccessExpr().getScope().toString();
            String root = scope.split("\\.", 2)[0];
            for (var imported : source.getImports()) if (!imported.isStatic() && !imported.isAsterisk()
                    && imported.getName().getIdentifier().equals(root)) {
                scope = imported.getNameAsString() + scope.substring(root.length()); break;
            }
            String resolved = scope;
            if (microserviceRoot(productionMethod).filter(prefix -> resolved.startsWith(prefix + ".")).isPresent()
                    && !declaredFixtureTypes.contains(resolved)) {
                problems.add("Enum fixture type '" + resolved + "' is not declared in the supplied source. Use a verified enum constant; "
                        + "when the raw Enum contract only consumes name(), declare a local test-fixture enum preserving the approved name, not a production type.");
            }
        }
        // Giá trị Enum trả về từ SUT/getter vẫn hợp lệ để kiểm tra kết quả.
        Set<String> forbiddenFactories = Set.of("assertThrows", "assertThrowsExactly", "forName",
                "getEnumConstants", "getField", "getDeclaredField", "valueOf");
        if (fixtures.stream().flatMap(fixture -> fixture.findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> forbiddenFactories.contains(call.getNameAsString()))) {
            problems.add("Enum fixtures and arguments must use a declared project enum constant or null; "
                    + "do not use reflection, assertThrows results, or method-call expressions to manufacture an Enum.");
        }
    }

    private static void validateProductionMethodCalls(
            UnitTestContextDto context,
            MethodContextDto productionMethod,
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        if (context.classes() == null || productionMethod == null) return;
        Optional<CompilationUnit> parsedTest = parseCompilationUnit(generatedTest.sourceCode());
        if (parsedTest.isEmpty()) return;
        for (ClassContextDto javaClass : context.classes()) {
            if (!javaClass.qualifiedName().equals(productionMethod.classQualifiedName())) continue;
            Optional<ClassOrInterfaceDeclaration> declaration = serviceDeclaration(javaClass);
            if (declaration.isEmpty() || !declaration.get().getExtendedTypes().isEmpty()) continue;
            Set<String> declaredMethods = declaration.get().getMethods().stream()
                    .map(MethodDeclaration::getNameAsString).collect(java.util.stream.Collectors.toSet());
            for (MethodDeclaration testMethod : parsedTest.get().findAll(MethodDeclaration.class)) {
                for (MethodCallExpr call : testMethod.findAll(MethodCallExpr.class)) {
                    if (!isProductionReceiver(testMethod, call, javaClass.qualifiedName())) continue;
                    String methodName = call.getNameAsString();
                    if (!declaredMethods.contains(methodName)
                            && !Set.of("toString", "hashCode", "equals", "getClass", "wait", "notify", "notifyAll")
                                    .contains(methodName)) {
                        problems.add("Method '" + methodName + "' does not exist in the current production source of "
                                + javaClass.className() + ". Call an existing method from the approved Test Case.");
                    }
                }
            }
        }
    }

    private static void validateRepositorySaveMatcherType(GeneratedUnitTestDto test, List<String> problems) {
        parseCompilationUnit(test.sourceCode()).ifPresent(source -> source.findAll(MethodCallExpr.class).stream()
                .filter(call -> "save".equals(call.getNameAsString()))
                .flatMap(call -> call.getArguments().stream())
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(matcher -> "any".equals(matcher.getNameAsString()) && matcher.getArguments().size() == 1
                        && matcher.getArgument(0).isClassExpr() && isMockitoMatcher(matcher, source))
                .map(matcher -> simpleType(matcher.getArgument(0).asClassExpr().getType().asString()))
                .filter(type -> type.endsWith("Repository"))
                .forEach(type -> problems.add("Repository.save(...) requires an entity matcher, not " + type
                        + ". Use any(Entity.class) with the entity type accepted by save.")));
    }

    private static void validateMockitoSettingsSyntax(GeneratedUnitTestDto test, List<String> problems) {
        parseCompilationUnit(test.sourceCode()).ifPresent(source -> source.findAll(AnnotationExpr.class).stream()
                .filter(annotation -> "MockitoSettings".equals(annotation.getName().getIdentifier()))
                .filter(AnnotationExpr::isSingleMemberAnnotationExpr)
                .forEach(annotation -> problems.add("@MockitoSettings requires strictness = Strictness.STRICT_STUBS, "
                        + "not a positional value.")));
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

    private static void validateJUnit5AssertionOrder(
            GeneratedUnitTestDto generatedTest,
            TestFramework framework,
            List<String> problems) {
        if (generatedTest == null || generatedTest.sourceCode() == null) return;
        String source = generatedTest.sourceCode();
        boolean isJunit5 = framework == TestFramework.JUNIT5
                || source.contains("org.junit.jupiter")
                || (!source.contains("org.junit.Assert") && !source.contains("org.junit.Test"));
        if (!isJunit5) return;

        Optional<CompilationUnit> parsed = parseCompilationUnit(source);
        if (parsed.isEmpty()) return;

        boolean hasLeadingMessage = parsed.get().findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().startsWith("assert"))
                .anyMatch(GeneratedUnitTestSemanticValidator::hasLeadingJunit4Message);
        if (hasLeadingMessage) {
            problems.add("In JUnit 5 (org.junit.jupiter.api.Assertions), the failure message is the LAST parameter (or omit it). "
                    + "Do not put String message first (e.g. use assertTrue(condition, \"message\"), not assertTrue(\"message\", condition)).");
        }
    }

    private static boolean hasLeadingJunit4Message(MethodCallExpr call) {
        if (!java.util.Set.of(
                "assertEquals", "assertNotEquals", "assertSame", "assertNotSame", "assertArrayEquals",
                "assertTrue", "assertFalse", "assertNull", "assertNotNull").contains(call.getNameAsString())) {
            return false;
        }
        int size = call.getArguments().size();
        if (size == 0 || !call.getArgument(0).isStringLiteralExpr()) return false;
        if (size >= 3) return true;
        return size == 2 && switch (call.getNameAsString()) {
            case "assertTrue", "assertFalse" -> true;
            case "assertNull", "assertNotNull" -> !call.getArgument(1).isStringLiteralExpr();
            default -> false;
        };
    }

    /** Giữ yêu cầu không gọi collaborator của TC; không suy diễn từ hành vi implementation. */
    private static void validateApprovedNegativeInteractions(UnitTestContextDto context, GeneratedUnitTestDto test,
            CompilationUnit source, List<String> problems) {
        String expected = context.approvedTestCases().stream().filter(c -> java.util.Objects.equals(c.id(), test.caseId()))
                .map(c -> c.expectedResult()).filter(java.util.Objects::nonNull).findFirst().orElse("");
        if (expected.isEmpty()) return;
        findTestMethod(source, test.testMethodName()).ifPresent(method -> {
            for (var call : method.findAll(MethodCallExpr.class)) {
                if (call.findAncestor(MethodDeclaration.class).filter(method::equals).isEmpty() || topLevelStatement(call, method).isEmpty()) continue;
                var verify = call.getScope().filter(Expression::isMethodCallExpr).map(Expression::asMethodCallExpr).orElse(null);
                if (verify == null || !verify.getNameAsString().equals("verify") || !isUnshadowedMockitoCall(verify, source)
                        || verify.getArguments().isEmpty()) continue;
                if (verify.getScope().isEmpty() && method.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(c -> !c.getMethodsByName("verify").isEmpty() || !c.getExtendedTypes().isEmpty()).orElse(false)) continue;
                var mock = mockReference(verify.getArgument(0));
                if (mock.isEmpty()) continue;
                String interaction = mock.get() + "." + call.getNameAsString();
                String quoted = java.util.regex.Pattern.quote(interaction);
                String identifier = "(?:`" + quoted + "`|\"" + quoted + "\"|“" + quoted + "”|" + quoted + ")(?![\\p{javaJavaIdentifierPart}]|\\s*\\()";
                String sentence = "(?:^|[.;\\n])\\s*";
                // Chỉ cấm gọi toàn method; đối số, điều kiện và giới hạn số lần cần chứng cứ riêng.
                String end = "(?:\\s*(?:\\.(?![\\p{javaJavaIdentifierPart}])|$)|\\s+(?iuU:vì|because)\\s+)";
                String postfix = sentence + identifier + "\\s+(?iuU:không\\s+(?:được\\s+)?gọi|(?:is|must|should)\\s+(?:not|never)\\s+(?:be\\s+)?(?:called|invoked))" + end;
                String prefix = sentence + "(?iuU:do\\s+not\\s+call|never\\s+call|không\\s+(?:được\\s+)?gọi)\\s+" + identifier + end;
                String repository = mock.get().replaceFirst("^this\\.", "");
                String entity = repository.replaceFirst("(?:Repository|Repo)$", "");
                boolean forbiddenSave = call.getNameAsString().equals("save") && !entity.isBlank() && !entity.equals(repository)
                        && java.util.regex.Pattern.compile("(?iuU)" + sentence + "(?:không\\s+lưu|do\\s+not\\s+save|never\\s+save)\\s+"
                                + java.util.regex.Pattern.quote(entity) + "(?=\\s*(?:\\.(?![\\p{javaJavaIdentifierPart}])|$)|\\s+(?:và|and)\\s+(?:không|not|never|do\\s+not)\\b)").matcher(expected).find();
                boolean unqualified = !java.util.regex.Pattern.compile("(?iuU)\\b(?:if|when|unless|except|nếu|khi|trừ|với\\s+(?!thông\\s+điệp\\b))\\b").matcher(expected).find()
                        && !java.util.regex.Pattern.compile("[\"“”'`]").matcher(expected).find();
                boolean exactBan = unqualified && java.util.regex.Pattern.compile("(?iuU)\\b(?:không\\s+gọi|do\\s+not\\s+call|never\\s+call)\\s+"
                        + identifier + end).matcher(expected).find();
                boolean forbidden = forbiddenSave || exactBan || java.util.regex.Pattern.compile(postfix).matcher(expected).find()
                        || java.util.regex.Pattern.compile(prefix).matcher(expected).find();
                if (forbidden && isPositiveVerification(verify, source)) {
                    problems.add("Approved negative interaction for case " + test.caseId() + ": " + interaction
                            + " must not be called, but " + verify + " requires a positive call. Use the approved never()/times(0) verification; do not drop or reverse the business assertion.");
                }
                if (forbidden && verify.getArguments().size() == 2 && verify.getArgument(1).isMethodCallExpr()) {
                    var mode = verify.getArgument(1).asMethodCallExpr();
                    boolean zero = isUnshadowedMockitoCall(mode, source) && (mode.getNameAsString().equals("never") && mode.getArguments().isEmpty()
                            || mode.getNameAsString().equals("times") && mode.getArguments().size() == 1 && mode.getArgument(0).toString().equals("0"));
                    if (zero && call.getArguments().stream().anyMatch(a -> nullExcludingReferenceMatcher(a, source))) {
                        problems.add("Null-inclusive verification required for " + interaction + ": this approved ban covers every argument, including null. "
                                + "Use ArgumentMatchers.any() with no Class argument, or verifyNoInteractions when all calls are forbidden; preserve the approved negative assertion.");
                    }
                }
            }
        });
    }

    private static boolean nullExcludingReferenceMatcher(Expression argument, CompilationUnit source) {
        if (!argument.isMethodCallExpr()) return false;
        var matcher = argument.asMethodCallExpr();
        boolean reference = Set.of("anyString", "anyList", "anySet", "anyMap", "anyCollection", "anyIterable").contains(matcher.getNameAsString())
                && matcher.getArguments().isEmpty();
        if (matcher.getNameAsString().equals("any") && matcher.getArguments().size() == 1 && matcher.getArgument(0).isClassExpr()) {
            var type = matcher.getArgument(0).asClassExpr().getType();
            reference = type.isClassOrInterfaceType() && !Set.of("Boolean", "Byte", "Character", "Double", "Float", "Integer", "Long", "Short")
                    .contains(type.asClassOrInterfaceType().getNameAsString());
        }
        if (!reference) return false;
        Set<String> owners = Set.of("org.mockito.ArgumentMatchers", "org.mockito.Matchers", "org.mockito.Mockito");
        if (matcher.getScope().isPresent()) {
            String name = matcher.getScope().get().toString();
            var roots = matcher.getScope().get().findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                    .map(n -> n.getNameAsString()).collect(java.util.stream.Collectors.toSet());
            if (source.findAll(VariableDeclarator.class).stream().anyMatch(v -> roots.contains(v.getNameAsString()))
                    || source.findAll(Parameter.class).stream().anyMatch(p -> roots.contains(p.getNameAsString()))
                    || source.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream().anyMatch(t -> roots.contains(t.getNameAsString()))) return false;
            return owners.contains(name) || source.getImports().stream().anyMatch(i -> !i.isStatic() && !i.isAsterisk()
                    && owners.contains(i.getNameAsString()) && i.getName().getIdentifier().equals(name));
        }
        if (source.findAll(MethodDeclaration.class).stream().anyMatch(m -> m.getNameAsString().equals(matcher.getNameAsString()))
                || source.findAll(ClassOrInterfaceDeclaration.class).stream().anyMatch(c -> !c.getExtendedTypes().isEmpty())) return false;
        var explicit = source.getImports().stream().filter(i -> i.isStatic() && !i.isAsterisk()
                && i.getName().getIdentifier().equals(matcher.getNameAsString())).toList();
        if (explicit.stream().anyMatch(i -> !owners.contains(i.getName().getQualifier().map(Object::toString).orElse("")))) return false;
        return source.getImports().stream().anyMatch(i -> i.isStatic() && (i.isAsterisk() && owners.contains(i.getNameAsString())
                || !i.isAsterisk() && explicit.contains(i)));
    }

    private static void validateMockitoVerifyNoInteractions(
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        if (generatedTest == null || generatedTest.sourceCode() == null) return;
        Optional<CompilationUnit> parsed = parseCompilationUnit(generatedTest.sourceCode());
        if (parsed.isEmpty()) return;

        for (MethodDeclaration testMethod : parsed.get().findAll(MethodDeclaration.class)) {
            Map<String, List<ExpressionStmt>> interactionsByMock = new HashMap<>();
            Map<String, List<ExpressionStmt>> clearedByMock = new HashMap<>();
            Map<String, List<ExpressionStmt>> noInteractionsByMock = new HashMap<>();
            for (MethodCallExpr call : testMethod.findAll(MethodCallExpr.class)) {
                String name = call.getNameAsString();
                if ("toString".equals(name) && call.getScope().filter(Expression::isMethodCallExpr)
                        .map(Expression::asMethodCallExpr).filter(scope -> isUnshadowedMockitoCall(scope, parsed.get())
                                && scope.getNameAsString().equals("verify")).isPresent()) {
                    problems.add("Mockito cannot verify toString(). Verify the actual dependency method required by the approved case, "
                            + "using never() or times(0) for a forbidden call; do not add a dummy Object-method interaction.");
                }
                Optional<ExpressionStmt> statement = topLevelStatement(call, testMethod);
                if ("verify".equals(name) && isPositiveVerification(call, parsed.get())) {
                    mockReference(call.getArgument(0)).ifPresent(mock -> statement.ifPresent(value -> interactionsByMock
                            .computeIfAbsent(mock, ignored -> new ArrayList<>()).add(value)));
                } else if ("clearInvocations".equals(name) || "reset".equals(name)) {
                    for (Expression arg : call.getArguments()) {
                        mockReference(arg).ifPresent(mock -> statement.ifPresent(value -> clearedByMock
                                .computeIfAbsent(mock, ignored -> new ArrayList<>()).add(value)));
                    }
                }
                if (!"verifyNoInteractions".equals(name) && !"verifyZeroInteractions".equals(name)) continue;
                for (Expression arg : call.getArguments()) {
                    if (arg.isMethodCallExpr()) {
                        String methodName = arg.asMethodCallExpr().getNameAsString();
                        if (methodName.startsWith("any") || "eq".equals(methodName) || "isNull".equals(methodName) || "notNull".equals(methodName) || "isNotNull".equals(methodName)) {
                            problems.add("verifyNoInteractions() only accepts mock instances (e.g. verifyNoInteractions(repository)); "
                                    + "do not pass argument matchers like " + methodName + "() into verifyNoInteractions(). "
                                    + "To verify a method was never invoked, use verify(mock, never()).method(" + methodName + "()).");
                            break;
                        }
                    }
                    mockReference(arg).ifPresent(mock -> statement.ifPresent(value -> noInteractionsByMock
                            .computeIfAbsent(mock, ignored -> new ArrayList<>()).add(value)));
                }
            }
            noInteractionsByMock.forEach((mock, noInteractionCalls) -> noInteractionCalls.forEach(noInteractionCall -> {
                boolean hasPriorInteraction = interactionsByMock.getOrDefault(mock, List.of()).stream().anyMatch(interaction ->
                        appearsBefore(interaction, noInteractionCall)
                                && clearedByMock.getOrDefault(mock, List.of()).stream()
                                .noneMatch(clearCall -> appearsBefore(interaction, clearCall)
                                        && appearsBefore(clearCall, noInteractionCall)));
                if (hasPriorInteraction) {
                    problems.add("verifyNoInteractions(" + mock + ") contradicts an earlier stub or verify on the same mock. "
                            + "Use verifyNoMoreInteractions(" + mock + ") after explicit verification, or remove the contradictory assertion.");
                }
            }));
        }
    }

    private static void validateMockitoMatcherConsistency(
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        if (generatedTest == null || generatedTest.sourceCode() == null) return;
        Optional<CompilationUnit> parsed = parseCompilationUnit(generatedTest.sourceCode());
        if (parsed.isEmpty()) return;
        CompilationUnit cu = parsed.get();

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if ("when".equals(call.getNameAsString()) && !call.getArguments().isEmpty()
                    && isMockitoWhenCall(call, cu)) {
                Expression target = call.getArgument(0);
                if (target.isMethodCallExpr()) {
                    validateMatcherArguments(target.asMethodCallExpr(), cu, problems);
                }
            }
            if (call.getScope().filter(Expression::isMethodCallExpr)
                    .map(Expression::asMethodCallExpr)
                    .filter(scope -> "verify".equals(scope.getNameAsString())
                            && isMockitoApiCall(scope, "verify", cu))
                    .isPresent()) {
                validateMatcherArguments(call, cu, problems);
            }
            if (call.getScope().filter(Expression::isMethodCallExpr)
                    .map(Expression::asMethodCallExpr)
                    .filter(scope -> "when".equals(scope.getNameAsString())
                            && isMockitoWhenCall(scope, cu)
                            && scope.getScope().filter(Expression::isMethodCallExpr)
                            .map(Expression::asMethodCallExpr)
                            .filter(stubber -> isMockitoStubber(stubber, cu))
                            .isPresent())
                    .isPresent()) {
                validateMatcherArguments(call, cu, problems);
            }
        }
    }

    private static void validateMatcherArguments(
            MethodCallExpr invocation,
            CompilationUnit cu,
            List<String> problems) {
        if (invocation.getArguments().isEmpty()) return;
        boolean hasMatcher = invocation.getArguments().stream()
                .anyMatch(argument -> isMockitoMatcher(argument, cu));
        boolean hasRawArgument = invocation.getArguments().stream()
                .anyMatch(argument -> !isMockitoMatcher(argument, cu));
        if (hasMatcher && hasRawArgument) {
            problems.add("In Mockito when/verify invocations, once one argument is a matcher, all arguments must use Mockito matchers; wrap literals and constants with eq(...).");
        }
    }

    private static boolean isMockitoMatcher(Expression expression, CompilationUnit cu) {
        if (!expression.isMethodCallExpr()) return false;
        MethodCallExpr matcher = expression.asMethodCallExpr();
        String name = matcher.getNameAsString();
        if (!Set.of(
                "any", "anyBoolean", "anyByte", "anyChar", "anyDouble", "anyFloat", "anyInt",
                "anyLong", "anyShort", "anyString", "anyList", "anyMap", "anySet", "anyCollection",
                "argThat", "capture", "contains", "endsWith", "eq", "isA", "isAny", "isNotNull",
                "isNull", "matches", "nullable", "not", "notNull", "refEq", "same", "startsWith")
                .contains(name)) return false;
        if (matcher.getScope().isPresent()) {
            String scope = matcher.getScope().get().toString();
            if (scope.equals("Mockito") || scope.equals("org.mockito.Mockito")
                    || scope.equals("ArgumentMatchers") || scope.equals("org.mockito.ArgumentMatchers")
                    || scope.equals("AdditionalMatchers") || scope.equals("org.mockito.AdditionalMatchers")) {
                return true;
            }
            if ("capture".equals(name) && isArgumentCaptorScope(matcher.getScope().get(), cu)) return true;
            return false;
        }
        return cu.getImports().stream()
                .filter(imported -> imported.isStatic())
                .anyMatch(imported -> imported.isAsterisk()
                        && ("org.mockito.Mockito".equals(imported.getNameAsString())
                        || "org.mockito.ArgumentMatchers".equals(imported.getNameAsString())
                        || "org.mockito.AdditionalMatchers".equals(imported.getNameAsString()))
                        || !imported.isAsterisk()
                        && ("org.mockito.Mockito." + name).equals(imported.getNameAsString())
                        || ("org.mockito.ArgumentMatchers." + name).equals(imported.getNameAsString())
                        || ("org.mockito.AdditionalMatchers." + name).equals(imported.getNameAsString()));
    }

    private static boolean isArgumentCaptorScope(Expression scope, CompilationUnit cu) {
        if (scope.isMethodCallExpr()) {
            MethodCallExpr call = scope.asMethodCallExpr();
            return "forClass".equals(call.getNameAsString())
                    && call.getScope().map(value -> "ArgumentCaptor".equals(value.toString())
                    || "org.mockito.ArgumentCaptor".equals(value.toString())).orElse(false);
        }
        if (!scope.isNameExpr()) return false;
        String name = scope.asNameExpr().getNameAsString();
        return cu.findAll(VariableDeclarator.class).stream()
                .filter(variable -> name.equals(variable.getNameAsString()))
                .anyMatch(variable -> variable.getType().asString().contains("ArgumentCaptor"));
    }

    private static boolean isMockitoWhenCall(MethodCallExpr call, CompilationUnit cu) {
        if (!isMockitoApiCall(call, "when", cu)) {
            return call.getScope().filter(Expression::isMethodCallExpr)
                    .map(Expression::asMethodCallExpr)
                    .map(stubber -> isMockitoStubber(stubber, cu))
                    .orElse(false);
        }
        return true;
    }

    private static boolean isMockitoStubber(MethodCallExpr call, CompilationUnit cu) {
        return Set.of("doAnswer", "doCallRealMethod", "doNothing", "doReturn", "doThrow")
                .contains(call.getNameAsString()) && isMockitoApiCall(call, call.getNameAsString(), cu);
    }

    private static boolean isMockitoApiCall(MethodCallExpr call, String methodName, CompilationUnit cu) {
        if (!methodName.equals(call.getNameAsString())) return false;
        if (call.getScope().isPresent()) {
            return isMockitoScope(call.getScope().get());
        }
        return cu.getImports().stream()
                .filter(imported -> imported.isStatic())
                .anyMatch(imported -> imported.isAsterisk()
                        && ("org.mockito.Mockito".equals(imported.getNameAsString())
                        || "org.mockito.BDDMockito".equals(imported.getNameAsString()))
                        || !imported.isAsterisk()
                        && ("org.mockito.Mockito." + methodName).equals(imported.getNameAsString())
                        || ("org.mockito.BDDMockito." + methodName).equals(imported.getNameAsString()));
    }

    private static void validateTransactionTemplateStubbing(
            MethodContextDto productionMethod,
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        if (productionMethod == null || productionMethod.sourceCode() == null
                || generatedTest == null || generatedTest.sourceCode() == null) return;
        Optional<MethodDeclaration> parsedProduction = parseMethod(productionMethod.sourceCode());
        if (parsedProduction.isEmpty()) return;
        List<MethodCallExpr> productionTransactionCalls = parsedProduction.get().findAll(MethodCallExpr.class).stream()
                .filter(GeneratedUnitTestSemanticValidator::isTransactionMethod)
                .toList();
        if (productionTransactionCalls.isEmpty()) return;

        Optional<CompilationUnit> parsed = parseCompilationUnit(generatedTest.sourceCode());
        if (parsed.isEmpty()) return;
        CompilationUnit cu = parsed.get();
        boolean mocksTransactionTemplate = cu.findAll(FieldDeclaration.class).stream()
                .filter(field -> field.getAnnotationByName("Mock").isPresent())
                .anyMatch(field -> "TransactionTemplate".equals(simpleType(field.getElementType().asString())))
                || cu.findAll(MethodCallExpr.class).stream()
                .filter(call -> "mock".equals(call.getNameAsString()) && !call.getArguments().isEmpty())
                .anyMatch(call -> call.getArgument(0).isClassExpr()
                        && "TransactionTemplate".equals(simpleType(
                        call.getArgument(0).asClassExpr().getType().asString())));
        if (!mocksTransactionTemplate) return;

        List<MethodCallExpr> transactionStubs = cu.findAll(MethodCallExpr.class).stream()
                .filter(GeneratedUnitTestSemanticValidator::isTransactionMethod)
                .filter(call -> transactionStubber(call, cu).isPresent())
                .toList();
        if (transactionStubs.isEmpty()) {
            if (testCanEnterTransaction(productionMethod, generatedTest)) {
                problems.add(transactionCallbackError());
            }
            return;
        }

        Set<String> productionReceivers = productionTransactionCalls.stream()
                .flatMap(call -> call.getScope().stream())
                .flatMap(scope -> mockReference(scope).stream())
                .collect(java.util.stream.Collectors.toSet());
        if (productionReceivers.isEmpty()) {
            problems.add(transactionCallbackError());
            return;
        }
        List<MethodCallExpr> relevantStubs = transactionStubs.stream()
                .filter(stub -> transactionStubReceiver(stub, cu)
                        .map(productionReceivers::contains)
                        .orElse(false))
                .toList();
        if (relevantStubs.isEmpty()
                || relevantStubs.stream().anyMatch(stub -> !transactionStubInvokesCallback(stub, cu))) {
            problems.add(transactionCallbackError());
        }
    }

    private static boolean isTransactionMethod(MethodCallExpr call) {
        return "execute".equals(call.getNameAsString())
                || "executeWithoutResult".equals(call.getNameAsString());
    }

    private static Optional<MethodCallExpr> transactionStubber(
            MethodCallExpr transactionCall,
            CompilationUnit cu) {
        return transactionCall.getScope().filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(whenCall -> "when".equals(whenCall.getNameAsString())
                        && isMockitoWhenCall(whenCall, cu))
                .flatMap(whenCall -> whenCall.getScope()
                        .filter(Expression::isMethodCallExpr)
                        .map(Expression::asMethodCallExpr))
                .filter(stubber -> isMockitoStubber(stubber, cu));
    }

    private static Optional<String> transactionStubReceiver(
            MethodCallExpr transactionCall,
            CompilationUnit cu) {
        return transactionCall.getScope().filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(whenCall -> "when".equals(whenCall.getNameAsString())
                        && isMockitoWhenCall(whenCall, cu))
                .filter(whenCall -> !whenCall.getArguments().isEmpty())
                .flatMap(whenCall -> mockReference(whenCall.getArgument(0)));
    }

    private static boolean transactionStubInvokesCallback(
            MethodCallExpr transactionCall,
            CompilationUnit cu) {
        MethodCallExpr stubber = transactionStubber(transactionCall, cu)
                .orElse(null);
        if (stubber == null) return false;
        String callbackMethod = "executeWithoutResult".equals(transactionCall.getNameAsString())
                ? "accept" : "doInTransaction";
        return stubber.findAll(MethodCallExpr.class).stream()
                .filter(call -> callbackMethod.equals(call.getNameAsString()))
                .anyMatch(callback -> callback.getScope()
                        .map(GeneratedUnitTestSemanticValidator::isFirstInvocationArgument)
                        .orElse(false));
    }

    private static boolean isFirstInvocationArgument(Expression expression) {
        while (expression.isEnclosedExpr() || expression.isCastExpr()) {
            expression = expression.isEnclosedExpr()
                    ? expression.asEnclosedExpr().getInner()
                    : expression.asCastExpr().getExpression();
        }
        if (!expression.isMethodCallExpr()) return false;
        MethodCallExpr call = expression.asMethodCallExpr();
        return "getArgument".equals(call.getNameAsString())
                && call.getArguments().size() == 1
                && call.getArgument(0).isIntegerLiteralExpr()
                && "0".equals(call.getArgument(0).asIntegerLiteralExpr().getValue());
    }

    private static String transactionCallbackError() {
        return "A mocked TransactionTemplate must use doAnswer to invoke the transaction callback: "
                + "executeWithoutResult Consumer callback needs getArgument(0).accept(...), while execute needs "
                + "getArgument(0).doInTransaction(...); otherwise the production transaction body is never executed.";
    }

    private static boolean testCanEnterTransaction(
            MethodContextDto productionMethod,
            GeneratedUnitTestDto generatedTest) {
        Optional<MethodDeclaration> production = parseMethod(productionMethod.sourceCode());
        Optional<CompilationUnit> parsedTest = parseCompilationUnit(generatedTest.sourceCode());
        if (production.isEmpty() || parsedTest.isEmpty()) return false;
        Optional<MethodDeclaration> testMethod = findTestMethod(parsedTest.get(), generatedTest.testMethodName());
        if (testMethod.isEmpty()) return false;

        List<MethodCallExpr> transactionCalls = production.get().findAll(MethodCallExpr.class).stream()
                .filter(GeneratedUnitTestSemanticValidator::isTransactionMethod)
                .toList();
        if (transactionCalls.isEmpty()) return false;

        return testMethod.get().findAll(MethodCallExpr.class).stream()
                .filter(call -> productionMethod.methodName().equals(call.getNameAsString()))
                .filter(call -> isProductionReceiver(
                        testMethod.get(), call, productionMethod.classQualifiedName()))
                .anyMatch(invocation -> transactionCalls.stream()
                        .noneMatch(transactionCall -> hasGuaranteedEarlyReturn(
                                production.get(), transactionCall, invocation)));
    }

    private static boolean hasGuaranteedEarlyReturn(
            MethodDeclaration production,
            MethodCallExpr transactionCall,
            MethodCallExpr invocation) {
        return production.findAll(IfStmt.class).stream()
                .filter(statement -> statement.findAncestor(LambdaExpr.class).isEmpty())
                .filter(statement -> statement.findAncestor(com.github.javaparser.ast.stmt.ForStmt.class).isEmpty())
                .filter(statement -> statement.findAncestor(com.github.javaparser.ast.stmt.ForEachStmt.class).isEmpty())
                .filter(statement -> statement.findAncestor(com.github.javaparser.ast.stmt.WhileStmt.class).isEmpty())
                .filter(statement -> statement.findAncestor(com.github.javaparser.ast.stmt.DoStmt.class).isEmpty())
                .filter(statement -> sameBlock(statement, transactionCall))
                .filter(statement -> appearsBefore(statement, transactionCall))
                .anyMatch(statement -> conditionMatchesInvocation(
                        statement.getCondition(), production, invocation)
                        .map(thenSelected -> thenSelected
                                ? exitsBeforeSwitch(statement.getThenStmt())
                                : statement.getElseStmt()
                                        .map(branch -> exitsBeforeSwitch(branch))
                                        .orElse(false))
                        .orElse(false));
    }

    private static Optional<Boolean> conditionMatchesInvocation(
            Expression condition,
            MethodDeclaration production,
            MethodCallExpr invocation) {
        return evaluateCondition(condition, production, invocation);
    }

    private static Optional<Boolean> evaluateCondition(
            Expression condition,
            MethodDeclaration production,
            MethodCallExpr invocation) {
        Expression unwrapped = condition;
        while (unwrapped.isEnclosedExpr()) unwrapped = unwrapped.asEnclosedExpr().getInner();
        if (unwrapped.isUnaryExpr()
                && unwrapped.asUnaryExpr().getOperator()
                        == com.github.javaparser.ast.expr.UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            return evaluateCondition(unwrapped.asUnaryExpr().getExpression(), production, invocation)
                    .map(value -> !value);
        }
        if (unwrapped.isBinaryExpr()) {
            BinaryExpr binary = unwrapped.asBinaryExpr();
            Optional<Boolean> left = evaluateCondition(binary.getLeft(), production, invocation);
            Optional<Boolean> right = evaluateCondition(binary.getRight(), production, invocation);
            if (binary.getOperator() == BinaryExpr.Operator.OR) {
                if (left.isPresent() && left.get()) return Optional.of(true);
                if (right.isPresent() && right.get()) return Optional.of(true);
                return left.isPresent() && right.isPresent() ? Optional.of(false) : Optional.empty();
            }
            if (binary.getOperator() == BinaryExpr.Operator.AND) {
                if (left.isPresent() && !left.get()) return Optional.of(false);
                if (right.isPresent() && !right.get()) return Optional.of(false);
                return left.isPresent() && right.isPresent() ? Optional.of(true) : Optional.empty();
            }
            if (binary.getOperator() == BinaryExpr.Operator.EQUALS
                    || binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS) {
                Optional<Boolean> leftNull = nullness(binary.getLeft(), production, invocation);
                Optional<Boolean> rightNull = nullness(binary.getRight(), production, invocation);
                if (leftNull.isPresent() && rightNull.isPresent()) {
                    boolean equal = leftNull.get().equals(rightNull.get());
                    return Optional.of(binary.getOperator() == BinaryExpr.Operator.EQUALS ? equal : !equal);
                }
            }
            return Optional.empty();
        }
        if (!unwrapped.isNameExpr()) return Optional.empty();
        return booleanParameterValue(unwrapped.asNameExpr().getNameAsString(), production, invocation);
    }

    private static Optional<Boolean> booleanParameterValue(
            String parameterName,
            MethodDeclaration production,
            MethodCallExpr invocation) {
        for (int index = 0; index < production.getParameters().size(); index++) {
            if (parameterName.equals(production.getParameter(index).getNameAsString())
                    && invocation.getArguments().size() > index) {
                Expression argument = invocation.getArgument(index);
                return argument.isBooleanLiteralExpr()
                        ? Optional.of(argument.asBooleanLiteralExpr().getValue())
                        : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<Boolean> nullness(
            Expression expression,
            MethodDeclaration production,
            MethodCallExpr invocation) {
        Expression unwrapped = expression;
        while (unwrapped.isEnclosedExpr()) unwrapped = unwrapped.asEnclosedExpr().getInner();
        if (unwrapped.isNullLiteralExpr()) return Optional.of(true);
        if (unwrapped.isBooleanLiteralExpr()) return Optional.of(false);
        if (!unwrapped.isNameExpr()) return Optional.empty();
        for (int index = 0; index < production.getParameters().size(); index++) {
            if (unwrapped.asNameExpr().getNameAsString().equals(production.getParameter(index).getNameAsString())
                    && invocation.getArguments().size() > index) {
                Expression argument = invocation.getArgument(index);
                if (argument.isNullLiteralExpr()) return Optional.of(true);
                if (argument.isLiteralExpr()) return Optional.of(false);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<String> mockReference(Expression expression) {
        if (expression.isNameExpr()) return Optional.of(expression.asNameExpr().getNameAsString());
        if (expression.isFieldAccessExpr()) {
            var fieldAccess = expression.asFieldAccessExpr();
            return Optional.of(fieldAccess.getScope().isThisExpr()
                    ? fieldAccess.getNameAsString()
                    : fieldAccess.toString());
        }
        if (expression.isMethodCallExpr()) return Optional.of(expression.toString());
        if (expression.isEnclosedExpr()) return mockReference(expression.asEnclosedExpr().getInner());
        if (expression.isCastExpr()) return mockReference(expression.asCastExpr().getExpression());
        return Optional.empty();
    }

    private static boolean sameBlock(Node first, Node second) {
        return first.findAncestor(BlockStmt.class)
                .flatMap(firstBlock -> second.findAncestor(BlockStmt.class)
                        .map(firstBlock::equals))
                .orElse(false);
    }

    private static boolean isPositiveVerification(MethodCallExpr verifyCall, CompilationUnit source) {
        if (verifyCall.getArguments().isEmpty()) return false;
        if (verifyCall.getArguments().size() == 1) return true;
        Expression mode = verifyCall.getArgument(1);
        if (!mode.isMethodCallExpr()) return false;
        MethodCallExpr modeCall = mode.asMethodCallExpr();
        if (!isUnshadowedMockitoCall(modeCall, source)) return false;
        if (Set.of("atLeastOnce", "only").contains(modeCall.getNameAsString()) && modeCall.getArguments().isEmpty()) return true;
        if (("times".equals(modeCall.getNameAsString()) || "atLeast".equals(modeCall.getNameAsString()))
                && modeCall.getArguments().size() == 1 && modeCall.getArgument(0).isIntegerLiteralExpr()) {
            try {
                return Integer.parseInt(modeCall.getArgument(0).asIntegerLiteralExpr().getValue()) > 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean isUnshadowedMockitoCall(MethodCallExpr call, CompilationUnit source) {
        if (!isMockitoApiCall(call, call.getNameAsString(), source)) return false;
        if (call.getScope().isPresent()) {
            var scope = call.getScope().get();
            var names = scope.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                    .map(n -> n.getNameAsString()).collect(java.util.stream.Collectors.toSet());
            if (source.findAll(VariableDeclarator.class).stream().anyMatch(v -> names.contains(v.getNameAsString()))
                    || source.findAll(Parameter.class).stream().anyMatch(p -> names.contains(p.getNameAsString()))
                    || source.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream().anyMatch(t -> names.contains(t.getNameAsString()))) return false;
            if (scope.isNameExpr() && source.getImports().stream().noneMatch(i -> !i.isStatic()
                    && (i.isAsterisk() && i.getNameAsString().equals("org.mockito")
                        || i.getNameAsString().equals("org.mockito." + scope)))) return false;
        }
        return call.getScope().isPresent() || call.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(c -> c.getMethodsByName(call.getNameAsString()).isEmpty() && c.getExtendedTypes().isEmpty()).orElse(false);
    }

    private static Optional<ExpressionStmt> topLevelStatement(
            MethodCallExpr call,
            MethodDeclaration testMethod) {
        Node current = call;
        while (current.getParentNode().isPresent()) {
            current = current.getParentNode().get();
            if (current instanceof LambdaExpr) return Optional.empty();
            if (current instanceof ExpressionStmt statement && statement.getParentNode()
                    .filter(BlockStmt.class::isInstance)
                    .flatMap(Node::getParentNode)
                    .filter(testMethod::equals)
                    .isPresent()) return Optional.of(statement);
            if (current instanceof MethodDeclaration) return Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean appearsBefore(Node first, Node second) {
        return first.getRange().flatMap(firstRange -> second.getRange().map(secondRange ->
                firstRange.begin.line < secondRange.begin.line
                        || firstRange.begin.line == secondRange.begin.line
                        && firstRange.begin.column < secondRange.begin.column)).orElse(false);
    }

    private static void validateImportsFromContext(
            UnitTestContextDto context,
            MethodContextDto productionMethod,
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        if (context == null || context.classes() == null || generatedTest == null || generatedTest.sourceCode() == null) return;
        Optional<CompilationUnit> parsed = parseCompilationUnit(generatedTest.sourceCode());
        if (parsed.isEmpty()) return;

        Set<String> knownProjectPackages = context.classes().stream()
                .map(ClassContextDto::qualifiedName)
                .map(GeneratedUnitTestSemanticValidator::packageName)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        microserviceRoot(productionMethod).ifPresent(knownProjectPackages::add);
        Map<String, Set<String>> knownQualifiedNames = new HashMap<>();
        Set<String> knownStaticImports = new HashSet<>();
        for (ClassContextDto clazz : context.classes()) {
            if (clazz.className() != null && clazz.qualifiedName() != null) {
                knownQualifiedNames.computeIfAbsent(clazz.className(), ignored -> new HashSet<>())
                        .add(clazz.qualifiedName());
            }
            if (clazz.validationSourceCode() != null) {
                parseCompilationUnit(clazz.validationSourceCode()).ifPresent(source -> source.getImports().forEach(imported -> {
                    if (imported.isStatic()) {
                        knownStaticImports.add(imported.getNameAsString()
                                + (imported.isAsterisk() ? ".*" : ""));
                    } else if (!imported.isAsterisk()) {
                        String importedFqn = imported.getNameAsString();
                        int dot = importedFqn.lastIndexOf('.');
                        if (dot > 0 && isProjectPackage(importedFqn.substring(0, dot), knownProjectPackages)) {
                            String simple = importedFqn.substring(dot + 1);
                            knownQualifiedNames.computeIfAbsent(simple, ignored -> new HashSet<>())
                                    .add(importedFqn);
                        }
                    }
                }));
            }
        }

        for (var importDecl : parsed.get().getImports()) {
            if (importDecl.isStatic()) {
                String importedStatic = importDecl.getNameAsString();
                boolean exactProductionImport = knownStaticImports.contains(importedStatic)
                        || importDecl.isAsterisk() && knownStaticImports.contains(importedStatic + ".*");
                boolean projectOwned = isProjectStaticImport(
                        importedStatic, importDecl.isAsterisk(), knownQualifiedNames,
                        knownProjectPackages, productionMethod);
                if (projectOwned && !exactProductionImport) {
                    problems.add("Unknown project static import '" + importDecl.toString().trim()
                            + "'. Use the exact static import declared by the production source.");
                }
                continue;
            }
            String importedFqn = importDecl.getNameAsString();
            if (importDecl.isAsterisk()) {
                String importedPackage = importedFqn;
                if (isProjectPackage(importedPackage, knownProjectPackages)) {
                    problems.add("Do not use guessed wildcard project import '" + importedFqn
                            + "'. Use the exact import for each type declared in source context.");
                }
                continue;
            }
            int dot = importedFqn.lastIndexOf('.');
            if (dot > 0) {
                String simpleName = importedFqn.substring(dot + 1);
                Set<String> expectedFqns = knownQualifiedNames.get(simpleName);
                boolean usedByTarget = isUsedByTargetService(context, productionMethod, importedFqn);
                if (expectedFqns == null && isProjectPackage(importedFqn.substring(0, dot), knownProjectPackages)
                        && !usedByTarget) {
                    problems.add("Unknown project import '" + importedFqn
                            + "'. Do not guess a package; use the exact FQN declared in source context.");
                }
                if (expectedFqns != null && !usedByTarget
                        && (expectedFqns.size() > 1 || !expectedFqns.contains(importedFqn))) {
                    String expected = expectedFqns.size() == 1
                            ? expectedFqns.iterator().next()
                            : "ambiguous declarations; use the exact import used by the target production class";
                    problems.add("Invalid import '" + importDecl.toString().trim() + "'. "
                            + "Class " + simpleName + " is declared as " + expected + " in this project. "
                            + "Use the exact import: import " + expected + ";");
                }
            }
        }

        microserviceRoot(productionMethod).ifPresent(serviceRoot -> {
            String[] serviceParts = serviceRoot.split("\\.");
            if (serviceParts.length == 2
                    && java.util.Set.of("com", "org", "net", "io", "javax", "jakarta")
                            .contains(serviceParts[0])) return;
            int serviceSeparator = serviceRoot.lastIndexOf('.');
            if (serviceSeparator < 0) return;
            String organizationRoot = serviceRoot.substring(0, serviceSeparator);
            for (var importDecl : parsed.get().getImports()) {
                if (importDecl.isStatic()) continue;
                String importedFqn = importDecl.getNameAsString();
                boolean projectOwned = importedFqn.startsWith(organizationRoot + ".");
                if (importDecl.isAsterisk()) {
                    if (projectOwned) {
                        problems.add("Do not use guessed wildcard project import '" + importedFqn
                                + "'. Use the exact import for each type declared in source context.");
                    }
                    continue;
                }
                int dot = importedFqn.lastIndexOf('.');
                String simpleName = dot > 0 ? importedFqn.substring(dot + 1) : importedFqn;
                Set<String> expectedFqns = knownQualifiedNames.get(simpleName);
                boolean usedByTarget = isUsedByTargetService(context, productionMethod, importedFqn);
                if (projectOwned && expectedFqns == null && !usedByTarget) {
                    problems.add("Unknown project import '" + importedFqn
                            + "'. Do not guess a package; use the exact FQN declared in source context.");
                }
                if (importedFqn.startsWith(organizationRoot + ".")
                        && !importedFqn.startsWith(serviceRoot + ".")
                        && !usedByTarget && !isDeclaredInTargetModule(context, productionMethod, importedFqn)) {
                    problems.add("Import '" + importedFqn + "' belongs to another microservice. "
                            + "Only import DTOs and models under " + serviceRoot
                            + ", except clients declared under " + serviceRoot + ".client.");
                }
            }
        });
    }

    private static boolean isProjectPackage(String importedPackage, Set<String> knownProjectPackages) {
        return knownProjectPackages.stream()
                .anyMatch(knownPackage -> importedPackage.equals(knownPackage)
                        || importedPackage.startsWith(knownPackage + ".")
                        || knownPackage.startsWith(importedPackage + "."));
    }

    private static boolean isProjectStaticImport(
            String importedStatic,
            boolean wildcard,
            Map<String, Set<String>> knownQualifiedNames,
            Set<String> knownProjectPackages,
            MethodContextDto productionMethod) {
        boolean matchesKnownType = knownQualifiedNames.values().stream()
                .flatMap(Set::stream)
                .anyMatch(qualifiedName -> importedStatic.equals(qualifiedName)
                        || importedStatic.startsWith(qualifiedName + "."));
        if (matchesKnownType) return true;

        String owner = wildcard ? importedStatic
                : importedStatic.substring(0, importedStatic.lastIndexOf('.'));
        return isProjectPackage(owner, knownProjectPackages)
                || microserviceRoot(productionMethod)
                        .map(root -> importedStatic.startsWith(root + "."))
                        .orElse(false);
    }

    private static String packageName(String qualifiedName) {
        int lastDot = qualifiedName == null ? -1 : qualifiedName.lastIndexOf('.');
        return lastDot > 0 ? qualifiedName.substring(0, lastDot) : null;
    }

    private static void validateSutDependencyMocks(
            UnitTestContextDto context,
            MethodContextDto productionMethod,
            GeneratedUnitTestDto generatedTest,
            List<String> problems) {
        if (context == null || productionMethod == null || productionMethod.classQualifiedName() == null
                || generatedTest == null || generatedTest.sourceCode() == null) return;
        ClassContextDto sut = context.classes().stream()
                .filter(javaClass -> productionMethod.classQualifiedName().equals(javaClass.qualifiedName()))
                .findFirst().orElse(null);
        if (sut == null) return;
        Optional<CompilationUnit> parsedTest = parseCompilationUnit(generatedTest.sourceCode());
        if (parsedTest.isEmpty()) return;

        for (ObjectCreationExpr creation : parsedTest.get().findAll(ObjectCreationExpr.class)) {
            if (isSutCreation(creation, parsedTest.get(), productionMethod.classQualifiedName())
                    && creation.getArguments().stream().anyMatch(Expression::isNullLiteralExpr)) {
                problems.add("Do not pass null to the System Under Test constructor. Declare a @Mock dependency or use a non-null configuration value.");
            }
        }
        boolean hasSutInjectMocks = parsedTest.get().findAll(FieldDeclaration.class).stream()
                .filter(field -> field.getAnnotationByName("InjectMocks").isPresent())
                .anyMatch(field -> simpleType(field.getElementType().asString()).equals(sut.className()));
        if (!hasSutInjectMocks) return;
        if (explicitSutConstructionBeforeAct(context, productionMethod, generatedTest, parsedTest.get())) return;

        List<String> requiredDependencies = injectedDependencyTypes(sut);
        if (requiredDependencies.isEmpty()) return;

        Map<String, Integer> mockCounts = new HashMap<>();
        Set<String> uncertainSpyTypes = new HashSet<>();
        for (FieldDeclaration field : parsedTest.get().findAll(FieldDeclaration.class)) {
            String type = simpleType(field.getElementType().asString());
            Boolean injectable = field.getAnnotationByName("Mock").isPresent() ? Boolean.TRUE
                    : isMockitoSpyDependency(field, parsedTest.get());
            if (Boolean.FALSE.equals(injectable)) continue;
            Set<String> suppliedTypes = requiredDependencies.contains(type) ? Set.of(type)
                    : sourceAssignableDependencyTypes(field, parsedTest.get(), context, sut);
            if (injectable == null) { uncertainSpyTypes.addAll(suppliedTypes); continue; }
            suppliedTypes.forEach(supplied -> mockCounts.merge(supplied, field.getVariables().size(), Integer::sum));
        }
        Map<String, Integer> requiredCounts = new HashMap<>();
        requiredDependencies.forEach(type -> requiredCounts.merge(type, 1, Integer::sum));
        List<String> missing = requiredCounts.entrySet().stream()
                .filter(entry -> mockCounts.getOrDefault(entry.getKey(), 0) < entry.getValue() && !uncertainSpyTypes.contains(entry.getKey()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            problems.add("The Service constructor or injected fields require @Mock or @Spy dependencies: "
                    + String.join(", ", missing)
                    + ". Declare each dependency as a class-level @Mock or a source-supported real @Spy, or construct the SUT explicitly, so @InjectMocks does not receive null.");
        }

    }

    /** Constructor trực tiếp trước Act thay thế đối tượng do InjectMocks khởi tạo. */
    private static boolean explicitSutConstructionBeforeAct(UnitTestContextDto context, MethodContextDto production,
            GeneratedUnitTestDto generated, CompilationUnit test) {
        var api = new GeneratedTestApiValidator(context);
        var owner = api.declaration(production.classQualifiedName());
        // Constructor không chứng minh field/setter injection đã được khởi tạo.
        if (owner == null || !owner.getExtendedTypes().isEmpty() || owner.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
                .anyMatch(a -> Set.of("Autowired", "Inject", "Resource").contains(a.getName().getIdentifier()))) return false;
        var fields = test.findAll(FieldDeclaration.class).stream().filter(f -> f.getAnnotationByName("InjectMocks").isPresent()
                && f.getElementType().isClassOrInterfaceType()
                && production.classQualifiedName().equals(api.qualifiedType(f.getElementType().asClassOrInterfaceType(), test))).toList();
        var method = findTestMethod(test, generated.testMethodName()).orElse(null);
        if (fields.size() != 1 || fields.get(0).getVariables().size() != 1 || method == null || method.getBody().isEmpty()) return false;
        var field = fields.get(0).getVariable(0);
        var assignments = method.findAll(AssignExpr.class).stream()
                .filter(a -> api.expressionType(a.getTarget(), test) == field.getType()).toList();
        if (assignments.size() != 1) return false;
        var assignment = assignments.get(0);
        if (assignment.getOperator() != AssignExpr.Operator.ASSIGN || !assignment.getValue().isObjectCreationExpr()
                || !isSutCreation(assignment.getValue().asObjectCreationExpr(), test, production.classQualifiedName())
                || assignment.findAncestor(ExpressionStmt.class).filter(s -> s.getExpression() == assignment
                        && s.getParentNode().filter(method.getBody().get()::equals).isPresent()).isEmpty()) return false;
        var calls = method.findAll(MethodCallExpr.class).stream().filter(c -> c.getScope().isPresent()
                && api.expressionType(c.getScope().get(), test) == field.getType()).toList();
        return calls.stream().anyMatch(c -> c.getNameAsString().equals(production.methodName()))
                && assignment.getRange().isPresent() && calls.stream().allMatch(c -> c.getRange().isPresent()
                        && assignment.getRange().get().end.isBefore(c.getRange().get().begin));
    }

    /** Chỉ tính kiểu cha khi source chứng minh được quan hệ; không đoán từ hậu tố Impl. */
    private static Set<String> sourceAssignableDependencyTypes(
            FieldDeclaration field, CompilationUnit test, UnitTestContextDto context, ClassContextDto sut) {
        Set<String> supplied = new HashSet<>();
        if (!field.getElementType().isClassOrInterfaceType()) return supplied;
        supplied.add(simpleType(field.getElementType().asString()));
        var declarations = new ArrayList<>(test.findAll(ClassOrInterfaceDeclaration.class));
        context.classes().forEach(type -> serviceDeclaration(type).ifPresent(declarations::add));
        var service = serviceDeclaration(sut);
        if (service.isEmpty()) return supplied;
        Map<String, Set<String>> requiredBindings = new HashMap<>();
        injectedDependencyReferences(service.get()).forEach(reference -> {
            String identity = dependencyTypeIdentity(reference, declarations);
            if (identity != null) requiredBindings.computeIfAbsent(reference.getNameAsString(), ignored -> new HashSet<>()).add(identity);
        });
        List<ClassOrInterfaceType> pending = new ArrayList<>();
        pending.add(field.getElementType().asClassOrInterfaceType());
        Set<String> visited = new HashSet<>();
        for (int index = 0; index < pending.size(); index++) {
            var reference = pending.get(index);
            String identity = dependencyTypeIdentity(reference, declarations);
            if (identity == null || !visited.add(identity)) continue;
            // Hai interface trùng tên ở package khác nhau không thể thay thế cho nhau.
            if (requiredBindings.getOrDefault(reference.getNameAsString(), Set.of()).equals(Set.of(identity))) {
                supplied.add(reference.getNameAsString());
            }
            var candidates = declarations.stream().filter(d -> d.getFullyQualifiedName().filter(identity::equals).isPresent()).toList();
            if (candidates.size() != 1) continue;
            pending.addAll(candidates.get(0).getExtendedTypes());
            pending.addAll(candidates.get(0).getImplementedTypes());
        }
        return supplied;
    }

    private static String dependencyTypeIdentity(ClassOrInterfaceType reference, List<ClassOrInterfaceDeclaration> declarations) {
        var unit = reference.findCompilationUnit().orElse(null);
        if (unit == null) return null;
        String name = reference.getNameWithScope();
        if (reference.getScope().isPresent()) return name;
        var local = unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> type.getNameAsString().equals(name))
                .flatMap(type -> type.getFullyQualifiedName().stream()).distinct().toList();
        if (!local.isEmpty()) return local.size() == 1 ? local.get(0) : null;
        var imports = unit.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk()
                && i.getName().getIdentifier().equals(name)).map(i -> i.getNameAsString()).distinct().toList();
        if (!imports.isEmpty()) return imports.size() == 1 ? imports.get(0) : null;
        String packagePrefix = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
        if (declarations.stream().flatMap(type -> type.getFullyQualifiedName().stream())
                .anyMatch(fqn -> fqn.equals(packagePrefix + name))) return packagePrefix + name;
        var candidates = declarations.stream().flatMap(type -> type.getFullyQualifiedName().stream())
                .filter(fqn -> unit.getImports().stream().anyMatch(i -> !i.isStatic()
                        && i.isAsterisk() && fqn.equals(i.getNameAsString() + "." + name))).distinct().toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /** null khi binding còn phụ thuộc type kế thừa; không suy thành dependency bị thiếu. */
    static Boolean isMockitoSpyDependency(FieldDeclaration field, CompilationUnit unit) {
        if (field.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("org.mockito.Spy"))) return true;
        if (field.getAnnotations().stream().noneMatch(a -> a.getNameAsString().equals("Spy"))) return false;
        boolean shadowed = field.stream(Node.TreeTraversal.PARENTS)
                .filter(n -> n instanceof com.github.javaparser.ast.body.TypeDeclaration<?>)
                .map(n -> (com.github.javaparser.ast.body.TypeDeclaration<?>) n)
                .anyMatch(owner -> owner.getMembers().stream().anyMatch(member ->
                        member instanceof com.github.javaparser.ast.body.TypeDeclaration<?> type && type.getNameAsString().equals("Spy"))
                        );
        boolean imported = unit.getImports().stream().anyMatch(i -> !i.isStatic()
                && (i.getNameAsString().equals("org.mockito.Spy") || i.isAsterisk() && i.getNameAsString().equals("org.mockito")))
                && unit.getImports().stream().noneMatch(i -> !i.isStatic() && !i.isAsterisk()
                        && i.getName().getIdentifier().equals("Spy") && !i.getNameAsString().equals("org.mockito.Spy"));
        if (!imported || shadowed) return false;
        boolean inherited = field.stream(Node.TreeTraversal.PARENTS).filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .anyMatch(type -> !type.getExtendedTypes().isEmpty() || !type.getImplementedTypes().isEmpty());
        return inherited ? null : Boolean.TRUE;
    }

    private static List<String> injectedDependencyTypes(ClassContextDto sut) {
        Optional<ClassOrInterfaceDeclaration> declaration = serviceDeclaration(sut);
        if (declaration.isEmpty()) return List.of();
        return injectedDependencyReferences(declaration.get()).stream().map(reference -> simpleType(reference.asString()))
                .filter(type -> !isConfigurationType(type)).distinct().toList();
    }

    private static List<ClassOrInterfaceType> injectedDependencyReferences(ClassOrInterfaceDeclaration declaration) {
        List<ClassOrInterfaceType> dependencies = new ArrayList<>();
        declaration.getConstructors().stream()
                .max(java.util.Comparator.comparingInt(constructor -> constructor.getParameters().size()))
                .ifPresent(constructor -> constructor.getParameters().stream()
                        .filter(parameter -> parameter.getAnnotationByName("Value").isEmpty())
                        .map(Parameter::getType).filter(type -> type.isClassOrInterfaceType())
                        .forEach(type -> dependencies.add(type.asClassOrInterfaceType())));

        boolean usesRequiredArgsConstructor = declaration.getAnnotationByName("RequiredArgsConstructor").isPresent();
        for (FieldDeclaration field : declaration.getFields()) {
            boolean requiredByLombok = usesRequiredArgsConstructor
                    && (field.isFinal() || field.getAnnotationByName("NonNull").isPresent());
            if (field.isStatic() || field.getAnnotationByName("Value").isPresent()
                    || !(field.getAnnotationByName("Autowired").isPresent() || requiredByLombok)) continue;
            for (VariableDeclarator variable : field.getVariables()) {
                if (requiredByLombok && variable.getInitializer().isPresent()) continue;
                if (field.getElementType().isClassOrInterfaceType()) dependencies.add(field.getElementType().asClassOrInterfaceType());
            }
        }
        return List.copyOf(dependencies);
    }

    private static boolean isConfigurationType(String type) {
        return java.util.Set.of("String", "Boolean", "Byte", "Character", "Double", "Float", "Integer", "Long",
                "Short", "boolean", "byte", "char", "double", "float", "int", "long", "short").contains(type);
    }

    private static boolean isUsedByTargetService(
            UnitTestContextDto context,
            MethodContextDto productionMethod,
            String importedFqn) {
        if (context == null || productionMethod == null || productionMethod.classQualifiedName() == null) return false;
        return context.classes().stream()
                .filter(javaClass -> productionMethod.classQualifiedName().equals(javaClass.qualifiedName()))
                .map(ClassContextDto::validationSourceCode)
                .filter(java.util.Objects::nonNull)
                .anyMatch(source -> sourceContainsExactQualifiedName(source, importedFqn));
    }

    private static boolean isDeclaredInTargetModule(UnitTestContextDto context, MethodContextDto method, String fqn) {
        var owners = context.classes().stream().filter(c -> method.classQualifiedName().equals(c.qualifiedName())).toList();
        var declarations = context.classes().stream().filter(c -> fqn.equals(c.qualifiedName())).toList();
        if (owners.size() != 1 || declarations.size() != 1) return false;
        var owner = owners.get(0);
        var declaration = declarations.get(0);
        if (owner.filePath() == null || declaration.filePath() == null
                || !owner.filePath().replace('\\', '/').contains("src/main/java/")
                || !declaration.filePath().replace('\\', '/').contains("src/main/java/")) return false;
        // Package con không phải ranh giới microservice; chỉ chấp nhận declaration đã kiểm chứng cùng module.
        return com.greytest.service.ServiceScopeResolver.modulePath(owner.filePath()).equals(
                com.greytest.service.ServiceScopeResolver.modulePath(declaration.filePath()))
                && parseCompilationUnit(declaration.validationSourceCode()).stream()
                        .flatMap(unit -> unit.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream())
                        .anyMatch(type -> type.getFullyQualifiedName().filter(fqn::equals).isPresent());
    }

    private static boolean sourceContainsExactQualifiedName(String source, String qualifiedName) {
        Optional<CompilationUnit> parsed = parseCompilationUnit(source);
        if (parsed.isPresent()) {
            boolean exactImport = parsed.get().getImports().stream()
                    .anyMatch(imported -> !imported.isStatic()
                            && !imported.isAsterisk()
                            && imported.getNameAsString().equals(qualifiedName));
            boolean qualifiedType = parsed.get().findAll(ClassOrInterfaceType.class).stream()
                    .anyMatch(type -> type.getNameWithScope().equals(qualifiedName));
            boolean qualifiedCreation = parsed.get().findAll(ObjectCreationExpr.class).stream()
                    .anyMatch(creation -> creation.getType().getNameWithScope().equals(qualifiedName));
            if (exactImport || qualifiedType || qualifiedCreation) return true;
            return false;
        }
        return false;
    }

    private static Optional<ClassOrInterfaceDeclaration> serviceDeclaration(ClassContextDto sut) {
        if (sut.validationSourceCode() == null || sut.className() == null) return Optional.empty();
        return parseCompilationUnit(sut.validationSourceCode()).flatMap(source -> source.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(declaration -> declaration.getFullyQualifiedName()
                        .map(name -> name.equals(sut.qualifiedName()))
                        .orElse(declaration.getNameAsString().equals(sut.className())
                                && declaration.getParentNode().filter(CompilationUnit.class::isInstance).isPresent()))
                .findFirst());
    }

    private static Optional<String> microserviceRoot(MethodContextDto productionMethod) {
        if (productionMethod == null || productionMethod.classQualifiedName() == null) return Optional.empty();
        String[] parts = productionMethod.classQualifiedName().split("\\.");
        for (int index = 1; index < parts.length - 1; index++) {
            if ("service".equalsIgnoreCase(parts[index])) {
                return Optional.of(String.join(".", java.util.Arrays.copyOf(parts, index)));
            }
        }
        return Optional.empty();
    }

    private static String simpleType(String type) {
        int genericStart = type.indexOf('<');
        String rawType = genericStart < 0 ? type : type.substring(0, genericStart);
        int lastDot = rawType.lastIndexOf('.');
        return lastDot < 0 ? rawType : rawType.substring(lastDot + 1);
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

    private static boolean isProductionReceiver(
            MethodDeclaration testMethod,
            MethodCallExpr call,
            String classQualifiedName) {
        if (call.getScope().isEmpty() || classQualifiedName == null) return false;
        String expectedSimpleName = classQualifiedName.substring(classQualifiedName.lastIndexOf('.') + 1);
        return isProductionReceiver(testMethod, call.getScope().get(), expectedSimpleName);
    }

    private static boolean isProductionReceiver(
            MethodDeclaration testMethod,
            Expression scope,
            String expectedSimpleName) {
        if (scope.isObjectCreationExpr()) {
            return expectedSimpleName.equals(scope.asObjectCreationExpr().getType().getNameAsString());
        }
        if (scope.isNameExpr()) {
            String name = scope.asNameExpr().getNameAsString();
            return expectedSimpleName.equals(name)
                    || isVariableOfType(testMethod, name, expectedSimpleName);
        }
        if (scope.isFieldAccessExpr()) {
            var fieldAccess = scope.asFieldAccessExpr();
            return fieldAccess.getScope().isThisExpr()
                    ? isVariableOfType(testMethod, fieldAccess.getNameAsString(), expectedSimpleName)
                    : isProductionReceiver(testMethod, fieldAccess.getScope(), expectedSimpleName);
        }
        if (scope.isMethodCallExpr()) {
            MethodCallExpr helperCall = scope.asMethodCallExpr();
            if (helperCall.getScope().isEmpty()) {
                return testMethod.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(type -> type.getMethods().stream()
                                .filter(method -> method.getNameAsString().equals(helperCall.getNameAsString()))
                                .map(method -> simpleType(method.getType().asString()))
                                .anyMatch(expectedSimpleName::equals))
                        .orElse(false);
            }
        }
        return false;
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

    private static Optional<CompilationUnit> parseCompilationUnit(String source) {
        var result = javaParser().parse(source);
        return result.isSuccessful() ? result.getResult() : Optional.empty();
    }

    private static JavaParser javaParser() {
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    }

    private static Optional<MethodDeclaration> parseMethod(String source) {
        var result = javaParser().parseMethodDeclaration(source);
        return result.isSuccessful() ? result.getResult() : Optional.empty();
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

}
