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

    GeneratedOutputDependencyResolver(SourceDependencyIndex index, List<String> problems) {
        this.index = index;
        this.problems = problems;
        visited.add("output-dependency-mode");
    }

    Map<String, String> resolve(List<String> sources) {
        var solver = new JavaSymbolSolver(index.outputSolver());
        for (String source : sources) {
            var parsed = GenerationJobContext.memoize("output-dependency-ast-v1:" + GenerationFingerprintService.hash(source), () -> {
                GenerationJobContext.increment("outputDependencyAstParses");
                return new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(source);
            });
            if (!parsed.isSuccessful()) { problems.add("unresolved output source syntax"); continue; }
            CompilationUnit unit = parsed.getResult().orElseThrow().clone();
            unit.setData(com.github.javaparser.ast.Node.SYMBOL_RESOLVER_KEY, solver);
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
            TypeDeclaration<?> owner = index.outputType(declaringType, problems);
            if (owner != null) index.outputMethod(owner, resolved.getName(), resolved.getSignature(),
                    call.getArguments().size(), dependencies, visited, problems);
            return;
        } catch (RuntimeException exception) {
            TypeDeclaration<?> owner = call.getScope().map(scope -> receiverType(scope, unit)).orElse(null);
            if (owner != null) {
                index.outputMethod(owner, call.getNameAsString(), null, call.getArguments().size(), dependencies, visited, problems);
                return;
            }
            if (knownExternal(call, unit) || ownTestHelper(call, unit)) return;
            problems.add("unresolved output call: " + call.getNameAsString());
        }
    }

    private boolean knownSpringFixture(MethodCallExpr call, CompilationUnit unit) {
        if (!Set.of("setField", "getField").contains(call.getNameAsString())) return false;
        String fixture = "org.springframework.test.util.ReflectionTestUtils";
        if (index.outputType(fixture, problems) != null) return false;
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
            if (type.isReferenceType()) return index.outputType(type.asReferenceType().getQualifiedName(), problems);
        } catch (RuntimeException exception) { /* Static type qualifiers are not value expressions. */ }
        return sourceType(scope.toString(), unit);
    }

    private TypeDeclaration<?> sourceType(String name, CompilationUnit unit) {
        if (name.contains(".")) return index.outputType(name, problems);
        for (var imported : unit.getImports()) {
            if (!imported.isAsterisk() && !imported.isStatic() && imported.getName().getIdentifier().equals(name)) {
                return index.outputType(imported.getNameAsString(), problems);
            }
        }
        String local = unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("") + name;
        TypeDeclaration<?> type = index.outputType(local, problems);
        if (type != null) return type;
        TypeDeclaration<?> selected = null;
        for (var imported : unit.getImports()) {
            if (!imported.isAsterisk() || imported.isStatic()) continue;
            var candidate = index.outputType(imported.getNameAsString() + "." + name, problems);
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
                    if (index.outputType(name, problems) != null) return false;
                    return name.startsWith("java.") || name.startsWith("org.junit.") || name.startsWith("org.mockito.")
                            || name.startsWith("org.assertj.");
                }
            } catch (RuntimeException exception) { /* Check explicitly imported test framework qualifiers below. */ }
            String qualifier = scope.toString();
            for (var imported : unit.getImports()) {
                if (!imported.isStatic() && imported.getName().getIdentifier().equals(qualifier)
                        && testFramework(imported.getNameAsString())) return true;
            }
            return testFramework(qualifier);
        }
        return unit.getImports().stream().anyMatch(imported -> imported.isStatic() && testFramework(imported.getNameAsString())
                && (imported.isAsterisk() || imported.getName().getIdentifier().equals(call.getNameAsString())));
    }

    private boolean testFramework(String name) {
        return name.startsWith("org.junit.") || name.startsWith("org.mockito.") || name.startsWith("org.assertj.");
    }
}
