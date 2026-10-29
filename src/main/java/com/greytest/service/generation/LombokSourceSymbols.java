package com.greytest.service.generation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;

/** Bổ sung symbol Lombok mặc định vào index riêng; không sửa source hay chạy annotation processor của dự án. */
final class LombokSourceSymbols {
    private static final DataKey<Boolean> GENERATED = new DataKey<>() {};
    private static final DataKey<String> UNSUPPORTED = new DataKey<>() {};
    private static final Set<String> SUPPORTED = Set.of("lombok.Data", "lombok.Getter", "lombok.Setter",
            "lombok.NoArgsConstructor", "lombok.AllArgsConstructor", "lombok.RequiredArgsConstructor", "lombok.NonNull", "lombok.extern.slf4j.Slf4j");

    private LombokSourceSymbols() { }

    static void expand(Path root, Map<String, CompilationUnit> units) {
        Set<String> declared = units.values().stream().flatMap(unit -> unit.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream())
                .map(type -> String.valueOf(type.getFullyQualifiedName().orElse(""))).collect(java.util.stream.Collectors.toSet());
        for (var entry : units.entrySet()) {
            CompilationUnit unit = entry.getValue();
            for (var type : new ArrayList<>(unit.findAll(ClassOrInterfaceDeclaration.class))) {
                if (type.isInterface()) continue;
                var lombokAnnotations = type.findAll(AnnotationExpr.class).stream().filter(a -> qualify(a, unit).startsWith("lombok.")).toList();
                if (lombokAnnotations.isEmpty()) continue;
                if (type.getParentNode().filter(CompilationUnit.class::isInstance).isEmpty() || hasConfiguration(root.resolve(entry.getKey()).getParent())
                        || lombokAnnotations.stream().anyMatch(a -> !SUPPORTED.contains(qualify(a, unit)) || !a.isMarkerAnnotationExpr() || declared.contains(qualify(a, unit)))) {
                    type.setData(UNSUPPORTED, "unresolved Lombok configuration: " + type.getNameAsString()); continue;
                }
                boolean data = marker(type.getAnnotations(), unit, "lombok.Data");
                boolean getter = data || marker(type.getAnnotations(), unit, "lombok.Getter");
                boolean setter = data || marker(type.getAnnotations(), unit, "lombok.Setter");
                List<VariableDeclarator> required = new ArrayList<>();
                List<VariableDeclarator> all = new ArrayList<>();
                for (FieldDeclaration field : new ArrayList<>(type.getFields())) {
                    if (field.isStatic()) continue;
                    for (VariableDeclarator variable : field.getVariables()) {
                        String name = variable.getNameAsString();
                        if (name.startsWith("$")) continue;
                        if (!field.isFinal() || variable.getInitializer().isEmpty()) all.add(variable);
                        if (variable.getInitializer().isEmpty() && (field.isFinal() || has(field.getAnnotations(), unit, "lombok.NonNull"))) required.add(variable);
                        if (has(field.getAnnotations(), unit, "lombok.experimental.Accessors")) continue;
                        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                        boolean bool = variable.getType().isPrimitiveType() && variable.getType().asString().equals("boolean");
                        if (bool) continue;
                        if (bool && name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) suffix = name.substring(2);
                        String getterName = (bool ? "is" : "get") + suffix;
                        boolean fieldGetter = marker(field.getAnnotations(), unit, "lombok.Getter");
                        boolean fieldSetter = marker(field.getAnnotations(), unit, "lombok.Setter");
                        if ((fieldGetter || getter && !has(field.getAnnotations(), unit, "lombok.Getter")) && !suppressed(type, getterName, 0)) {
                            var method = new MethodDeclaration().setName(getterName).setType(variable.getType().clone())
                                    .setModifiers(Modifier.Keyword.PUBLIC).setBody(new BlockStmt().addStatement(
                                            new ReturnStmt(new FieldAccessExpr(new ThisExpr(), name))));
                            generated(method); type.addMember(method);
                        }
                        String setterName = "set" + suffix;
                        if (!field.isFinal() && (fieldSetter || setter && !has(field.getAnnotations(), unit, "lombok.Setter")) && !suppressed(type, setterName, 1)) {
                            var method = new MethodDeclaration().setName(setterName).setType("void").setModifiers(Modifier.Keyword.PUBLIC);
                            method.addParameter(variable.getType().clone(), name);
                            method.setBody(new BlockStmt().addStatement(new AssignExpr(new FieldAccessExpr(new ThisExpr(), name), new NameExpr(name), AssignExpr.Operator.ASSIGN)));
                            generated(method); type.addMember(method);
                        }
                    }
                }
                boolean noArgs = marker(type.getAnnotations(), unit, "lombok.NoArgsConstructor");
                boolean allArgs = marker(type.getAnnotations(), unit, "lombok.AllArgsConstructor");
                boolean requiredArgs = marker(type.getAnnotations(), unit, "lombok.RequiredArgsConstructor");
                boolean constructorAnnotation = has(type.getAnnotations(), unit, "lombok.NoArgsConstructor")
                        || has(type.getAnnotations(), unit, "lombok.AllArgsConstructor") || has(type.getAnnotations(), unit, "lombok.RequiredArgsConstructor");
                if (data && !constructorAnnotation && type.getConstructors().isEmpty()) requiredArgs = true;
                if (noArgs && required.stream().noneMatch(v -> v.findAncestor(FieldDeclaration.class).orElseThrow().isFinal())) constructor(type, List.of());
                if (allArgs) constructor(type, all);
                if (requiredArgs) constructor(type, required);
                if (marker(type.getAnnotations(), unit, "lombok.extern.slf4j.Slf4j") && type.getFieldByName("log").isEmpty()) {
                    var field = type.addField("org.slf4j.Logger", "log", Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);
                    field.getVariable(0).setInitializer(StaticJavaParser.parseExpression("org.slf4j.LoggerFactory.getLogger(" + type.getNameAsString() + ".class)"));
                    generated(field);
                }
            }
        }
    }

