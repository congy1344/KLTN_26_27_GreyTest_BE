package com.greytest.service.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;

/** Kiểm tra constructor, generic và kiểu giá trị từ khai báo source, không đoán API còn thiếu. */
final class GeneratedTestApiValidator {
    private static final Set<String> JAVA_LANG_TYPES = Set.of(
            "Object", "String", "Boolean", "Byte", "Character", "Double", "Float", "Integer", "Long", "Short");
    private final Map<String, ClassOrInterfaceDeclaration> declarations = new HashMap<>();
    private final Map<String, String> sourcePaths = new HashMap<>();
    private final List<com.greytest.dto.agent.GenerationContextDtos.ResourceEvidenceDto> resources;
    private final List<String> contextWarnings;

    private GeneratedTestApiValidator(UnitTestContextDto context) {
        resources = context.supportingResources() == null ? List.of() : context.supportingResources();
        contextWarnings = context.contextWarnings() == null ? List.of() : context.contextWarnings();
        if (context.classes() == null) return;
        for (var clazz : context.classes()) {
            if (clazz.qualifiedName() != null && clazz.filePath() != null) sourcePaths.put(clazz.qualifiedName(), clazz.filePath().replace('\\', '/'));
            if (clazz.validationSourceCode() == null || clazz.qualifiedName() == null) continue;
            var parsed = new JavaParser(new ParserConfiguration().setLanguageLevel(
                    ParserConfiguration.LanguageLevel.JAVA_21)).parse(clazz.validationSourceCode());
            if (!parsed.isSuccessful()) continue;
            parsed.getResult().orElseThrow().findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(type -> type.getFullyQualifiedName().filter(clazz.qualifiedName()::equals).isPresent())
                    .findFirst().ifPresent(type -> declarations.put(clazz.qualifiedName(), type));
        }
    }

    static List<String> validate(UnitTestContextDto context, CompilationUnit test) {
        var validator = new GeneratedTestApiValidator(context);
        List<String> problems = new ArrayList<>();
        test.findAll(ObjectCreationExpr.class).forEach(creation -> validator.validateConstructor(creation, test, problems));
        test.findAll(MethodCallExpr.class).forEach(call -> {
            validator.validateCall(call, test, problems);
            validator.validateNullAssertion(call, test, problems);
        });
        return problems;
    }

    private void validateNullAssertion(MethodCallExpr call, CompilationUnit test, List<String> problems) {
        String name = call.getNameAsString();
        if (!Set.of("assertNull", "assertEquals").contains(name)) return;
        String junit = junitAssertionsOwner(call, test);
        if (junit == null) return;
        Expression value = null;
        int size = call.getArguments().size();
        if (name.equals("assertNull") && (size == 1 || size == 2)) {
            value = call.getArgument(junit.equals("org.junit.Assert") ? size - 1 : 0);
        } else if (name.equals("assertEquals") && (size == 2 || size == 3)) {
            int first = junit.equals("org.junit.Assert") ? size - 2 : 0;
            Expression left = call.getArgument(first);
            Expression right = call.getArgument(first + 1);
            if (left.isNullLiteralExpr()) value = right;
            else if (right.isNullLiteralExpr()) value = left;
        }
        SourceValueType resolved = value == null ? null : declaredValueType(value, test);
        Type type = resolved == null ? null : resolved.type();
        if (type != null && type.isPrimitiveType()) {
            problems.add("Source value contract: " + value + " has primitive type " + type
                    + " and cannot be null. Follow the source-defined return, fallback or exception; "
                    + "preserve the approved expected result and report a contradiction if it requires null.");
        }
    }

