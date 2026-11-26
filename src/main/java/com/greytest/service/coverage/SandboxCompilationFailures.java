package com.greytest.service.coverage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.greytest.service.UnitTestService.TestFailure;

/** Dùng đúng source đã export để dòng lỗi vẫn khớp sau khi gộp nhiều test vào một file. */
final class SandboxCompilationFailures {
    private static final Pattern ERROR = Pattern.compile(
            "^(?:\\[ERROR]\\s*)?((?:/|[A-Za-z]:[/\\\\]|(?:\\.\\.?/)?src/).*?\\.java):(?:\\[(\\d+),\\d+]|(\\d+):\\s*error:)\\s*(.*)$");
    private static final Pattern COMPILATION_HEADER = Pattern.compile("(?m)^\\[ERROR]\\h+COMPILATION ERROR(?:\\h*:)?\\h*$");
    private static final java.util.Set<String> TEST_ANNOTATIONS = java.util.Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");

    record Result(List<TestFailure> failures, String projectError, boolean compilationError) { }
    private record Diagnostic(String path, int line, String message) { }

    static Result parse(String log, byte[] archive, String workdir, int maxDetail) throws IOException {
        Map<String, String> sources = sources(archive, workdir);
        String plainLog = log.replaceAll("\\x1B\\[[;\\d]*m", "");
        var errors = diagnostics(plainLog);
        for (Diagnostic error : errors) {
            String path = absolutePath(error.path(), workdir);
            if (!sources.containsKey(path)) return new Result(List.of(), path + ":" + error.line() + ": " + error.message(), true);
        }
        Map<String, LinkedHashSet<String>> details = new LinkedHashMap<>();
        Map<String, CompilationUnit> parsedSources = new LinkedHashMap<>();
        for (Diagnostic error : errors) {
            String path = absolutePath(error.path(), workdir);
            var unit = parsedSources.computeIfAbsent(path, ignored -> {
                var parsed = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(sources.get(path));
                return parsed.isSuccessful() ? parsed.getResult().orElse(null) : null;
            });
            if (unit == null) return new Result(List.of(), null, true);
            attribute(error, unit, sources.get(path), details);
        }
        List<TestFailure> failures = new ArrayList<>();
        details.forEach((key, messages) -> {
            String detail = "COMPILATION ERROR: (line numbers refer to the exported merged file)\n" + String.join("\n", messages);
            int separator = key.lastIndexOf('#');
            failures.add(new TestFailure(key.substring(0, separator), key.substring(separator + 1),
                    detail.substring(0, Math.min(detail.length(), maxDetail)), false));
        });
        return new Result(failures, null, !errors.isEmpty() || COMPILATION_HEADER.matcher(plainLog).find());
    }

    private static Map<String, String> sources(byte[] archive, String workdir) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory() && entry.getName().startsWith("src/test/java/") && entry.getName().endsWith(".java")) {
                    sources.put(workdir + "/" + entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return sources;
    }

    static java.util.Set<String> exportedFixtureMethods(byte[] archive) throws IOException {
        var methods = new LinkedHashSet<String>();
        var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        for (String source : sources(archive, "").values()) {
            var parsed = parser.parse(source);
            if (!parsed.isSuccessful()) continue;
            var unit = parsed.getResult().orElseThrow();
            String prefix = unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("");
            for (var method : unit.findAll(MethodDeclaration.class)) {
                if (method.getAnnotations().stream().noneMatch(a -> TEST_ANNOTATIONS.contains(a.getName().getIdentifier()))) continue;
                var type = method.findAncestor(TypeDeclaration.class).orElse(null);
                if (type == null || type.getParentNode().orElse(null) != unit) continue;
                String name = type.getNameAsString();
                if (name.matches(".*_(?:Fixture\\d+Test|GreyTest_[\\da-f]+(?:_\\d+)?Test)"))
                    methods.add(prefix + name + "#" + method.getNameAsString());
            }
        }
        return methods;
    }

    private static void attribute(Diagnostic error, CompilationUnit unit, String source, Map<String, LinkedHashSet<String>> details) {
        List<MethodDeclaration> tests = unit.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getAnnotations().stream().anyMatch(annotation -> TEST_ANNOTATIONS.contains(annotation.getName().getIdentifier())))
                .toList();
        List<MethodDeclaration> affected = tests.stream().filter(method -> containsLine(method, error.line())).toList();
        // Import, fixture hoặc helper hỏng có thể chặn mọi test trong cùng lớp.
        if (affected.isEmpty()) {
            var owner = unit.findAll(TypeDeclaration.class).stream()
                    .filter(type -> type.getRange().map(range -> range.begin.line <= error.line() && error.line() <= range.end.line).orElse(false))
                    .min(java.util.Comparator.comparingInt(type -> type.getRange().orElseThrow().end.line - type.getRange().orElseThrow().begin.line));
            affected = tests.stream().filter(method -> owner.isEmpty() || method.findAncestor(TypeDeclaration.class).equals(owner)).toList();
        }
        String[] lines = source.split("\\R", -1);
        String detail = "line " + error.line() + ": " + error.message()
                + (error.line() > 0 && error.line() <= lines.length ? "\nSource: " + lines[error.line() - 1].strip() : "");
        for (var method : affected) {
            String className = method.findAncestor(TypeDeclaration.class).orElseThrow().getNameAsString();
            String qualified = unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString() + ".").orElse("") + className;
            details.computeIfAbsent(qualified + "#" + method.getNameAsString(), ignored -> new LinkedHashSet<>()).add(detail);
        }
    }

    private static String absolutePath(String path, String workdir) {
        String normalized = path.replace('\\', '/');
        return normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")
                ? normalized : workdir + "/" + normalized.replaceFirst("^\\./", "");
    }

    private static boolean containsLine(MethodDeclaration method, int line) {
        return method.getRange().map(range -> range.begin.line <= line && line <= range.end.line).orElse(false);
    }

    private static List<Diagnostic> diagnostics(String log) {
        List<Diagnostic> errors = new ArrayList<>();
        String path = null;
        int line = 0;
        StringBuilder message = new StringBuilder();
        for (String raw : log.split("\\R")) {
            var match = ERROR.matcher(raw);
            if (match.matches()) {
                if (path != null) errors.add(new Diagnostic(path, line, message.toString()));
                path = match.group(1);
                line = Integer.parseInt(match.group(2) == null ? match.group(3) : match.group(2));
                message = new StringBuilder(match.group(4));
            } else if (path != null) {
                String continuation = raw.replaceFirst("^\\[ERROR]", "");
                if (!continuation.isBlank() && Character.isWhitespace(continuation.charAt(0))) {
                    message.append('\n').append(continuation.strip());
                } else {
                    errors.add(new Diagnostic(path, line, message.toString()));
                    path = null;
                }
            }
        }
        if (path != null) errors.add(new Diagnostic(path, line, message.toString()));
        return errors;
    }
}
