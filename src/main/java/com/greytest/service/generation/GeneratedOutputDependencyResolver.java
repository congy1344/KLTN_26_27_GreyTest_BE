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
        index.classpathEvidence(dependencies);
        for (String source : sources) {
            var parsed = GenerationJobContext.memoize("output-dependency-ast-v1:" + GenerationFingerprintService.hash(source), () -> {
                GenerationJobContext.increment("outputDependencyAstParses");
                return new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(source);
            });
            if (!parsed.isSuccessful()) { problems.add("unresolved output source syntax"); continue; }
            CompilationUnit unit = parsed.getResult().orElseThrow().clone();
            unit.setData(com.github.javaparser.ast.Node.SYMBOL_RESOLVER_KEY, new JavaSymbolSolver(index.outputSolver(module)));
            for (ClassOrInterfaceType type : unit.findAll(ClassOrInterfaceType.class)) {
                TypeDeclaration<?> sourceType = sourceType(type.getNameWithScope(), unit);
                if (sourceType != null) index.outputContract(sourceType, dependencies, visited, problems);
            }
            for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) resolveCall(call, unit);
            if (!unit.findAll(MethodReferenceExpr.class).isEmpty()) problems.add("unresolved output method reference");
        }
        return dependencies;
    }

    private void resolveCall(MethodCallExpr call, CompilationUnit unit) {
        if (knownSpringFixture(call, unit)) return;
        try {
            var resolved = call.resolve();
            String declaringType = resolved.declaringType().getQualifiedName();
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
            if (owner != null) index.outputMethod(owner, resolved.getName(), resolved.getSignature(),
                    call.getArguments().size(), call, dependencies, visited, problems);
            return;
        } catch (RuntimeException exception) {
            TypeDeclaration<?> owner = call.getScope().map(scope -> receiverType(scope, unit)).orElse(null);
            if (owner != null) {
                index.outputMethod(owner, call.getNameAsString(), null, call.getArguments().size(), call, dependencies, visited, problems);
                return;
            }
            if (knownMockitoInvocation(call, unit) || knownExternal(call, unit) || ownTestHelper(call, unit)) return;
            problems.add("unresolved output call: " + call.getNameAsString());
        }
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
            if (Set.of("verify", "spy", "when").contains(wrapper.getNameAsString())
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
