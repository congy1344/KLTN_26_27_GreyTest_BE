package com.greytest.service.agent;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;

/** Phát hiện lời gọi Mockito thiếu static import trước khi lưu output AI hoặc xuất file test. */
final class GeneratedMockitoImportValidator {

    private static final Set<String> MOCKITO = Set.of("mock", "spy", "when", "verify", "times", "never", "atLeast",
            "atLeastOnce", "atMost", "atMostOnce", "only", "timeout", "after", "doReturn", "doThrow", "doAnswer",
            "doNothing", "doCallRealMethod", "verifyNoMoreInteractions", "verifyNoInteractions", "verifyZeroInteractions",
            "reset", "clearInvocations", "ignoreStubs", "inOrder", "mockStatic", "mockConstruction", "withSettings",
            "description", "lenient", "mockingDetails", "mockitoSession", "calls");
    private static final Set<String> MATCHERS = Set.of("any", "anyObject", "anyVararg", "anyBoolean", "anyByte",
            "anyChar", "anyShort", "anyInt", "anyLong", "anyFloat", "anyDouble", "anyString", "anyList", "anySet",
            "anyMap", "anyCollection", "anyIterable", "eq", "refEq", "same", "isNull", "notNull", "isNotNull", "isA",
            "nullable", "argThat", "assertArg", "booleanThat", "byteThat", "charThat", "shortThat", "intThat",
            "longThat", "floatThat", "doubleThat", "contains", "endsWith", "startsWith", "matches");
    private static final Set<String> BDD = Set.of("given", "then", "willReturn", "willThrow", "willAnswer",
            "willDoNothing", "willCallRealMethod");
    private static final Set<String> KNOWN_WILDCARDS = Set.of("org.mockito.Mockito", "org.mockito.BDDMockito",
            "org.mockito.ArgumentMatchers", "org.mockito.Matchers", "org.junit.Assert", "org.junit.Assume",
            "org.junit.jupiter.api.Assertions", "org.junit.jupiter.api.Assumptions");

    private GeneratedMockitoImportValidator() { }

    static List<String> validate(CompilationUnit source) {
        List<String> problems = new java.util.ArrayList<>(validateStrictness(source));
        Set<String> missing = new TreeSet<>();
        // shortcut: Wildcard thư viện chưa biết có thể cung cấp helper; chỉ chặn binding chắc chắn thiếu.
        boolean unknownWildcard = source.getImports().stream().anyMatch(item -> item.isStatic() && item.isAsterisk()
                && !KNOWN_WILDCARDS.contains(item.getNameAsString()));
        if (unknownWildcard) return problems;
        for (MethodCallExpr call : source.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (call.getScope().isPresent() || !MOCKITO.contains(name) && !MATCHERS.contains(name) && !BDD.contains(name)) continue;
            boolean imported = source.getImports().stream().anyMatch(item -> item.isStatic()
                    && (!item.isAsterisk() && item.getName().getIdentifier().equals(name)
                    || item.isAsterisk() && supplies(item.getNameAsString(), name)));
            if (!imported && !hasHelper(call, name)) missing.add(name);
        }
        problems.addAll(missing.stream().map(name -> "Unqualified Mockito call '" + name
                + "' has no static import or declared/inherited helper. Add its matching Mockito/ArgumentMatchers "
                + "static import or use a fully qualified API supported by the target Mockito version; preserve test assertions.")
                .toList());
        return problems;
    }

    private static List<String> validateStrictness(CompilationUnit source) {
        String wrong = "org.mockito.junit.jupiter.Strictness";
        boolean wrongPackage = source.getImports().stream().anyMatch(item ->
                item.getNameAsString().equals(wrong) || item.getNameAsString().startsWith(wrong + "."))
                || source.findAll(FieldAccessExpr.class).stream()
                        .anyMatch(value -> value.getScope().toString().equals(wrong));
        boolean imported = source.getImports().stream().anyMatch(item -> !item.isStatic()
                && (item.getNameAsString().equals("org.mockito.quality.Strictness")
                || item.isAsterisk() && item.getNameAsString().equals("org.mockito.quality")));
        boolean missingImport = !imported && source.findAll(AnnotationExpr.class).stream()
                .filter(annotation -> annotation.getName().getIdentifier().equals("MockitoSettings"))
                .flatMap(annotation -> annotation.findAll(FieldAccessExpr.class).stream())
                .anyMatch(value -> value.getScope().toString().equals("Strictness"));
        // Parser chỉ kiểm tra cú pháp; enum đúng package phải được kiểm chứng trước khi lưu/xuất test.
        return wrongPackage || missingImport ? List.of("Mockito Strictness belongs to org.mockito.quality.Strictness; "
                + "import that enum or use its fully qualified name in @MockitoSettings(strictness = ...).") : List.of();
    }

    private static boolean supplies(String owner, String name) {
        if (owner.equals("org.mockito.BDDMockito")) return true;
        if (owner.equals("org.mockito.Mockito")) return MOCKITO.contains(name) || MATCHERS.contains(name);
        return (owner.equals("org.mockito.ArgumentMatchers") || owner.equals("org.mockito.Matchers")) && MATCHERS.contains(name);
    }

    private static boolean hasHelper(MethodCallExpr call, String name) {
        Node node = call;
        while (node.getParentNode().isPresent()) {
            node = node.getParentNode().orElseThrow();
            if (node instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) return true;
            if (node instanceof TypeDeclaration<?> owner && owner.getMembers().stream()
                    .filter(MethodDeclaration.class::isInstance).map(MethodDeclaration.class::cast)
                    .anyMatch(method -> method.getNameAsString().equals(name))) return true;
            if (node instanceof ClassOrInterfaceDeclaration owner
                    && (!owner.getExtendedTypes().isEmpty() || !owner.getImplementedTypes().isEmpty())) return true;
        }
        return false;
    }
}
