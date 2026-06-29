package com.greytest.service.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.greytest.dto.ExistingTestDto;
import com.greytest.entity.ExistingTest;
import com.greytest.entity.JavaClass;
import com.greytest.exception.SourceAnalysisException;
import com.greytest.repository.ExistingTestRepository;
import com.greytest.repository.JavaClassRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ExistingTestService {

    private static final ParserConfiguration PARSER_CONFIGURATION = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    private static final Set<String> TEST_METHOD_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");
    private static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            ".git", ".gradle", "target", "build", "out", "node_modules",
            "generated-sources", "generated-test-sources");

    private final ExistingTestRepository existingTestRepository;
    private final JavaClassRepository javaClassRepository;

    public ExistingTestService(
            ExistingTestRepository existingTestRepository,
            JavaClassRepository javaClassRepository) {
        this.existingTestRepository = existingTestRepository;
        this.javaClassRepository = javaClassRepository;
    }

    @Transactional
    public int refresh(Long projectId, Path projectDir) {
        existingTestRepository.deleteByProjectId(projectId);
        List<JavaClass> productionClasses = javaClassRepository.findByProjectId(projectId);
        Map<String, JavaClass> classesBySimpleName = productionClasses.stream()
                .collect(Collectors.toMap(JavaClass::getClassName, item -> item, (left, right) -> left));

        int saved = 0;
        for (Path testFile : findTestJavaFiles(projectDir)) {
            try {
                ExistingTest existingTest = parseTestFile(projectId, projectDir, testFile, classesBySimpleName);
                existingTestRepository.save(existingTest);
                saved++;
            } catch (Exception exception) {
                log.warn("Bo qua existing test khong parse duoc {}: {}", testFile, firstLine(exception.getMessage()));
            }
        }
        log.info("Stored {} existing test context files for project {}", saved, projectId);
        return saved;
    }

    @Transactional
    public void deleteByProjectId(Long projectId) {
        existingTestRepository.deleteByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public List<ExistingTestDto> list(Long projectId) {
        return existingTestRepository.findByProjectId(projectId).stream()
                .map(this::toDto)
                .toList();
    }

    private ExistingTest parseTestFile(
            Long projectId,
            Path projectDir,
            Path testFile,
            Map<String, JavaClass> classesBySimpleName) throws IOException {
        ParseResult<CompilationUnit> parseResult = new JavaParser(PARSER_CONFIGURATION).parse(testFile);
        if (!parseResult.isSuccessful()) throw new ParseProblemException(parseResult.getProblems());
        CompilationUnit unit = parseResult.getResult()
                .orElseThrow(() -> new ParseProblemException(parseResult.getProblems()));

        ClassOrInterfaceDeclaration testClass = unit.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
        String testClassName = testClass == null ? testFile.getFileName().toString().replace(".java", "")
                : testClass.getNameAsString();
        JavaClass relatedClass = classesBySimpleName.get(productionClassCandidate(testClassName));

        ExistingTest existingTest = new ExistingTest();
        existingTest.setProjectId(projectId);
        existingTest.setFilePath(relativePath(projectDir, testFile));
        existingTest.setPackageName(unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString()).orElse(""));
        existingTest.setTestClassName(testClassName);
        existingTest.setRelatedClassId(relatedClass == null ? null : relatedClass.getId());
        existingTest.setRelatedMethodId(null);
        existingTest.setImports(unit.getImports().stream().map(importDecl -> importDecl.getNameAsString()).toList());
        existingTest.setTestMethods(testClass == null ? List.of() : extractTestMethods(testClass));
        existingTest.setSourceCode(Files.readString(testFile));
        return existingTest;
    }

    private List<Map<String, Object>> extractTestMethods(ClassOrInterfaceDeclaration testClass) {
        List<Map<String, Object>> methods = new ArrayList<>();
        for (MethodDeclaration method : testClass.getMethods()) {
            List<String> annotations = method.getAnnotations().stream()
                    .map(annotation -> annotation.getNameAsString())
                    .toList();
            boolean looksLikeTest = annotations.stream().anyMatch(TEST_METHOD_ANNOTATIONS::contains)
                    || method.getNameAsString().toLowerCase().contains("test");
            if (!looksLikeTest) continue;

            List<String> calls = method.findAll(MethodCallExpr.class).stream()
                    .map(call -> call.getNameAsString())
                    .toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", method.getNameAsString());
            row.put("annotations", annotations);
            row.put("assertions", calls.stream()
                    .filter(name -> name.startsWith("assert") || name.equals("assertThat"))
                    .distinct()
                    .toList());
            row.put("mocks", calls.stream()
                    .filter(name -> Set.of("when", "given", "verify", "doReturn", "doThrow").contains(name))
                    .distinct()
                    .toList());
            row.put("lineStart", method.getRange().map(range -> range.begin.line).orElse(0));
            row.put("lineEnd", method.getRange().map(range -> range.end.line).orElse(0));
            methods.add(row);
        }
        return methods;
    }

    private List<Path> findTestJavaFiles(Path projectDir) {
        try (Stream<Path> walk = Files.walk(projectDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isIgnoredPath(projectDir, path))
                    .filter(path -> isUnderTestSource(projectDir, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw new SourceAnalysisException("Khong doc duoc existing tests trong: " + projectDir, exception);
        }
    }

    private boolean isIgnoredPath(Path rootDir, Path file) {
        for (Path segment : rootDir.relativize(file)) {
            if (IGNORED_DIRECTORY_NAMES.contains(segment.toString())) return true;
        }
        return false;
    }

    private boolean isUnderTestSource(Path rootDir, Path file) {
        String relativePath = "/" + rootDir.relativize(file).toString().replace('\\', '/') + "/";
        return relativePath.contains("/src/test/java/");
    }

    private String productionClassCandidate(String testClassName) {
        return testClassName.replaceAll("(IT|Tests|Test)$", "");
    }

    private ExistingTestDto toDto(ExistingTest test) {
        return new ExistingTestDto(
                test.getId(),
                test.getProjectId(),
                test.getFilePath(),
                test.getPackageName(),
                test.getTestClassName(),
                test.getRelatedClassId(),
                test.getRelatedMethodId(),
                test.getTestMethods(),
                test.getImports(),
                test.getCreatedAt());
    }

    private String relativePath(Path rootDir, Path file) {
        return rootDir.relativize(file).toString().replace('\\', '/');
    }

    private String firstLine(String message) {
        if (message == null || message.isBlank()) return "Unknown parse error";
        int lineBreak = message.indexOf('\n');
        return lineBreak >= 0 ? message.substring(0, lineBreak) : message;
    }
}
