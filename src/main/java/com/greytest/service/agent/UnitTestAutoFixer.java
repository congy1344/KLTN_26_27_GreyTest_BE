package com.greytest.service.agent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

/**
 * Sửa máy móc test do AI sinh, không đổi ý nghĩa, trước khi chạy validator: thêm static import còn thiếu cho API
 * JUnit/Mockito quen thuộc và gắn {@code @Spy} cho field {@code Clock} đã khởi tạo khi test dùng {@code @InjectMocks}
 * (field thường không được Mockito inject nên SUT nhận null). Lỗi khác vẫn để validator báo và AI sửa.
 */
public final class UnitTestAutoFixer {
    private static final Map<String, String> STATIC_OWNERS = new HashMap<>();

    static {
        for (String name : new String[] {"when", "verify", "mock", "spy", "times", "never", "atLeast", "atLeastOnce", "atMost",
                "only", "doThrow", "doReturn", "doNothing", "doAnswer", "lenient", "verifyNoInteractions",
                "verifyNoMoreInteractions", "inOrder", "reset"}) STATIC_OWNERS.put(name, "org.mockito.Mockito");
        for (String name : new String[] {"any", "anyLong", "anyInt", "anyString", "anyBoolean", "anyDouble", "anyList", "anySet",
                "anyMap", "anyCollection", "eq", "argThat", "isNull", "notNull", "isA", "same", "nullable", "contains", "startsWith",
                "endsWith"}) STATIC_OWNERS.put(name, "org.mockito.ArgumentMatchers");
        for (String name : new String[] {"assertEquals", "assertNotEquals", "assertTrue", "assertFalse", "assertNull",
                "assertNotNull", "assertThrows", "assertDoesNotThrow", "assertSame", "assertNotSame", "assertAll",
                "assertIterableEquals", "assertArrayEquals", "fail"}) STATIC_OWNERS.put(name, "org.junit.jupiter.api.Assertions");
    }

    private UnitTestAutoFixer() { }