    private String junitAssertionsOwner(MethodCallExpr call, CompilationUnit test) {
        Set<String> owners = Set.of("org.junit.Assert", "org.junit.jupiter.api.Assertions");
        if (call.getScope().isPresent()) {
            String scope = call.getScope().get().toString();
            if (owners.contains(scope)) return scope;
            if (!call.getScope().get().isNameExpr() || expressionType(call.getScope().get(), test) != null
                    || test.findAll(ClassOrInterfaceDeclaration.class).stream()
                            .anyMatch(type -> type.getNameAsString().equals(scope))) return null;
            return test.getImports().stream().filter(imported -> !imported.isStatic() && !imported.isAsterisk())
                    .map(imported -> imported.getNameAsString()).filter(owners::contains)
                    .filter(owner -> owner.endsWith("." + scope)).findFirst().orElse(null);
        }
        if (test.findAll(MethodDeclaration.class).stream()
                .anyMatch(method -> method.getNameAsString().equals(call.getNameAsString()))) return null;
        if (insideAnonymousClass(call) || call.stream(Node.TreeTraversal.PARENTS)
                .filter(ClassOrInterfaceDeclaration.class::isInstance).map(ClassOrInterfaceDeclaration.class::cast)
                .anyMatch(owner -> !owner.getExtendedTypes().isEmpty() || !owner.getImplementedTypes().isEmpty())) return null;
        var importedOwners = test.getImports().stream().filter(imported -> imported.isStatic())
                .filter(imported -> imported.isAsterisk() || imported.getName().getIdentifier().equals(call.getNameAsString()))
                .map(imported -> imported.isAsterisk() ? imported.getNameAsString()
                        : imported.getName().getQualifier().map(Object::toString).orElse(""))
                .distinct().toList();
        return importedOwners.size() == 1 && owners.contains(importedOwners.get(0)) ? importedOwners.get(0) : null;
    }

    private record SourceValueType(Type type, CompilationUnit unit) {}

    private SourceValueType declaredValueType(Expression value, CompilationUnit test) {
        if (value.isEnclosedExpr()) return declaredValueType(value.asEnclosedExpr().getInner(), test);
        if (!value.isMethodCallExpr()) {
            Type type = expressionType(value, test);
            return type == null ? null : new SourceValueType(type, test);
        }
        MethodCallExpr getter = value.asMethodCallExpr();
        SourceValueType receiver = getter.getScope().map(scope -> declaredValueType(scope, test)).orElse(null);
        if (receiver == null || !receiver.type().isClassOrInterfaceType()) return null;
        ClassOrInterfaceType receiverType = receiver.type().asClassOrInterfaceType();
        String qualified = qualifiedType(receiverType, receiver.unit());
        SourceValueType element = collectionElementType(getter, receiverType, qualified, receiver.unit());
        if (element != null) return element;
        ClassOrInterfaceDeclaration owner = declarations.get(qualified);
        if (owner == null) return null;
        // Không suy ra overload theo số tham số: chỉ getter không đối số có kiểu trả về chắc chắn.
        if (!getter.getArguments().isEmpty()) return null;
        var methods = owner.getMethodsByName(getter.getNameAsString()).stream()
                .filter(method -> method.getParameters().isEmpty()).toList();
        CompilationUnit production = owner.findCompilationUnit().orElseThrow();
        if (methods.size() == 1 && methods.get(0).getTypeParameters().isEmpty()) {
            return resolveMemberType(methods.get(0).getType(), owner, production, receiverType, receiver.unit());
        }
        if (!methods.isEmpty()) return null;
        // Chỉ suy ra getter Lombok khi annotation xác nhận, ưu tiên method viết tay ở trên.
        if (owner.getAnnotations().stream().anyMatch(annotation -> annotation.getName().getIdentifier().equals("Accessors"))) return null;
        for (var field : owner.getFields()) {
            if (field.isStatic() || field.getAnnotations().stream()
                    .anyMatch(annotation -> annotation.getName().getIdentifier().equals("Accessors")
                            || annotation.getName().getIdentifier().equals("Getter") && annotation.toString().contains("NONE"))) continue;
            if (!lombok(field, production, "Getter") && !lombok(owner, production, "Getter")
                    && !lombok(owner, production, "Data") && !lombok(owner, production, "Value")) continue;
            for (var variable : field.getVariables()) {
                String expected = expectedGetter(owner, variable);
                if (expected != null && expected.equals(getter.getNameAsString())) return resolveMemberType(variable.getType(), owner, production, receiverType, receiver.unit());
            }
        }
        return null;
    }

