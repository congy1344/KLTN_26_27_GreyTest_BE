package com.greytest.service.generation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.greytest.service.GenerationJobContext;

/** Thu dependency production của mọi setup/test helper; không đưa byte output vào fingerprint. */
final class GeneratedOutputDependencyResolver {
    private final SourceDependencyIndex index;
    private final Map<String, String> dependencies = new TreeMap<>();
    private final Set<String> visited = new HashSet<>();
    private final List<String> problems;
    private String module;

    GeneratedOutputDependencyResolver(SourceDependencyIndex index, List<String> problems, String module) {
        this.index = index;
        this.problems = problems;
        this.module = module;
        visited.add("output-dependency-mode");
    }

    Map<String, String> resolve(List<String> sources) {
        index.outputClasspathEvidence(dependencies);
        for (String source : sources) {
            var parsed = GenerationJobContext.memoize("output-dependency-ast-v1:" + GenerationFingerprintService.hash(source), () -> {
                GenerationJobContext.increment("outputDependencyAstParses");
                return new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(source);
            });
            if (!parsed.isSuccessful()) { problems.add("unresolved output source syntax"); continue; }
            CompilationUnit unit = parsed.getResult().orElseThrow().clone();
            unit.setData(com.github.javaparser.ast.Node.SYMBOL_RESOLVER_KEY, new JavaSymbolSolver(index.outputSolver(module)));
            Set<String> mockNames = mockVariableNames(unit);
            Set<String> mockTypes = mockTypeNames(unit);
            for (ClassOrInterfaceType type : unit.findAll(ClassOrInterfaceType.class)) {
                TypeDeclaration<?> sourceType = sourceType(type.getNameWithScope(), unit);
                if (sourceType != null) {
                    if (mockTypes.contains(sourceType.getNameAsString()) && !isSutType(sourceType, unit)) {
                        index.outputMockContract(sourceType, dependencies, visited, problems);
                    } else {
                        index.outputContract(sourceType, dependencies, visited, problems);
                    }
                }
            }
            var calls = new java.util.ArrayList<>(unit.findAll(MethodCallExpr.class));
            java.util.Collections.reverse(calls);
            for (MethodCallExpr call : calls) resolveCall(call, unit, mockNames, mockTypes);
            // Method reference chỉ bị coi là lỗi khi có JAR để kiểm chứng; không có JAR thì để bước compile thật quyết định.
            if (!unit.findAll(MethodReferenceExpr.class).isEmpty()) {
                if (index.hasDependencyClasspath()) problems.add("unresolved output method reference");
                else dependencies.put("resolved-call:outside-source:method-reference", "external-or-source");
            }
            requireDeclaredTypes(unit);
        }
        return dependencies;
    }

