package com.greytest.service.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.greytest.dto.agent.GenerationContextDtos.MethodContextDto;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;

/** Kiểm tra expected element còn phụ thuộc index của fixture bị source thay đổi trực tiếp. */
final class GeneratedFixtureMutationValidator {
    private GeneratedFixtureMutationValidator() { }

    static List<String> validate(UnitTestContextDto context, MethodContextDto method, CompilationUnit test) {
        if (method == null || context.classes() == null) return List.of();
        var source = context.classes().stream().filter(c -> method.classQualifiedName().equals(c.qualifiedName()))
                .findFirst().orElse(null);
        if (source == null || source.validationSourceCode() == null) return List.of();
        var parsed = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(source.validationSourceCode()).getResult();
        if (parsed.isEmpty()) return List.of();
        var owner = parsed.get().findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getFullyQualifiedName().filter(method.classQualifiedName()::equals).isPresent()).findFirst();
        if (owner.isEmpty()) return List.of();
        var targets = owner.get().getMethodsByName(method.methodName());
        if (targets.size() != 1) return List.of();
        var target = targets.get(0);
        List<String> problems = new ArrayList<>();
        // shortcut: Chỉ theo dõi list nhận trực tiếp từ collaborator; alias/helper cần chạy test thật.
        for (var variable : target.findAll(VariableDeclarator.class)) {
            if (!isList(variable) || rebound(variable, target)
                    || variable.getInitializer().filter(Expression::isMethodCallExpr).isEmpty()) continue;
            var lookup = variable.getInitializer().orElseThrow().asMethodCallExpr();
            if (lookup.getScope().isEmpty()) continue;
            var collaborator = declaration(lookup.getScope().orElseThrow(), target);
            if (collaborator == null) continue;
            String collaboratorType = qualifiedType(collaborator, parsed.get());
            boolean mutates = target.findAll(MethodCallExpr.class).stream()
                    .anyMatch(call -> Set.of("remove", "clear", "removeIf").contains(call.getNameAsString())
                            && call.getScope().map(e -> declaration(e, target) == variable).orElse(false)
                            && !insideDeferredCode(call, target));
            if (!mutates) continue;
            for (var candidate : test.findAll(MethodDeclaration.class)) {
                var acts = candidate.findAll(MethodCallExpr.class).stream()
                        .filter(call -> call.getNameAsString().equals(method.methodName()) && !insideDeferredCode(call, candidate))
                        .filter(call -> call.getScope().map(scope -> declaration(scope, candidate))
                                .map(d -> method.classQualifiedName().equals(qualifiedType(d, test))).orElse(false)).toList();
                for (var act : acts) {
                    for (var stub : candidate.findAll(MethodCallExpr.class)) {
                        if (!stub.getNameAsString().equals("thenReturn") || stub.getArguments().size() != 1
                                || !stub.getArgument(0).isNameExpr() || !before(stub, act) || insideDeferredCode(stub, candidate)
                                || stub.getScope().filter(Expression::isMethodCallExpr).isEmpty()) continue;
                        var when = stub.getScope().orElseThrow().asMethodCallExpr();
                        if (!isMockitoWhen(when, test) || when.getArguments().size() != 1 || !when.getArgument(0).isMethodCallExpr()) continue;
                        var invocation = when.getArgument(0).asMethodCallExpr();
                        if (!invocation.getNameAsString().equals(lookup.getNameAsString())) continue;
                        var dependency = invocation.getScope().map(scope -> declaration(scope, candidate)).orElse(null);
                        if (dependency == null || collaboratorType == null || !collaboratorType.equals(qualifiedType(dependency, test))) continue;
                        var fixture = declaration(stub.getArgument(0), candidate);
                        if (fixture == null || !isList(fixture) || rebound(fixture, candidate)) continue;
                        for (var read : candidate.findAll(MethodCallExpr.class)) {
                            if (!read.getNameAsString().equals("get") || read.getArguments().size() != 1
                                    || !before(act, read) || insideDeferredCode(read, candidate)
                                    || read.getScope().map(scope -> declaration(scope, candidate)).orElse(null) != fixture) continue;
                            if (read.getParentNode().filter(MethodCallExpr.class::isInstance).map(MethodCallExpr.class::cast)
                                    .filter(call -> call.getNameAsString().equals("contains") && call.getArguments().contains(read)).isEmpty()) continue;
                            problems.add("Mutable fixture contract: source may mutate mock-returned list '" + fixture.getNameAsString()
                                    + "' in place. Capture the expected excluded element before calling " + method.methodName()
                                    + " and assert using that saved reference, instead of reading " + read + " after the mutation.");
                        }
                    }
                }
            }
        }
        return problems;
    }

    private static boolean before(Node first, Node second) {
        return first.getBegin().isPresent() && second.getBegin().isPresent()
                && first.getBegin().orElseThrow().isBefore(second.getBegin().orElseThrow());
    }

    private static boolean rebound(VariableDeclarator variable, MethodDeclaration method) {
        return method.findAll(AssignExpr.class).stream()
                .anyMatch(assignment -> declaration(assignment.getTarget(), method) == variable);
    }

    private static boolean insideDeferredCode(Node node, MethodDeclaration method) {
        return node.stream(Node.TreeTraversal.PARENTS).takeWhile(parent -> parent != method)
                .anyMatch(parent -> parent instanceof LambdaExpr || parent instanceof MethodDeclaration
                        || parent instanceof ClassOrInterfaceDeclaration);
    }

    private static boolean isMockitoWhen(MethodCallExpr when, CompilationUnit unit) {
        if (!when.getNameAsString().equals("when")) return false;
        if (when.getScope().isPresent()) {
            String scope = when.getScope().orElseThrow().toString();
            return scope.equals("org.mockito.Mockito") || scope.equals("Mockito") && unit.getImports().stream()
                    .anyMatch(i -> !i.isStatic() && i.getNameAsString().equals("org.mockito.Mockito"));
        }
        return unit.findAll(MethodDeclaration.class).stream().noneMatch(m -> m.getNameAsString().equals("when"))
                && unit.getImports().stream().anyMatch(i -> i.isStatic()
                        && (i.isAsterisk() && i.getNameAsString().equals("org.mockito.Mockito")
                        || i.getNameAsString().equals("org.mockito.Mockito.when")));
    }

    private static VariableDeclarator declaration(Expression expression, MethodDeclaration method) {
        String name;
        if (expression.isNameExpr()) name = expression.asNameExpr().getNameAsString();
        else if (expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getScope().isThisExpr()) {
            if (expression.asFieldAccessExpr().getScope().asThisExpr().getTypeName().isPresent()) return null;
            name = expression.asFieldAccessExpr().getNameAsString();
        } else return null;
        if (expression.isNameExpr()) {
            var locals = method.findAll(VariableDeclarator.class).stream()
                    .filter(v -> v.getNameAsString().equals(name))
                    .filter(v -> v.findAncestor(MethodDeclaration.class).filter(method::equals).isPresent()).toList();
            if (locals.size() == 1 && before(locals.get(0), expression) && locals.get(0)
                    .findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class)
                    .filter(block -> block.isAncestorOf(expression)).isPresent()) return locals.get(0);
            if (!locals.isEmpty() || method.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))) return null;
        }
        return method.findAncestor(ClassOrInterfaceDeclaration.class).stream().flatMap(c -> c.getFields().stream())
                .flatMap(f -> f.getVariables().stream()).filter(v -> v.getNameAsString().equals(name)).findFirst().orElse(null);
    }

    private static boolean isList(VariableDeclarator variable) {
        String type = qualifiedType(variable, variable.findCompilationUnit().orElseThrow());
        return type != null && Set.of("java.util.List", "java.util.ArrayList", "java.util.LinkedList").contains(type);
    }

    private static String qualifiedType(VariableDeclarator variable, CompilationUnit unit) {
        if (!variable.getType().isClassOrInterfaceType()) return null;
        String name = variable.getType().asClassOrInterfaceType().getNameWithScope();
        if (name.contains(".")) return name;
        var imports = unit.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk())
                .map(i -> i.getNameAsString()).filter(i -> i.endsWith("." + name)).toList();
        if (imports.size() == 1) return imports.get(0);
        if (!imports.isEmpty() || unit.getImports().stream().anyMatch(i -> !i.isStatic() && i.isAsterisk())) return null;
        return unit.getPackageDeclaration().map(p -> p.getNameAsString() + "." + name).orElse(name);
    }
}
