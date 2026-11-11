package com.greytest.service.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import com.greytest.dto.agent.GenerationContextDtos.UnitTestContextDto;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;

/**
 * Sửa import có đáp án duy nhất ngay trên output, không tốn thêm một lượt gọi AI.
 * Chỉ đụng dòng import: logic test và kết quả mong đợi giữ nguyên từng byte.
 */
final class GeneratedImportRepair {
    private static final List<String> JDK_PACKAGES = List.of("java.util", "java.util.stream", "java.util.function",
            "java.util.concurrent", "java.math", "java.time", "java.io", "java.nio.file", "java.nio.charset");

    private static final Pattern SOURCE_IMPORT = Pattern.compile("(?m)^\\s*import\\s+((?:[a-z_][\\w]*\\.)+([A-Z]\\w*))\\s*;");

    /** AI đôi khi lặp từ khóa "import import x.Y;"; Java không có cú pháp hợp lệ nào như vậy nên gộp lại là an toàn. */
    private static final Pattern REPEATED_IMPORT = Pattern.compile("(?m)^([ \\t]*import)(?:[ \\t]+import)+(?=[ \\t])");

    private GeneratedImportRepair() { }

    static UnitTestResponseDto repair(UnitTestContextDto context, UnitTestResponseDto response) {
        // Response thiếu danh sách test hoặc context không có class: để validator báo lỗi như cũ.
        if (response.unitTests() == null || context.classes() == null) return response;
        Map<String, Set<String>> declared = new TreeMap<>();
        context.classes().forEach(type -> {
            if (type.className() != null && type.qualifiedName() != null) {
                declared.computeIfAbsent(type.className(), ignored -> new TreeSet<>()).add(type.qualifiedName());
            }
        });
        // Import rõ ràng trong source được cung cấp: bằng chứng duy nhất về package của kiểu thư viện.
        Map<String, Set<String>> sourceImports = new TreeMap<>();
        context.classes().forEach(type -> {
            if (type.sourceCode() == null) return;
            var imports = SOURCE_IMPORT.matcher(type.sourceCode());
            while (imports.find()) sourceImports.computeIfAbsent(imports.group(2), ignored -> new TreeSet<>()).add(imports.group(1));
        });
        List<GeneratedUnitTestDto> repaired = new ArrayList<>();
        for (GeneratedUnitTestDto test : response.unitTests()) {
            String source = repairSource(test.sourceCode(), declared, sourceImports);
            repaired.add(source.equals(test.sourceCode()) ? test : new GeneratedUnitTestDto(test.caseId(), test.testClassName(),
                    test.testMethodName(), test.packageName(), test.generationType(), source));
        }
        return new UnitTestResponseDto(repaired, response.blockedCases());
    }

    private static String repairSource(String source, Map<String, Set<String>> declared, Map<String, Set<String>> sourceImports) {
        if (source == null) return null;
        source = REPEATED_IMPORT.matcher(source).replaceAll("$1");
        var parsed = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) return source;
        CompilationUnit unit = parsed.getResult().get();
        String result = source;
        Set<String> imported = new TreeSet<>();
        for (var item : unit.getImports()) {
            if (item.isStatic() || item.isAsterisk()) continue;
            String name = item.getNameAsString();
            String simple = item.getName().getIdentifier();
            Set<String> candidates = declared.getOrDefault(simple, Set.of());
            // Tên class duy nhất trong source nhưng package sai và source cũng không import tên đó từ thư viện: thay bằng khai báo thật.
            if (candidates.size() == 1 && !candidates.contains(name) && sharesRoot(name, candidates.iterator().next())
                    && !sourceImports.getOrDefault(simple, Set.of()).contains(name)) {
                result = Pattern.compile("import\\s+" + Pattern.quote(name) + "\\s*;")
                        .matcher(result).replaceFirst("import " + candidates.iterator().next() + ";");
            }
            imported.add(simple);
        }
        if (unit.getImports().stream().anyMatch(item -> !item.isStatic() && item.isAsterisk())) return result;
        String pkg = unit.getPackageDeclaration().map(item -> item.getNameAsString()).orElse("");
        List<String> additions = new ArrayList<>();
        for (String name : usedTypeNames(unit)) {
            if (imported.contains(name)) continue;
            String resolved = uniqueDeclaration(name, pkg, declared, sourceImports);
            if (resolved != null) additions.add("import " + resolved + ";");
        }
        if (additions.isEmpty()) return result;
        String block = String.join("\n", additions) + "\n";
        var anchor = Pattern.compile("(?m)^\\s*package\\s+[^;]+;\\s*\\R").matcher(result);
        return anchor.find() ? result.substring(0, anchor.end()) + block + result.substring(anchor.end()) : block + result;
    }

    private static Set<String> usedTypeNames(CompilationUnit unit) {
        Set<String> names = new TreeSet<>();
        // Bỏ đoạn package của tên đầy đủ: JavaParser cũng tách chúng thành type lồng nhau.
        unit.findAll(ClassOrInterfaceType.class).stream()
                .filter(type -> type.getScope().isEmpty() && type.getParentNode().filter(ClassOrInterfaceType.class::isInstance)
                        .map(ClassOrInterfaceType.class::cast).flatMap(ClassOrInterfaceType::getScope).filter(scope -> scope == type).isEmpty())
                .forEach(type -> names.add(type.getNameAsString()));
        Set<String> variables = new TreeSet<>();
        unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).forEach(item -> variables.add(item.getNameAsString()));
        unit.findAll(NameExpr.class).stream()
                .filter(name -> name.getParentNode().filter(parent -> parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == name).isPresent()
                        || parent instanceof FieldAccessExpr).isPresent())
                .map(NameExpr::getNameAsString)
                .filter(name -> Character.isUpperCase(name.charAt(0)) && !variables.contains(name)).forEach(names::add);
        unit.findAll(TypeDeclaration.class).forEach(type -> names.remove(type.getNameAsString()));
        unit.findAll(TypeParameter.class).forEach(type -> names.remove(type.getNameAsString()));
        return names;
    }

    /** Thiếu import chỉ tự thêm khi đúng một lựa chọn; cùng package hay java.lang thì không cần import. */
    private static String uniqueDeclaration(String name, String pkg, Map<String, Set<String>> declared, Map<String, Set<String>> sourceImports) {
        Set<String> candidates = declared.getOrDefault(name, Set.of());
        if (candidates.contains(pkg + "." + name) || exists("java.lang." + name)) return null;
        if (candidates.size() == 1) return candidates.iterator().next();
        if (!candidates.isEmpty()) return null;
        Set<String> imported = sourceImports.getOrDefault(name, Set.of());
        if (!imported.isEmpty()) return imported.size() == 1 ? imported.iterator().next() : null;
        var jdk = JDK_PACKAGES.stream().map(item -> item + "." + name).filter(GeneratedImportRepair::exists).toList();
        return jdk.size() == 1 ? jdk.get(0) : null;
    }

    private static boolean exists(String qualifiedName) {
        try {
            Class.forName(qualifiedName, false, ClassLoader.getPlatformClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    /** Hai tên cùng hai đoạn package đầu mới coi là import nhầm trong project, không đụng thư viện trùng tên. */
    private static boolean sharesRoot(String imported, String expected) {
        String[] left = imported.split("\\."), right = expected.split("\\.");
        return left.length > 2 && right.length > 2 && left[0].equals(right[0]) && left[1].equals(right[1]);
    }
}
