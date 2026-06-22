package com.greytest.service.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.greytest.exception.SourceAnalysisException;
import com.greytest.service.analysis.JavaParserHelper.ParsedFile;
import com.greytest.service.analysis.JavaParserHelper.SourceScanResult;

import lombok.extern.slf4j.Slf4j;

/** Phát hiện source root, loại test/build output và parse production files bằng Java 17. */
@Slf4j
class JavaSourceScanner {

    private static final ParserConfiguration PARSER_CONFIGURATION = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    private static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            ".git", ".gradle", "target", "build", "out", "node_modules",
            "generated-sources", "generated-test-sources");

    List<ParsedFile> parseDirectory(Path dir) {
        return parseFiles(dir, findJavaFiles(dir));
    }

    SourceScanResult scanProject(Path projectDir) {
        List<Path> allJavaFiles = findJavaFiles(projectDir);
        List<Path> testFiles = allJavaFiles.stream()
                .filter(file -> isUnderSourceSet(projectDir, file, "test"))
                .toList();
        List<Path> productionFiles = allJavaFiles.stream()
                .filter(file -> isUnderSourceSet(projectDir, file, "main"))
                .toList();
        if (productionFiles.isEmpty()) {
            productionFiles = allJavaFiles.stream()
                    .filter(file -> !isUnderSourceSet(projectDir, file, "test"))
                    .toList();
        }

        List<ParsedFile> parsedFiles = parseFiles(projectDir, productionFiles);
        log.info("Phân loại source: {} production files, {} existing test files",
                productionFiles.size(), testFiles.size());
        return new SourceScanResult(parsedFiles, testFiles.size());
    }

    int countExistingTestFiles(Path projectDir) {
        return (int) findJavaFiles(projectDir).stream()
                .filter(file -> isUnderSourceSet(projectDir, file, "test"))
                .count();
    }

    private List<Path> findJavaFiles(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isIgnoredPath(dir, path))
                    .toList();
        } catch (IOException exception) {
            throw new SourceAnalysisException("Không đọc được thư mục source: " + dir, exception);
        }
    }

    private List<ParsedFile> parseFiles(Path rootDir, List<Path> javaFiles) {
        List<ParsedFile> results = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();
        log.info("Tìm thấy {} production file .java trong {}", javaFiles.size(), rootDir);

        for (Path file : javaFiles) {
            try {
                ParseResult<CompilationUnit> parseResult = new JavaParser(PARSER_CONFIGURATION).parse(file);
                if (!parseResult.isSuccessful()) throw new ParseProblemException(parseResult.getProblems());
                CompilationUnit unit = parseResult.getResult()
                        .orElseThrow(() -> new ParseProblemException(parseResult.getProblems()));
                results.add(new ParsedFile(unit, rootDir.relativize(file).toString().replace('\\', '/')));
            } catch (Exception exception) {
                log.warn("Không thể parse file {}: {}", file, firstLine(exception.getMessage()));
                failedFiles.add(rootDir.relativize(file).toString());
            }
        }

        if (!failedFiles.isEmpty()) {
            throw new SourceAnalysisException(
                    "Không thể parse " + failedFiles.size() + " file Java: " + String.join(", ", failedFiles));
        }
        if (results.isEmpty()) throw new SourceAnalysisException("Không tìm thấy file .java nào trong project");
        return results;
    }

    private boolean isIgnoredPath(Path rootDir, Path file) {
        for (Path segment : rootDir.relativize(file)) {
            if (IGNORED_DIRECTORY_NAMES.contains(segment.toString())) return true;
        }
        return false;
    }

    private boolean isUnderSourceSet(Path rootDir, Path file, String sourceSet) {
        String relativePath = "/" + rootDir.relativize(file).toString().replace('\\', '/') + "/";
        return relativePath.contains("/src/" + sourceSet + "/java/");
    }

    private String firstLine(String message) {
        if (message == null || message.isBlank()) return "Lỗi parse không xác định";
        int lineBreak = message.indexOf('\n');
        return lineBreak >= 0 ? message.substring(0, lineBreak) : message;
    }
}
