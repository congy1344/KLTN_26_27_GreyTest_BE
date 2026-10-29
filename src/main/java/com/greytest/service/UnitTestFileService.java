package com.greytest.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.greytest.dto.UnitTestFileDto;
import com.greytest.entity.UnitTest;
import com.greytest.exception.StorageException;
import com.greytest.service.agent.GenerationSourceFingerprint;
import com.greytest.service.agent.GenerationValidationGate;

import lombok.extern.slf4j.Slf4j;

/**
 * Gộp các unit test record có fixture tương thích thành file Java hoàn chỉnh.
 * Fixture khác nhau được xuất thành class riêng để giữ đúng hành vi từng test.
 * DB vẫn giữ 1 record cho mỗi test case để phục vụ traceability.
 */
@Slf4j
@Service
public class UnitTestFileService {

    private static final String JACOCO_VERSION = "0.8.15";
    private static final String WINDOWS_SCRIPT_NAME = "run-greytest-coverage.cmd";
    private static final String UNIX_SCRIPT_NAME = "run-greytest-coverage.sh";
    private static final String GRADLE_INIT_SCRIPT_NAME = "greytest-jacoco.init.gradle";
    private static final String README_NAME = "README-GREYTEST.txt";

    public record MergedFile(String filePath, String testClassName, String packageName,
            List<Long> caseIds, String sourceCode) {
    }

    public List<MergedFile> mergeByClass(List<UnitTest> tests) {
        Map<String, List<UnitTest>> byFile = new LinkedHashMap<>();
        for (UnitTest test : tests) {
            byFile.computeIfAbsent(test.getFilePath(), key -> new ArrayList<>()).add(test);
        }
        Set<String> usedClasses = tests.stream().map(test -> (test.getPackageName() + "." + test.getTestClassName()).toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> usedPaths = new HashSet<>();
        List<MergedFile> files = new ArrayList<>();
        for (var group : byFile.values()) {
            for (var merged : mergeCompatibleFixtures(group, usedClasses)) {
                // Windows ghi đè hai file chỉ khác hoa/thường; giữ riêng class cho từng fixture.
                while (!usedPaths.add(merged.filePath().replace('\\', '/').toLowerCase(java.util.Locale.ROOT))) {
                    merged = renameVariant(merged, group.get(0), usedClasses);
                }
                files.add(merged);
            }
        }
        return files;
    }

    private List<MergedFile> mergeCompatibleFixtures(List<UnitTest> tests, Set<String> usedClasses) {
        try {
            return splitCompatibleFixtures(tests, usedClasses);
        } catch (StorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new StorageException("Không gộp được Unit Test thành file Java chạy được", exception);
        }
    }

    private List<MergedFile> splitCompatibleFixtures(List<UnitTest> tests, Set<String> usedClasses) {
        if (tests.size() < 2 || allFixturesCompatible(tests)) return List.of(merge(tests));
        // Không chọn ngầm setUp/field của record đầu: mỗi biến thể giữ fixture riêng.
        List<List<UnitTest>> variants = new ArrayList<>();
        var ordered = new ArrayList<>(tests);
        ordered.sort(java.util.Comparator.comparing(UnitTest::getTestCaseId,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(test -> GenerationSourceFingerprint.hash(test.getSourceCode())));
        for (UnitTest test : ordered) {
            var compatible = variants.stream().filter(group -> group.stream()
                    .allMatch(existing -> fixturesCompatible(existing, test))).findFirst().orElse(null);
            if (compatible == null) variants.add(new ArrayList<>(List.of(test)));
            else compatible.add(test);
        }
        return variants.stream().map(group -> renameVariant(merge(group), group.get(0), usedClasses)).toList();
    }

    private boolean allFixturesCompatible(List<UnitTest> tests) {
        for (int i = 0; i < tests.size(); i++) {
            for (int j = i + 1; j < tests.size(); j++) {
                if (!fixturesCompatible(tests.get(i), tests.get(j))) return false;
            }
        }
        return true;
    }

    private boolean fixturesCompatible(UnitTest first, UnitTest second) {
        CompilationUnit base = parse(first.getSourceCode());
        CompilationUnit next = parse(second.getSourceCode());
        TypeDeclaration<?> left = typeByName(base, first.getTestClassName());
        TypeDeclaration<?> right = typeByName(next, second.getTestClassName());
        Map<String, String> leftBindings = fixtureStaticBindings(base, left, first.getTestMethodName());
        Map<String, String> rightBindings = fixtureStaticBindings(next, right, second.getTestMethodName());
        Map<String, String> conflicts = new LinkedHashMap<>();
        try {
            next.getImports().forEach(imp -> addImport(base, left, next, right, imp, conflicts));
        } catch (StorageException exception) {
            // Giữ binding của từng record khi cùng tên static member thuộc các owner khác nhau.
            if (exception.getMessage() != null && exception.getMessage().startsWith("Xung dot static import:")) return false;
            throw exception;
        }
        qualifyConflictingTypes(right, conflicts);
        Map<String, String> leftFields = fixtureFields(left);
        Map<String, String> rightFields = fixtureFields(right);
        boolean injection = left.getFields().stream().anyMatch(field -> field.getAnnotationByName("InjectMocks").isPresent())
                || right.getFields().stream().anyMatch(field -> field.getAnnotationByName("InjectMocks").isPresent());
        return classHeader(left).equals(classHeader(right))
                && !conflictingEntries(leftFields, rightFields)
                && (!injection || leftFields.equals(rightFields))
                && fixtureMethods(left, first.getTestMethodName()).equals(fixtureMethods(right, second.getTestMethodName()))
                && !conflictingEntries(fixtureMethods(left, null), fixtureMethods(right, null))
                && !conflictingEntries(leftBindings, rightBindings)
                && lifecycleMembers(left).equals(lifecycleMembers(right));
    }

    private Map<String, String> fixtureStaticBindings(CompilationUnit unit, TypeDeclaration<?> type, String testMethodName) {
        var fixture = type.clone();
        fixture.getMethods().stream().filter(method -> method.getNameAsString().equals(testMethodName))
                .toList().forEach(MethodDeclaration::remove);
        Map<String, String> bindings = new LinkedHashMap<>();
        for (var call : fixture.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)) {
            if (call.getScope().isPresent()) continue;
            String name = call.getNameAsString();
            var explicit = unit.getImports().stream().filter(imp -> imp.isStatic() && !imp.isAsterisk()
                    && importSimpleName(imp).equals(name)).map(imp -> imp.getNameAsString()).sorted().toList();
            var imported = explicit.isEmpty() ? unit.getImports().stream().filter(imp -> imp.isStatic() && imp.isAsterisk())
                    .map(imp -> imp.getNameAsString() + "." + name).sorted().toList() : explicit;
            if (!imported.isEmpty()) bindings.put(name, String.join(",", imported));
        }
        return bindings;
    }

    private String classHeader(TypeDeclaration<?> type) {
        var header = type.clone();
        header.getMembers().clear();
        header.getAnnotations().clear();
        return canonical(header);
    }

    private Map<String, String> fixtureFields(TypeDeclaration<?> type) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (var field : type.getFields()) {
            for (var variable : field.getVariables()) {
                var single = field.clone();
                single.getVariables().clear();
                single.getVariables().add(variable.clone());
                fields.put(variable.getNameAsString(), canonical(single));
            }
        }
        return fields;
    }

    private Map<String, String> fixtureMethods(TypeDeclaration<?> type, String testMethodName) {
        Map<String, String> methods = new LinkedHashMap<>();
        for (var method : type.getMethods()) {
            if (testMethodName != null && (method.getNameAsString().equals(testMethodName) || method.getAnnotations().stream()
                    .anyMatch(annotation -> Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate")
                            .contains(annotation.getName().getIdentifier())))) continue;
            var normalized = method.clone();
            String signature = methodSignature(method);
            String[] parameterTypes = signature.substring(signature.indexOf('(') + 1, signature.length() - 1).split(",");
            for (int i = 0; i < normalized.getParameters().size(); i++) {
                normalized.getParameter(i).setVarArgs(false).setType(parameterTypes[i]);
            }
            normalized.getTypeParameters().clear();
            methods.put(signature, canonical(normalized));
        }
        return methods;
    }