    private SourceValueType resolveMemberType(Type member, ClassOrInterfaceDeclaration owner, CompilationUnit unit,
            ClassOrInterfaceType receiver, CompilationUnit receiverUnit) {
        if (owner.getTypeParameters().isEmpty()) return declaredMemberType(member, owner, unit);
        var actual = receiver.getTypeArguments();
        if (actual.isEmpty() || actual.get().size() != owner.getTypeParameters().size()) return null;
        Type resolved = member.clone();
        for (int index = 0; index < owner.getTypeParameters().size(); index++) {
            String parameter = owner.getTypeParameter(index).getNameAsString();
            Type argument = actual.get().get(index);
            if (!argument.isClassOrInterfaceType()) return null;
            String qualified = concreteType(argument, receiverUnit);
            if (qualified == null) return null;
            Type replacement = new JavaParser().parseType(qualified).getResult().orElse(null);
            if (replacement == null) return null;
            if (resolved.asString().equals(parameter)) resolved = replacement;
            else for (var nested : new ArrayList<>(resolved.findAll(ClassOrInterfaceType.class))) {
                if (nested.getNameWithScope().equals(parameter)) nested.replace(replacement.clone());
            }
        }
        return declaredMemberType(resolved, owner, unit);
    }

    private SourceValueType declaredMemberType(Type type, ClassOrInterfaceDeclaration owner, CompilationUnit unit) {
        // Chỉ resolve kiểu tham chiếu khi đã có đủ khai báo tổ tiên để loại trừ kiểu lồng che khuất.
        if (!type.isPrimitiveType() && inheritedTypeIsUnknown(owner, type, new java.util.HashSet<>())) return null;
        return new SourceValueType(type, unit);
    }

    private boolean inheritedTypeIsUnknown(ClassOrInterfaceDeclaration owner, Type member, java.util.Set<String> visited) {
        if (!visited.add(owner.getFullyQualifiedName().orElse(owner.getNameAsString()))) return true;
        for (var parent : java.util.stream.Stream.concat(owner.getExtendedTypes().stream(), owner.getImplementedTypes().stream()).toList()) {
            String qualified = qualifiedType(parent, owner.findCompilationUnit().orElseThrow());
            var declaration = declarations.get(qualified);
            if (declaration == null || declaration.getMembers().stream().filter(com.github.javaparser.ast.body.TypeDeclaration.class::isInstance)
                    .map(nested -> (com.github.javaparser.ast.body.TypeDeclaration<?>) nested).anyMatch(nested -> member.findAll(ClassOrInterfaceType.class).stream()
                            .anyMatch(reference -> reference.getNameWithScope().equals(nested.getNameAsString())))
                    || inheritedTypeIsUnknown(declaration, member, new java.util.HashSet<>(visited))) return true;
        }
        return false;
    }

    private boolean insideAnonymousClass(Node node) {
        return node.stream(Node.TreeTraversal.PARENTS).anyMatch(parent -> parent instanceof ObjectCreationExpr creation
                && creation.getAnonymousClassBody().isPresent());
    }

    private SourceValueType collectionElementType(MethodCallExpr call, ClassOrInterfaceType receiver,
            String qualified, CompilationUnit unit) {
        if (!call.getNameAsString().equals("get") || receiver.getTypeArguments().isEmpty()) return null;
        var arguments = receiver.getTypeArguments().orElseThrow();
        if ("java.util.Optional".equals(qualified) && arguments.size() == 1 && call.getArguments().isEmpty()) {
            return new SourceValueType(arguments.get(0), unit);
        }
        if (call.getArguments().size() != 1) return null;
        // Chỉ dùng generic của collection JDK đã resolve; không đoán API của class cùng tên.
        if ("java.util.List".equals(qualified) && arguments.size() == 1) {
            return new SourceValueType(arguments.get(0), unit);
        }
        if ("java.util.Map".equals(qualified) && arguments.size() == 2) {
            return new SourceValueType(arguments.get(1), unit);
        }
        return null;
    }

