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
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.greytest.dto.UnitTestFileDto;
import com.greytest.entity.UnitTest;
import com.greytest.exception.StorageException;

import lombok.extern.slf4j.Slf4j;

/**
 * Gộp các unit test record cùng test class thành một file Java hoàn chỉnh
 * (dedupe import/field, cộng dồn @Test method) — giống file test trong project thật.
 * DB vẫn giữ 1 record cho mỗi test case để phục vụ traceability.
 */
@Slf4j
@Service
public class UnitTestFileService {

    private static final String JACOCO_VERSION = "0.8.15";
    private static final String WINDOWS_SCRIPT_NAME = "run-greytest-coverage.cmd";
    private static final String UNIX_SCRIPT_NAME = "run-greytest-coverage.sh";
    private static final String README_NAME = "README-GREYTEST.txt";

    public record MergedFile(String filePath, String testClassName, String packageName,
            List<Long> caseIds, String sourceCode) {
    }

    public List<MergedFile> mergeByClass(List<UnitTest> tests) {
        Map<String, List<UnitTest>> byFile = new LinkedHashMap<>();
        for (UnitTest test : tests) {
            byFile.computeIfAbsent(test.getFilePath(), key -> new ArrayList<>()).add(test);
        }
        return byFile.values().stream().map(this::merge).toList();
    }

