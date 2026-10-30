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
            "lombok.NoArgsConstructor", "lombok.AllArgsConstructor", "lombok.RequiredArgsConstructor", "lombok.NonNull", "lombok.extern.slf4j.Slf4j",
            "lombok.Builder", "lombok.EqualsAndHashCode", "lombok.ToString");

    private LombokSourceSymbols() { }

    private static boolean isSupportedAnnotation(AnnotationExpr a, CompilationUnit unit) {
        String qualified = qualify(a, unit);
        if (!SUPPORTED.contains(qualified)) return false;
        if (a.isMarkerAnnotationExpr()) return true;
        return Set.of("lombok.NoArgsConstructor", "lombok.AllArgsConstructor", "lombok.RequiredArgsConstructor",
                "lombok.Builder", "lombok.EqualsAndHashCode", "lombok.ToString").contains(qualified);
    }

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
                        || lombokAnnotations.stream().anyMatch(a -> !isSupportedAnnotation(a, unit) || declared.contains(qualify(a, unit)))) {
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
                boolean noArgs = has(type.getAnnotations(), unit, "lombok.NoArgsConstructor");
                boolean allArgs = has(type.getAnnotations(), unit, "lombok.AllArgsConstructor");
                boolean requiredArgs = has(type.getAnnotations(), unit, "lombok.RequiredArgsConstructor");
                boolean constructorAnnotation = noArgs || allArgs || requiredArgs;
                if (data && !constructorAnnotation && type.getConstructors().isEmpty()) requiredArgs = true;
                if (noArgs && required.stream().noneMatch(v -> v.findAncestor(FieldDeclaration.class).orElseThrow().isFinal()))
                    constructor(type, List.of(), constructorModifier(findAnnotation(type.getAnnotations(), unit, "lombok.NoArgsConstructor")));
                if (allArgs)
                    constructor(type, all, constructorModifier(findAnnotation(type.getAnnotations(), unit, "lombok.AllArgsConstructor")));
                if (requiredArgs)
                    constructor(type, required, constructorModifier(findAnnotation(type.getAnnotations(), unit, "lombok.RequiredArgsConstructor")));
                if (marker(type.getAnnotations(), unit, "lombok.extern.slf4j.Slf4j") && type.getFieldByName("log").isEmpty()) {
                    var field = type.addField("org.slf4j.Logger", "log", Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);
                    field.getVariable(0).setInitializer(StaticJavaParser.parseExpression("org.slf4j.LoggerFactory.getLogger(" + type.getNameAsString() + ".class)"));
                    generated(field);
                }
                boolean builder = has(type.getAnnotations(), unit, "lombok.Builder");
                if (builder) {
                    String builderClassName = type.getNameAsString() + "Builder";
                    if (type.getMethodsByName("builder").isEmpty()) {
                        var bMethod = new MethodDeclaration().setName("builder")
                                .setType(builderClassName).setModifiers(Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC);
                        bMethod.setBody(new BlockStmt().addStatement(new ReturnStmt(StaticJavaParser.parseExpression("new " + builderClassName + "()"))));
                        generated(bMethod); type.addMember(bMethod);
                    }
                    if (type.getMembers().stream().noneMatch(m -> m.isClassOrInterfaceDeclaration()
                            && m.asClassOrInterfaceDeclaration().getNameAsString().equals(builderClassName))) {
                        var bClass = new ClassOrInterfaceDeclaration().setName(builderClassName)
                                .setModifiers(Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC);
                        for (VariableDeclarator var : all) {
                            String fname = var.getNameAsString();
                            var fluentMethod = new MethodDeclaration().setName(fname).setType(builderClassName).setModifiers(Modifier.Keyword.PUBLIC);
                            fluentMethod.addParameter(var.getType().clone(), fname);
                            fluentMethod.setBody(new BlockStmt().addStatement(new ReturnStmt(new ThisExpr())));
                            generated(fluentMethod); bClass.addMember(fluentMethod);
                        }
                        var buildMethod = new MethodDeclaration().setName("build").setType(type.getNameAsString()).setModifiers(Modifier.Keyword.PUBLIC);
                        buildMethod.setBody(new BlockStmt().addStatement(new ReturnStmt(StaticJavaParser.parseExpression("new " + type.getNameAsString() + "()"))));
                        generated(buildMethod); bClass.addMember(buildMethod);
                        generated(bClass); type.addMember(bClass);
                    }
                }
                boolean equalsAndHashCode = has(type.getAnnotations(), unit, "lombok.EqualsAndHashCode");
                if ((data || equalsAndHashCode) && type.getMethodsByName("equals").isEmpty()) {
                    var eq = new MethodDeclaration().setName("equals").setType("boolean").setModifiers(Modifier.Keyword.PUBLIC);
                    eq.addParameter("Object", "o");
                    eq.setBody(new BlockStmt().addStatement(new ReturnStmt(StaticJavaParser.parseExpression("o == this"))));
                    generated(eq); type.addMember(eq);
                }
                if ((data || equalsAndHashCode) && type.getMethodsByName("hashCode").isEmpty()) {
                    var hc = new MethodDeclaration().setName("hashCode").setType("int").setModifiers(Modifier.Keyword.PUBLIC);
                    hc.setBody(new BlockStmt().addStatement(new ReturnStmt(StaticJavaParser.parseExpression("0"))));
                    generated(hc); type.addMember(hc);
                }
                if ((data || has(type.getAnnotations(), unit, "lombok.ToString")) && type.getMethodsByName("toString").isEmpty()) {
                    var ts = new MethodDeclaration().setName("toString").setType("String").setModifiers(Modifier.Keyword.PUBLIC);
                    ts.setBody(new BlockStmt().addStatement(new ReturnStmt(StaticJavaParser.parseExpression("\"\""))));
                    generated(ts); type.addMember(ts);
                }
            }
        }
    }

    private static Modifier.Keyword constructorModifier(AnnotationExpr annotation) {
        if (annotation != null && annotation.isNormalAnnotationExpr()) {
            for (var pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if (pair.getNameAsString().equals("access")) {
                    String val = pair.getValue().toString();
                    if (val.contains("PROTECTED")) return Modifier.Keyword.PROTECTED;
                    if (val.contains("PRIVATE")) return Modifier.Keyword.PRIVATE;
                }
            }
        }
        return Modifier.Keyword.PUBLIC;
    }

    private static AnnotationExpr findAnnotation(List<AnnotationExpr> annotations, CompilationUnit unit, String name) {
        return annotations.stream().filter(a -> matches(a, unit, name)).findFirst().orElse(null);
    }

    private static void constructor(ClassOrInterfaceDeclaration type, List<VariableDeclarator> fields, Modifier.Keyword modifier) {
        boolean exists = type.getConstructors().stream().anyMatch(c -> c.getParameters().size() == fields.size()
                && java.util.stream.IntStream.range(0, fields.size()).allMatch(i -> c.getParameter(i).getType().equals(fields.get(i).getType())));
        if (exists) return;
        var constructor = new ConstructorDeclaration().setName(type.getNameAsString());
        if (modifier != null) constructor.setModifiers(modifier);
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
        String simple = name.substring(name.lastIndexOf('.') + 1);
        if (!actual.equals(simple)) return false;
        if (unit.getImports().stream().anyMatch(i -> !i.isStatic() && !i.isAsterisk() && i.getNameAsString().equals(name))) return true;
        String pkg = name.substring(0, name.lastIndexOf('.'));
        boolean wildcard = unit.getImports().stream().anyMatch(i -> !i.isStatic() && i.isAsterisk() && i.getNameAsString().equals(pkg));
        if (!wildcard) return false;
        return unit.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream().noneMatch(t -> t.getNameAsString().equals(simple));
    }
    private static final Set<String> LOMBOK_ANNOTATIONS = Set.of(
            "Data", "Getter", "Setter", "NoArgsConstructor", "AllArgsConstructor", "RequiredArgsConstructor",
            "NonNull", "Builder", "EqualsAndHashCode", "ToString", "Value", "Cleanup", "Synchronized",
            "SneakyThrows", "With", "Slf4j", "CommonsLog", "Log", "Log4j", "Log4j2");

    private static String qualify(AnnotationExpr annotation, CompilationUnit unit) {
        String name = annotation.getNameAsString();
        if (name.contains(".")) return name;
        for (var imported : unit.getImports()) {
            if (!imported.isStatic() && !imported.isAsterisk() && imported.getName().getIdentifier().equals(name)) {
                return imported.getNameAsString();
            }
        }
        for (var imported : unit.getImports()) {
            if (!imported.isStatic() && imported.isAsterisk() && imported.getNameAsString().startsWith("lombok")) {
                if (!LOMBOK_ANNOTATIONS.contains(name)) continue;
                boolean shadowed = unit.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream()
                        .anyMatch(t -> t.getNameAsString().equals(name));
                if (shadowed) return "shadowed." + name;
                return imported.getNameAsString() + "." + name;
            }
        }
        return name;
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