    private void validateConstructor(ObjectCreationExpr creation, CompilationUnit test, List<String> problems) {
        String fqn = qualifiedType(creation.getType(), test);
        ClassOrInterfaceDeclaration type = declarations.get(fqn);
        if (type == null || type.isInterface() || creation.getAnonymousClassBody().isPresent()) return;
        CompilationUnit production = type.findCompilationUnit().orElseThrow();
        // Builder có thể sinh constructor riêng; chỉ chặn khi danh sách constructor được biết chắc.
        if (lombok(type, production, "Builder") || lombok(type, production, "SuperBuilder")) return;
        if (type.getConstructors().stream().anyMatch(constructor -> lombok(constructor, production, "Tolerate"))) return;
        boolean value = lombok(type, production, "Value");
        if (value && type.getFields().stream().filter(field -> !field.isStatic())
                .flatMap(field -> field.getVariables().stream()).anyMatch(variable -> variable.getInitializer().isPresent())) return;
        List<Integer> arities = new ArrayList<>();
        for (var constructor : type.getConstructors()) {
            int count = constructor.getParameters().size();
            if (count > 0 && constructor.getParameter(count - 1).isVarArgs()
                    && creation.getArguments().size() >= count - 1) return;
            arities.add(count);
        }
        if (lombok(type, production, "NoArgsConstructor")) arities.add(0);
        boolean noArgs = lombok(type, production, "NoArgsConstructor");
        boolean allArgs = lombok(type, production, "AllArgsConstructor");
        boolean requiredArgs = lombok(type, production, "RequiredArgsConstructor");
        if (allArgs || value && type.getConstructors().isEmpty()) {
            arities.add((int) type.getFields().stream().filter(field -> !field.isStatic())
                    .flatMap(field -> field.getVariables().stream())
                    .filter(variable -> !variable.findAncestor(com.github.javaparser.ast.body.FieldDeclaration.class)
                            .orElseThrow().isFinal() || variable.getInitializer().isEmpty()).count());
        }
        if (requiredArgs || lombok(type, production, "Data") && type.getConstructors().isEmpty() && !noArgs && !allArgs) {
            arities.add((int) type.getFields().stream().filter(field -> !field.isStatic())
                    .filter(field -> field.isFinal() || lombok(field, production, "NonNull"))
                    .flatMap(field -> field.getVariables().stream()).filter(variable -> variable.getInitializer().isEmpty()).count());
        }
        if (arities.isEmpty() && type.getConstructors().isEmpty()) arities.add(0);
        if (!arities.contains(creation.getArguments().size())) {
            problems.add("Source constructor contract: " + fqn + " has no constructor with "
                    + creation.getArguments().size() + " arguments; declared/generated arities are " + arities
                    + ". Use the exact production constructor or confirmed setters.");
        }
    }

    private boolean lombok(NodeWithAnnotations<?> type, CompilationUnit unit, String name) {
        return type.getAnnotations().stream().anyMatch(annotation -> {
            String declared = annotation.getNameAsString();
            return declared.equals("lombok." + name) || declared.equals("lombok.experimental." + name)
                    || declared.equals(name) && unit.getImports().stream().anyMatch(imported -> !imported.isStatic()
                    && (imported.getNameAsString().equals("lombok." + name)
                    || imported.getNameAsString().equals("lombok.experimental." + name)
                    || imported.isAsterisk() && Set.of("lombok", "lombok.experimental").contains(imported.getNameAsString())));
        });
    }

