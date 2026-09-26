package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import com.greytest.entity.UnitTest;

class UnitTestFileServiceTest {

    private final UnitTestFileService service = new UnitTestFileService();

    private UnitTest test(Long caseId, String methodName, String source) {
        UnitTest unitTest = new UnitTest();
        unitTest.setTestCaseId(caseId);
        unitTest.setTestClassName("UserServiceTest");
        unitTest.setPackageName("com.example");
        unitTest.setTestMethodName(methodName);
        unitTest.setFilePath("src/test/java/com/example/UserServiceTest.java");
        unitTest.setSourceCode(source);
        return unitTest;
    }

    @Test
    void gopCacTestCungClassThanhMotFile() {
        String source1 = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.mockito.Mock;
                public class UserServiceTest {
                    @Mock
                    private UserRepository userRepository;
                    @Test
                    void create_Valid() { }
                }
                """;
        String source2 = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import java.util.Optional;
                public class UserServiceTest {
                    @Mock
                    private UserRepository userRepository;
                    @Test
                    void findById_Existing() { }
                }
                """;

        var merged = service.mergeByClass(List.of(
                test(1L, "create_Valid", source1),
                test(2L, "findById_Existing", source2)));

        assertThat(merged).hasSize(1);
        var file = merged.get(0);
        assertThat(file.caseIds()).containsExactly(1L, 2L);
        // Cả 2 @Test method nằm trong 1 class, field mock không bị nhân đôi
        assertThat(file.sourceCode()).contains("create_Valid", "findById_Existing", "import java.util.Optional;");
        assertThat(countOf(file.sourceCode(), "class UserServiceTest")).isEqualTo(1);
        assertThat(countOf(file.sourceCode(), "private UserRepository userRepository")).isEqualTo(1);
    }

