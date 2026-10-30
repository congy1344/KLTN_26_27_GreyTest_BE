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
            if (!unit.findAll(MethodReferenceExpr.class).isEmpty()) problems.add("unresolved output method reference");
        }
        return dependencies;
    }

    private void resolveCall(MethodCallExpr call, CompilationUnit unit, Set<String> mockNames, Set<String> mockTypes) {
        if (knownSpringFixture(call, unit)) return;
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
            if (mockitoApi(call, unit)) {
                problems.add("unresolved Mockito signature: " + call.getNameAsString() + " (" + exception.getClass().getSimpleName()
                        + ": " + java.util.Objects.toString(exception.getMessage(), "") + ")");
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
        if (!Set.of("setField", "getField").contains(call.getNameAsString())) return false;
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
