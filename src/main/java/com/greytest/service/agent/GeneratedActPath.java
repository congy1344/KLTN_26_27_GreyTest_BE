package com.greytest.service.agent;

import java.util.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;

/** Nhận diện Act trực tiếp hoặc lấy phần tử đầu tiên, không suy diễn pipeline có filter/mapper tùy ý. */
final class GeneratedActPath {
    record Selection(MethodCallExpr entry, boolean firstElement) { }
    record Projection(MethodCallExpr lookup, MethodDeclaration helper) { }
    private GeneratedActPath() { }

    static Selection select(MethodCallExpr expression, String sut) {
        if (expression.getScope().map(Object::toString).filter(sut::equals).isPresent()) return new Selection(expression, false);
        var call = expression;
        boolean nullable = call.getNameAsString().equals("orElse") && call.getArguments().size() == 1 && call.getArgument(0).isNullLiteralExpr();
        boolean required = Set.of("get", "orElseThrow").contains(call.getNameAsString()) && call.getArguments().isEmpty();
        if ((!nullable && !required) || call.getScope().filter(Expression::isMethodCallExpr).isEmpty()) return null;
        call = call.getScope().get().asMethodCallExpr();
        if (!Set.of("findFirst", "findAny").contains(call.getNameAsString()) || !call.getArguments().isEmpty()
                || call.getScope().filter(Expression::isMethodCallExpr).isEmpty()) return null;
        call = call.getScope().get().asMethodCallExpr();
        if (!call.getNameAsString().equals("stream") || !call.getArguments().isEmpty()
                || call.getScope().filter(Expression::isMethodCallExpr).isEmpty()) return null;
        call = call.getScope().get().asMethodCallExpr();
        return call.getScope().map(Object::toString).filter(sut::equals).isPresent() ? new Selection(call, true) : null;
    }

    static Projection projection(MethodDeclaration entry, ClassOrInterfaceDeclaration owner) {
        if (entry.getBody().isEmpty() || entry.getBody().get().getStatements().isEmpty()) return null;
        var statements = entry.getBody().get().getStatements();
        for (int index = 0; index < statements.size() - 1; index++) {
            var statement = statements.get(index);
            if (!statement.isExpressionStmt() || !statement.asExpressionStmt().getExpression().isMethodCallExpr()) return null;
            var log = statement.asExpressionStmt().getExpression().asMethodCallExpr();
            var unit = owner.findCompilationUnit().orElseThrow();
            boolean slf4j = owner.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("lombok.extern.slf4j.Slf4j")
                    || a.getNameAsString().equals("Slf4j") && unit.getImports().stream().anyMatch(i -> i.getNameAsString().equals("lombok.extern.slf4j.Slf4j")));
            if (!slf4j || owner.getFieldByName("log").isPresent() || log.getScope().map(Object::toString).filter("log"::equals).isEmpty()
                    || !Set.of("trace", "debug", "info", "warn", "error").contains(log.getNameAsString())
                    || log.getArguments().stream().anyMatch(a -> !a.isLiteralExpr() && !a.isNameExpr())) return null;
        }
        var last = statements.get(statements.size() - 1);
        if (!last.isReturnStmt()) return null;
        var value = last.asReturnStmt().getExpression().orElse(null);
        if (value == null || !value.isMethodCallExpr()) return null;
        var terminal = value.asMethodCallExpr();
        boolean toList = terminal.getNameAsString().equals("toList") && terminal.getArguments().isEmpty();
        if (terminal.getNameAsString().equals("collect") && terminal.getArguments().size() == 1 && terminal.getArgument(0).isMethodCallExpr()) {
            var collector = terminal.getArgument(0).asMethodCallExpr();
            var unit = owner.findCompilationUnit().orElseThrow();
            String scope = collector.getScope().map(Object::toString).orElse("");
            toList = collector.getNameAsString().equals("toList") && collector.getArguments().isEmpty()
                    && (scope.equals("java.util.stream.Collectors") || scope.equals("Collectors") && unit.getImports().stream()
                            .anyMatch(i -> !i.isStatic() && i.getNameAsString().equals("java.util.stream.Collectors")));
        }
        if (!toList || terminal.getScope().filter(Expression::isMethodCallExpr).isEmpty()) return null;
        var map = terminal.getScope().get().asMethodCallExpr();
        if (!map.getNameAsString().equals("map") || map.getArguments().size() != 1 || !map.getArgument(0).isMethodReferenceExpr()
                || map.getScope().filter(Expression::isMethodCallExpr).isEmpty()) return null;
        var reference = map.getArgument(0).asMethodReferenceExpr();
        if (!reference.getScope().isThisExpr()) return null;
        var helpers = owner.getMethodsByName(reference.getIdentifier()).stream().filter(m -> m.getParameters().size() == 1).toList();
        if (helpers.size() != 1) return null;
        var stream = map.getScope().get().asMethodCallExpr();
        if (!stream.getNameAsString().equals("stream") || !stream.getArguments().isEmpty()
                || stream.getScope().filter(Expression::isMethodCallExpr).isEmpty()) return null;
        return new Projection(stream.getScope().get().asMethodCallExpr(), helpers.get(0));
    }

    static Optional<String> missingCollectionStub(Projection projection, ClassOrInterfaceDeclaration owner,
            Map<String, String> mocks, GeneratedTestApiValidator api) {
        if (projection == null) return Optional.empty();
        var lookup = projection.lookup();
        String fieldName = lookup.getScope().map(Object::toString).orElse("");
        if (!mocks.containsKey(fieldName)) return Optional.empty();
        var entry = lookup.findAncestor(MethodDeclaration.class).orElse(null);
        if (entry == null || entry.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(fieldName))
                || entry.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(fieldName))) return Optional.empty();
        var field = owner.getFieldByName(fieldName).orElse(null);
        var unit = owner.findCompilationUnit().orElseThrow();
        var repository = field == null ? null : api.declaration(field.getElementType(), unit);
        if (repository == null) return Optional.empty();
        var methods = repository.getMethodsByName(lookup.getNameAsString()).stream()
                .filter(m -> m.getParameters().size() == lookup.getArguments().size()).toList();
        if (methods.size() != 1 || !methods.get(0).getType().isClassOrInterfaceType()) return Optional.empty();
        String returned = api.qualifiedType(methods.get(0).getType().asClassOrInterfaceType(), repository.findCompilationUnit().orElseThrow());
        if (!Set.of("java.util.List", "java.util.Collection", "java.util.Set").contains(returned)) return Optional.empty();
        return Optional.of("Missing collection stub: " + mocks.get(fieldName) + "." + lookup.getNameAsString()
                + " returns an empty collection by default, but the test requires a non-null element from the public result. "
                + "Stub the source-supported repository call with the approved entity collection, then configure the reached helper dependencies. "
                + "Preserve approved inputs, expected values and strict stubbing; do not replace assertions with null/empty expectations.");
    }
}