    /** Tên kiểu không import, không cùng package, không thuộc java.lang chắc chắn không compile được. */
    private void requireDeclaredTypes(CompilationUnit unit) {
        if (unit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream().anyMatch(type -> !type.getExtendedTypes().isEmpty())) return;
        if (unit.getImports().stream().anyMatch(item -> !item.isStatic() && item.isAsterisk() && !item.getNameAsString().startsWith("java."))) return;
        Set<String> names = new java.util.TreeSet<>();
        unit.findAll(ClassOrInterfaceType.class).stream().filter(type -> type.getScope().isEmpty() && !isScopeFragment(type)).forEach(type -> names.add(type.getNameAsString()));
        unit.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                .filter(name -> name.getParentNode().filter(parent -> parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == name).isPresent()
                        || parent instanceof com.github.javaparser.ast.expr.FieldAccessExpr).isPresent())
                .map(com.github.javaparser.ast.expr.NameExpr::getNameAsString)
                .filter(name -> Character.isUpperCase(name.charAt(0)) && declaredVariableTypes(name, unit).isEmpty()).forEach(names::add);
        for (String name : names) {
            boolean declaredInTest = unit.findAll(TypeDeclaration.class).stream().anyMatch(type -> type.getNameAsString().equals(name))
                    || unit.findAll(com.github.javaparser.ast.type.TypeParameter.class).stream().anyMatch(type -> type.getNameAsString().equals(name));
            boolean imported = unit.getImports().stream().anyMatch(item -> !item.isAsterisk() && item.getName().getIdentifier().equals(name));
            var reference = new ClassOrInterfaceType(null, name);
            String qualified = index.qualifiedReference(reference, unit, module);
            boolean known = qualified.contains(".") || sourceType(name, unit) != null || isTestHelperInPackage(name, unit);
            if (!declaredInTest && !imported && !known) problems.add("unresolved output type: " + name);
        }
    }

    /** Field đặt qua ReflectionTestUtils phải tồn tại, trừ khi owner có cha ngoài source chưa kiểm chứng được. */
    private void requireFixtureField(MethodCallExpr call, CompilationUnit unit) {
        if (call.getArguments().size() < 2 || !call.getArgument(1).isStringLiteralExpr()) return;
        String field = call.getArgument(1).asStringLiteralExpr().getValue();
        Expression target = call.getArgument(0);
        if (target.isThisExpr()) {
            var test = call.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).orElse(null);
            if (test != null && test.getExtendedTypes().isEmpty() && test.getFieldByName(field).isEmpty()) problems.add("unresolved output field: " + field);
            return;
        }
        TypeDeclaration<?> owner = target.isClassExpr() ? sourceType(target.asClassExpr().getType().asString(), unit) : receiverType(target, unit);
        if (owner != null && !index.mayDeclareField(owner, field, module)) problems.add("unresolved output field: " + identityName(owner) + "#" + field);
    }

    private String identityName(TypeDeclaration<?> type) { return type.getFullyQualifiedName().orElse(type.getNameAsString()); }

    /** Không có JAR dependency thì phần đi qua thư viện ngoài chỉ kiểm chứng được khi compile thật ở máy chạy test. */
    private boolean reachesOutsideSource(MethodCallExpr call, CompilationUnit unit) {
        if (index.hasDependencyClasspath()) return false;
        if (lambdaParameterOfCallOutsideSource(call, unit)) return true;
        TypeDeclaration<?> receiver = call.getScope().map(scope -> receiverType(scope, unit)).orElse(null);
        if (receiver != null) {
            String method = call.getNameAsString();
            if (index.inheritedFromOutsideSource(receiver, method, module) || index.signatureLeavesSource(receiver, method, module)) return true;
            // Solver không tính được kiểu của receiver có cha ngoài source, nên chỉ từ chối khi tự so đối số thấy sai chắc chắn.
            if (index.leavesSource(receiver, module)) return !argumentsProvablyWrong(receiver, call);
            // Source đã kết luận được method này: chỉ đối số mới có thể làm solver thiếu thông tin.
            return index.declaresCall(receiver, method, call.getArguments().size(), module)
                    && call.getArguments().stream().anyMatch(argument -> expressionReachesOutside(argument, unit));
        }
        if (call.getScope().filter(this::hasResolvedType).isPresent()) {
            return namesTypeOutsideSource(call, unit)
                    || call.getArguments().stream().anyMatch(argument -> expressionReachesOutside(argument, unit));
        }
        return expressionReachesOutside(call, unit);
    }

    /** Mọi overload cùng số tham số trong source đều không nhận được ít nhất một đối số đã biết kiểu. */
    private boolean argumentsProvablyWrong(TypeDeclaration<?> owner, MethodCallExpr call) {
        var candidates = index.declaredOverloads(owner, call.getNameAsString(), call.getArguments().size(), module);
        return !candidates.isEmpty() && candidates.stream().allMatch(method -> {
            for (int i = 0; i < call.getArguments().size(); i++) {
                if (argumentMismatch(method.getParameter(i), call.getArgument(i))) return true;
            }
            return false;
        });
    }

    private boolean argumentMismatch(com.github.javaparser.ast.body.Parameter parameter, Expression argument) {
        if (parameter.isVarArgs()) return false;
        try {
            var actual = argument.calculateResolvedType();
            return !actual.isTypeVariable() && !actual.isNull() && !parameter.getType().resolve().isAssignableBy(actual);
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** Receiver tính được kiểu thì lỗi không đến từ nó, ví dụ thenReturn sau một stub đã resolve. */
    private boolean hasResolvedType(Expression expression) {
        try {
            expression.calculateResolvedType();
            return true;
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    private boolean expressionReachesOutside(com.github.javaparser.ast.Node expression, CompilationUnit unit) {
        if (namesTypeOutsideSource(expression, unit)) return true;
        if (expression.findAll(ClassOrInterfaceType.class).stream().anyMatch(type -> !isScopeFragment(type) && sourceTypeLeavesSource(type, unit))) return true;
        for (var name : expression.findAll(com.github.javaparser.ast.expr.NameExpr.class)) {
            if (declaredVariableTypes(name.getNameAsString(), unit).stream().anyMatch(type -> typeOutsideSource(type, unit) || sourceTypeLeavesSource(type, unit))) return true;
        }
        for (MethodCallExpr nested : expression.findAll(MethodCallExpr.class)) {
            Expression scope = nested.getScope().orElse(null);
            if (scope == null) continue;
            if (scope.isFieldAccessExpr() && Character.isLowerCase(scope.toString().charAt(0))
                    && !scope.asFieldAccessExpr().getScope().isThisExpr() && !testFramework(scope.toString())
                    && index.outsideSource(scope.toString(), module)) return true;
            if (scope.isNameExpr() && (importedOutsideSource(scope.asNameExpr().getNameAsString(), unit)
                    || isTestHelperInPackage(scope.asNameExpr().getNameAsString(), unit))) return true;
            TypeDeclaration<?> owner = receiverType(scope, unit);
            if (owner != null && (index.inheritedFromOutsideSource(owner, nested.getNameAsString(), module)
                    || index.signatureLeavesSource(owner, nested.getNameAsString(), module))) return true;
        }
        return false;
    }

    private boolean namesTypeOutsideSource(com.github.javaparser.ast.Node expression, CompilationUnit unit) {
        return expression.findAll(ClassOrInterfaceType.class).stream().anyMatch(type -> !isScopeFragment(type) && typeOutsideSource(type, unit));
    }

    private boolean isTestHelperInPackage(String name, CompilationUnit unit) {
        return index.isTestSource(unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("") + name, module);
    }

    /** Tham số lambda không khai báo kiểu lấy kiểu từ lời gọi bao ngoài, nên phụ thuộc lời gọi đó. */
    private boolean lambdaParameterOfCallOutsideSource(MethodCallExpr call, CompilationUnit unit) {
        Expression scope = call.getScope().orElse(null);
        if (scope == null || !scope.isNameExpr()) return false;
        String name = scope.asNameExpr().getNameAsString();
        for (var node = call.getParentNode().orElse(null); node != null; node = node.getParentNode().orElse(null)) {
            if (!(node instanceof com.github.javaparser.ast.expr.LambdaExpr lambda)
                    || lambda.getParameters().stream().noneMatch(parameter -> parameter.getNameAsString().equals(name) && parameter.getType().isUnknownType())) continue;
            MethodCallExpr outer = null;
            for (var parent = lambda.getParentNode().orElse(null); parent != null; parent = parent.getParentNode().orElse(null)) {
                if (parent instanceof MethodCallExpr candidate) outer = candidate;
            }
            return outer != null && reachesOutsideSource(outer, unit);
        }
        return false;
    }

    /** JavaParser tách java.util.List thành các type lồng nhau; đoạn package không phải tham chiếu kiểu. */
    private boolean isScopeFragment(ClassOrInterfaceType type) {
        return type.getParentNode().filter(ClassOrInterfaceType.class::isInstance)
                .map(ClassOrInterfaceType.class::cast).flatMap(ClassOrInterfaceType::getScope).filter(scope -> scope == type).isPresent();
    }

    private boolean typeOutsideSource(ClassOrInterfaceType type, CompilationUnit unit) {
        if (type.getTypeArguments().stream().flatMap(List::stream).filter(com.github.javaparser.ast.type.Type::isClassOrInterfaceType)
                .anyMatch(argument -> typeOutsideSource(argument.asClassOrInterfaceType(), unit))) return true;
        String name = type.getNameWithScope();
        boolean imported = unit.getImports().stream().anyMatch(item -> !item.isStatic() && !item.isAsterisk()
                && item.getName().getIdentifier().equals(name.contains(".") ? name.substring(0, name.indexOf('.')) : name));
        if (!imported && !name.contains(".")) {
            return isTestHelperInPackage(name, unit) || sourceType(name, unit) == null
                    && !index.qualifiedReference(type, unit, module).contains(".") && index.wildcardMayProvide(name, unit);
        }
        if (!imported && !Character.isLowerCase(name.charAt(0))) return false;
        String qualified = index.qualifiedReference(type, unit, module);
        return !testFramework(qualified) && index.outsideSource(qualified, module);
    }

    /** Class của project có cha ngoài source: solver không tính được kiểu của nó. */
    private boolean sourceTypeLeavesSource(ClassOrInterfaceType type, CompilationUnit unit) {
        TypeDeclaration<?> source = sourceType(type.getNameWithScope(), unit);
        return source != null && index.leavesSource(source, module);
    }

    private boolean importedOutsideSource(String name, CompilationUnit unit) {
        return unit.getImports().stream().anyMatch(item -> !item.isStatic() && !item.isAsterisk() && item.getName().getIdentifier().equals(name)
                && !testFramework(item.getNameAsString()) && index.outsideSource(item.getNameAsString(), module));
    }

    private List<ClassOrInterfaceType> declaredVariableTypes(String name, CompilationUnit unit) {
        var result = new java.util.ArrayList<ClassOrInterfaceType>();
        for (var variable : unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
            if (variable.getNameAsString().equals(name) && variable.getType().isClassOrInterfaceType()) result.add(variable.getType().asClassOrInterfaceType());
        }
        for (var parameter : unit.findAll(com.github.javaparser.ast.body.Parameter.class)) {
            if (parameter.getNameAsString().equals(name) && parameter.getType().isClassOrInterfaceType()) result.add(parameter.getType().asClassOrInterfaceType());
        }
        return result;
    }

    /** Lời gọi gán field static của class khác SUT sẽ làm rò trạng thái sang test sau nếu không khôi phục. */
    private void requireStaticStateRestored(MethodCallExpr call, CompilationUnit unit, Set<String> mockNames) {
        Expression scope = call.getScope().orElse(null);
        if (scope == null || scope.isNameExpr() && mockNames.contains(scope.asNameExpr().getNameAsString())) return;
        TypeDeclaration<?> owner = receiverType(scope, unit);
        if (owner == null || isClassUnderTest(owner, unit)) return;
        for (var method : owner.getMethodsByName(call.getNameAsString())) {
            if (method.getParameters().size() != call.getArguments().size()) continue;
            for (var assignment : method.findAll(com.github.javaparser.ast.expr.AssignExpr.class)) {
                String field = assignment.getTarget().isFieldAccessExpr() ? assignment.getTarget().asFieldAccessExpr().getNameAsString()
                        : assignment.getTarget().isNameExpr() ? assignment.getTarget().asNameExpr().getNameAsString() : null;
                if (field == null || owner.getFieldByName(field).filter(com.github.javaparser.ast.body.FieldDeclaration::isStatic).isEmpty()) continue;
                // Tham số hoặc biến cục bộ trùng tên che field static, phép gán không đổi trạng thái chung.
                if (assignment.getTarget().isNameExpr() && (method.getParameterByName(field).isPresent()
                        || method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream().anyMatch(local -> local.getNameAsString().equals(field)))) continue;
                boolean restored = unit.findAll(MethodCallExpr.class).stream().anyMatch(other -> other.getNameAsString().equals("setField")
                        && other.getArguments().size() >= 2 && other.getArgument(0).isClassExpr()
                        && other.getArgument(0).asClassExpr().getType().asString().endsWith(owner.getNameAsString())
                        && other.getArgument(1).isStringLiteralExpr() && other.getArgument(1).asStringLiteralExpr().getValue().equals(field))
                        || cleanedUpInTest(owner, unit);
                if (!restored) problems.add("unresolved output static state: " + owner.getNameAsString() + "." + field + " is changed through " + call.getNameAsString()
                        + "; save it before the change and restore it with ReflectionTestUtils.setField(" + owner.getNameAsString() + ".class, \"" + field + "\", saved) in @AfterEach");
            }
        }
    }

    /** Chỉ các lỗi này là kết luận của solver về output; lỗi khác là giới hạn của chính JavaParser. */
    private boolean solverGaveUp(RuntimeException exception) {
        return !(exception instanceof com.github.javaparser.resolution.UnsolvedSymbolException
                || exception instanceof com.github.javaparser.resolution.MethodAmbiguityException
                || exception instanceof IllegalArgumentException);
    }

    /** Khối dọn dẹp của test gọi lại class đó: để method của project tự khôi phục, không ép dùng reflection. */
    private boolean cleanedUpInTest(TypeDeclaration<?> owner, CompilationUnit unit) {
        var cleanups = new java.util.ArrayList<com.github.javaparser.ast.Node>();
        unit.findAll(MethodDeclaration.class).stream()
                .filter(method -> Set.of("AfterEach", "After", "AfterAll", "AfterClass").stream().anyMatch(name -> method.getAnnotationByName(name).isPresent()))
                .forEach(cleanups::add);
        unit.findAll(com.github.javaparser.ast.stmt.TryStmt.class).forEach(attempt -> attempt.getFinallyBlock().ifPresent(cleanups::add));
        return cleanups.stream().flatMap(block -> block.findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> call.getScope().map(scope -> receiverType(scope, unit)).filter(type -> type == owner).isPresent());
    }

    /** Class đang được test tự đổi trạng thái của nó là hành vi cần kiểm thử, không phải fixture bị rò. */
    private boolean isClassUnderTest(TypeDeclaration<?> type, CompilationUnit unit) {
        return unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream().anyMatch(field -> field.getAnnotationByName("InjectMocks").isPresent()
                && field.getElementType().asString().endsWith(type.getNameAsString()))
                || unit.getTypes().stream().anyMatch(test -> test.getNameAsString().startsWith(type.getNameAsString()));
    }

    private void resolveCall(MethodCallExpr call, CompilationUnit unit, Set<String> mockNames, Set<String> mockTypes) {
        requireStaticStateRestored(call, unit, mockNames);
        if (knownSpringFixture(call, unit)) {
            requireFixtureField(call, unit);
            return;
        }
        try {
            bindMockitoMatchers(call, unit);
            bindPolyFactoryArguments(call);
            var resolved = call.resolve();
            String declaringType = resolved.declaringType().getQualifiedName();
            if (declaringType.startsWith("org.mockito.")) {
                var usage = com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.get(index.outputSolver(module)).solveMethodAsUsage(call);
                for (int i = 0; i < call.getArguments().size(); i++) {
                    var argument = call.getArgument(i);
                    if (argument.isLambdaExpr()) continue;
                    int parameter = Math.min(i, usage.getNoParams() - 1);
                    var expected = usage.getParamType(parameter);
                    var actual = argument.calculateResolvedType();
                    if (resolved.hasVariadicParameter() && parameter == usage.getNoParams() - 1 && expected.isArray()
                            && !(i == parameter && actual.isArray() && expected.isAssignableBy(actual)))
                        expected = expected.asArrayType().getComponentType();
                    if (!expected.isAssignableBy(actual))
                        throw new IllegalArgumentException("Mockito argument incompatible with " + usage.getQualifiedSignature());
                }
                dependencies.put("resolved-mockito:" + usage.getQualifiedSignature(), usage.returnType().describe());
            }
            if (declaringType.startsWith("java.lang.reflect.") || declaringType.startsWith("java.lang.invoke.")
                    || declaringType.equals("org.springframework.test.util.ReflectionTestUtils") && call.getNameAsString().startsWith("invoke")
                    || declaringType.equals("java.lang.Class") && Set.of("getDeclaredMethod", "getMethod", "newInstance").contains(call.getNameAsString())) {
                problems.add("unresolved reflective output API");
            }
            TypeDeclaration<?> owner = productionType(declaringType);
            if (owner != null && !resolved.isStatic() && call.getScope().isPresent()) {
                Expression receiver = call.getScope().orElseThrow();
                if (receiver.isNameExpr()) receiver.asNameExpr().resolve();
                else if (receiver.isFieldAccessExpr()) receiver.asFieldAccessExpr().resolve();
                else if (receiver.isTypeExpr()) throw new IllegalArgumentException("Instance method called through a type");
            }
            if (owner != null) {
                boolean mock = isMockReceiver(call, unit, mockNames, mockTypes);
                if (mock) {
                    index.outputMockMethod(owner, resolved.getName(), resolved.getSignature(),
                            call.getArguments().size(), dependencies, visited, problems);
                } else {
                    index.outputMethod(owner, resolved.getName(), resolved.getSignature(),
                            call.getArguments().size(), call, dependencies, visited, problems);
                }
                return;
            }
        } catch (RuntimeException exception) {
            if (exception instanceof com.github.javaparser.resolution.UnsolvedSymbolException
                    && resolveOngoingStubbingFallback(call, unit)) return;
            // Gọi method production qua reflection luôn bị chặn, kể cả khi thư viện reflection không có trên classpath.
            boolean reflective = call.getNameAsString().startsWith("invoke") && calledOnSpringFixture(call, unit);
            // Hai kiểu đã resolve mà vẫn không gán được thì source bác bỏ được, trừ khi kiểu đó thuộc thư viện ngoài.
            boolean provenMismatch = exception instanceof IllegalArgumentException;
            // JavaParser tự lỗi khi suy luận kiểu (ví dụ new HashSet<>(...) trong thenReturn) thì chưa kết luận được gì về output.
            if (!reflective && solverGaveUp(exception)) {
                dependencies.put("resolved-call:solver-gave-up:" + call.getNameAsString(), "external-or-source");
                return;
            }
            if (!reflective && !index.hasDependencyClasspath()
                    && (provenMismatch ? namesTypeOutsideSource(call, unit) : reachesOutsideSource(call, unit))) {
                dependencies.put("resolved-call:outside-source:" + call.getNameAsString(), "external-or-source");
                return;
            }
            if (mockitoApi(call, unit)) {
                problems.add("unresolved Mockito signature: " + call.getNameAsString() + " (" + exception.getClass().getSimpleName()
                        + ": " + java.util.Objects.toString(exception.getMessage(), "") + ")" + bareAnyHint(call));
                return;
            }
            TypeDeclaration<?> owner = call.getScope().map(scope -> receiverType(scope, unit)).orElse(null);
            if (owner != null) {
                boolean mock = isMockReceiver(call, unit, mockNames, mockTypes);
                if (mock) {
                    index.outputMockMethod(owner, call.getNameAsString(), null, call.getArguments().size(), dependencies, visited, problems);
                } else {
                    index.outputMethod(owner, call.getNameAsString(), null, call.getArguments().size(), call, dependencies, visited, problems);
                }
                return;
            }
            if (knownMockitoInvocation(call, unit) || knownExternal(call, unit) || ownTestHelper(call, unit)) return;
            problems.add("unresolved output call: " + call.getNameAsString());
        }
    }

    /**
     * Fallback cho method chaining của Mockito (thenReturn, thenThrow...) khi JavaParser
     * SymbolSolver không suy luận được generic type lồng nhau trên OngoingStubbing.
     */
    private boolean resolveOngoingStubbingFallback(MethodCallExpr call, CompilationUnit unit) {
        if (!Set.of("thenReturn", "thenThrow", "thenAnswer", "thenCallRealMethod", "then").contains(call.getNameAsString())) {
            return false;
        }
        if (call.getScope().isEmpty()) return false;
        MethodCallExpr whenCall = findEnclosingWhenCall(call.getScope().get());
        if (whenCall == null || whenCall.getArguments().size() != 1) return false;
        try {
            var expectedType = whenCall.getArgument(0).calculateResolvedType();
            if ("thenCallRealMethod".equals(call.getNameAsString())) {
                dependencies.put("resolved-mockito:org.mockito.stubbing.OngoingStubbing.thenCallRealMethod()", expectedType.describe());
                return true;
            }
            if ("thenThrow".equals(call.getNameAsString())) {
                dependencies.put("resolved-mockito:org.mockito.stubbing.OngoingStubbing.thenThrow()", expectedType.describe());
                return true;
            }
            if ("thenAnswer".equals(call.getNameAsString()) || "then".equals(call.getNameAsString())) {
                dependencies.put("resolved-mockito:org.mockito.stubbing.OngoingStubbing.thenAnswer()", expectedType.describe());
                return true;
            }
            if ("thenReturn".equals(call.getNameAsString()) && !call.getArguments().isEmpty()) {
                for (int i = 0; i < call.getArguments().size(); i++) {
                    var arg = call.getArgument(i);
                    var actualType = arg.calculateResolvedType();
                    if (i > 0 && actualType.isArray()) {
                        actualType = actualType.asArrayType().getComponentType();
                    }
                    if (!isStubCompatible(expectedType, actualType)) {
                        return false;
                    }
                }
                dependencies.put("resolved-mockito:org.mockito.stubbing.OngoingStubbing.thenReturn(" + expectedType.describe() + ")", expectedType.describe());
                return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private MethodCallExpr findEnclosingWhenCall(Expression expr) {
        if (expr.isMethodCallExpr()) {
            MethodCallExpr call = expr.asMethodCallExpr();
            if ("when".equals(call.getNameAsString())) return call;
            if (call.getScope().isPresent()) return findEnclosingWhenCall(call.getScope().get());
        }
        return null;
    }

    private boolean isStubCompatible(com.github.javaparser.resolution.types.ResolvedType expected,
                                     com.github.javaparser.resolution.types.ResolvedType actual) {
        if (expected.describe().equals(actual.describe())) return true;
        try {
            if (expected.isAssignableBy(actual)) return true;
        } catch (RuntimeException ignored) {
        }
        if (expected.isReferenceType() && actual.isReferenceType()) {
            var expRef = expected.asReferenceType();
            var actRef = actual.asReferenceType();
            String expName = expRef.getQualifiedName();
            String actName = actRef.getQualifiedName();
            if (expName.equals(actName)) {
                var expParams = expRef.typeParametersValues();
                var actParams = actRef.typeParametersValues();
                if (expParams.isEmpty() && actParams.isEmpty()) return true;
                if (expParams.size() == actParams.size()) {
                    for (int p = 0; p < expParams.size(); p++) {
                        if (!isStubCompatible(expParams.get(p), actParams.get(p))) return false;
                    }
                    return true;
                }
                return false;
            }
            if (isCollectionAssignable(expName, actName)) {
                var expParams = expRef.typeParametersValues();
                var actParams = actRef.typeParametersValues();
                if (expParams.isEmpty() || actParams.isEmpty()) return true;
                if (expParams.size() == 1 && actParams.size() == 1) {
                    return isStubCompatible(expParams.get(0), actParams.get(0));
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isCollectionAssignable(String expectedType, String actualType) {
        if (expectedType.equals(actualType)) {
            return Set.of("java.util.Collection", "java.util.List", "java.util.ArrayList",
                    "java.util.Set", "java.util.HashSet", "java.lang.Iterable").contains(expectedType);
        }
        if ("java.util.Collection".equals(expectedType) || "java.lang.Iterable".equals(expectedType)) {
            return Set.of("java.util.Collection", "java.util.List", "java.util.ArrayList",
                    "java.util.Set", "java.util.HashSet").contains(actualType);
        }
        if ("java.util.List".equals(expectedType)) {
            return Set.of("java.util.List", "java.util.ArrayList").contains(actualType);
        }
        if ("java.util.Set".equals(expectedType)) {
            return Set.of("java.util.Set", "java.util.HashSet").contains(actualType);
        }
        return false;
    }

    /** Gán target type cho matcher generic khi chữ ký production duy nhất đã được chứng minh. */
    private void bindMockitoMatchers(MethodCallExpr call, CompilationUnit unit) {
        if (call.getScope().isEmpty()) return;
        TypeDeclaration<?> owner = receiverType(call.getScope().orElseThrow(), unit);
        if (owner == null) return;
        var methods = owner.getMethodsByName(call.getNameAsString()).stream()
                .filter(m -> m.getParameters().size() == call.getArguments().size()).toList();
        if (methods.size() != 1) return;
        for (int i = 0; i < call.getArguments().size(); i++) {
            var argument = call.getArgument(i);
            if (!argument.isMethodCallExpr()) continue;
            var matcher = argument.asMethodCallExpr();
            if (!matcher.getArguments().isEmpty() || matcher.getTypeArguments().isPresent()) continue;
            var declared = matcher.resolve();
            if (!declared.declaringType().getQualifiedName().equals("org.mockito.ArgumentMatchers")
                    || !declared.getReturnType().isTypeVariable()) continue;
            var type = methods.get(0).getParameter(i).getType().resolve();
            var receiver = call.getScope().orElseThrow().calculateResolvedType();
            if (receiver.isReferenceType()) type = receiver.asReferenceType().typeParametersMap().replaceAll(type);
            if (!type.isReferenceType() || hasFreeTypeVariable(type)) continue;
            matcher.setTypeArguments(new com.github.javaparser.ast.NodeList<>(com.github.javaparser.StaticJavaParser.parseType(type.describe())));
        }
    }

    private boolean hasFreeTypeVariable(com.github.javaparser.resolution.types.ResolvedType type) {
        if (type.isTypeVariable()) return true;
        if (type.isArray()) return hasFreeTypeVariable(type.asArrayType().getComponentType());
        if (type.isWildcard()) return type.asWildcard().isBounded() && hasFreeTypeVariable(type.asWildcard().getBoundedType());
        return type.isReferenceType() && type.asReferenceType().typeParametersValues().stream().anyMatch(this::hasFreeTypeVariable);
    }

    /** Tránh lỗi JavaParser suy ra Optional<Optional<T>> khi factory generic nằm trong thenReturn. */
    private void bindPolyFactoryArguments(MethodCallExpr call) {
        if (!call.getNameAsString().equals("thenReturn") || call.getScope().isEmpty()) return;
        var receiver = call.getScope().orElseThrow().calculateResolvedType();
        if (!receiver.isReferenceType() || !receiver.asReferenceType().getQualifiedName().equals("org.mockito.stubbing.OngoingStubbing")) return;
        var values = receiver.asReferenceType().typeParametersValues();
        if (values.size() != 1 || !values.get(0).isReferenceType()) return;
        var expected = values.get(0).asReferenceType();
        for (Expression argument : call.getArguments()) {
            if (!argument.isMethodCallExpr()) continue;
            var factory = argument.asMethodCallExpr();
            if (!factory.getArguments().isEmpty() || factory.getTypeArguments().isPresent()) continue;
            var method = factory.resolve();
            if (!method.isStatic() || !method.getReturnType().isReferenceType() || method.getTypeParameters().isEmpty()) continue;
            var returned = method.getReturnType().asReferenceType();
            if (!returned.getQualifiedName().equals(expected.getQualifiedName())) continue;
            var parameters = returned.typeParametersValues();
            if (parameters.size() != expected.typeParametersValues().size()) continue;
            var witnesses = new com.github.javaparser.ast.NodeList<com.github.javaparser.ast.type.Type>();
            for (var parameter : method.getTypeParameters()) {
                int position = -1;
                for (int i = 0; i < parameters.size(); i++)
                    if (parameters.get(i).isTypeVariable() && parameters.get(i).asTypeVariable().asTypeParameter().equals(parameter)) position = i;
                if (position < 0 || !expected.typeParametersValues().get(position).isReferenceType()) { witnesses.clear(); break; }
                var witness = expected.typeParametersValues().get(position);
                if (parameter.getBounds().stream().anyMatch(bound -> !bound.isExtends() || !bound.getType().isAssignableBy(witness))) {
                    witnesses.clear(); break;
                }
                witnesses.add(com.github.javaparser.StaticJavaParser.parseType(expected.typeParametersValues().get(position).describe()));
            }
            // Chỉ thêm type witness trên AST riêng của validator; không sửa byte output đã lưu.
            if (witnesses.size() == method.getTypeParameters().size()) factory.setTypeArguments(witnesses);
        }
    }

    private Set<String> mockVariableNames(CompilationUnit unit) {
        Set<String> names = new HashSet<>();
        for (var field : unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class)) {
            boolean isMock = field.getAnnotations().stream().anyMatch(a -> {
                String name = a.getNameAsString();
                return name.endsWith("Mock") || name.endsWith("MockBean");
            });
            if (isMock) {
                field.getVariables().forEach(v -> names.add(v.getNameAsString()));
            }
        }
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if ("mock".equals(call.getNameAsString()) && !call.getArguments().isEmpty()) {
                call.getParentNode().ifPresent(parent -> {
                    if (parent instanceof com.github.javaparser.ast.body.VariableDeclarator v) names.add(v.getNameAsString());
                    else if (parent instanceof com.github.javaparser.ast.expr.AssignExpr a && a.getTarget().isNameExpr()) names.add(a.getTarget().asNameExpr().getNameAsString());
                });
            }
        }
        return names;
    }

    private Set<String> mockTypeNames(CompilationUnit unit) {
        Set<String> types = new HashSet<>();
        for (var field : unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class)) {
            boolean isMock = field.getAnnotations().stream().anyMatch(a -> {
                String name = a.getNameAsString();
                return name.endsWith("Mock") || name.endsWith("MockBean");
            });
            if (isMock) {
                types.add(field.getElementType().asString());
            }
        }
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if ("mock".equals(call.getNameAsString()) && !call.getArguments().isEmpty()) {
                var arg = call.getArgument(0);
                if (arg instanceof com.github.javaparser.ast.expr.ClassExpr classExpr) {
                    types.add(classExpr.getType().asString());
                }
            }
        }
        return types;
    }

    private boolean isSutType(TypeDeclaration<?> type, CompilationUnit unit) {
        String simpleName = type.getNameAsString();
        for (var field : unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class)) {
            if (field.getElementType().asString().equals(simpleName)) {
                if (field.getAnnotations().stream().anyMatch(a -> a.getNameAsString().endsWith("InjectMocks"))) {
                    return true;
                }
            }
        }
        for (var creation : unit.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class)) {
            if (creation.getType().getNameAsString().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMockReceiver(MethodCallExpr call, CompilationUnit unit, Set<String> mockNames, Set<String> mockTypes) {
        if (call.getScope().isEmpty()) return false;
        Expression scope = call.getScope().orElseThrow();
        if (scope.isMethodCallExpr()) {
            MethodCallExpr wrapper = scope.asMethodCallExpr();
            if (Set.of("verify", "when").contains(wrapper.getNameAsString()) && mockitoApi(wrapper, unit)
                    && !wrapper.getArguments().isEmpty() && wrapper.getArgument(0).isNameExpr()
                    && mockNames.contains(wrapper.getArgument(0).asNameExpr().getNameAsString())) {
                return true;
            }
        }
        if (scope.isNameExpr()) {
            String name = scope.asNameExpr().getNameAsString();
            return mockNames.contains(name);
        }
        if (scope.isFieldAccessExpr() && scope.asFieldAccessExpr().getScope().isThisExpr())
            return mockNames.contains(scope.asFieldAccessExpr().getNameAsString());
        return false;
    }

    /** Chỉ nhận InvocationOnMock từ lambda Answer của Mockito, không bỏ qua API production trong lambda. */
    private boolean knownMockitoInvocation(MethodCallExpr call, CompilationUnit unit) {
        if (!Set.of("getArgument", "getArguments", "getMock", "getMethod", "callRealMethod").contains(call.getNameAsString())
                || call.getScope().filter(Expression::isNameExpr).isEmpty()) return false;
        String name = call.getScope().orElseThrow().asNameExpr().getNameAsString();
        for (var node = call.getParentNode().orElse(null); node != null; node = node.getParentNode().orElse(null)) {
            if (!(node instanceof com.github.javaparser.ast.expr.LambdaExpr lambda)) continue;
            var parameter = lambda.getParameters().stream().filter(p -> p.getNameAsString().equals(name)).findFirst();
            if (parameter.isEmpty()) continue;
            if (lambda.getParameters().size() != 1) return false;
            String type = parameter.orElseThrow().getTypeAsString();
            if (!parameter.orElseThrow().getType().isUnknownType()
                    && !type.equals("org.mockito.invocation.InvocationOnMock")
                    && !(type.equals("InvocationOnMock") && unit.getImports().stream().anyMatch(i -> !i.isStatic()
                            && i.getNameAsString().equals("org.mockito.invocation.InvocationOnMock")))) return false;
            var parent = lambda.getParentNode().orElse(null);
            return parent instanceof MethodCallExpr answer
                    && Set.of("thenAnswer", "doAnswer").contains(answer.getNameAsString())
                    && knownExternal(answer, unit);
        }
        return false;
    }
    private boolean knownSpringFixture(MethodCallExpr call, CompilationUnit unit) {
        return Set.of("setField", "getField").contains(call.getNameAsString()) && calledOnSpringFixture(call, unit);
    }

    private boolean calledOnSpringFixture(MethodCallExpr call, CompilationUnit unit) {
        String fixture = "org.springframework.test.util.ReflectionTestUtils";
        if (productionType(fixture) != null) return false;
        if (call.getScope().isEmpty()) return unit.getImports().stream().anyMatch(imported -> imported.isStatic()
                && (imported.isAsterisk() && imported.getNameAsString().equals(fixture)
                    || imported.getNameAsString().equals(fixture + "." + call.getNameAsString())));
        String qualifier = call.getScope().orElseThrow().toString();
        if (qualifier.equals(fixture)) return true;
        return qualifier.equals("ReflectionTestUtils") && unit.getImports().stream()
                .anyMatch(imported -> !imported.isStatic() && imported.getNameAsString().equals(fixture));
    }

    private TypeDeclaration<?> receiverType(Expression scope, CompilationUnit unit) {
        if (scope.isMethodCallExpr()) {
            MethodCallExpr wrapper = scope.asMethodCallExpr();
            boolean stubberWhen = wrapper.getNameAsString().equals("when")
                    && wrapper.getScope().filter(Expression::isMethodCallExpr).isPresent();
            if ((Set.of("verify", "spy").contains(wrapper.getNameAsString()) || stubberWhen)
                    && !wrapper.getArguments().isEmpty() && knownExternal(wrapper, unit)) {
                TypeDeclaration<?> wrapped = receiverType(wrapper.getArgument(0), unit);
                if (wrapped != null) return wrapped;
            }
        }
        try {
            var type = scope.calculateResolvedType();
            if (type.isReferenceType()) return productionType(type.asReferenceType().getQualifiedName());
        } catch (RuntimeException exception) { /* Static type qualifiers are not value expressions. */ }
        return sourceType(scope.toString(), unit);
    }

    /** Thông điệp thô của solver không chỉ ra cách sửa, nên AI lặp lại đúng lỗi này ở lượt sau. */
    private String bareAnyHint(MethodCallExpr call) {
        boolean bareAny = call.getArguments().stream().anyMatch(argument -> argument.isMethodCallExpr()
                && argument.asMethodCallExpr().getNameAsString().equals("any")
                && argument.asMethodCallExpr().getArguments().isEmpty()
                && argument.asMethodCallExpr().getTypeArguments().isEmpty());
        return bareAny ? " If the method is overloaded, a bare any() does not compile (ambiguous); keep it null-inclusive with an explicit type, for example ArgumentMatchers.<java.util.List<String>>any(), once per overload the ban covers." : "";
    }

    private boolean mockitoApi(MethodCallExpr call, CompilationUnit unit) {
        if (call.getScope().filter(Expression::isMethodCallExpr).isPresent())
            return mockitoApi(call.getScope().orElseThrow().asMethodCallExpr(), unit);
        if (call.getScope().isPresent()) {
            Expression receiver = call.getScope().orElseThrow();
            try {
                var type = receiver.calculateResolvedType();
                if (type.isReferenceType() && type.asReferenceType().getQualifiedName().startsWith("org.mockito.")) return true;
            } catch (RuntimeException unresolved) { /* Kiểm tra qualifier được import bên dưới. */ }
            String scope = receiver.toString();
            return scope.startsWith("org.mockito.") || unit.getImports().stream()
                    .anyMatch(i -> !i.isStatic() && i.getNameAsString().startsWith("org.mockito.")
                            && i.getName().getIdentifier().equals(scope));
        }
        return unit.getImports().stream().anyMatch(i -> i.isStatic()
                && i.getNameAsString().startsWith("org.mockito.")
                && (i.isAsterisk() || i.getName().getIdentifier().equals(call.getNameAsString())));
    }

    private TypeDeclaration<?> sourceType(String name, CompilationUnit unit) {
        if (name.contains(".")) return productionType(name);
        for (var imported : unit.getImports()) {
            if (!imported.isAsterisk() && !imported.isStatic() && imported.getName().getIdentifier().equals(name)) {
                return productionType(imported.getNameAsString());
            }
        }
        String local = unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("") + name;
        TypeDeclaration<?> type = productionType(local);
        if (type != null) return type;
        TypeDeclaration<?> selected = null;
        for (var imported : unit.getImports()) {
            if (!imported.isAsterisk() || imported.isStatic()) continue;
            var candidate = productionType(imported.getNameAsString() + "." + name);
            if (candidate == null) continue;
            if (selected != null) { problems.add("unresolved output wildcard type: " + name); return null; }
            selected = candidate;
        }
        return selected;
    }

    private boolean ownTestHelper(MethodCallExpr call, CompilationUnit unit) {
        if (call.getScope().isPresent() && !call.getScope().orElseThrow().isThisExpr()) return false;
        return unit.findAll(MethodDeclaration.class).stream().anyMatch(method -> method.getNameAsString().equals(call.getNameAsString())
                && method.getParameters().size() == call.getArguments().size());
    }

    private boolean knownExternal(MethodCallExpr call, CompilationUnit unit) {
        if (call.getScope().isPresent()) {
            Expression scope = call.getScope().orElseThrow();
            if (scope.isMethodCallExpr()) return knownExternal(scope.asMethodCallExpr(), unit);
            try {
                var type = scope.calculateResolvedType();
                if (type.isReferenceType()) {
                    String name = type.asReferenceType().getQualifiedName();
                    if (productionType(name) != null) return false;
                    return name.startsWith("java.") || name.startsWith("org.junit.") || name.startsWith("org.mockito.")
                            || name.startsWith("org.assertj.");
                }
            } catch (RuntimeException exception) { /* Check explicitly imported test framework qualifiers below. */ }
            String qualifier = scope.toString();
            for (var imported : unit.getImports()) {
                if (!imported.isStatic() && imported.getName().getIdentifier().equals(qualifier)
                        && testFramework(imported.getNameAsString())) return true;
            }
            if (isTestFrameworkVariable(scope, unit)) return true;
            return testFramework(qualifier);
        }
        return unit.getImports().stream().anyMatch(imported -> imported.isStatic() && testFramework(imported.getNameAsString())
                && (imported.isAsterisk() || imported.getName().getIdentifier().equals(call.getNameAsString())));
    }

    private boolean isTestFrameworkVariable(Expression scope, CompilationUnit unit) {
        if (!scope.isNameExpr()) return false;
        String varName = scope.asNameExpr().getNameAsString();
        for (var declarator : unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
            if (declarator.getNameAsString().equals(varName)) {
                String typeName = declarator.getType().asString();
                if (typeName.contains("<")) typeName = typeName.substring(0, typeName.indexOf('<')).trim();
                for (var imported : unit.getImports()) {
                    if (!imported.isStatic() && imported.getName().getIdentifier().equals(typeName)
                            && testFramework(imported.getNameAsString())) return true;
                }
                return testFramework(typeName) || typeName.equals("ArgumentCaptor");
            }
        }
        return false;
    }

    private boolean testFramework(String name) {
        return name.startsWith("org.junit.") || name.startsWith("org.mockito.") || name.startsWith("org.assertj.");
    }

    private TypeDeclaration<?> productionType(String name) { return index.outputType(name, module, problems); }
}
