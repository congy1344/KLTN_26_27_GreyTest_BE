package com.greytest.service.agent;

import java.util.*;
import com.github.javaparser.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.type.Type;
import com.greytest.dto.agent.GenerationContextDtos.*;

/** Chỉ mục khai báo đã cung cấp; tên kiểu mơ hồ không được dùng làm bằng chứng. */
final class SourceEvidenceIndex {
    record SourceType(TypeDeclaration<?> node, CompilationUnit unit, String file, String name) { }
    private final Map<String, SourceType> types = new HashMap<>();
    private final Set<String> ambiguous = new HashSet<>();
    private final JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    SourceEvidenceIndex(List<ClassContextDto> classes) {
        if (classes == null) return;
        for (var item : classes) {
            String code = item.validationSourceCode() != null ? item.validationSourceCode() : item.sourceCode();
            if (code == null || item.qualifiedName() == null) continue;
            var parsed = parser.parse(code);
            if (!parsed.isSuccessful()) continue;
            var unit = parsed.getResult().orElseThrow();
            for (var raw : unit.findAll(TypeDeclaration.class)) {
                TypeDeclaration<?> node = raw;
                String name = node.getFullyQualifiedName().orElse(null);
                if (name == null || !(name.equals(item.qualifiedName()) || name.startsWith(item.qualifiedName() + "."))) continue;
                var old = types.get(name);
                if (old != null && (!Objects.equals(old.file(), item.filePath()) || !old.unit().toString().equals(unit.toString()))) ambiguous.add(name);
                types.putIfAbsent(name, new SourceType(node, unit, item.filePath(), name));
            }
        }
    }

    SourceType type(String name) { return name == null || ambiguous.contains(name) ? null : types.get(name); }

    MethodDeclaration method(MethodContextDto dto) {
        if (dto == null || dto.sourceCode() == null) return null;
        var parsed = parser.parseMethodDeclaration(dto.sourceCode());
        return parsed.isSuccessful() ? parsed.getResult().orElse(null) : null;
    }

    String resolve(Type type, CompilationUnit unit) {
        if (!type.isClassOrInterfaceType()) return type.asString();
        return resolve(type.asClassOrInterfaceType().getNameWithScope(), unit);
    }

    String resolve(String name, CompilationUnit unit) {
        if (name == null || name.isBlank()) return null;
        if (name.contains(".") && Character.isLowerCase(name.charAt(0))) return name;
        var local = unit.findAll(TypeDeclaration.class).stream().filter(t -> t.getNameAsString().equals(name))
                .flatMap(t -> ((TypeDeclaration<?>) t).getFullyQualifiedName().stream()).distinct().toList();
        if (!local.isEmpty()) return local.size() == 1 ? local.get(0) : null;
        String first = name.split("\\.", 2)[0];
        var explicit = unit.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(first))
                .map(i -> i.getNameAsString() + name.substring(first.length())).distinct().toList();
        if (!explicit.isEmpty()) return explicit.size() == 1 ? explicit.get(0) : null;
        String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
        if (types.containsKey(pkg + name)) return pkg + name;
        Set<String> candidates = new HashSet<>();
        unit.getImports().stream().filter(i -> !i.isStatic() && i.isAsterisk()).map(i -> i.getNameAsString() + "." + name)
                .filter(types::containsKey).forEach(candidates::add);
        if (Set.of("List", "ArrayList", "LinkedList", "Set", "Collection", "Collections", "Optional").contains(name)
                && unit.getImports().stream().anyMatch(i -> !i.isStatic() && i.isAsterisk() && i.getNameAsString().equals("java.util")))
            candidates.add("java.util." + name);
        if (Set.of("String", "Object", "Long", "Integer", "Boolean", "Throwable", "Exception", "RuntimeException", "Error",
                "IllegalArgumentException", "IllegalStateException", "NullPointerException", "UnsupportedOperationException", "ArithmeticException").contains(name))
            candidates.add("java.lang." + name);
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    Type propertyType(SourceType owner, String name) {
        if (owner == null) return null;
        for (var field : owner.node().getFields())
            if (field.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(name))) return field.getElementType();
        if (owner.node().isRecordDeclaration()) return owner.node().asRecordDeclaration().getParameters().stream()
                .filter(p -> p.getNameAsString().equals(name)).map(Parameter::getType).findFirst().orElse(null);
        return null;
    }

    boolean getterGenerated(SourceType owner) {
        if (owner == null) return false;
        return owner.node().isRecordDeclaration() || owner.node().getAnnotations().stream().anyMatch(a -> {
            String name = a.getNameAsString();
            if (Set.of("lombok.Data", "lombok.Getter", "lombok.Value").contains(name)) return true;
            return Set.of("Data", "Getter", "Value").contains(name)
                    && owner.unit().findAll(TypeDeclaration.class).stream().noneMatch(t -> t.getNameAsString().equals(name))
                    && owner.unit().getImports().stream().anyMatch(i -> !i.isStatic()
                            && (i.getNameAsString().equals("lombok." + name) || i.isAsterisk() && i.getNameAsString().equals("lombok")));
        });
    }
}
