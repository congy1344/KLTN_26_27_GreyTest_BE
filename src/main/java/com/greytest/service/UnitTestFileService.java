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
    private static final String GRADLE_INIT_SCRIPT_NAME = "greytest-jacoco.init.gradle";
    private static final String README_NAME = "README-GREYTEST.txt";
    private static final String MOCKITO_MOCK_MAKER_FILE = "src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker";
    private static final String MOCKITO_SUBCLASS_MOCK_MAKER_CONTENT = "mock-maker-subclass\n";

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
        if (testFiles == null || testFiles.isEmpty()) {
            throw new StorageException("Khong co Unit Test de dong goi", null);
        }
        testFiles.forEach(this::validateArchiveFile);
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
                writeArchiveEntry(zip, safeTestPath(testFile.filePath()), safeSource);
            }
            writeArchiveEntry(zip, WINDOWS_SCRIPT_NAME, windowsCoverageScript(testSelector, gradleTestFilters));
            writeArchiveEntry(zip, UNIX_SCRIPT_NAME, unixCoverageScript(testSelector, gradleTestFilters));
            writeArchiveEntry(zip, GRADLE_INIT_SCRIPT_NAME, gradleJacocoInitScript());
            writeArchiveEntry(zip, README_NAME, coverageReadme());
            writeArchiveEntry(zip, MOCKITO_MOCK_MAKER_FILE, MOCKITO_SUBCLASS_MOCK_MAKER_CONTENT);
        } catch (IOException exception) {
            throw new StorageException("Khong tao duoc ZIP unit test", exception);
        }
        return output.toByteArray();
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
            throw new StorageException("Source Unit Test rong, khong the dong goi", null);
        }
        String path = safeTestPath(testFile.filePath());
        CompilationUnit unit = parseForArchive(stripLeadingBom(testFile.sourceCode()));
        TypeDeclaration<?> type;
        try {
            type = typeByName(unit, testFile.testClassName());
        } catch (RuntimeException exception) {
            throw new StorageException("Khong tim thay test class trong source: " + testFile.testClassName(), exception);
        }
        String packageName = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString()).orElse("");
        String expectedPackage = testFile.packageName() == null ? "" : testFile.packageName();
        if (!packageName.equals(expectedPackage)) {
            throw new StorageException("Package Unit Test khong khop voi source: " + testFile.testClassName(), null);
        }
        String expectedPath = "src/test/java/"
                + (expectedPackage.isBlank() ? "" : expectedPackage.replace('.', '/') + "/")
                + type.getNameAsString() + ".java";
        if (!path.equals(expectedPath)) {
            throw new StorageException("Duong dan Unit Test khong khop voi package/class", null);
        }
    }

    private String safeTestPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new StorageException("Duong dan Unit Test khong hop le", null);
        }
        String normalized = filePath.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.startsWith("/") || normalized.contains("..") || normalized.contains(":")) {
            throw new StorageException("Duong dan Unit Test khong hop le", null);
        }
        int testRootIndex = normalized.indexOf("src/test/java/");
        if (testRootIndex < 0) {
            throw new StorageException("Duong dan Unit Test khong hop le: phai chua src/test/java/", null);
        }
        return normalized.substring(testRootIndex);
    }

    private String windowsCoverageScript(String testSelector, String gradleTestFilters) {
        return """
                @echo off
                setlocal enabledelayedexpansion
                chcp 65001 >nul
                cd /d "%%~dp0"

                set "GT_SRC_DIR=%%~dp0"
                if "!GT_SRC_DIR:~-1!"=="\\" set "GT_SRC_DIR=!GT_SRC_DIR:~0,-1!"

                if not defined GREYTEST_JVM_ARGS set "GREYTEST_JVM_ARGS=-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading"

                if not "!GREYTEST_JUNCTION_RUN!"=="1" (
                  powershell -NoProfile -ExecutionPolicy Bypass -Command "if ('!GT_SRC_DIR!' -match '[^\\x00-\\x7F]') { exit 1 } else { exit 0 }" >nul 2>&1
                  if errorlevel 1 (
                    echo [GreyTest] Canh bao: Duong dan thu muc chua ky tu co dau/Unicode: "!GT_SRC_DIR!"
                    echo [GreyTest] Dang tu dong tao Junction ASCII tam thoi de build tool [gradlew/mvnw] hoat dong on dinh...
                    if exist "!GT_SRC_DIR!\\..\\pom.xml" (
                      echo [GreyTest] Phat hien module con cua du an Maven multi-module. Dang cai dat parent POM vao local repository...
                      pushd "!GT_SRC_DIR!\\.."
                      set "PRE_MAVEN=mvn"
                      if exist "mvnw.cmd" set "PRE_MAVEN=mvnw.cmd"
                      call !PRE_MAVEN! -N install -DskipTests >nul 2>&1
                      popd
                    )
                    set "GT_JUNC_BASE=%%SystemDrive%%\\tmp"
                    if not exist "!GT_JUNC_BASE!" mkdir "!GT_JUNC_BASE!" >nul 2>&1
                    set "GT_JUNC_DIR=!GT_JUNC_BASE!\\gt-run-%%RANDOM%%"
                    powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Junction -Path '!GT_JUNC_DIR!' -Target '!GT_SRC_DIR!' | Out-Null" >nul 2>&1
                    if exist "!GT_JUNC_DIR!\\run-greytest-coverage.cmd" (
                      echo [GreyTest] Chuyen huong thuc thi qua Junction: "!GT_JUNC_DIR!"
                      set "GREYTEST_JUNCTION_RUN=1"
                      pushd "!GT_JUNC_DIR!"
                      call "!GT_JUNC_DIR!\\run-greytest-coverage.cmd" %%*
                      set "GT_EXIT=!ERRORLEVEL!"
                      popd
                      rmdir "!GT_JUNC_DIR!" >nul 2>&1
                      exit /b !GT_EXIT!
                    )
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
                  echo [GreyTest] Tip: Neu gap loi 'Non-resolvable parent POM', hay chay 'mvn -N install' tai thu muc chua parent pom.xml truoc.
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
                  "-Dtest=%2$s" ^
                  -DfailIfNoTests=true ^
                  org.jacoco:jacoco-maven-plugin:%3$s:prepare-agent ^
                  org.apache.maven.plugins:maven-surefire-plugin:test ^
                  org.jacoco:jacoco-maven-plugin:%4$s:report %%*
                if errorlevel 1 (
                  echo [GreyTest] ERROR: Build or tests failed. Review the Maven output above.
                  echo [GreyTest] Tip: Neu gap loi Mockito attach Byte Buddy, kiem tra GREYTEST_JVM_ARGS hoac dung JDK 17+.
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
                    exit /b 1
                  )
                )

                echo [GreyTest] Running Gradle tests and generating JaCoCo XML...
                call %%GRADLE_COMMAND%% --init-script "greytest-jacoco.init.gradle" clean test %1$s jacocoTestReport %%*
                if errorlevel 1 (
                  echo [GreyTest] ERROR: Gradle project or generated tests could not be compiled or executed.
                  echo [GreyTest] Luu y: Neu thu muc du an chua tieng Viet co dau (vi du: mau, sua), hay dung junction ASCII hoac doi ten thu muc khong dau.
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
                echo [GreyTest] Neu la du an multi-module, vui long copy script nay va thu muc src vao thu muc module con (noi chua pom.xml cua service can test, vi du: backend/appointment-service) truoc khi chay.
                exit /b 1

                :success
                endlocal
                """.formatted(gradleTestFilters, testSelector, JACOCO_VERSION, JACOCO_VERSION);
    }

    private String unixCoverageScript(String testSelector, String gradleTestFilters) {
        return """
                #!/usr/bin/env sh
                set -u
                cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)" || exit 1

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

                if [ -f "../pom.xml" ] && [ "$build_kind" = "maven" ]; then
                  echo "[GreyTest] Phat hien module con. Dang cai dat parent POM vao Maven local repository..."
                  (cd .. && run_maven -N install -DskipTests >/dev/null 2>&1)
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
                """.formatted(gradleTestFilters, testSelector, JACOCO_VERSION, JACOCO_VERSION);
    }

    private String gradleJacocoInitScript() {
        return """
                allprojects {
                    plugins.withId('java') {
                        apply plugin: 'jacoco'
                    }
                    plugins.withId('jacoco') {
                        tasks.named('jacocoTestReport') {
                            reports {
                                xml.required = true
                                xml.outputLocation = layout.buildDirectory.file('reports/jacoco/test/jacocoTestReport.xml')
                                html.required = false
                            }
                        }
                    }
                }
                """;
    }

    private String coverageReadme() {
        return """
                GREYTEST - CHAY UNIT TEST VA TAO JACOCO XML

                1. Giai nen toan bo ZIP vao thu muc module Maven hoac Gradle co file build.
                   Vi du: piggymetrics/notification-service/
                   - Voi du an da module (multi-module, vi du: backend/appointment-service):
                     Giai nen truc tiep hoac copy toan bo noi dung ZIP (bao gom thu muc 'src' va file 'run-greytest-coverage.cmd') vao thu muc module con (noi chua pom.xml cua service can test).
                2. Kiem tra cac file test .java da nam trong thu muc src/test/java cua module do.
                3. Windows: chay run-greytest-coverage.cmd
                   Linux/macOS: chay sh ./run-greytest-coverage.sh
                4. Tai file target/site/jacoco/jacoco.xml (Maven) hoac build/reports/jacoco/test/jacocoTestReport.xml (Gradle) len GreyTest.

                CAU HINH MOCKITO VA JVM (CHO RUNTIME TEST)
                - File 'src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker' (mock-maker-subclass) duoc dong goi san trong ZIP de tranh loi 'Could not initialize inline Byte Buddy mock maker' tren cac phien ban Java 17/21+.
                - Script tu dong them cac tham so JVM can thiet (-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading). Neu can truyen them JVM args rieng, set bien GREYTEST_JVM_ARGS truoc khi chay script.

                DU AN MULTI-MODULE (PARENT POM)
                - Neu chay module con gap loi 'Non-resolvable parent POM' (vi du parent nam o thu muc cha ../pom.xml), hay chay lenh:
                  cd <thu muc goc chua parent pom.xml>
                  mvn -N install -DskipTests
                  sau do chay lai run-greytest-coverage.cmd trong module con.

                Script tu nhan dien Maven hoac Gradle va khong sua source code cua project.
                Voi Maven, script su dung JaCoCo %s va khong them cau hinh JaCoCo vao pom.xml.
                Voi Gradle, script tu tam ap dung plugin JaCoCo, bat XML report va chi chay cac test class duoc sinh.
                Script Maven tach compile va coverage thanh 2 lan de khong gan trung JaCoCo agent cua project.
                Project can co san dependency cua test duoc sinh (JUnit 5/Mockito/Spring Test neu co dung).
                Neu project can Maven profile, truyen tham so khi chay script, vi du:
                run-greytest-coverage.cmd -Ptest
                """.formatted(JACOCO_VERSION);
    }

    private MergedFile merge(List<UnitTest> tests) {
        UnitTest first = tests.get(0);
        List<Long> caseIds = tests.stream().map(UnitTest::getTestCaseId).toList();
        String source = tests.size() == 1 ? first.getSourceCode() : mergeSources(tests);
        return new MergedFile(first.getFilePath(), first.getTestClassName(), first.getPackageName(), caseIds, ensureMockitoRunner(source));
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
            log.warn("Khong merge duoc unit test bang JavaParser: {}", exception.getMessage());
            throw new StorageException("Khong gop duoc Unit Test thanh file Java chay duoc", exception);
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
                log.warn("Bo qua static import trung ten khi gop Unit Test: {}", candidate.getNameAsString());
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
                        .orElseThrow(() -> new IllegalStateException("Khong parse duoc annotation: " + qualifiedName)));
            }
        });
    }

    private com.github.javaparser.ast.type.ClassOrInterfaceType parseType(String qualifiedName) {
        return new JavaParser().parseClassOrInterfaceType(qualifiedName)
                .getResult()
                .orElseThrow(() -> new IllegalStateException("Khong parse duoc type: " + qualifiedName));
    }

    private com.github.javaparser.ast.expr.Expression parseExpression(String qualifiedName) {
        return new JavaParser().parseExpression(qualifiedName)
                .getResult()
                .orElseThrow(() -> new IllegalStateException("Khong parse duoc expression: " + qualifiedName));
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
                .orElseThrow(() -> new IllegalStateException("Parse loi: "
                        + result.getProblems().stream().findFirst().map(Object::toString).orElse("unknown")));
    }

    private CompilationUnit parseForArchive(String source) {
        try {
            return parse(source);
        } catch (RuntimeException exception) {
            throw new StorageException("Source Unit Test khong phai Java hop le", exception);
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
            log.warn("Khong the tu dong chuan hoa Unit Test: {}", e.getMessage());
            return sourceCode;
        }
    }
}