    private void validateCall(MethodCallExpr call, CompilationUnit test, List<String> problems) {
        validateAccessor(call, test, problems);
        Type receiver = call.getScope().map(scope -> expressionType(scope, test)).orElse(null);
        if (receiver == null || !receiver.isClassOrInterfaceType()) return;
        ClassOrInterfaceDeclaration owner = declarations.get(qualifiedType(receiver.asClassOrInterfaceType(), test));
        // Không đoán substitution, overload kế thừa hoặc method generic chưa resolve được.
        if (owner == null || !owner.getTypeParameters().isEmpty() || !owner.getExtendedTypes().isEmpty()
                || !owner.getImplementedTypes().isEmpty()) return;
        var namedMethods = owner.getMethodsByName(call.getNameAsString());
        if (namedMethods.stream().anyMatch(method -> method.getParameters().stream().anyMatch(parameter -> parameter.isVarArgs())
                && call.getArguments().size() >= method.getParameters().size() - 1)) return;
        var methods = namedMethods.stream()
                .filter(method -> method.getParameters().size() == call.getArguments().size())
                .filter(method -> method.getParameters().stream().noneMatch(parameter -> parameter.isVarArgs())).toList();
        if (methods.size() != 1 || !methods.get(0).getTypeParameters().isEmpty()) return;
        MethodDeclaration method = methods.get(0);
        CompilationUnit production = owner.findCompilationUnit().orElseThrow();
        for (int i = 0; i < call.getArguments().size(); i++) {
            Type actual = expressionType(call.getArgument(i), test);
            reportMismatch(call.getNameAsString(), method.getParameter(i).getType(), production, actual, test, problems);
        }
        call.getParentNode().filter(VariableDeclarator.class::isInstance).map(VariableDeclarator.class::cast)
                .filter(variable -> variable.getInitializer().filter(call::equals).isPresent())
                .ifPresent(variable -> reportMismatch(call.getNameAsString(), variable.getType(), test,
                        method.getType(), production, problems));
    }