    @Test
    void motTestMotFileGiuNguyenSource() {
        String source = "package com.example;\npublic class UserServiceTest { }\n";
        var merged = service.mergeByClass(List.of(test(1L, "any", source)));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).sourceCode()).isEqualTo(source);
    }

    @Test
    void parseLoiThiTuChoiKhongTaoFileJavaKhongChayDuoc() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.mergeByClass(List.of(
                test(1L, "a", "khong phai java {{{"),
                test(2L, "b", "cung khong phai java"))))
                .isInstanceOf(com.greytest.exception.StorageException.class)
                .hasMessageContaining("Khong gop duoc Unit Test");
    }

    @Test
    void chiLoaiFieldTrungTrongDeclarationNhieuBien() {
        String source1 = "package com.example; class UserServiceTest { Object repository; void first(){} }";
        String source2 = "package com.example; class UserServiceTest { Object repository, auditRepository; void second(){} }";

        var source = service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))).get(0).sourceCode();

        assertThat(countOf(source, "repository;")).isEqualTo(1);
        assertThat(source).contains("auditRepository");
    }

    @Test
    void mergeDungTestClassKhiHelperClassDungTruoc() {
        String source1 = "package com.example; class Support {} class UserServiceTest { void first(){} }";
        String source2 = "package com.example; class Support {} class UserServiceTest { void second(){} }";

        var source = service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))).get(0).sourceCode();

        assertThat(source).contains("void first()", "void second()");
    }

    @Test
    void mergeKhongLapConstructorVaNestedClass() {
        String source1 = "package com.example; class UserServiceTest { UserServiceTest(){} class Fixture {} void first(){} }";
        String source2 = "package com.example; class UserServiceTest { UserServiceTest(){} class Fixture {} void second(){} }";

        var source = service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))).get(0).sourceCode();

        assertThat(countOf(source, "UserServiceTest()")).isEqualTo(1);
        assertThat(countOf(source, "class Fixture")).isEqualTo(1);
    }

    @Test
    void mergeTuChoiNestedClassTrungTenNhungKhacNoiDung() {
        String source1 = "package com.example; class UserServiceTest { class Fixture { String value = \"a b\"; } void first(){} }";
        String source2 = "package com.example; class UserServiceTest { class Fixture { String value = \"ab\"; } void second(){} }";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))))
                .isInstanceOf(com.greytest.exception.StorageException.class)
                .hasMessageContaining("Xung dot nested class");
    }

    @Test
    void mergeGopEnumConstantsKhiHaiTestDungCungNestedEnumKhacEntries() {
        String source1 = "package com.example; class UserServiceTest { enum TestEventType { APPOINTMENT_CREATED } void first(){} }";
        String source2 = "package com.example; class UserServiceTest { enum TestEventType { PAYMENT_UPDATED } void second(){} }";

        var merged = service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))).get(0).sourceCode();

        assertThat(merged).contains("enum TestEventType", "APPOINTMENT_CREATED", "PAYMENT_UPDATED");
    }

    @Test
    void mergeGiuImportDauTienKhiHaiTestDungCungTenImportKhacPackage() {
        String source1 = "package com.example; import a.Status; class UserServiceTest { Status firstStatus; void first(){} }";
        String source2 = "package com.example; import b.Status; class UserServiceTest { Status secondStatus; void second(){ Status.valueOf(\"OK\"); } }";

        var source = service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))).get(0).sourceCode();

        assertThat(source).contains("import a.Status;", "b.Status secondStatus;", "b.Status.valueOf(\"OK\")", "void first()", "void second()")
                .doesNotContain("import b.Status;");
    }

    @Test
    void mergeTuChoiHaiWildcardImportKhacPackage() {
        String source1 = "package com.example; import a.*; class UserServiceTest { Status firstStatus; void first(){} }";
        String source2 = "package com.example; import b.*; class UserServiceTest { Status secondStatus; void second(){} }";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))))
                .isInstanceOf(com.greytest.exception.StorageException.class)
                .hasMessageContaining("Xung dot wildcard import");
    }

    @Test
    void qualifiesExplicitImportWhenBaseFileUsesWildcardImport() {
        String source1 = "package com.example; import a.*; class UserServiceTest { Status firstStatus; void first(){} }";
        String source2 = "package com.example; import b.Status; class UserServiceTest { Status secondStatus; void second(){} }";

        var source = service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))).get(0).sourceCode();

        assertThat(source).contains("import a.*;", "b.Status secondStatus")
                .doesNotContain("import b.Status;");
    }

    @Test
    void rejectsWildcardImportWhenBaseFileUsesExplicitImport() {
        String source1 = "package com.example; import a.Status; class UserServiceTest { Status firstStatus; void first(){} }";
        String source2 = "package com.example; import b.*; class UserServiceTest { Status secondStatus; void second(){} }";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))))
                .isInstanceOf(com.greytest.exception.StorageException.class)
                .hasMessageContaining("wildcard");
    }

    @Test
    void allowsStaticWildcardImportsAlongsideNormalImports() {
        String source1 = "package com.example; import java.util.List; import static org.mockito.Mockito.*; "
                + "class UserServiceTest { List<String> list; void first(){ when(null).thenReturn(null); } }";
        String source2 = "package com.example; import java.util.Map; import static org.junit.jupiter.api.Assertions.*; "
                + "class UserServiceTest { Map<String, String> map; void second(){ assertTrue(true); } }";

        var source = service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))).get(0).sourceCode();

        assertThat(source)
                .contains("import static org.mockito.Mockito.*;")
                .contains("import static org.junit.jupiter.api.Assertions.*;")
                .contains("import java.util.List;")
                .contains("import java.util.Map;")
                .contains("void first()")
                .contains("void second()");
    }

    @Test
    void rejectsConflictingStaticImports() {
        String source1 = "package com.example; import static a.Assertions.assertThat; "
                + "class UserServiceTest { void first(){ assertThat(true); } }";
        String source2 = "package com.example; import static b.Assertions.assertThat; "
                + "class UserServiceTest { void second(){ assertThat(true); } }";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))))
                .isInstanceOf(com.greytest.exception.StorageException.class)
                .hasMessageContaining("static");
    }

    @Test
    void giuLaiHelperMethodOverloadKhacSignature() {
        String source1 = "package com.example; class UserServiceTest { Object build(){return null;} void first(){} }";
        String source2 = "package com.example; class UserServiceTest { Object build(String id){return null;} void second(){} }";

        var source = service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))).get(0).sourceCode();

        assertThat(source).contains("build()", "build(String id)", "void second()");
    }

    @Test
    void loaiHelperMethodTrungSauTypeErasure() {
        String source1 = "package com.example; class UserServiceTest { "
                + "void build(java.util.List<String> values){} void first(){} }";
        String source2 = "package com.example; class UserServiceTest { "
                + "void build(java.util.List<Integer> values){} void second(){} }";

        var source = service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))).get(0).sourceCode();

        assertThat(countOf(source, "void build(")).isEqualTo(1);
        assertThat(source).contains("void second()");
    }

    @Test
    void coiVarargsVaArrayLaCungSignature() {
        String source1 = "package com.example; class UserServiceTest { "
                + "void build(String... values){} void first(){} }";
        String source2 = "package com.example; class UserServiceTest { "
                + "void build(String[] values){} void second(){} }";

        var source = service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))).get(0).sourceCode();

        assertThat(countOf(source, "void build(")).isEqualTo(1);
        assertThat(source).contains("void second()");
    }

    @Test
    void xoaTypeVariableKhiSoSanhSignature() {
        String source1 = "package com.example; class UserServiceTest { "
                + "<T> void convert(T value){} void first(){} }";
        String source2 = "package com.example; class UserServiceTest { "
                + "<U> void convert(U value){} void second(){} }";

        var source = service.mergeByClass(List.of(
                test(1L, "first", source1), test(2L, "second", source2))).get(0).sourceCode();

        assertThat(countOf(source, "void convert(")).isEqualTo(1);
        assertThat(source).contains("void second()");
    }

    @Test
    void archiveKemScriptTaoJacocoXmlMaKhongCanThemCauHinhJacocoVaoPom() throws IOException {
        var mergedFile = new com.greytest.dto.UnitTestFileDto(
                "src/test/java/com/example/UserServiceTest.java",
                "UserServiceTest",
                "com.example",
                1,
                List.of("TC-001"),
                "package com.example; class UserServiceTest {}");

        Map<String, String> entries = zipEntries(service.createCoverageArchive(List.of(mergedFile)));

        assertThat(entries).containsKeys(
                "src/test/java/com/example/UserServiceTest.java",
                "src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker",
                "run-greytest-coverage.cmd",
                "run-greytest-coverage.sh",
                "README-GREYTEST.txt");
        assertThat(entries.get("src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker"))
                .isEqualTo("mock-maker-subclass\n");
        assertThat(entries.get("run-greytest-coverage.cmd"))
                .contains("jacoco-maven-plugin:0.8.15:prepare-agent")
                .contains("jacoco-maven-plugin:0.8.15:report")
                .contains("cd /d \"%~dp0\"", "call %MAVEN_COMMAND%", "%*")
                .contains("-Djacoco.propertyName=greytestJacocoArgLine")
                .contains("-DargLine=@{greytestJacocoArgLine} %GREYTEST_JVM_ARGS%")
                .contains("set \"GREYTEST_JVM_ARGS=-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading\"")
                .contains("Phat hien module con cua du an Maven multi-module")
                .contains("clean test-compile")
                .contains("-DfailIfNoTests=true")
                .contains("src\\test\\java\\*.java", "target\\test-classes\\*.class")
                .contains("-Dtest=com.example.UserServiceTest")
                .contains("target\\site\\jacoco\\jacoco.xml", "build.gradle", "gradlew.bat", "jacocoTestReport");
        assertThat(entries.get("run-greytest-coverage.cmd"))
                .contains("--init-script", "--tests \"com.example.UserServiceTest\"")
                .contains("clean test --tests \"com.example.UserServiceTest\" jacocoTestReport")
                .contains("GREYTEST_JUNCTION_RUN", "New-Item -ItemType Junction");
        assertThat(entries.get("run-greytest-coverage.sh"))
                .contains("jacoco-maven-plugin:0.8.15:prepare-agent")
                .contains("-Djacoco.propertyName=greytestJacocoArgLine")
                .contains("-DargLine=@{greytestJacocoArgLine} ${GREYTEST_JVM_ARGS:-}")
                .contains("export GREYTEST_JVM_ARGS=\"${GREYTEST_JVM_ARGS:--Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading}\"")
                .contains("src/test/java", "target/test-classes", "*.java", "*.class")
                .contains("-Dtest=com.example.UserServiceTest")
                .contains("-DfailIfNoTests=true", "build.gradle", "gradlew", "jacocoTestReport")
                .contains("clean test --tests \"com.example.UserServiceTest\" jacocoTestReport")
                .contains("target/site/jacoco/jacoco.xml");
        assertThat(entries.get("run-greytest-coverage.sh"))
                .contains("--init-script", "--tests \"com.example.UserServiceTest\"");
        assertThat(entries.get("greytest-jacoco.init.gradle"))
                .contains("jacoco", "jacocoTestReport", "xml.required = true",
                        "reports/jacoco/test/jacocoTestReport.xml");
        assertThat(entries.get("README-GREYTEST.txt"))
                .contains("run-greytest-coverage.cmd", "src/test/java", "GREYTEST_JVM_ARGS", "mock-maker-subclass");
        assertThat(entries.get("run-greytest-coverage.cmd"))
                .doesNotContain("(where pom.xml is located)");
    }

    @Test
    void archiveRemovesLeadingUtf8BomFromJavaSource() throws IOException {
        var file = new com.greytest.dto.UnitTestFileDto(
                "src/test/java/com/example/UserServiceTest.java",
                "UserServiceTest", "com.example", 1, List.of("TC-001"),
                "\uFEFFpackage com.example; class UserServiceTest {}\n");

        assertThat(zipEntries(service.createCoverageArchive(List.of(file)))
                .get("src/test/java/com/example/UserServiceTest.java"))
                .doesNotStartWith("\uFEFF");
    }

    @Test
    void archiveRongBiTuChoiDeKhongTaiZipChacChanChayThatBai() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createCoverageArchive(List.of()))
                .isInstanceOf(com.greytest.exception.StorageException.class)
                .hasMessageContaining("Khong co Unit Test de dong goi");
    }

    @Test
    void windowsScriptKhongBiLoiParserKhiChayNgoaiModule() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        var file = new com.greytest.dto.UnitTestFileDto(
                "src/test/java/UserServiceTest.java", "UserServiceTest", "", 1,
                List.of("TC-001"), "class UserServiceTest {}");
        Path script = Files.createTempFile("greytest-coverage-", ".cmd");
        Files.writeString(script, zipEntries(service.createCoverageArchive(List.of(file))).get("run-greytest-coverage.cmd"));

        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", script.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).isEqualTo(1);
        assertThat(output).doesNotContain("was unexpected at this time");
    }

    @Test
    void archiveTuChoiDuongDanTestThoatKhoiThuMucChoPhep() {
        var mergedFile = new com.greytest.dto.UnitTestFileDto(
                "../OutsideTest.java", "OutsideTest", "", 1, List.of("TC-001"), "class OutsideTest {}");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.createCoverageArchive(List.of(mergedFile)))
                .hasMessageContaining("Duong dan Unit Test khong hop le");
    }

    @Test
    void archiveGiuNguyenSourceThayViThayChuoiMockitoLamHongCode() throws IOException {
        String source = "import org.mockito.Matchers; class LegacyTest { "
                + "String text = \"org.mockito.Matchers\"; Object value = Matchers.any(); }";
        var file = new com.greytest.dto.UnitTestFileDto(
                "src/test/java/LegacyTest.java", "LegacyTest", "", 1, List.of("TC-001"), source);

        Map<String, String> entries = zipEntries(service.createCoverageArchive(List.of(file)));

        assertThat(entries.get("src/test/java/LegacyTest.java")).isEqualTo(source);
    }

    @Test
    void multiModuleSafeTestPathAndArchive() throws IOException {
        String source = "package com.example; public class SubServiceTest {}";
        var file = new com.greytest.dto.UnitTestFileDto(
                "sub-module/src/test/java/com/example/SubServiceTest.java",
                "SubServiceTest", "com.example", 1, List.of("TC-001"), source);

        Map<String, String> entries = zipEntries(service.createCoverageArchive(List.of(file)));
        assertThat(entries).containsKey("src/test/java/com/example/SubServiceTest.java");
    }

    @Test
    void ensureMockitoRunnerAutoAddsExtensionForJUnit5() {
        String testWithoutRunner = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.mockito.Mock;
                public class ServiceTest {
                    @Mock
                    private Repo repo;
                    @Test
                    void testMethod() {}
                }
                """;
        String fixed = service.ensureMockitoRunner(testWithoutRunner);
        assertThat(fixed).contains("@ExtendWith(MockitoExtension.class)");
        assertThat(fixed).contains("import org.junit.jupiter.api.extension.ExtendWith;");
        assertThat(fixed).contains("import org.mockito.junit.jupiter.MockitoExtension;");
    }

    @Test
    void mergePreservesClassAnnotationsFromSubsequentTests() {
        String source1 = """
                package com.example;
                import org.junit.jupiter.api.Test;
                public class UserServiceTest {
                    @Test
                    void test1() {}
                }
                """;
        String source2 = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                @ExtendWith(MockitoExtension.class)
                public class UserServiceTest {
                    @Mock
                    private Repo repo;
                    @Test
                    void test2() {}
                }
                """;

        var merged = service.mergeByClass(List.of(
                test(1L, "test1", source1),
                test(2L, "test2", source2)));

        assertThat(merged).hasSize(1);
        String code = merged.get(0).sourceCode();
        assertThat(code).contains("@ExtendWith(MockitoExtension.class)");
        assertThat(code).contains("import org.junit.jupiter.api.extension.ExtendWith;");
        assertThat(code).contains("import org.mockito.junit.jupiter.MockitoExtension;");
    }

    private Map<String, String> zipEntries(byte[] archive) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private int countOf(String text, String token) {
        return text.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }
}
