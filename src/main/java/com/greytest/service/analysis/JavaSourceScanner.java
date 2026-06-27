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

/** Detects source roots, excludes tests/build output, and parses production Java files. */
@Slf4j
class JavaSourceScanner {

    private static final ParserConfiguration PARSER_CONFIGURATION = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    private static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            ".git", ".gradle", "target", "build", "out", "node_modules",
            "generated-sources", "generated-test-sources");

    List<ParsedFile> parseDirectory(Path dir) {
        return parseFiles(dir, findJavaFiles(dir), true).parsedFiles();
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

        ParseFilesResult parseResult = parseFiles(projectDir, productionFiles, false);
        log.info("Classified source: {} production files, {} parsed, {} failed, {} existing test files",
                productionFiles.size(), parseResult.parsedFiles().size(), parseResult.failedFiles().size(),
                testFiles.size());
        return new SourceScanResult(
                parseResult.parsedFiles(),
                testFiles.size(),
                productionFiles.size(),
                parseResult.failedFiles());
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
            throw new SourceAnalysisException("Khong doc duoc thu muc source: " + dir, exception);
        }
    }

    private ParseFilesResult parseFiles(Path rootDir, List<Path> javaFiles, boolean failOnError) {
        List<ParsedFile> results = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();
        log.info("Found {} production .java files in {}", javaFiles.size(), rootDir);

        for (Path file : javaFiles) {
            try {
                ParseResult<CompilationUnit> parseResult = new JavaParser(PARSER_CONFIGURATION).parse(file);
                if (!parseResult.isSuccessful()) throw new ParseProblemException(parseResult.getProblems());
                CompilationUnit unit = parseResult.getResult()
                        .orElseThrow(() -> new ParseProblemException(parseResult.getProblems()));
                results.add(new ParsedFile(unit, relativePath(rootDir, file)));
            } catch (Exception exception) {
                log.warn("Could not parse file {}: {}", file, firstLine(exception.getMessage()));
                failedFiles.add(relativePath(rootDir, file));
            }
        }

        if (failOnError && !failedFiles.isEmpty()) {
            throw new SourceAnalysisException(
                    "Khong the parse " + failedFiles.size() + " file Java: " + String.join(", ", failedFiles));
        }
        if (results.isEmpty()) {
            if (failedFiles.isEmpty()) throw new SourceAnalysisException("Khong tim thay file .java nao trong project");
            throw new SourceAnalysisException("Khong parse duoc file production Java nao. File loi: "
                    + String.join(", ", failedFiles));
        }
        return new ParseFilesResult(results, failedFiles);
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

    private String relativePath(Path rootDir, Path file) {
        return rootDir.relativize(file).toString().replace('\\', '/');
    }

    private String firstLine(String message) {
        if (message == null || message.isBlank()) return "Unknown parse error";
        int lineBreak = message.indexOf('\n');
        return lineBreak >= 0 ? message.substring(0, lineBreak) : message;
    }

    private record ParseFilesResult(List<ParsedFile> parsedFiles, List<String> failedFiles) {
    }
}
