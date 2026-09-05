package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

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
    void parseLoiThiNoiThoCacFile() {
        var merged = service.mergeByClass(List.of(
                test(1L, "a", "khong phai java {{{"),
                test(2L, "b", "cung khong phai java")));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).sourceCode()).contains("khong phai java", "gop thu cong");
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
                "run-greytest-coverage.cmd",
                "run-greytest-coverage.sh",
                "README-GREYTEST.txt");
        assertThat(entries.get("run-greytest-coverage.cmd"))
                .contains("jacoco-maven-plugin:0.8.15:prepare-agent")
                .contains("jacoco-maven-plugin:0.8.15:report")
                .contains("cd /d \"%~dp0\"", "call %MAVEN_COMMAND%", "%*")
                .contains("-Djacoco.propertyName=greytestJacocoArgLine")
                .contains("-DargLine=@{greytestJacocoArgLine} %GREYTEST_JVM_ARGS%")
                .contains("clean test-compile")
                .contains("-DfailIfNoTests=true")
                .contains("src\\test\\java\\*.java", "target\\test-classes\\*.class")
                .contains("-Dtest=com.example.UserServiceTest")
                .contains("target\\site\\jacoco\\jacoco.xml");
        assertThat(entries.get("run-greytest-coverage.sh"))
                .contains("jacoco-maven-plugin:0.8.15:prepare-agent")
                .contains("-Djacoco.propertyName=greytestJacocoArgLine")
                .contains("-DargLine=@{greytestJacocoArgLine} ${GREYTEST_JVM_ARGS:-}")
                .contains("src/test/java", "target/test-classes", "*.java", "*.class")
                .contains("-Dtest=com.example.UserServiceTest")
                .contains("-DfailIfNoTests=true")
                .contains("target/site/jacoco/jacoco.xml");
        assertThat(entries.get("README-GREYTEST.txt"))
                .contains("run-greytest-coverage.cmd", "src/test/java", "GREYTEST_JVM_ARGS", "khong gan trung JaCoCo agent");
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