    private void validateAccessor(MethodCallExpr call, CompilationUnit test, List<String> problems) {
        if (!call.getArguments().isEmpty() || !call.getNameAsString().matches("(?:get|is)[A-Z].*")) return;
        SourceValueType receiver = call.getScope().map(scope -> declaredValueType(scope, test)).orElse(null);
        if (receiver == null || !receiver.type().isClassOrInterfaceType()) return;
        var owner = declarations.get(qualifiedType(receiver.type().asClassOrInterfaceType(), receiver.unit()));
        if (owner == null || !owner.getExtendedTypes().isEmpty() || !owner.getImplementedTypes().isEmpty()
                || !owner.getMethodsByName(call.getNameAsString()).isEmpty()
                || owner.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("Accessors")
                        || a.getName().getIdentifier().equals("Getter") && a.toString().contains("NONE"))) return;
        var unit = owner.findCompilationUnit().orElseThrow();
        for (var field : owner.getFields()) {
            if (field.isStatic() || field.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("Accessors"))
                    || field.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("Getter") && a.toString().contains("NONE"))) continue;
            if (!lombok(field, unit, "Getter") && !lombok(owner, unit, "Data")
                    && !lombok(owner, unit, "Getter") && !lombok(owner, unit, "Value")) continue;
            for (var variable : field.getVariables()) {
                String name = variable.getNameAsString();
                String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                String expected = expectedGetter(owner, variable);
                if (expected == null) continue;
                if ((call.getNameAsString().equals("get" + suffix) || call.getNameAsString().equals("is" + suffix))
                        && !call.getNameAsString().equals(expected)) {
                    problems.add("Source accessor contract: " + owner.getNameAsString() + "." + call.getNameAsString()
                            + "() is not declared; Lombok field " + name + " (" + variable.getType()
                            + ") exposes " + expected + "(). Use the source-proven accessor.");
                }
            }
        }
    }

    private String expectedGetter(ClassOrInterfaceDeclaration owner, VariableDeclarator variable) {
        if (contextWarnings.stream().anyMatch(w -> w.toLowerCase(java.util.Locale.ROOT).contains("lombok"))) return null;
        String path = sourcePaths.get(owner.getFullyQualifiedName().orElse(""));
        Map<String, String> settings = new java.util.LinkedHashMap<>();
        if (path != null) {
            var configs = resources.stream().filter(resource -> resource.kind().equals("LOMBOK_CONFIGURATION"))
                    .filter(resource -> path.startsWith(resource.filePath().substring(0,
                            resource.filePath().length() - "lombok.config".length())))
                    .sorted(java.util.Comparator.comparingInt((com.greytest.dto.agent.GenerationContextDtos.ResourceEvidenceDto r)
                            -> r.filePath().length()).reversed()).toList();
            List<com.greytest.dto.agent.GenerationContextDtos.ResourceEvidenceDto> applicable = new ArrayList<>();
            for (var config : configs) {
                applicable.add(config);
                if (config.content().lines().anyMatch(line -> line.matches("config\\.stopBubbling\\s*=\\s*true"))) break;
            }
            java.util.Collections.reverse(applicable);
            for (var config : applicable) config.content().lines().forEach(line -> {
                if (line.startsWith("clear ")) settings.remove(line.substring(6).trim());
                else if (line.contains("=")) {
                    String[] pair = line.split("=", 2); settings.put(pair[0].trim(), pair[1].trim());
                }
            });
        }
        // Accessors tùy biến cần contract riêng; không đoán tên từ convention mặc định.
        if (settings.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith("lombok.accessors.")
                && !entry.getKey().equals("lombok.accessors.chain"))) return null;
        String name = variable.getNameAsString();
        boolean bool = variable.getType().asString().equals("boolean")
                && !"true".equalsIgnoreCase(settings.get("lombok.getter.noIsPrefix"));
        return bool && name.matches("is[A-Z].*") ? name
                : (bool ? "is" : "get") + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private void reportMismatch(String method, Type expected, CompilationUnit expectedUnit,
            Type actual, CompilationUnit actualUnit, List<String> problems) {
        if (actual == null || !actual.isClassOrInterfaceType() || !expected.isClassOrInterfaceType()) return;
        var expectedClass = expected.asClassOrInterfaceType();
        var actualClass = actual.asClassOrInterfaceType();
        if (expectedClass.getTypeArguments().filter(arguments -> !arguments.isEmpty()).isEmpty()
                || actualClass.getTypeArguments().filter(arguments -> !arguments.isEmpty()).isEmpty()) return;
        String raw = qualifiedType(expectedClass, expectedUnit);
        if (raw == null || !raw.equals(qualifiedType(actualClass, actualUnit))) return;
        String expectedType = concreteType(expected, expectedUnit);
        String actualType = concreteType(actual, actualUnit);
        if (expectedType != null && actualType != null && !expectedType.equals(actualType)) {
            problems.add("Source generic contract for " + method + ": expected " + expectedType + ", got "
                    + actualType + ". Concrete generic arguments are invariant; copy exact types from production.");
        }
    }

    private String concreteType(Type type, CompilationUnit unit) {
        if (!type.isClassOrInterfaceType()) return null;
        var classType = type.asClassOrInterfaceType();
        String raw = qualifiedType(classType, unit);
        if (raw == null) return null;
        if (classType.getTypeArguments().isEmpty()) return raw;
        List<String> arguments = new ArrayList<>();
        for (Type argument : classType.getTypeArguments().orElseThrow()) {
            String resolved = concreteType(argument, unit);
            if (resolved == null) return null;
            arguments.add(resolved);
        }
        return raw + "<" + String.join(",", arguments) + ">";
    }

    private Type expressionType(Expression expression, CompilationUnit test) {
        if (insideAnonymousClass(expression)) return null;
        if (expression.isObjectCreationExpr()) return expression.asObjectCreationExpr().getType();
        if (expression.isEnclosedExpr()) return expressionType(expression.asEnclosedExpr().getInner(), test);
        String name;
        if (expression.isNameExpr()) name = expression.asNameExpr().getNameAsString();
        else if (expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getScope().isThisExpr()) {
            if (expression.asFieldAccessExpr().getScope().asThisExpr().getTypeName().isPresent()) return null;
            name = expression.asFieldAccessExpr().getNameAsString();
        } else return null;
        var method = expression.findAncestor(MethodDeclaration.class);
        if (method.isPresent() && !expression.isFieldAccessExpr()) {
            var namedVariables = method.get().findAll(VariableDeclarator.class).stream()
                    .filter(variable -> variable.getNameAsString().equals(name))
                    .filter(variable -> variable.findAncestor(MethodDeclaration.class).equals(method)).toList();
            // Scope for/try/lambda cần phân giải đầy đủ; bỏ qua thay vì gán nhầm type của field.
            if (namedVariables.stream().anyMatch(variable -> variable.getParentNode()
                    .flatMap(parent -> parent.getParentNode()).filter(ExpressionStmt.class::isInstance).isEmpty())) return null;
            if (method.get().findAll(Parameter.class).stream().anyMatch(parameter -> parameter.getNameAsString().equals(name)
                    && !method.get().getParameters().contains(parameter))) return null;
            var locals = namedVariables.stream()
                    .filter(variable -> variable.getBegin().isPresent() && expression.getBegin().isPresent()
                            && variable.getBegin().orElseThrow().isBefore(expression.getBegin().orElseThrow()))
                    .filter(variable -> variable.findAncestor(BlockStmt.class)
                            .filter(block -> block.isAncestorOf(expression)).isPresent()).toList();
            if (locals.size() == 1) return locals.get(0).getType();
            if (locals.size() > 1) return null;
            var parameter = method.get().getParameters().stream().filter(p -> p.getNameAsString().equals(name)).findFirst();
            if (parameter.isPresent()) return parameter.get().getType();
        }
        return expression.findAncestor(ClassOrInterfaceDeclaration.class).stream()
                .flatMap(owner -> owner.getFields().stream()).flatMap(field -> field.getVariables().stream())
                .filter(variable -> variable.getNameAsString().equals(name))
                .map(VariableDeclarator::getType).findFirst().orElse(null);
    }

    private String qualifiedType(ClassOrInterfaceType type, CompilationUnit unit) {
        String name = type.getNameWithScope();
        if (name.contains(".") && Character.isLowerCase(name.charAt(0))) return name;
        if (unit.findAll(com.github.javaparser.ast.type.TypeParameter.class).stream()
                .anyMatch(parameter -> parameter.getNameAsString().equals(name))) return null;
        if (unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .anyMatch(local -> local.getNameAsString().equals(name)
                        && (local.getParentNode().filter(CompilationUnit.class::isInstance).isEmpty()
                        || local.getFullyQualifiedName().filter(declarations::containsKey).isEmpty()))) return null;
        var explicit = unit.getImports().stream().filter(imported -> !imported.isStatic() && !imported.isAsterisk())
                .map(imported -> imported.getNameAsString()).filter(imported -> imported.endsWith("." + name)).toList();
        if (explicit.size() == 1) return explicit.get(0);
        String pkg = unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString() + ".").orElse("");
        if (declarations.containsKey(pkg + name)) return pkg + name;
        if (JAVA_LANG_TYPES.contains(name)) return "java.lang." + name;
        var wildcard = unit.getImports().stream().filter(imported -> !imported.isStatic() && imported.isAsterisk())
                .map(imported -> imported.getNameAsString() + "." + name).filter(declarations::containsKey).toList();
        return wildcard.size() == 1 ? wildcard.get(0) : null;
    }
}