    private List<String> lifecycleMembers(TypeDeclaration<?> type) {
        List<String> members = new ArrayList<>();
        for (var member : type.getMembers()) {
            if (member instanceof ConstructorDeclaration || member instanceof com.github.javaparser.ast.body.InitializerDeclaration) {
                members.add(canonical(member));
            } else if (member instanceof MethodDeclaration method && method.getAnnotations().stream()
                    .anyMatch(annotation -> Set.of("BeforeEach", "AfterEach", "BeforeAll", "AfterAll", "Before", "After",
                            "BeforeClass", "AfterClass").contains(annotation.getName().getIdentifier()))) {
                members.add(canonical(method));
            } else if (member instanceof FieldDeclaration field) {
                field.getVariables().stream().filter(variable -> variable.getInitializer().isPresent())
                        .forEach(variable -> members.add(canonical(variable)));
            }
        }
        for (var annotation : type.getAnnotations()) {
            // MockitoExtension được thêm sẵn khi export các field mock chưa có runner.
            if (!onlyMockitoExtension(annotation)) {
                members.add(canonical(annotation));
            }
        }
        return members;
    }

    private boolean onlyMockitoExtension(com.github.javaparser.ast.expr.AnnotationExpr annotation) {
        if (!annotation.getName().getIdentifier().equals("ExtendWith") || !annotation.isSingleMemberAnnotationExpr()) return false;
        var value = annotation.asSingleMemberAnnotationExpr().getMemberValue();
        if (value.isArrayInitializerExpr()) {
            if (value.asArrayInitializerExpr().getValues().size() != 1) return false;
            value = value.asArrayInitializerExpr().getValues().get(0);
        }
        if (!value.isClassExpr()) return false;
        return Set.of("MockitoExtension", "org.mockito.junit.jupiter.MockitoExtension")
                .contains(value.asClassExpr().getType().asString());
    }

    private boolean conflictingEntries(Map<String, String> left, Map<String, String> right) {
        return left.entrySet().stream().anyMatch(entry -> right.containsKey(entry.getKey())
                && !entry.getValue().equals(right.get(entry.getKey())));
    }

    private String canonical(com.github.javaparser.ast.Node node) {
        var copy = node.clone();
        copy.getAllContainedComments().forEach(com.github.javaparser.ast.comments.Comment::remove);
        copy.removeComment();
        return copy.toString();
    }

