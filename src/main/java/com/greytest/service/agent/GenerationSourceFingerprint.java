package com.greytest.service.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.service.ServiceScopeResolver;

/** Lưu dấu vết source thực tế dùng để sinh test, không tạo lại lịch sử khi export. */
public final class GenerationSourceFingerprint {
    public static final String PREFIX = "// GREYTEST_SOURCE_V1 ";
    public static final String UNVERIFIED = "// GREYTEST_SOURCE_UNVERIFIED";
    private static final long MAX_SOURCE_BYTES = 2_000_000;

    private GenerationSourceFingerprint() {}

    /** Chuẩn hóa metadata theo source hiện tại nhưng không tạo lại hash lịch sử. */
    public static List<String> alignToSourceRoot(List<String> sources, Path sourceRoot) {
        String combined = aggregate(sources);
        if (!combined.contains(PREFIX)) return sources;
        Map<String, String> paths = new LinkedHashMap<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        try {
            Path root = sourceRoot.toRealPath();
            List<String> candidates;
            try (var walk = Files.walk(root)) {
                candidates = walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java"))
                        .map(path -> root.relativize(path).toString().replace('\\', '/')).toList();
            }
            for (String line : combined.lines().filter(value -> value.startsWith(PREFIX)).toList()) {
                String[] fields = line.substring(PREFIX.length()).split(" ");
                String recorded = decodedPath(fields[1]);
                // Chỉ thêm/bớt tiền tố cây thư mục; không dò basename hay chọn file theo hash.
                var matches = candidates.stream().filter(path -> path.equals(recorded)
                        || path.endsWith("/" + recorded) || recorded.endsWith("/" + path)).toList();
                if (matches.isEmpty()) throw new IllegalArgumentException("Generation source file is missing: "
                        + recorded + "; restore the source or regenerate the affected tests");
                if (matches.size() != 1) throw new IllegalArgumentException("Ambiguous generation source path: "
                        + recorded + "; regenerate the affected tests");
                String actual = matches.get(0);
                Path file = root.resolve(actual).toRealPath();
                if (!file.startsWith(root) || Files.size(file) > MAX_SOURCE_BYTES) {
                    throw new IllegalArgumentException("Unsafe or oversized generation source: " + actual);
                }
                String actualHash = hashes.get(actual);
                if (actualHash == null) {
                    actualHash = hash(Files.readString(file, StandardCharsets.UTF_8));
                    hashes.put(actual, actualHash);
                }
                if (!actualHash.equals(fields[0])) throw new IllegalArgumentException("Source changed: "
                        + recorded + "; regenerate the affected tests");
                paths.put(recorded, actual);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read generation source; restore the source before exporting tests", exception);
        }
        validateModuleAlignment(paths);
        return sources.stream().map(source -> {
            String metadata = aggregate(List.of(source)).lines().map(line -> {
                if (!line.startsWith(PREFIX)) return line;
                String[] fields = line.substring(PREFIX.length()).split(" ");
                return PREFIX + fields[0] + " " + Base64.getEncoder().encodeToString(
                        paths.get(decodedPath(fields[1])).getBytes(StandardCharsets.UTF_8));
            }).distinct().collect(java.util.stream.Collectors.joining("\n", "", "\n"));
            return attach(source, metadata);
        }).toList();
    }

    private static void validateModuleAlignment(Map<String, String> paths) {
        Map<String, String> actualByRecordedModule = new LinkedHashMap<>();
        Map<String, List<String>> recordedByActualModule = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> filesByRecordedModule = new LinkedHashMap<>();
        paths.forEach((recorded, actual) -> filesByRecordedModule
                .computeIfAbsent(ServiceScopeResolver.modulePath(recorded), ignored -> new java.util.HashSet<>()).add(actual));
        for (var entry : paths.entrySet()) {
            String recorded = ServiceScopeResolver.modulePath(entry.getKey());
            String actual = ServiceScopeResolver.modulePath(entry.getValue());
            String previous = actualByRecordedModule.putIfAbsent(recorded, actual);
            var aliases = recordedByActualModule.computeIfAbsent(actual, ignored -> new java.util.ArrayList<>());
            // Không gộp module phụ thuộc vào module đang chọn dù tên file và hash đều trùng.
            if (previous != null && !previous.equals(actual) || aliases.stream().anyMatch(alias ->
                    !sameModuleAlias(alias, recorded, actual, filesByRecordedModule))) {
                throw new IllegalArgumentException("Ambiguous generation source module layout; preserve the recorded "
                        + "sibling modules or regenerate the affected tests");
            }
            if (!aliases.contains(recorded)) aliases.add(recorded);
        }
    }

    private static boolean sameModuleAlias(String left, String right, String actual,
            Map<String, java.util.Set<String>> filesByRecordedModule) {
        if (left.equals(right) || left.endsWith("/" + right) || right.endsWith("/" + left)
                || !actual.equals(".") && left.endsWith("/" + actual) && right.endsWith("/" + actual)) return true;
        // Root không có tên module: chỉ nhận wrapper khi hai bộ fingerprint cùng chỉ tới một file thực tế.
        return actual.equals(".") && (left.equals(".") || right.equals("."))
                && !java.util.Collections.disjoint(filesByRecordedModule.get(left), filesByRecordedModule.get(right));
    }

    private static String decodedPath(String encoded) {
        return safePath(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
    }

    public static String capture(Path sourceRoot, List<ClassContextDto> classes) {
        if (sourceRoot == null || classes == null || classes.isEmpty()) return UNVERIFIED + "\n";
        Map<String, String> fingerprints = new LinkedHashMap<>();
        try {
            Path root = sourceRoot.toRealPath();
            for (ClassContextDto owner : classes) {
                String relative = safePath(owner.filePath());
                Path source = root.resolve(relative).normalize();
                if (!source.startsWith(root) || !source.toRealPath().startsWith(root)
                        || !Files.isRegularFile(source) || Files.size(source) > MAX_SOURCE_BYTES) {
                    throw new IllegalArgumentException("Unsafe or oversized generation source: " + relative);
                }
                String raw = Files.readString(source, StandardCharsets.UTF_8);
                verifyContext(raw, owner);
                fingerprints.put(relative, hash(raw));
                if (fingerprints.size() > 256) throw new IllegalArgumentException("Too many generation source files");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read generation source; re-analyze the project before generating tests", exception);
        }
        return metadata(fingerprints);
    }

    private static void verifyContext(String raw, ClassContextDto owner) {
        String expected = owner.validationSourceCode();
        if (expected == null || expected.isBlank()) return;
        CompilationUnit actualUnit = parse(raw);
        CompilationUnit expectedUnit = parse(expected);
        // Analysis giữ riêng outer type; không so toàn file khi có nhiều top-level type.
        var names = expectedUnit.getTypes().stream().map(type -> type.getNameAsString()).toList();
        actualUnit.getTypes().removeIf(type -> !names.contains(type.getNameAsString()));
        actualUnit.getAllContainedComments().forEach(comment -> comment.remove());
        expectedUnit.getAllContainedComments().forEach(comment -> comment.remove());
        actualUnit.removeComment();
        expectedUnit.removeComment();
        if (!normalize(actualUnit.toString()).equals(normalize(expectedUnit.toString()))) {
            throw new IllegalArgumentException("Source version differs from analysis for " + owner.filePath()
                    + "; re-analyze the project before generating tests");
        }
    }

    private static CompilationUnit parse(String source) {
        return new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(normalize(source)).getResult()
                .orElseThrow(() -> new IllegalArgumentException("Cannot parse generation source for version verification"));
    }

    public static String hash(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalize(source).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String normalize(String source) {
        String normalized = source.startsWith("\uFEFF") ? source.substring(1) : source;
        return normalized.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static String safePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Missing generation source path");
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":") || normalized.contains("\n")
                || normalized.contains("\r") || normalized.contains("\u0000")) {
            throw new IllegalArgumentException("Unsafe generation source path");
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Unsafe generation source path");
            }
        }
        return normalized;
    }

    public static String aggregate(List<String> sources) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        boolean unverified = false;
        for (String source : sources) {
            boolean found = false;
            var lines = normalize(source).lines().toList();
            var metadataLines = metadataLines(source);
            for (int index = 0; index < lines.size(); index++) {
                if (!metadataLines.contains(index)) continue;
                String line = lines.get(index);
                if (line.equals(UNVERIFIED)) unverified = true;
                if (!line.startsWith(PREFIX)) continue;
                String[] fields = line.substring(PREFIX.length()).split(" ", -1);
                if (fields.length != 2 || !fields[0].matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("Invalid GreyTest source version metadata");
                }
                String path = safePath(new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8));
                String previous = fingerprints.putIfAbsent(path, fields[0]);
                if (previous != null && !previous.equals(fields[0])) {
                    throw new IllegalArgumentException("Conflicting source versions for " + path + "; regenerate the affected tests");
                }
                found = true;
            }
            if (!found) unverified = true;
        }
        return (unverified ? UNVERIFIED + "\n" : "") + metadata(fingerprints);
    }

    private static String metadata(Map<String, String> fingerprints) {
        StringBuilder metadata = new StringBuilder();
        fingerprints.forEach((path, hash) -> metadata.append(PREFIX).append(hash).append(' ')
                .append(Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8))).append('\n'));
        return metadata.toString();
    }

    public static String attach(String source, String metadata) {
        // Metadata do server tạo; không tin fingerprint do LLM tự khai báo.
        var metadataLines = metadataLines(source);
        var lines = normalize(source).lines().toList();
        String clean = java.util.stream.IntStream.range(0, lines.size())
                .filter(index -> !metadataLines.contains(index)).mapToObj(lines::get)
                .collect(java.util.stream.Collectors.joining("\n"));
        return metadata + clean;
    }

    private static java.util.Set<Integer> metadataLines(String source) {
        CompilationUnit unit = parse(source);
        var comments = new java.util.ArrayList<>(unit.getAllContainedComments());
        unit.getComment().ifPresent(comments::add);
        // Không đọc/xóa dòng giống metadata bên trong text block hoặc block comment của test.
        return comments.stream().filter(comment -> comment.isLineComment())
                .filter(comment -> ("//" + comment.getContent()).startsWith(PREFIX)
                        || ("//" + comment.getContent()).equals(UNVERIFIED))
                .flatMap(comment -> comment.getRange().stream())
                .filter(range -> range.begin.column == 1 && range.begin.line == range.end.line)
                .map(range -> range.begin.line - 1).collect(java.util.stream.Collectors.toSet());
    }

    public static String manifest(String metadata) {
        return metadata.lines().filter(line -> line.startsWith(PREFIX))
                .map(line -> line.substring(PREFIX.length()))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
    }
}
