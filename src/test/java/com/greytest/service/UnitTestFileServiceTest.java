package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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

    private int countOf(String text, String token) {
        return text.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }
}