    private MergedFile renameVariant(MergedFile file, UnitTest first, Set<String> usedClasses) {
        CompilationUnit unit = parse(file.sourceCode());
        if (unit.getTypes().size() != 1) {
            throw new StorageException("Không tách được fixture có nhiều top-level class", null);
        }
        String oldName = file.testClassName();
        String suffix = first.getTestCaseId() == null
                ? GenerationSourceFingerprint.hash(first.getSourceCode()).substring(0, 12) : first.getTestCaseId().toString();
        String stem = oldName + "_GreyTest_" + suffix;
        String name = stem;
        int collision = 2;
        while (!usedClasses.add((file.packageName() + "." + name).toLowerCase(java.util.Locale.ROOT))) name = stem + "_" + collision++;
        String newName = name;
        TypeDeclaration<?> type = typeByName(unit, oldName);
        type.setName(newName);
        type.getConstructors().forEach(constructor -> constructor.setName(newName));
        unit.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class).stream()
                .filter(reference -> reference.getNameAsString().equals(oldName))
                .forEach(reference -> reference.setName(newName));
        unit.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                .filter(reference -> reference.getNameAsString().equals(oldName))
                .forEach(reference -> reference.setName(newName));
        unit.findAll(com.github.javaparser.ast.expr.ThisExpr.class).stream()
                .filter(reference -> reference.getTypeName().filter(nameRef -> nameRef.asString().equals(oldName)).isPresent())
                .forEach(reference -> reference.setTypeName(new com.github.javaparser.ast.expr.Name(newName)));
        unit.findAll(com.github.javaparser.ast.expr.SuperExpr.class).stream()
                .filter(reference -> reference.getTypeName().filter(nameRef -> nameRef.asString().equals(oldName)).isPresent())
                .forEach(reference -> reference.setTypeName(new com.github.javaparser.ast.expr.Name(newName)));
        String metadata = sourceMetadata(List.of(file.sourceCode()));
        String source = unit.toString();
        if (metadata.contains(GenerationSourceFingerprint.PREFIX)) source = GenerationSourceFingerprint.attach(source, metadata);
        String path = file.filePath().substring(0, file.filePath().length() - oldName.length() - ".java".length())
                + newName + ".java";
        return new MergedFile(path, newName, file.packageName(), file.caseIds(), source);
    }

    /**
     * Đóng gói test cùng script để tạo JaCoCo XML mà không thêm cấu hình JaCoCo vào pom.xml.
     */
    public byte[] createCoverageArchive(List<UnitTestFileDto> testFiles) {
        if (testFiles == null || testFiles.isEmpty()) {
            throw new StorageException("Không có Unit Test để đóng gói", null);
        }
        testFiles.forEach(this::validateArchiveFile);
        String sourceMetadata = sourceMetadata(testFiles.stream().map(UnitTestFileDto::sourceCode).toList());
        var output = new ByteArrayOutputStream();
        String testSelector = testFiles.stream()
                .map(test -> test.packageName() == null || test.packageName().isBlank()
                        ? test.testClassName() : test.packageName() + "." + test.testClassName())
                .distinct()
                .collect(Collectors.joining(","));
        String gradleTestFilters = testFiles.stream()
                .map(test -> test.packageName() == null || test.packageName().isBlank()
                        ? test.testClassName() : test.packageName() + "." + test.testClassName())
                .distinct()
                .map(testClass -> "--tests \"" + testClass + "\"")
                .collect(Collectors.joining(" "));
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (UnitTestFileDto testFile : testFiles) {
                String safeSource = ensureMockitoRunner(testFile.sourceCode());
                if (testFile.sourceCode().contains(GenerationSourceFingerprint.PREFIX)) {
                    safeSource = GenerationSourceFingerprint.attach(safeSource,
                            sourceMetadata(List.of(testFile.sourceCode())));
                }
                writeArchiveEntry(zip, safeTestPath(testFile.filePath()), safeSource);
            }
            writeArchiveEntry(zip, WINDOWS_SCRIPT_NAME, windowsCoverageScript(testSelector, gradleTestFilters, sourceMetadata));
            writeArchiveEntry(zip, UNIX_SCRIPT_NAME, unixCoverageScript(testSelector, gradleTestFilters, sourceMetadata));
            writeArchiveEntry(zip, GRADLE_INIT_SCRIPT_NAME, gradleJacocoInitScript());
            for (String resource : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties", "LICENSE", "NOTICE")) {
                writeCoverageResource(zip, ".greytest/maven/" + resource, "maven-wrapper/" + resource);
            }
            writeArchiveEntry(zip, "greytest-source-version.txt", GenerationSourceFingerprint.manifest(sourceMetadata));
            try (var helper = UnitTestFileService.class.getResourceAsStream("/coverage/GreyTestSourceCheck.java")) {
                if (helper == null) throw new IOException("Missing coverage source verifier resource");
                writeArchiveEntry(zip, "GreyTestSourceCheck.java", new String(helper.readAllBytes(), StandardCharsets.UTF_8));
            }
            writeArchiveEntry(zip, README_NAME, coverageReadme());
        } catch (IOException exception) {
            throw new StorageException("Không tạo được ZIP unit test", exception);
        }
        return output.toByteArray();
    }

    /** Đồng bộ đường dẫn trong header và manifest với source đang hoạt động trước khi xuất ZIP. */
    public byte[] createCoverageArchive(List<UnitTestFileDto> testFiles, Path sourceRoot) {
        if (sourceRoot == null || testFiles == null || testFiles.isEmpty()) return createCoverageArchive(testFiles);
        try {
            List<String> aligned = GenerationSourceFingerprint.alignToSourceRoot(
                    testFiles.stream().map(UnitTestFileDto::sourceCode).toList(), sourceRoot);
            List<UnitTestFileDto> files = new ArrayList<>();
            for (int index = 0; index < testFiles.size(); index++) {
                var file = testFiles.get(index);
                files.add(new UnitTestFileDto(file.filePath(), file.testClassName(), file.packageName(),
                        file.testCount(), file.caseCodes(), aligned.get(index)));
            }
            return createCoverageArchive(files);
        } catch (IllegalArgumentException exception) {
            throw new StorageException(exception.getMessage(), exception);
        }
    }

    private void writeCoverageResource(ZipOutputStream zip, String entry, String resource) throws IOException {
        try (var input = UnitTestFileService.class.getResourceAsStream("/coverage/" + resource)) {
            if (input == null) throw new IOException("Missing coverage resource: " + resource);
            writeArchiveEntry(zip, entry, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void writeArchiveEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        String safeContent = name.endsWith(".java") ? stripLeadingBom(content) : content;
        zip.write((safeContent == null ? "" : safeContent).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String stripLeadingBom(String source) {
        return source != null && !source.isEmpty() && source.charAt(0) == '\uFEFF'
                ? source.substring(1) : source;
    }

    private void validateArchiveFile(UnitTestFileDto testFile) {
        if (testFile == null || testFile.sourceCode() == null || testFile.sourceCode().isBlank()) {
            throw new StorageException("Source Unit Test rỗng, không thể đóng gói", null);
        }
        String path = safeTestPath(testFile.filePath());
        CompilationUnit unit = parseForArchive(stripLeadingBom(testFile.sourceCode()));
        GenerationValidationGate.validateExportFile(testFile, unit, path)
                .ifPresent(problem -> { throw new StorageException(problem, null); });
    }

    private String safeTestPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new StorageException("Đường dẫn Unit Test không hợp lệ", null);
        }
        String normalized = filePath.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.startsWith("/") || normalized.contains("..") || normalized.contains(":")) {
            throw new StorageException("Đường dẫn Unit Test không hợp lệ", null);
        }
        int testRootIndex = normalized.indexOf("src/test/java/");
        if (testRootIndex < 0) {
            throw new StorageException("Đường dẫn Unit Test không hợp lệ: phải chứa src/test/java/", null);
        }
        return normalized.substring(testRootIndex);
    }

    private String windowsCoverageScript(String testSelector, String gradleTestFilters, String metadata) {
        return """
                @echo off
                setlocal enabledelayedexpansion
                chcp 65001 >nul
                cd /d "%%~dp0"

                %7$s

                %5$s

                set "GT_SRC_DIR=%%~dp0"
                if "!GT_SRC_DIR:~-1!"=="\\" set "GT_SRC_DIR=!GT_SRC_DIR:~0,-1!"

                if not defined GREYTEST_JVM_ARGS set "GREYTEST_JVM_ARGS=-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading"

                if not "!GREYTEST_JUNCTION_RUN!"=="1" (
                  powershell -NoProfile -ExecutionPolicy Bypass -Command "$gradle = (Test-Path -LiteralPath 'build.gradle') -or (Test-Path -LiteralPath 'build.gradle.kts'); $nested = $false; if ($gradle -and -not ((Test-Path -LiteralPath 'settings.gradle') -or (Test-Path -LiteralPath 'settings.gradle.kts'))) { for ($p = [IO.Directory]::GetParent($env:GT_SRC_DIR); $null -ne $p; $p = $p.Parent) { if ((Test-Path -LiteralPath (Join-Path $p.FullName 'settings.gradle')) -or (Test-Path -LiteralPath (Join-Path $p.FullName 'settings.gradle.kts'))) { $nested = $true; break } } }; if ($env:GT_SRC_DIR -match '[^\\x00-\\x7F]' -or ($gradle -and -not $nested -and $env:GT_SRC_DIR.Length -gt 120)) { exit 1 } else { exit 0 }" >nul 2>&1
                  if errorlevel 1 (
                    echo [GreyTest] Cảnh báo: Đường dẫn Unicode hoặc quá dài cho build tool Windows: "!GT_SRC_DIR!"
                    echo [GreyTest] Đang tự động tạo Junction ASCII tạm thời để build tool [gradlew/mvnw] hoạt động ổn định...
                    if exist "pom.xml" (
                      call :resolveMaven
                      if errorlevel 1 goto :junctionFailed
                    )
                    if exist "pom.xml" if exist "!GT_SRC_DIR!\\..\\pom.xml" (
                      echo [GreyTest] Phát hiện module con của dự án Maven multi-module. Đang cài đặt parent POM vào local repository...
                      pushd "!GT_SRC_DIR!\\.."
                      call "!MAVEN_COMMAND!" -N install -DskipTests
                      set "GT_PARENT_EXIT=!ERRORLEVEL!"
                      popd
                      if not "!GT_PARENT_EXIT!"=="0" (
                        echo [GreyTest] ERROR: Maven parent POM installation failed. Review the Maven output above.
                        goto :junctionFailed
                      )
                    )
                    set "GT_JUNC_BASE=%%TEMP%%\\greytest-junctions"
                    powershell -NoProfile -ExecutionPolicy Bypass -Command "if ($env:GT_JUNC_BASE -match '[^\\x00-\\x7F]') { exit 1 } else { exit 0 }" >nul 2>&1
                    if errorlevel 1 (
                      echo [GreyTest] ERROR: TEMP must point to a writable ASCII directory for a Unicode source path.
                      goto :junctionFailed
                    )
                    if not exist "!GT_JUNC_BASE!" mkdir "!GT_JUNC_BASE!" >nul 2>&1
                    set "GT_JUNC_DIR=!GT_JUNC_BASE!\\gt-run-!RANDOM!-!RANDOM!"
                    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; New-Item -ItemType Junction -Path $env:GT_JUNC_DIR -Target $env:GT_SRC_DIR | Out-Null" >nul 2>&1
                    if errorlevel 1 (
                      echo [GreyTest] ERROR: Could not create temporary ASCII junction under "!GT_JUNC_BASE!".
                      goto :junctionFailed
                    )
                    if exist "!GT_JUNC_DIR!\\run-greytest-coverage.cmd" (
                      echo [GreyTest] Chuyển hướng thực thi qua Junction: "!GT_JUNC_DIR!"
                      set "GREYTEST_JUNCTION_RUN=1"
                      pushd "!GT_JUNC_DIR!"
                      call "!GT_JUNC_DIR!\\run-greytest-coverage.cmd" %%*
                      set "GT_EXIT=!ERRORLEVEL!"
                      popd
                      rmdir "!GT_JUNC_DIR!" >nul 2>&1
                      goto :junctionComplete
                    )
                    rmdir "!GT_JUNC_DIR!" >nul 2>&1
                    echo [GreyTest] ERROR: Could not access coverage script through temporary ASCII junction.
                    goto :junctionFailed
                  )
                )

                set "BUILD_KIND="
                if exist "pom.xml" set "BUILD_KIND=maven"
                if not defined BUILD_KIND if exist "build.gradle" set "BUILD_KIND=gradle"
                if not defined BUILD_KIND if exist "build.gradle.kts" set "BUILD_KIND=gradle"
                if not defined BUILD_KIND goto :missingBuild

                if not exist "src\\test\\java" (
                  echo [GreyTest] ERROR: Directory 'src\\test\\java' not found.
                  echo [GreyTest] Please make sure you extract the ZIP contents directly into the module root where the build file is located.
                  echo [GreyTest] All test files must be located inside 'src\\test\\java'.
                  exit /b 1
                )

                dir /s /b "src\\test\\java\\*.java" >nul 2>&1
                if errorlevel 1 (
                  echo [GreyTest] ERROR: No generated Java test file was found under src\\test\\java.
                  echo [GreyTest] Do not paste tests into src\\service or src\\main\\test. Extract the complete ZIP at the module root.
                  exit /b 1
                )

                if /i "%%BUILD_KIND%%"=="gradle" goto :gradle

                :maven
                call :resolveMaven
                if errorlevel 1 exit /b 1

                echo [GreyTest] Running tests and generating JaCoCo XML...
                call "%%MAVEN_COMMAND%%" clean test-compile ^
                  -Dfile.encoding=UTF-8 -Dproject.build.sourceEncoding=UTF-8 -Dproject.reporting.outputEncoding=UTF-8 ^
                  %%*
                if errorlevel 1 (
                  echo [GreyTest] ERROR: Project or generated tests could not be compiled.
                  echo [GreyTest] Tip: For Non-resolvable parent POM, use the project wrapper with -N install or see README-GREYTEST.txt.
                  exit /b 1
                )

                dir /s /b "target\\test-classes\\*.class" >nul 2>&1
                if errorlevel 1 (
                  echo [GreyTest] ERROR: Maven compiled no generated test class. Check Maven test source configuration.
                  exit /b 1
                )

                call "%%MAVEN_COMMAND%%" ^
                  -Djacoco.propertyName=greytestJacocoArgLine ^
                  "-DargLine=@{greytestJacocoArgLine} %%GREYTEST_JVM_ARGS%%" ^
                  "-Dtest=%2$s" ^
                  -DfailIfNoTests=true ^
                  org.jacoco:jacoco-maven-plugin:%3$s:prepare-agent ^
                  org.apache.maven.plugins:maven-surefire-plugin:test ^
                  org.jacoco:jacoco-maven-plugin:%4$s:report %%*
                if errorlevel 1 (
                  echo [GreyTest] ERROR: Build or tests failed. Review the Maven output above.
                echo [GreyTest] Gợi ý: Nếu gặp lỗi Mockito attach Byte Buddy, kiểm tra GREYTEST_JVM_ARGS hoặc dùng JDK 17+.
                  exit /b 1
                )

                if not exist "target\\site\\jacoco\\jacoco.xml" (
                  echo [GreyTest] ERROR: target\\site\\jacoco\\jacoco.xml was not created.
                  echo [GreyTest] Check whether the module has compiled tests and whether its packaging supports JaCoCo.
                  exit /b 2
                )

                echo [GreyTest] SUCCESS: target\\site\\jacoco\\jacoco.xml
                goto :success

                :gradle
                set "GRADLE_COMMAND=gradle"
                if exist "gradlew.bat" set "GRADLE_COMMAND=gradlew.bat"
                if not exist "gradlew.bat" (
                  where gradle >nul 2>&1
                  if errorlevel 1 (
                    echo [GreyTest] ERROR: Gradle was not found in PATH and gradlew.bat is missing.
                    echo [GreyTest] Restore gradlew.bat and gradle/wrapper from the project, or install its required Gradle version and add bin to PATH.
                    exit /b 1
                  )
                )

                echo [GreyTest] Running Gradle tests and generating JaCoCo XML...
                call %%GRADLE_COMMAND%% --init-script "greytest-jacoco.init.gradle" clean test %1$s jacocoTestReport %%*
                if errorlevel 1 (
                  echo [GreyTest] ERROR: Gradle project or generated tests could not be compiled or executed.
                  echo [GreyTest] Lưu ý: Nếu thư mục dự án chứa tiếng Việt có dấu [ví dụ: mẫu, sửa], hãy dùng junction ASCII hoặc đổi tên thư mục không dấu.
                  exit /b 1
                )

                if not exist "build\\reports\\jacoco\\test\\jacocoTestReport.xml" (
                  echo [GreyTest] ERROR: build\\reports\\jacoco\\test\\jacocoTestReport.xml was not created.
                  echo [GreyTest] Check whether the project applies the Gradle JaCoCo plugin.
                  exit /b 2
                )

                echo [GreyTest] SUCCESS: build\\reports\\jacoco\\test\\jacocoTestReport.xml
                goto :success

                :missingBuild
                echo [GreyTest] ERROR: No pom.xml, build.gradle, or build.gradle.kts was found in current directory (%%CD%%).
                echo [GreyTest] Nếu là dự án multi-module, vui lòng copy script này và thư mục src vào thư mục module con (nơi chứa pom.xml của service cần test, ví dụ: backend/appointment-service) trước khi chạy.
                exit /b 1

                :junctionFailed
                exit /b 1

                :junctionComplete
                exit /b %%GT_EXIT%%

                :success
                endlocal
                exit /b 0

                %6$s
                """.formatted(gradleTestFilters, testSelector, JACOCO_VERSION, JACOCO_VERSION,
                        sourceVersionCheck(true, metadata), windowsMavenResolver(), windowsJdkCheck())
                .replace("\n", "\r\n");
    }

    private String windowsJdkCheck() {
        return """
                if defined JAVA_HOME (
                  if not exist "%JAVA_HOME%\\bin\\java.exe" goto :missingJdk
                  if not exist "%JAVA_HOME%\\bin\\javac.exe" goto :missingJdk
                  set "PATH=%JAVA_HOME%\\bin;!PATH!"
                )
                java -version >nul 2>&1
                if errorlevel 1 goto :missingJdk
                javac -version >nul 2>&1
                if errorlevel 1 goto :missingJdk
                goto :jdkReady
                :missingJdk
                echo [GreyTest] ERROR: A working JDK is required. Set JAVA_HOME to the project JDK and add JAVA_HOME/bin to PATH.
                exit /b 1
                :jdkReady
                """;
    }

    private String windowsMavenResolver() {
        return """
                :resolveMaven
                set "MAVEN_COMMAND="
                set "GT_MAVEN_SCAN=%GT_SRC_DIR%"
                :findMavenWrapper
                if exist "!GT_MAVEN_SCAN!\\mvnw.cmd" (
                  set "MAVEN_COMMAND=!GT_MAVEN_SCAN!\\mvnw.cmd"
                  if not exist "!GT_MAVEN_SCAN!\\.mvn\\wrapper\\maven-wrapper.properties" (
                    echo [GreyTest] ERROR: Incomplete Maven wrapper. Restore .mvn/wrapper/maven-wrapper.properties and wrapper files from the project.
                    exit /b 1
                  )
                  goto :verifyMaven
                )
                for %%G in ("!GT_MAVEN_SCAN!\\..") do set "GT_MAVEN_PARENT=%%~fG"
                if "!GT_MAVEN_PARENT!"=="!GT_MAVEN_SCAN!" goto :findPathMaven
                set "GT_MAVEN_SCAN=!GT_MAVEN_PARENT!"
                goto :findMavenWrapper
                :findPathMaven
                for /f "delims=" %%M in ('where mvn.cmd mvn.bat mvn.exe 2^>nul') do (
                  call "%%~fM" -v >nul 2>&1
                  if not errorlevel 1 (
                    set "MAVEN_COMMAND=%%~fM"
                    exit /b 0
                  )
                )
                call :findLocalMaven
                if not errorlevel 1 exit /b 0
                echo [GreyTest] No usable Maven in PATH or .tools. Using bundled Maven wrapper; first run downloads Maven to the user's wrapper cache.
                set "MAVEN_COMMAND=%GT_SRC_DIR%\\.greytest\\maven\\mvnw.cmd"
                :verifyMaven
                call "!MAVEN_COMMAND!" -v >nul 2>&1
                if errorlevel 1 (
                  echo [GreyTest] ERROR: Maven wrapper could not start. Check JAVA_HOME, network/proxy and wrapper distributionUrl.
                  echo [GreyTest] Restore the complete ZIP or place portable Maven in .tools/apache-maven-VERSION/bin and retry.
                  exit /b 1
                )
                exit /b 0
                """ + windowsLocalMavenResolver();
    }

    private String windowsLocalMavenResolver() {
        return """
                :findLocalMaven
                for %%G in ("%GT_SRC_DIR%" "%GT_SRC_DIR%\\.." "%GT_SRC_DIR%\\..\\..") do (
                  for /d %%M in ("%%~fG\\.tools\\apache-maven-*") do (
                    if exist "%%~fM\\bin\\mvn.cmd" (
                      call "%%~fM\\bin\\mvn.cmd" -v >nul 2>&1
                      if not errorlevel 1 (
                        set "MAVEN_COMMAND=%%~fM\\bin\\mvn.cmd"
                        rem Chi tool da kiem chung moi duoc truyen qua PATH cho tien trinh junction.
                        set "PATH=%%~fM\\bin;!PATH!"
                        exit /b 0
                      )
                    )
                  )
                )
                exit /b 1
                """;
    }

    private String unixCoverageScript(String testSelector, String gradleTestFilters, String metadata) {
        return """
                #!/usr/bin/env sh
                set -u
                cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)" || exit 1

                case "$(uname -s)" in
                  CYGWIN*|MINGW*|MSYS*)
                    if [ -n "${JAVA_HOME:-}" ]; then JAVA_HOME="$(cygpath -u "$JAVA_HOME")"; export JAVA_HOME; fi
                    ;;
                esac
                if [ -n "${JAVA_HOME:-}" ]; then
                  if [ ! -x "$JAVA_HOME/bin/java" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
                    echo "[GreyTest] ERROR: A working JDK is required. Fix JAVA_HOME for this project."
                    exit 1
                  fi
                  PATH="$JAVA_HOME/bin:$PATH"
                  export PATH
                fi
                if ! java -version >/dev/null 2>&1 || ! javac -version >/dev/null 2>&1; then
                  echo "[GreyTest] ERROR: A working JDK is required. Set JAVA_HOME and add JAVA_HOME/bin to PATH."
                  exit 1
                fi

                export GREYTEST_JVM_ARGS="${GREYTEST_JVM_ARGS:--Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading}"

                build_kind=""
                if [ -f "pom.xml" ]; then
                  build_kind="maven"
                elif [ -f "build.gradle" ] || [ -f "build.gradle.kts" ]; then
                  build_kind="gradle"
                else
                  echo "[GreyTest] ERROR: No pom.xml, build.gradle, or build.gradle.kts was found."
                  echo "[GreyTest] Extract this ZIP into the selected Maven or Gradle module root folder."
                  exit 1
                fi

                %5$s

                if [ ! -d "src/test/java" ]; then
                  echo "[GreyTest] ERROR: Directory 'src/test/java' not found."
                  echo "[GreyTest] Please make sure you extract the ZIP contents directly into the module root where the build file is located."
                  echo "[GreyTest] All test files must be located inside 'src/test/java'."
                  exit 1
                fi

                if ! find "src/test/java" -type f -name '*.java' -print -quit | grep -q .; then
                  echo "[GreyTest] ERROR: No generated Java test file was found under src/test/java."
                  echo "[GreyTest] Do not paste tests into src/service or src/main/test. Extract the complete ZIP at the module root."
                  exit 1
                fi

                if [ "$build_kind" = "gradle" ]; then
                  if [ -f "./gradlew" ]; then
                    sh ./gradlew --init-script "greytest-jacoco.init.gradle" clean test %1$s jacocoTestReport "$@" || exit 1
                  elif command -v gradle >/dev/null 2>&1; then
                    gradle --init-script "greytest-jacoco.init.gradle" clean test %1$s jacocoTestReport "$@" || exit 1
                  else
                    echo "[GreyTest] ERROR: Gradle was not found in PATH and gradlew is missing."
                    echo "[GreyTest] Restore gradlew and gradle/wrapper from the project, or install its required Gradle version and add bin to PATH."
                    exit 1
                  fi
                  if [ ! -f "build/reports/jacoco/test/jacocoTestReport.xml" ]; then
                    echo "[GreyTest] ERROR: build/reports/jacoco/test/jacocoTestReport.xml was not created."
                    echo "[GreyTest] Check whether the project applies the Gradle JaCoCo plugin."
                    exit 2
                  fi
                  echo "[GreyTest] SUCCESS: build/reports/jacoco/test/jacocoTestReport.xml"
                  exit 0
                fi

                %6$s

                if [ -f "../pom.xml" ] && [ "$build_kind" = "maven" ]; then
                  echo "[GreyTest] Phát hiện module con. Đang cài đặt parent POM vào Maven local repository..."
                  (cd .. && run_maven -N install -DskipTests) || {
                    echo "[GreyTest] ERROR: Maven parent POM installation failed. Review the Maven output above."
                    exit 1
                  }
                fi

                echo "[GreyTest] Running tests and generating JaCoCo XML..."
                run_maven clean test-compile \
                  -Dfile.encoding=UTF-8 -Dproject.build.sourceEncoding=UTF-8 -Dproject.reporting.outputEncoding=UTF-8 \
                  "$@" || exit 1

                if ! find "target/test-classes" -type f -name '*.class' -print -quit | grep -q .; then
                  echo "[GreyTest] ERROR: Maven compiled no generated test class. Check Maven test source configuration."
                  exit 1
                fi

                run_maven \
                  -Djacoco.propertyName=greytestJacocoArgLine \
                  "-DargLine=@{greytestJacocoArgLine} ${GREYTEST_JVM_ARGS:-}" \
                  "-Dtest=%2$s" \
                  -DfailIfNoTests=true \
                  org.jacoco:jacoco-maven-plugin:%3$s:prepare-agent \
                  org.apache.maven.plugins:maven-surefire-plugin:test \
                  org.jacoco:jacoco-maven-plugin:%4$s:report "$@" || exit 1

                if [ ! -f "target/site/jacoco/jacoco.xml" ]; then
                  echo "[GreyTest] ERROR: target/site/jacoco/jacoco.xml was not created."
                  echo "[GreyTest] Check whether the module has compiled tests and whether its packaging supports JaCoCo."
                  exit 2
                fi

                echo "[GreyTest] SUCCESS: target/site/jacoco/jacoco.xml"
                """.formatted(gradleTestFilters.replace("$", "\\$"), testSelector.replace("$", "\\$"),
                        JACOCO_VERSION, JACOCO_VERSION, sourceVersionCheck(false, metadata), unixMavenResolver());
    }

    private String unixMavenResolver() {
        return """
                maven_command=''
                maven_wrapper=false
                scan="$PWD"
                while :; do
                  if [ -f "$scan/mvnw" ]; then
                    if [ ! -f "$scan/.mvn/wrapper/maven-wrapper.properties" ]; then
                      echo "[GreyTest] ERROR: Incomplete Maven wrapper. Restore .mvn/wrapper and wrapper files from the project."
                      exit 1
                    fi
                    maven_command="$scan/mvnw"
                    maven_wrapper=true
                    break
                  fi
                  parent="$(dirname "$scan")"
                  [ "$parent" != "$scan" ] || break
                  scan="$parent"
                done
                if [ -z "$maven_command" ] && command -v mvn >/dev/null 2>&1 && mvn -v >/dev/null 2>&1; then
                  maven_command="$(command -v mvn)"
                fi
                if [ -z "$maven_command" ]; then
                  for root in "$PWD" "$PWD/.." "$PWD/../.."; do
                    for tool in "$root"/.tools/apache-maven-*/bin/mvn; do
                      if [ -x "$tool" ] && "$tool" -v >/dev/null 2>&1; then maven_command="$tool"; break 2; fi
                    done
                  done
                fi
                if [ -z "$maven_command" ]; then
                  echo "[GreyTest] No usable Maven in PATH or .tools. Using bundled Maven wrapper; first run downloads Maven to the user's wrapper cache."
                  maven_command="$PWD/.greytest/maven/mvnw"
                  maven_wrapper=true
                fi
                run_maven() {
                  if [ "$maven_wrapper" = true ]; then sh "$maven_command" "$@"; else "$maven_command" "$@"; fi
                }
                if ! run_maven -v >/dev/null 2>&1; then
                  echo "[GreyTest] ERROR: Maven wrapper could not start. Check JAVA_HOME, network/proxy and wrapper distributionUrl."
                  echo "[GreyTest] Restore the complete ZIP or place portable Maven in .tools/apache-maven-VERSION/bin and retry."
                  exit 1
                fi
                """;
    }

    private String sourceVersionCheck(boolean windows, String metadata) {
        String warning = metadata.contains(GenerationSourceFingerprint.UNVERIFIED)
                ? "echo [GreyTest] WARNING: source version unverified for legacy tests; regenerate tests to record source versions.\n"
                : "";
        if (!metadata.contains(GenerationSourceFingerprint.PREFIX)) return warning;
        return warning + (windows ? """
                set "GT_CHECK_CLASSES=target\\greytest-source-check"
                if not exist "pom.xml" set "GT_CHECK_CLASSES=build\\greytest-source-check"
                if not exist "%GT_CHECK_CLASSES%" mkdir "%GT_CHECK_CLASSES%"
                if errorlevel 1 exit /b 1
                javac -encoding UTF-8 -d "%GT_CHECK_CLASSES%" "GreyTestSourceCheck.java"
                if errorlevel 1 exit /b 1
                java -cp "%GT_CHECK_CLASSES%" GreyTestSourceCheck
                if errorlevel 1 exit /b 1
                """ : """
                check_classes="target/greytest-source-check"
                if [ "$build_kind" = "gradle" ]; then check_classes="build/greytest-source-check"; fi
                mkdir -p "$check_classes" || exit 1
                javac -encoding UTF-8 -d "$check_classes" "GreyTestSourceCheck.java" || exit 1
                java -cp "$check_classes" GreyTestSourceCheck || exit 1
                """);
    }

    private String gradleJacocoInitScript() {
        return """
                allprojects { project ->
                    project.plugins.withId('java') {
                        project.apply plugin: 'jacoco'
                    }
                    project.plugins.withId('jacoco') {
                        project.tasks.named('jacocoTestReport') {
                            reports {
                                xml.required = true
                                xml.outputLocation = project.layout.buildDirectory.file('reports/jacoco/test/jacocoTestReport.xml')
                                html.required = false
                            }
                        }
                    }
                }
                """;
    }

    private String coverageReadme() {
        return """
                GREYTEST - CHẠY UNIT TEST VÀ TẠO JACOCO XML

                1. Giải nén toàn bộ ZIP vào thư mục module Maven hoặc Gradle có file build.
                   Ví dụ: piggymetrics/notification-service/
                   - Với dự án đa module (multi-module, ví dụ: backend/appointment-service):
                     Giải nén trực tiếp hoặc copy toàn bộ nội dung ZIP (bao gồm thư mục 'src' và file 'run-greytest-coverage.cmd') vào thư mục module con (nơi chứa pom.xml của service cần test).
                2. Kiểm tra các file test .java đã nằm trong thư mục src/test/java của module đó.
                3. Windows: chạy run-greytest-coverage.cmd
                   Linux/macOS: chạy sh ./run-greytest-coverage.sh
                4. Tải file target/site/jacoco/jacoco.xml (Maven) hoặc build/reports/jacoco/test/jacocoTestReport.xml (Gradle) lên GreyTest.

                BUILD TOOL VÀ JDK
                - Cần cài JDK đúng phiên bản project và cấu hình JAVA_HOME hoặc java/javac trong PATH. Wrapper không tự cài JDK.
                - Maven: ưu tiên wrapper của project/module cha, sau đó Maven chạy được trong PATH hoặc .tools/apache-maven-VERSION/bin.
                - Nếu chưa có Maven, ZIP có sẵn Apache Maven Wrapper 3.3.4 trong .greytest/maven, tự tải Maven 3.9.14 từ Maven Central.
                - Giữ nguyên thư mục ẩn .greytest khi giải nén/copy ZIP. Không ghi đè mvnw hoặc .mvn của project.
                - Lần đầu cần Internet/proxy đến distributionUrl; Maven được cache tại MAVEN_USER_HOME/wrapper/dists hoặc ~/.m2/wrapper/dists để dùng lại.
                - Nếu wrapper project lỗi, script dừng và báo lỗi; không tự đổi phiên bản build tool. Kiểm tra JDK, network/proxy và file wrapper.
                - Khi offline, chuẩn bị cache wrapper/dependency hoặc Maven portable trong .tools/apache-maven-VERSION/bin trước khi chạy.
                - Gradle: dùng gradlew/gradlew.bat của project hoặc Gradle trong PATH; không cần Maven. Nếu thiếu, khôi phục wrapper kèm gradle/wrapper hoặc cài đúng Gradle version project.
                - Wrapper tải build tool, không đảm bảo dependency/plugin đã có sẵn khi offline. Không có đường dẫn tool cố định theo máy sinh ZIP.

                CẤU HÌNH MOCKITO VÀ JVM (CHO RUNTIME TEST)
                - ZIP không ghi đè MockMaker. Giữ cấu hình Mockito mặc định của project để tương thích với phiên bản trong build file.
                - Script tự động thêm các tham số JVM cần thiết (-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading). Nếu cần truyền thêm JVM args riêng, set biến GREYTEST_JVM_ARGS trước khi chạy script.

                KIỂM TRA PHIÊN BẢN SOURCE
                - Giữ cả GreyTestSourceCheck.java và greytest-source-version.txt khi giải nén ZIP.
                - Nếu context có source ở module khác, giữ nguyên các module liên quan trong cùng cây thư mục project; checker vẫn đối chiếu hash của tất cả file, kể cả module phụ thuộc.
                - Không xóa dòng trong greytest-source-version.txt để bỏ qua dependency. Nếu chỉ copy module riêng lẻ, cần khôi phục source các module phụ thuộc đã ghi nhận.
                - Script dùng javac/java của JDK 8+ để kiểm tra SHA-256 source trước khi build; chấp nhận UTF-8 BOM và LF/CRLF.
                - Chỉ chứng thực các file Java trong generation context; không chứng thực build, config hay resource.
                - Nếu source khác phiên bản lúc sinh test, script dừng: dùng lại đúng snapshot hoặc phân tích/sinh test lại. Không sửa expected result để che sai lệch source.
                - Test cũ không có metadata vẫn chạy với cảnh báo "source version unverified". Export không tạo lại fingerprint lịch sử.

                DỰ ÁN MULTI-MODULE (PARENT POM)
                - Nếu chạy module con gặp lỗi 'Non-resolvable parent POM' (ví dụ parent nằm ở thư mục cha ../pom.xml), dùng wrapper project tại thư mục parent:
                  Windows: mvnw.cmd -N install -DskipTests
                  Linux/macOS: sh ./mvnw -N install -DskipTests
                - Nếu parent không có wrapper, có thể chạy wrapper dự phòng từ thư mục module vừa giải nén ZIP:
                  Windows: .greytest\\maven\\mvnw.cmd -f ..\\pom.xml -N install -DskipTests
                  Linux/macOS: sh ./.greytest/maven/mvnw -f ../pom.xml -N install -DskipTests
                  sau đó chạy lại run-greytest-coverage.cmd trong module con.

                Script tự nhận diện Maven hoặc Gradle và không sửa source code của project.
                Với Maven, script sử dụng JaCoCo %s và không thêm cấu hình JaCoCo vào pom.xml.
                Với Gradle, script tự tạm áp dụng plugin JaCoCo, bật XML report và chỉ chạy các test class được sinh.
                Script Maven tách compile và coverage thành 2 lần để không gắn trùng JaCoCo agent của project.
                Project cần có sẵn dependency của test được sinh (JUnit 5/Mockito/Spring Test nếu có dùng).
                Nếu project cần Maven profile, truyền tham số khi chạy script, ví dụ:
                run-greytest-coverage.cmd -Ptest
                """.formatted(JACOCO_VERSION);
    }

    private MergedFile merge(List<UnitTest> tests) {
        UnitTest first = tests.get(0);
        List<Long> caseIds = tests.stream().map(UnitTest::getTestCaseId).toList();
        String source = tests.size() == 1 ? first.getSourceCode() : mergeSources(tests);
        String metadata = sourceMetadata(tests.stream().map(UnitTest::getSourceCode).toList());
        String mergedSource = ensureMockitoRunner(source);
        if (metadata.contains(GenerationSourceFingerprint.PREFIX)) {
            mergedSource = GenerationSourceFingerprint.attach(mergedSource, metadata);
        }
        return new MergedFile(first.getFilePath(), first.getTestClassName(), first.getPackageName(), caseIds,
                mergedSource);
    }

    private String sourceMetadata(List<String> sources) {
        try {
            return GenerationSourceFingerprint.aggregate(sources);
        } catch (IllegalArgumentException exception) {
            throw new StorageException(exception.getMessage(), exception);
        }
    }

    private String mergeSources(List<UnitTest> tests) {
        try {
            CompilationUnit base = parse(tests.get(0).getSourceCode());
            String testClassName = tests.get(0).getTestClassName();
            TypeDeclaration<?> baseType = typeByName(base, testClassName);
            Set<String> methodSignatures = baseType.getMethods().stream()
                    .map(this::methodSignature).collect(Collectors.toCollection(HashSet::new));
            Set<String> fieldNames = baseType.getFields().stream()
                    .flatMap(field -> field.getVariables().stream().map(VariableDeclarator::getNameAsString))
                    .collect(Collectors.toCollection(HashSet::new));
            Set<String> constructorSignatures = baseType.getMembers().stream()
                    .filter(ConstructorDeclaration.class::isInstance)
                    .map(ConstructorDeclaration.class::cast)
                    .map(this::constructorSignature)
                    .collect(Collectors.toCollection(HashSet::new));
            for (int i = 1; i < tests.size(); i++) {
                appendMembers(base, baseType, parse(tests.get(i).getSourceCode()), testClassName,
                        methodSignatures, fieldNames, constructorSignatures);
            }
            return base.toString();
        } catch (StorageException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Không merge được unit test bằng JavaParser: {}", exception.getMessage());
            throw new StorageException("Không gộp được Unit Test thành file Java chạy được", exception);
        }
    }

    private void appendMembers(CompilationUnit base, TypeDeclaration<?> baseType, CompilationUnit next,
            String testClassName, Set<String> methodSignatures, Set<String> fieldNames,
            Set<String> constructorSignatures) {
        TypeDeclaration<?> nextType = typeByName(next, testClassName);
        for (var annotation : nextType.getAnnotations()) {
            if (baseType.getAnnotations().stream().noneMatch(existing -> existing.getNameAsString().equals(annotation.getNameAsString()))) {
                baseType.addAnnotation(annotation.clone());
            }
        }
        Map<String, String> conflictingImports = new LinkedHashMap<>();
        next.getImports().forEach(imp -> addImport(base, baseType, next, nextType, imp, conflictingImports));
        qualifyConflictingTypes(nextType, conflictingImports);
        for (BodyDeclaration<?> member : nextType.getMembers()) {
            if (member instanceof MethodDeclaration method
                    && !methodSignatures.add(methodSignature(method))) {
                continue; // method cùng signature (setup/helper) đã có ở file gốc
            }
            if (member instanceof FieldDeclaration field) {
                FieldDeclaration uniqueFields = field.clone();
                uniqueFields.getVariables().removeIf(
                        variable -> !fieldNames.add(variable.getNameAsString()));
                if (!uniqueFields.getVariables().isEmpty()) {
                    baseType.addMember(uniqueFields);
                }
                continue;
            }
            if (member instanceof ConstructorDeclaration constructor) {
                if (constructorSignatures.add(constructorSignature(constructor))) {
                    baseType.addMember(constructor.clone());
                }
                continue;
            }
            if (member instanceof TypeDeclaration<?> nestedType) {
                TypeDeclaration<?> existingNestedType = baseType.getMembers().stream()
                        .filter(TypeDeclaration.class::isInstance)
                        .map(TypeDeclaration.class::cast)
                        .filter(existing -> existing.getNameAsString().equals(nestedType.getNameAsString()))
                        .findFirst()
                        .orElse(null);
                if (existingNestedType == null) {
                    baseType.addMember(nestedType.clone());
                } else if (existingNestedType instanceof com.github.javaparser.ast.body.EnumDeclaration existingEnum
                        && nestedType instanceof com.github.javaparser.ast.body.EnumDeclaration newEnum) {
                    // Gộp các enum constant chưa có vào enum hiện tại
                    java.util.Set<String> existingEntries = existingEnum.getEntries().stream()
                            .map(com.github.javaparser.ast.nodeTypes.NodeWithSimpleName::getNameAsString)
                            .collect(java.util.stream.Collectors.toSet());
                    for (var entry : newEnum.getEntries()) {
                        if (existingEntries.add(entry.getNameAsString())) {
                            existingEnum.addEntry(entry.clone());
                        }
                    }
                } else if (!existingNestedType.toString().equals(nestedType.toString())) {
                    throw new StorageException("Xung dot nested class: " + nestedType.getNameAsString(), null);
                }
                continue;
            }
            baseType.addMember(member.clone());
        }
    }

    private void addImport(CompilationUnit base, TypeDeclaration<?> baseType, CompilationUnit next,
            TypeDeclaration<?> nextType, com.github.javaparser.ast.ImportDeclaration candidate,
            Map<String, String> conflictingImports) {
        if (base.getImports().contains(candidate)) {
            return;
        }
        if (candidate.isAsterisk() && !candidate.isStatic() && base.getImports().stream()
                .anyMatch(existing -> existing.isAsterisk() && !existing.isStatic()
                        && !existing.getNameAsString().equals(candidate.getNameAsString()))) {
            throw new StorageException("Xung dot wildcard import: " + candidate.getNameAsString(), null);
        }
        if (candidate.isAsterisk() && !candidate.isStatic() && base.getImports().stream()
                .filter(existing -> !existing.isAsterisk() && !existing.isStatic())
                .anyMatch(existing -> !importPackage(existing).equals(candidate.getNameAsString())
                        && typeUsesSimpleName(nextType, importSimpleName(existing))
                        && next.getImports().stream().noneMatch(imp -> !imp.isAsterisk()
                                && importSimpleName(imp).equals(importSimpleName(existing))))) {
            throw new StorageException("Xung dot wildcard voi explicit import: " + candidate.getNameAsString(), null);
        }
        String candidateName = importSimpleName(candidate);
        boolean staticConflict = candidate.isStatic() && !candidate.isAsterisk() && base.getImports().stream()
                .filter(existing -> existing.isStatic() && !existing.isAsterisk())
                .anyMatch(existing -> importSimpleName(existing).equals(candidateName)
                        && !existing.getNameAsString().equals(candidate.getNameAsString()));
        if (staticConflict) {
            throw new StorageException("Xung dot static import: " + candidate.getNameAsString(), null);
        }
        boolean wildcardConflict = !candidate.isAsterisk() && !candidate.isStatic()
                && base.getImports().stream()
                        .filter(existing -> existing.isAsterisk() && !existing.isStatic())
                        .anyMatch(existing -> !existing.getNameAsString().equals(importPackage(candidate))
                                && typeUsesSimpleName(baseType, candidateName));
        if (wildcardConflict) {
            conflictingImports.put(candidateName, candidate.getNameAsString());
            return;
        }
        boolean conflict = base.getImports().stream()
                .filter(existing -> !existing.isAsterisk() && !candidate.isAsterisk())
                .anyMatch(existing -> importSimpleName(existing).equals(candidateName)
                        && !existing.getNameAsString().equals(candidate.getNameAsString()));
        if (conflict) {
            if (!candidate.isStatic()) {
                conflictingImports.put(candidateName, candidate.getNameAsString());
                log.warn("Qualify import trung ten khi gop Unit Test: {}", candidate.getNameAsString());
            } else {
                log.warn("Bỏ qua static import trùng tên khi gộp Unit Test: {}", candidate.getNameAsString());
            }
            return;
        }
        base.addImport(candidate.clone());
    }

    private boolean typeUsesSimpleName(TypeDeclaration<?> type, String simpleName) {
        if (type == null || simpleName == null || simpleName.isBlank()) {
            return false;
        }
        return type.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class).stream()
                .anyMatch(t -> t.getNameAsString().equals(simpleName))
                || type.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                .anyMatch(n -> n.getNameAsString().equals(simpleName));
    }

    private void qualifyConflictingTypes(TypeDeclaration<?> type, Map<String, String> conflictingImports) {
        type.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class).forEach(candidate -> {
            String qualifiedName = conflictingImports.get(candidate.getNameAsString());
            if (qualifiedName == null || candidate.getScope().isPresent()) {
                return;
            }
            candidate.replace(parseType(qualifiedName));
        });
        type.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call ->
                call.getScope().filter(com.github.javaparser.ast.expr.NameExpr.class::isInstance)
                        .map(com.github.javaparser.ast.expr.NameExpr.class::cast)
                        .map(com.github.javaparser.ast.expr.NameExpr::getNameAsString)
                        .map(conflictingImports::get)
                        .ifPresent(qualifiedName -> call.setScope(parseExpression(qualifiedName))));
        type.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class).forEach(field -> {
            if (field.getScope().isNameExpr()) {
                String qualifiedName = conflictingImports.get(field.getScope().asNameExpr().getNameAsString());
                if (qualifiedName != null) {
                    field.setScope(parseExpression(qualifiedName));
                }
            }
        });
        type.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).forEach(annotation -> {
            String qualifiedName = conflictingImports.get(annotation.getNameAsString());
            if (qualifiedName != null) {
                annotation.setName(new JavaParser().parseName(qualifiedName)
                        .getResult()
                        .orElseThrow(() -> new IllegalStateException("Không parse được annotation: " + qualifiedName)));
            }
        });
    }

    private com.github.javaparser.ast.type.ClassOrInterfaceType parseType(String qualifiedName) {
        return new JavaParser().parseClassOrInterfaceType(qualifiedName)
                .getResult()
                .orElseThrow(() -> new IllegalStateException("Không parse được type: " + qualifiedName));
    }

    private com.github.javaparser.ast.expr.Expression parseExpression(String qualifiedName) {
        return new JavaParser().parseExpression(qualifiedName)
                .getResult()
                .orElseThrow(() -> new IllegalStateException("Không parse được expression: " + qualifiedName));
    }

    private String importSimpleName(com.github.javaparser.ast.ImportDeclaration declaration) {
        String name = declaration.getNameAsString();
        int lastDot = name.lastIndexOf('.');
        return lastDot < 0 ? name : name.substring(lastDot + 1);
    }

    private String importPackage(com.github.javaparser.ast.ImportDeclaration declaration) {
        String name = declaration.getNameAsString();
        int lastDot = name.lastIndexOf('.');
        return lastDot < 0 ? "" : name.substring(0, lastDot);
    }

    private TypeDeclaration<?> typeByName(CompilationUnit unit, String testClassName) {
        return unit.getTypes().stream()
                .filter(type -> type.getNameAsString().equals(testClassName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy test class " + testClassName));
    }

    private String methodSignature(MethodDeclaration method) {
        Map<String, String> typeVariables = method.getTypeParameters().stream()
                .collect(Collectors.toMap(
                        parameter -> parameter.getNameAsString(),
                        parameter -> parameter.getTypeBound().isEmpty()
                                ? "Object"
                                : erasedType(parameter.getTypeBound().get(0))));
        return method.getNameAsString() + "(" + method.getParameters().stream()
                .map(parameter -> eraseTypeVariables(erasedType(parameter.getType()), typeVariables)
                        + (parameter.isVarArgs() ? "[]" : ""))
                .collect(Collectors.joining(",")) + ")";
    }

    private String constructorSignature(ConstructorDeclaration constructor) {
        return constructor.getNameAsString() + "(" + constructor.getParameters().stream()
                .map(parameter -> erasedType(parameter.getType()) + (parameter.isVarArgs() ? "[]" : ""))
                .collect(Collectors.joining(",")) + ")";
    }

    private String eraseTypeVariables(String type, Map<String, String> typeVariables) {
        String erased = type;
        for (var entry : typeVariables.entrySet()) {
            erased = erased.replaceAll("(?<![A-Za-z0-9_$])" + java.util.regex.Pattern.quote(entry.getKey())
                    + "(?![A-Za-z0-9_$])", java.util.regex.Matcher.quoteReplacement(entry.getValue()));
        }
        return erased;
    }

    private String erasedType(com.github.javaparser.ast.type.Type type) {
        var erased = type.clone();
        erased.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class)
                .forEach(classType -> classType.removeTypeArguments());
        return erased.asString();
    }

    private CompilationUnit parse(String source) {
        ParseResult<CompilationUnit> result = new JavaParser().parse(source == null ? "" : source);
        return result.getResult().filter(unit -> result.isSuccessful())
                .orElseThrow(() -> new IllegalStateException("Parse lỗi: "
                        + result.getProblems().stream().findFirst().map(Object::toString).orElse("unknown")));
    }

    private CompilationUnit parseForArchive(String source) {
        try {
            return parse(source);
        } catch (RuntimeException exception) {
            throw new StorageException("Source Unit Test không phải Java hợp lệ", exception);
        }
    }

    public String ensureMockitoRunner(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) return sourceCode;

        boolean needsRunner = (sourceCode.contains("@Mock") || sourceCode.contains("@InjectMocks") || sourceCode.contains("@Spy"))
                && !sourceCode.contains("openMocks(") && !sourceCode.contains("initMocks(")
                && !sourceCode.contains("MockitoExtension.class") && !sourceCode.contains("MockitoJUnitRunner.class");

        boolean needsAssertionFix = !sourceCode.contains("org.junit.Assert")
                && (sourceCode.contains("assertTrue(\"") || sourceCode.contains("assertFalse(\"")
                        || sourceCode.contains("assertNotNull(\"") || sourceCode.contains("assertNull(\""));

        boolean needsVerifyFix = (sourceCode.contains("verifyNoInteractions") || sourceCode.contains("verifyZeroInteractions"))
                && (sourceCode.contains("any()") || sourceCode.contains("any(") || sourceCode.contains("eq("));

        if (!needsRunner && !needsAssertionFix && !needsVerifyFix) {
            return sourceCode;
        }

        try {
            CompilationUnit cu = parse(sourceCode);
            boolean isJunit4 = cu.getImports().stream()
                    .anyMatch(i -> i.getNameAsString().startsWith("org.junit.") && !i.getNameAsString().startsWith("org.junit.jupiter."));

            // 1. Đảm bảo Mockito Runner / Extension nếu dùng @Mock, @InjectMocks, @Spy
            if (needsRunner) {
                boolean hasMockFields = cu.findAll(FieldDeclaration.class).stream()
                        .anyMatch(f -> f.getAnnotationByName("Mock").isPresent()
                                || f.getAnnotationByName("InjectMocks").isPresent()
                                || f.getAnnotationByName("Spy").isPresent());
                if (hasMockFields) {
                    for (TypeDeclaration<?> type : cu.getTypes()) {
                        if (isJunit4) {
                            if (type.getAnnotationByName("RunWith").isEmpty()) {
                                cu.addImport("org.junit.runner.RunWith");
                                cu.addImport("org.mockito.junit.MockitoJUnitRunner");
                                type.addAnnotation(com.github.javaparser.StaticJavaParser.parseAnnotation("@RunWith(MockitoJUnitRunner.class)"));
                            }
                        } else {
                            if (type.getAnnotationByName("ExtendWith").isEmpty()) {
                                cu.addImport("org.junit.jupiter.api.extension.ExtendWith");
                                cu.addImport("org.mockito.junit.jupiter.MockitoExtension");
                                type.addAnnotation(com.github.javaparser.StaticJavaParser.parseAnnotation("@ExtendWith(MockitoExtension.class)"));
                            }
                        }
                    }
                }
            }

            // 2. Tự động sửa thứ tự tham số assertion JUnit 5 nếu String message bị đặt đầu
            if (needsAssertionFix && !isJunit4) {
                for (com.github.javaparser.ast.expr.MethodCallExpr call : cu.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)) {
                    String name = call.getNameAsString();
                    int argCount = call.getArguments().size();
                    if (("assertTrue".equals(name) || "assertFalse".equals(name)) && argCount == 2) {
                        if (call.getArgument(0).isStringLiteralExpr()) {
                            var msg = call.getArgument(0).clone();
                            var cond = call.getArgument(1).clone();
                            call.setArgument(0, cond);
                            call.setArgument(1, msg);
                        }
                    } else if (("assertNotNull".equals(name) || "assertNull".equals(name)) && argCount == 2) {
                        if (call.getArgument(0).isStringLiteralExpr() && !call.getArgument(1).isStringLiteralExpr()) {
                            var msg = call.getArgument(0).clone();
                            var actual = call.getArgument(1).clone();
                            call.setArgument(0, actual);
                            call.setArgument(1, msg);
                        }
                    }
                }
            }

            // 3. Loại bỏ lời gọi verifyNoInteractions chứa matcher không hợp lệ
            if (needsVerifyFix) {
                for (com.github.javaparser.ast.expr.MethodCallExpr call : cu.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)) {
                    String name = call.getNameAsString();
                    if ("verifyNoInteractions".equals(name) || "verifyZeroInteractions".equals(name)) {
                        boolean hasIllegalMatcher = call.getArguments().stream().anyMatch(arg -> {
                            if (arg.isMethodCallExpr()) {
                                String mName = arg.asMethodCallExpr().getNameAsString();
                                return mName.startsWith("any") || "eq".equals(mName) || "isNull".equals(mName) || "notNull".equals(mName);
                            }
                            return false;
                        });
                        if (hasIllegalMatcher) {
                            call.findAncestor(com.github.javaparser.ast.stmt.Statement.class).ifPresent(com.github.javaparser.ast.Node::remove);
                        }
                    }
                }
            }

            return cu.toString();
        } catch (Exception e) {
            log.warn("Không thể tự động chuẩn hóa Unit Test: {}", e.getMessage());
            return sourceCode;
        }
    }
}