    /**
     * Đóng gói test cùng script để tạo JaCoCo XML mà không thêm cấu hình JaCoCo vào pom.xml.
     */
    public byte[] createCoverageArchive(List<UnitTestFileDto> testFiles) {
        var output = new ByteArrayOutputStream();
        String testSelector = testFiles.stream()
                .map(test -> test.packageName() == null || test.packageName().isBlank()
                        ? test.testClassName() : test.packageName() + "." + test.testClassName())
                .distinct()
                .collect(Collectors.joining(","));
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (UnitTestFileDto testFile : testFiles) {
                writeArchiveEntry(zip, safeTestPath(testFile.filePath()), testFile.sourceCode());
            }
            writeArchiveEntry(zip, WINDOWS_SCRIPT_NAME, windowsCoverageScript(testSelector));
            writeArchiveEntry(zip, UNIX_SCRIPT_NAME, unixCoverageScript(testSelector));
            writeArchiveEntry(zip, README_NAME, coverageReadme());
        } catch (IOException exception) {
            throw new StorageException("Khong tao duoc ZIP unit test", exception);
        }
        return output.toByteArray();
    }

    private void writeArchiveEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String safeTestPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new StorageException("Duong dan Unit Test khong hop le", null);
        }
        Path testRoot = Path.of("src", "test", "java");
        Path normalized = Path.of(filePath).normalize();
        if (normalized.isAbsolute() || !normalized.startsWith(testRoot)) {
            throw new StorageException("Duong dan Unit Test khong hop le", null);
        }
        return normalized.toString().replace('\\', '/');
    }

    private String windowsCoverageScript(String testSelector) {
        return """
                @echo off
                setlocal
                chcp 65001 >nul
                cd /d "%%~dp0"

                if not exist "pom.xml" (
                  echo [GreyTest] ERROR: pom.xml not found. Extract this ZIP into the Maven module root folder.
                  exit /b 1
                )

                if not exist "src\\test\\java" (
                  echo [GreyTest] ERROR: Directory 'src\\test\\java' not found.
                  echo [GreyTest] Please make sure you extract the ZIP contents directly into the Maven module root folder (where pom.xml is located).
                  echo [GreyTest] All test files must be located inside 'src\\test\\java'.
                  exit /b 1
                )

                dir /s /b "src\\test\\java\\*.java" >nul 2>&1
                if errorlevel 1 (
                  echo [GreyTest] ERROR: No generated Java test file was found under src\\test\\java.
                  echo [GreyTest] Do not paste tests into src\\service or src\\main\\test. Extract the complete ZIP at the module root.
                  exit /b 1
                )

                set "MAVEN_COMMAND=mvn"
                if exist "mvnw.cmd" set "MAVEN_COMMAND=mvnw.cmd"
                if not exist "mvnw.cmd" (
                  where mvn >nul 2>&1
                  if errorlevel 1 (
                    echo [GreyTest] ERROR: Maven was not found in PATH.
                    exit /b 1
                  )
                )

                echo [GreyTest] Running tests and generating JaCoCo XML...
                call %%MAVEN_COMMAND%% clean test-compile ^
                  -Dfile.encoding=UTF-8 -Dproject.build.sourceEncoding=UTF-8 -Dproject.reporting.outputEncoding=UTF-8 ^
                  %%*
                if errorlevel 1 (
                  echo [GreyTest] ERROR: Project or generated tests could not be compiled.
                  exit /b 1
                )

                dir /s /b "target\\test-classes\\*.class" >nul 2>&1
                if errorlevel 1 (
                  echo [GreyTest] ERROR: Maven compiled no generated test class. Check Maven test source configuration.
                  exit /b 1
                )

                call %%MAVEN_COMMAND%% ^
                  -Djacoco.propertyName=greytestJacocoArgLine ^
                  "-DargLine=@{greytestJacocoArgLine} %%GREYTEST_JVM_ARGS%%" ^
                  "-Dtest=%s" ^
                  -DfailIfNoTests=true ^
                  org.jacoco:jacoco-maven-plugin:%s:prepare-agent ^
                  org.apache.maven.plugins:maven-surefire-plugin:test ^
                  org.jacoco:jacoco-maven-plugin:%s:report %%*
                if errorlevel 1 (
                  echo [GreyTest] ERROR: Build or tests failed. Review the Maven output above.
                  exit /b 1
                )

                if not exist "target\\site\\jacoco\\jacoco.xml" (
                  echo [GreyTest] ERROR: target\\site\\jacoco\\jacoco.xml was not created.
                  echo [GreyTest] Check whether the module has compiled tests and whether its packaging supports JaCoCo.
                  exit /b 2
                )

                echo [GreyTest] SUCCESS: target\\site\\jacoco\\jacoco.xml
                endlocal
                """.formatted(testSelector, JACOCO_VERSION, JACOCO_VERSION);
    }

    private String unixCoverageScript(String testSelector) {
        return """
                #!/usr/bin/env sh
                set -u
                cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)" || exit 1

                if [ ! -f "pom.xml" ]; then
                  echo "[GreyTest] ERROR: pom.xml not found. Extract this ZIP into the Maven module root folder."
                  exit 1
                fi

                if [ ! -d "src/test/java" ]; then
                  echo "[GreyTest] ERROR: Directory 'src/test/java' not found."
                  echo "[GreyTest] Please make sure you extract the ZIP contents directly into the Maven module root folder (where pom.xml is located)."
                  echo "[GreyTest] All test files must be located inside 'src/test/java'."
                  exit 1
                fi

                if ! find "src/test/java" -type f -name '*.java' -print -quit | grep -q .; then
                  echo "[GreyTest] ERROR: No generated Java test file was found under src/test/java."
                  echo "[GreyTest] Do not paste tests into src/service or src/main/test. Extract the complete ZIP at the module root."
                  exit 1
                fi

                run_maven() {
                  if [ -f "./mvnw" ]; then
                    sh ./mvnw "$@"
                  elif command -v mvn >/dev/null 2>&1; then
                    mvn "$@"
                  else
                    echo "[GreyTest] ERROR: Maven was not found in PATH."
                    return 1
                  fi
                }

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
                  "-Dtest=%s" \
                  -DfailIfNoTests=true \
                  org.jacoco:jacoco-maven-plugin:%s:prepare-agent \
                  org.apache.maven.plugins:maven-surefire-plugin:test \
                  org.jacoco:jacoco-maven-plugin:%s:report "$@" || exit 1

                if [ ! -f "target/site/jacoco/jacoco.xml" ]; then
                  echo "[GreyTest] ERROR: target/site/jacoco/jacoco.xml was not created."
                  echo "[GreyTest] Check whether the module has compiled tests and whether its packaging supports JaCoCo."
                  exit 2
                fi

                echo "[GreyTest] SUCCESS: target/site/jacoco/jacoco.xml"
                """.formatted(testSelector, JACOCO_VERSION, JACOCO_VERSION);
    }

    private String coverageReadme() {
        return """
                GREYTEST - CHAY UNIT TEST VA TAO JACOCO XML

                1. Giai nen toan bo ZIP vao thu muc module Maven co file pom.xml.
                   Vi du: piggymetrics/notification-service/
                2. Kiem tra cac file test .java da nam trong thu muc src/test/java.
                3. Windows: chay run-greytest-coverage.cmd
                   Linux/macOS: chay sh ./run-greytest-coverage.sh
                4. Tai file target/site/jacoco/jacoco.xml len GreyTest.

                Script su dung JaCoCo %s va khong them cau hinh JaCoCo vao pom.xml.
                Script tach compile va coverage thanh 2 lan Maven de khong gan trung JaCoCo agent cua project.
                Project can co san dependency cua test duoc sinh (JUnit 5/Mockito/Spring Test neu co dung).
                Neu project can Maven profile, truyen tham so khi chay script, vi du:
                run-greytest-coverage.cmd -Ptest

                TUY CHON JVM RIENG
                Script chu dong tach JaCoCo agent khoi argLine cua project de tranh gan trung agent.
                Neu test can JVM argument rieng, dat bien GREYTEST_JVM_ARGS truoc khi chay, vi du Windows:
                set "GREYTEST_JVM_ARGS=--add-opens=java.base/java.lang=ALL-UNNAMED"
                run-greytest-coverage.cmd
                """.formatted(JACOCO_VERSION);
    }

    private MergedFile merge(List<UnitTest> tests) {
        UnitTest first = tests.get(0);
        List<Long> caseIds = tests.stream().map(UnitTest::getTestCaseId).toList();
        String source = tests.size() == 1 ? first.getSourceCode() : mergeSources(tests);
        return new MergedFile(first.getFilePath(), first.getTestClassName(), first.getPackageName(), caseIds, source);
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
            for (int i = 1; i < tests.size(); i++) {
                appendMembers(base, baseType, parse(tests.get(i).getSourceCode()), testClassName,
                        methodSignatures, fieldNames);
            }
            return base.toString();
        } catch (Exception exception) {
            log.warn("Khong merge duoc unit test bang JavaParser, noi tho cac file: {}", exception.getMessage());
            // Fallback: nối thô để user vẫn xem/copy được, có thể cần chỉnh tay
            return tests.stream().map(UnitTest::getSourceCode)
                    .collect(Collectors.joining("\n\n// ==== GreyTest: khong tu merge duoc, gop thu cong ====\n\n"));
        }
    }

    private void appendMembers(CompilationUnit base, TypeDeclaration<?> baseType, CompilationUnit next,
            String testClassName, Set<String> methodSignatures, Set<String> fieldNames) {
        next.getImports().forEach(imp -> {
            if (!base.getImports().contains(imp)) {
                base.addImport(imp.clone());
            }
        });
        TypeDeclaration<?> nextType = typeByName(next, testClassName);
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
            baseType.addMember(member.clone());
        }
    }

    private TypeDeclaration<?> typeByName(CompilationUnit unit, String testClassName) {
        return unit.getTypes().stream()
                .filter(type -> type.getNameAsString().equals(testClassName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Khong tim thay test class " + testClassName));
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
                .orElseThrow(() -> new IllegalStateException("Parse loi: "
                        + result.getProblems().stream().findFirst().map(Object::toString).orElse("unknown")));
    }
}
