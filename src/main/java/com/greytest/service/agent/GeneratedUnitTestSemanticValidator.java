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
import com.github.javaparser.ast.body.MethodDeclaration;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
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
            if (test == null) continue;
            if (test.sourceCode() == null) {
                problems.add("Test case " + test.caseId() + " must contain valid Java test source.");
                continue;
            }
            MethodContextDto productionMethod = methodsByCaseId.get(test.caseId());
            validateFrameworkCompatibility(test, framework, problems);
            validateZeroSkippingPolicy(test, problems);
            validateNoLenientStubbing(test, problems);
            validateRemovedMockitoApis(test, problems);
            validateMockitoRunnerOrExtension(test, framework, problems);
            validateJUnit5AssertionOrder(test, framework, problems);
            validateMockitoVerifyNoInteractions(test, problems);
            validateImportsFromContext(context, productionMethod, test, problems);
            validateMockedEnums(context, test, problems);
            validateInjectMocksStubbing(test, problems);
            validateSutDependencyMocks(context, productionMethod, test, problems);
            validatePrivateMethodCalls(context, test, problems);
            validateAnonymousSutSubclassOverrides(productionMethod, test, problems);
            validateContentCast(test, problems);
            validateMultipartAssumption(productionMethod, test, problems);
            validateNullSwitchExpectation(productionMethod, test, problems);
        }
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join(" ", problems));
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
            GeneratedUnitTestDto generatedTest, List<String> problems) {
        if (generatedTest.sourceCode().matches("(?s).*\\bverifyZeroInteractions\\s*\\(.*")) {
            problems.add("Mockito removed verifyZeroInteractions; use verifyNoInteractions instead.");
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
                        || comment.matches("(?is).*@(?:[\\w.]*)(?:Test|TestFactory|TestTemplate|Disabled|Ignore)\\b.*"));
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
                Optional<ExpressionStmt> statement = topLevelStatement(call, testMethod);
                if ("verify".equals(name) && isPositiveVerification(call)) {
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

    private static Optional<String> mockReference(Expression expression) {
        if (expression.isNameExpr()) return Optional.of(expression.asNameExpr().getNameAsString());
        if (expression.isFieldAccessExpr()) {
            var fieldAccess = expression.asFieldAccessExpr();
            return Optional.of(fieldAccess.getScope().isThisExpr()
                    ? fieldAccess.getNameAsString()
                    : fieldAccess.toString());
        }
        return Optional.empty();
    }

    private static boolean isPositiveVerification(MethodCallExpr verifyCall) {
        if (verifyCall.getArguments().isEmpty()) return false;
        if (verifyCall.getArguments().size() == 1) return true;
        Expression mode = verifyCall.getArgument(1);
        if (!mode.isMethodCallExpr()) return false;
        MethodCallExpr modeCall = mode.asMethodCallExpr();
        if ("atLeastOnce".equals(modeCall.getNameAsString())) return true;
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

        Map<String, String> knownQualifiedNames = new HashMap<>();
        for (ClassContextDto clazz : context.classes()) {
            if (clazz.className() != null && clazz.qualifiedName() != null) {
                knownQualifiedNames.put(clazz.className(), clazz.qualifiedName());
            }
            if (clazz.sourceCode() != null) {
                for (String line : clazz.sourceCode().split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
                        String imported = trimmed.substring(7, trimmed.length() - 1).trim();
                        if (!imported.startsWith("static ") && !imported.endsWith(".*")) {
                            int dot = imported.lastIndexOf('.');
                            if (dot > 0) {
                                String simple = imported.substring(dot + 1);
                                knownQualifiedNames.put(simple, imported);
                            }
                        }
                    }
                }
            }
        }

        for (var importDecl : parsed.get().getImports()) {
            if (importDecl.isStatic() || importDecl.isAsterisk()) continue;
            String importedFqn = importDecl.getNameAsString();
            int dot = importedFqn.lastIndexOf('.');
            if (dot > 0) {
                String simpleName = importedFqn.substring(dot + 1);
                String expectedFqn = knownQualifiedNames.get(simpleName);
                if (expectedFqn != null && !expectedFqn.equals(importedFqn)) {
                    problems.add("Invalid import '" + importDecl.toString().trim() + "'. "
                            + "Class " + simpleName + " is declared as " + expectedFqn + " in this project. "
                            + "Use the exact import: import " + expectedFqn + ";");
                }
            }
        }

        microserviceRoot(productionMethod).ifPresent(serviceRoot -> {
            String organizationRoot = serviceRoot.substring(0, serviceRoot.lastIndexOf('.'));
            for (var importDecl : parsed.get().getImports()) {
                if (importDecl.isStatic() || importDecl.isAsterisk()) continue;
                String importedFqn = importDecl.getNameAsString();
                if (importedFqn.startsWith(organizationRoot + ".")
                        && !importedFqn.startsWith(serviceRoot + ".")
                        && !isUsedByTargetService(context, productionMethod, importedFqn)) {
                    problems.add("Import '" + importedFqn + "' belongs to another microservice. "
                            + "Only import DTOs and models under " + serviceRoot
                            + ", except clients declared under " + serviceRoot + ".client.");
                }
            }
        });
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

        List<String> requiredDependencies = injectedDependencyTypes(sut);
        if (requiredDependencies.isEmpty()) return;

        Map<String, Integer> mockCounts = new HashMap<>();
        for (FieldDeclaration field : parsedTest.get().findAll(FieldDeclaration.class)) {
            if (!field.getAnnotationByName("Mock").isPresent()) continue;
            String type = simpleType(field.getElementType().asString());
            mockCounts.merge(type, field.getVariables().size(), Integer::sum);
        }
        Map<String, Integer> requiredCounts = new HashMap<>();
        requiredDependencies.forEach(type -> requiredCounts.merge(type, 1, Integer::sum));
        List<String> missing = requiredCounts.entrySet().stream()
                .filter(entry -> mockCounts.getOrDefault(entry.getKey(), 0) < entry.getValue())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            problems.add("The Service constructor or injected fields require @Mock dependencies: "
                    + String.join(", ", missing)
                    + ". Declare every dependency as a class-level @Mock so @InjectMocks does not receive null.");
        }

    }

    private static List<String> injectedDependencyTypes(ClassContextDto sut) {
        Optional<ClassOrInterfaceDeclaration> declaration = serviceDeclaration(sut);
        if (declaration.isEmpty()) return List.of();
        java.util.LinkedHashSet<String> dependencies = new java.util.LinkedHashSet<>();
        List<ConstructorDeclaration> constructors = declaration.get().getConstructors();
        constructors.stream()
                .max(java.util.Comparator.comparingInt(constructor -> constructor.getParameters().size()))
                .ifPresent(constructor -> constructor.getParameters().stream()
                        .filter(parameter -> parameter.getAnnotationByName("Value").isEmpty())
                        .forEach(parameter -> addMockableDependency(
                                dependencies, simpleType(parameter.getType().asString()))));

        boolean usesRequiredArgsConstructor = declaration.get().getAnnotationByName("RequiredArgsConstructor").isPresent();
        for (FieldDeclaration field : declaration.get().getFields()) {
            boolean requiredByLombok = usesRequiredArgsConstructor
                    && (field.isFinal() || field.getAnnotationByName("NonNull").isPresent());
            if (field.isStatic() || field.getAnnotationByName("Value").isPresent()
                    || !(field.getAnnotationByName("Autowired").isPresent() || requiredByLombok)) continue;
            for (VariableDeclarator variable : field.getVariables()) {
                if (requiredByLombok && variable.getInitializer().isPresent()) continue;
                addMockableDependency(dependencies, simpleType(field.getElementType().asString()));
            }
        }
        return List.copyOf(dependencies);
    }

    private static void addMockableDependency(java.util.Set<String> dependencies, String type) {
        if (!isConfigurationType(type)) dependencies.add(type);
    }

    private static boolean isConfigurationType(String type) {
        return java.util.Set.of("String", "Boolean", "Byte", "Character", "Double", "Float", "Integer", "Long",
                "Short", "boolean", "byte", "char", "double", "float", "int", "long", "short").contains(type);
    }

    private static boolean isUsedByTargetService(
            UnitTestContextDto context,
            MethodContextDto productionMethod,
            String importedFqn) {
        return context.classes().stream()
                .filter(javaClass -> productionMethod.classQualifiedName().equals(javaClass.qualifiedName()))
                .map(ClassContextDto::sourceCode)
                .filter(java.util.Objects::nonNull)
                .anyMatch(source -> source.contains("import " + importedFqn + ";") || source.contains(importedFqn));
    }

    private static Optional<ClassOrInterfaceDeclaration> serviceDeclaration(ClassContextDto sut) {
        if (sut.sourceCode() == null || sut.className() == null) return Optional.empty();
        return parseCompilationUnit(sut.sourceCode()).flatMap(source -> source.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(declaration -> declaration.getFullyQualifiedName()
                        .map(name -> name.equals(sut.qualifiedName()))
                        .orElse(declaration.getNameAsString().equals(sut.className())
                                && declaration.getParentNode().filter(CompilationUnit.class::isInstance).isPresent()))
                .findFirst());
    }

    private static Optional<String> microserviceRoot(MethodContextDto productionMethod) {
        if (productionMethod == null || productionMethod.classQualifiedName() == null) return Optional.empty();
        String[] parts = productionMethod.classQualifiedName().split("\\.");
        if (parts.length < 4 || !"service".equals(parts[3])) return Optional.empty();
        return Optional.of(parts[0] + "." + parts[1] + "." + parts[2]);
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