    private static void constructor(ClassOrInterfaceDeclaration type, List<VariableDeclarator> fields) {
        boolean exists = type.getConstructors().stream().anyMatch(c -> c.getParameters().size() == fields.size()
                && java.util.stream.IntStream.range(0, fields.size()).allMatch(i -> c.getParameter(i).getType().equals(fields.get(i).getType())));
        if (exists) return;
        var constructor = new ConstructorDeclaration().setName(type.getNameAsString()).setModifiers(Modifier.Keyword.PUBLIC);
        var body = new BlockStmt();
        for (var field : fields) {
            String name = field.getNameAsString(); constructor.addParameter(field.getType().clone(), name);
            body.addStatement(new AssignExpr(new FieldAccessExpr(new ThisExpr(), name), new NameExpr(name), AssignExpr.Operator.ASSIGN));
        }
        constructor.setBody(body); generated(constructor); type.addMember(constructor);
    }

    private static boolean suppressed(ClassOrInterfaceDeclaration type, String name, int arity) {
        return type.getMethods().stream().anyMatch(m -> m.getNameAsString().equalsIgnoreCase(name)
                && (m.getParameters().size() == arity || !m.getParameters().isEmpty()
                    && m.getParameter(m.getParameters().size() - 1).isVarArgs() && arity >= m.getParameters().size() - 1));
    }

    private static boolean marker(List<AnnotationExpr> annotations, CompilationUnit unit, String name) {
        return annotations.stream().anyMatch(a -> a.isMarkerAnnotationExpr() && matches(a, unit, name));
    }
    private static boolean has(List<AnnotationExpr> annotations, CompilationUnit unit, String name) {
        return annotations.stream().anyMatch(a -> matches(a, unit, name));
    }
    private static boolean matches(AnnotationExpr annotation, CompilationUnit unit, String name) {
        String actual = annotation.getNameAsString();
        if (actual.equals(name)) return true;
        if (!actual.equals(name.substring(name.lastIndexOf('.') + 1))) return false;
        return unit.getImports().stream().anyMatch(i -> !i.isStatic() && !i.isAsterisk() && i.getNameAsString().equals(name));
    }
    private static String qualify(AnnotationExpr annotation, CompilationUnit unit) {
        String name = annotation.getNameAsString();
        if (name.contains(".")) return name;
        return unit.getImports().stream().filter(i -> !i.isStatic() && i.getName().getIdentifier().equals(name))
                .map(i -> i.getNameAsString()).findFirst().orElseGet(() -> unit.getImports().stream()
                        .filter(i -> !i.isStatic() && i.isAsterisk() && i.getNameAsString().startsWith("lombok"))
                        .map(i -> "lombok.unresolved." + name).findFirst().orElse(name));
    }
    static String unsupported(Node node) { return node.containsData(UNSUPPORTED) ? node.getData(UNSUPPORTED) : null; }
    static boolean isData(com.github.javaparser.ast.body.TypeDeclaration<?> type) {
        return type.findCompilationUnit().map(unit -> marker(type.getAnnotations(), unit, "lombok.Data")).orElse(false);
    }
    static boolean hasConfiguration(Path directory) {
        for (Path path = directory; path != null; path = path.getParent()) if (Files.exists(path.resolve("lombok.config"))) return true;
        return false;
    }
    private static void generated(Node node) { node.setData(GENERATED, true); }
    static boolean isGenerated(Node node) { return node.containsData(GENERATED); }
    static void removeGenerated(Node node) {
        new ArrayList<>(node.findAll(Node.class)).stream().filter(LombokSourceSymbols::isGenerated).forEach(Node::remove);
    }
}