    /** Áp dụng {@link #fix} cho mọi unit_tests[].source_code của payload JSON; payload khác trả nguyên văn. */
    static String fixPayload(String payload, com.fasterxml.jackson.databind.ObjectMapper mapper)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        var root = mapper.readTree(payload);
        if (root == null || !root.path("unit_tests").isArray()) return payload;
        boolean changed = false;
        for (var item : root.path("unit_tests")) {
            if (!(item instanceof com.fasterxml.jackson.databind.node.ObjectNode object) || !item.path("source_code").isTextual()) continue;
            String before = item.path("source_code").textValue();
            String after = fix(before);
            if (!after.equals(before)) { object.put("source_code", after); changed = true; }
        }
        return changed ? mapper.writeValueAsString(root) : payload;
    }

    static String fix(String source) {
        if (source == null || source.isBlank()) return source;
        var parsed = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) return source;
        CompilationUnit unit = parsed.getResult().get();
        Set<String> imports = missingStaticImports(unit);
        var clockFields = unit.findAll(FieldDeclaration.class).stream().filter(UnitTestAutoFixer::plainInitializedClock).toList();
        boolean injectMocks = unit.findAll(FieldDeclaration.class).stream().anyMatch(f -> f.isAnnotationPresent("InjectMocks"));
        if (imports.isEmpty() && (clockFields.isEmpty() || !injectMocks)) return source;
        LexicalPreservingPrinter.setup(unit);
        imports.forEach(name -> unit.addImport(new ImportDeclaration(name, true, false)));
        if (injectMocks && !clockFields.isEmpty()) {
            clockFields.forEach(field -> field.addMarkerAnnotation("Spy"));
            if (unit.getImports().stream().noneMatch(i -> !i.isStatic() && (i.getNameAsString().equals("org.mockito.Spy")
                    || i.isAsterisk() && i.getNameAsString().equals("org.mockito")))) unit.addImport("org.mockito.Spy");
        }
        return LexicalPreservingPrinter.print(unit);
    }

    /** Lời gọi không scope tới API quen thuộc mà không có static import, không phải method tự khai báo trong test. */
    private static Set<String> missingStaticImports(CompilationUnit unit) {
        Set<String> declared = new HashSet<>();
        unit.findAll(MethodDeclaration.class).forEach(m -> declared.add(m.getNameAsString()));
        Set<String> result = new LinkedHashSet<>();
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            String owner = STATIC_OWNERS.get(name);
            if (call.getScope().isPresent() || owner == null || declared.contains(name)) continue;
            // Mockito kế thừa ArgumentMatchers: import static Mockito.* đã gồm any(), eq()...
            boolean imported = unit.getImports().stream().anyMatch(i -> i.isStatic()
                    && (i.isAsterisk() ? i.getNameAsString().equals(owner)
                            || owner.equals("org.mockito.ArgumentMatchers") && i.getNameAsString().equals("org.mockito.Mockito")
                            : i.getNameAsString().endsWith("." + name)));
            if (!imported) result.add(owner + "." + name);
        }
        return result;
    }

    /**
     * Xoá các câu stub Mockito mà lượt chạy thật báo là thừa (UnnecessaryStubbingException) khỏi đúng method test.
     * Stub thừa không được gọi tới nên bỏ đi không đổi kết quả nghiệp vụ. Trả null khi có dòng không khớp đúng một câu stub,
     * để vẫn nhờ AI sửa như cũ.
     */
    public static String removeUnusedStubs(String source, String methodName, java.util.List<String> stubLines) {
        if (source == null || stubLines.isEmpty()) return null;
        var parsed = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) return null;
        CompilationUnit unit = parsed.getResult().get();
        var methods = unit.findAll(MethodDeclaration.class).stream().filter(m -> m.getNameAsString().equals(methodName)).toList();
        if (methods.size() != 1) return null;
        java.util.List<String> lines = source.lines().toList();
        // Source lưu trong CSDL chỉ có đúng một @Test: stub thừa của test này có thể nằm trong chính method đó hoặc trong setup.
        var scopes = new java.util.ArrayList<MethodDeclaration>(methods);
        unit.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.isAnnotationPresent("BeforeEach") || m.isAnnotationPresent("BeforeAll")
                        || m.isAnnotationPresent("org.junit.jupiter.api.BeforeEach") || m.isAnnotationPresent("org.junit.jupiter.api.BeforeAll"))
                .forEach(scopes::add);
        var stubs = scopes.stream().flatMap(m -> m.findAll(com.github.javaparser.ast.stmt.ExpressionStmt.class).stream())
                .filter(stmt -> stmt.getRange().isPresent() && isStub(stmt.getExpression().toString())).toList();
        Set<com.github.javaparser.ast.stmt.ExpressionStmt> remove = new LinkedHashSet<>();
        for (String wanted : stubLines) {
            String target = wanted.strip();
            if (target.isEmpty()) return null;
            var matches = stubs.stream().filter(stmt -> {
                var range = stmt.getRange().get();
                for (int line = range.begin.line; line <= range.end.line && line <= lines.size(); line++)
                    if (lines.get(line - 1).strip().equals(target)) return true;
                return false;
            }).toList();
            if (matches.size() != 1) return null;
            remove.add(matches.get(0));
        }
        try {
            LexicalPreservingPrinter.setup(unit);
            remove.forEach(stmt -> stmt.remove());
            return LexicalPreservingPrinter.print(unit);
        } catch (RuntimeException unsupported) {
            // LexicalPreservingPrinter không in lại được một số cú pháp: để AI sửa như cũ.
            return null;
        }
    }

    private static boolean isStub(String expression) {
        String text = expression.replaceAll("\\s+", "").replaceFirst("^org\\.mockito\\.", "");
        return text.matches("(?s)(Mockito\\.)?(lenient\\(\\)\\.)?when\\(.*") || text.matches("(?s)(BDDMockito\\.)?given\\(.*")
                || text.matches("(?s)(Mockito\\.)?(lenient\\(\\)\\.)?do(Return|Throw|Nothing|Answer|CallRealMethod)\\(.*");
    }

    private static boolean plainInitializedClock(FieldDeclaration field) {
        return field.getAnnotations().isEmpty() && !field.isStatic()
                && field.getElementType().asString().matches("(java\\.time\\.)?Clock")
                && field.getVariables().stream().allMatch(v -> v.getInitializer().isPresent())
                && field.findAncestor(ClassOrInterfaceDeclaration.class).isPresent();
    }
}
