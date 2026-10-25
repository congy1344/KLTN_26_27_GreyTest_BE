package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.dto.UnitTestFileDto;
import com.greytest.entity.UnitTest;

class UnitTestFixtureExportTest {
    private final UnitTestFileService service = new UnitTestFileService();

    @Test
    void exportedTestsKeepTheirRealAndSpySetups(@TempDir Path directory) throws Exception {
        var files = service.mergeByClass(List.of(
                test(1L, "realEncoder", encoderTest("new BCryptPasswordEncoder()", "realEncoder",
                        "assertTrue(passwordEncoder.matches(\"secret\", passwordEncoder.encode(\"secret\")));")),
                test(2L, "spyEncoder", encoderTest("spy(new BCryptPasswordEncoder())", "spyEncoder",
                        "verify(passwordEncoder, never()).encode(anyString());"))));

        compileAndRun(files, directory);

        assertThat(files).hasSize(2);
        assertThat(files).flatExtracting(UnitTestFileService.MergedFile::caseIds).containsExactlyInAnyOrder(1L, 2L);
        var dtos = files.stream().map(file -> new UnitTestFileDto(file.filePath(), file.testClassName(),
                file.packageName(), file.caseIds().size(), List.of(), file.sourceCode())).toList();
        assertThat(service.createCoverageArchive(dtos)).isNotEmpty();
    }

    @Test
    void keepsLifecycleAbsentFromAnotherRecord(@TempDir Path directory) throws Exception {
        String empty = fixture("int value;", "", "assertEquals(0, value);");
        String setup = fixture("int value;", "@BeforeEach void initialize() { value = 1; }", "assertEquals(1, value);");

        var files = service.mergeByClass(List.of(test(1L, "check", empty), test(2L, "check", setup)));

        assertThat(files).hasSize(2);
        compileAndRun(files, directory);
    }

    @Test
    void keepsConflictingFieldsAndHelpers(@TempDir Path directory) throws Exception {
        var files = service.mergeByClass(List.of(
                test(1L, "check", fixture("String value = \"a b\";", "String expected() { return \"a b\"; }",
                        "assertEquals(expected(), value);")),
                test(2L, "check", fixture("String value = \"ab\";", "String expected() { return \"ab\"; }",
                        "assertEquals(expected(), value);"))));

        assertThat(files).hasSize(2);
        compileAndRun(files, directory);
    }

    @Test
    void keepsAdditionalFieldInitializerFromChangingOtherTests(@TempDir Path directory) throws Exception {
        var files = service.mergeByClass(List.of(
                test(1L, "check", fixture("int value;", "", "assertEquals(0, value);")),
                test(2L, "check", fixture("int value; int increment = ++value;", "", "assertEquals(1, value);"))));

        assertThat(files).hasSize(2);
        compileAndRun(files, directory);
    }

    @Test
    void renamesConstructorsAndClassReferencesWithoutCollidingWithOriginalClasses(@TempDir Path directory) throws Exception {
        String refs = "assertEquals(UserServiceTest.class, new UserServiceTest().getClass());";
        var first = test(1L, "check", fixture("int value = 1;", "public UserServiceTest() {}", refs));
        var second = test(2L, "check", fixture("int value = 2;", "public UserServiceTest() {}", refs));
        var reserved = test(3L, "check", "package com.example; public class UserServiceTest_GreyTest_1 {}");
        reserved.setTestClassName("UserServiceTest_GreyTest_1");
        reserved.setFilePath("src/test/java/com/example/UserServiceTest_GreyTest_1.java");

        var files = service.mergeByClass(List.of(first, second, reserved));
        var reversed = service.mergeByClass(List.of(reserved, second, first));

        assertThat(files).extracting(UnitTestFileService.MergedFile::testClassName).doesNotHaveDuplicates();
        assertThat(reversed).extracting(UnitTestFileService.MergedFile::testClassName)
                .containsExactlyInAnyOrderElementsOf(files.stream().map(UnitTestFileService.MergedFile::testClassName).toList());
        compileAndRun(files, directory);
    }

    @Test
    void equivalentFixturesStillMerge() {
        String first = fixture("int value = 1;", "@BeforeEach void setUp() { value = 2; }", "assertEquals(2, value);");
        String second = first.replace("void check()", "void other()");

        var files = service.mergeByClass(List.of(test(1L, "check", first), test(2L, "other", second)));

        assertThat(files).hasSize(1);
        assertThat(files.get(0).caseIds()).containsExactly(1L, 2L);
    }

    @Test
    void keepsHelperOverloadsFromChangingAnotherTestsMethodBinding(@TempDir Path directory) throws Exception {
        String first = fixture("", "String label(Object value){return \"object\";}",
                "assertEquals(\"object\", label((String)null));");
        String second = fixture("", "String label(String value){return \"string\";}",
                "assertEquals(\"string\", label((String)null));").replace("void check()", "void other()");
        var files = service.mergeByClass(List.of(test(1L, "check", first), test(2L, "other", second)));

        compileAndRun(files, directory);
        assertThat(files).hasSize(2);
    }

    @Test
    void preservesDifferentTestBodiesWithTheSameSignature(@TempDir Path directory) throws Exception {
        var files = service.mergeByClass(List.of(
                test(1L, "check", fixture("", "", "assertEquals(1, 1);")),
                test(2L, "check", fixture("", "", "assertEquals(2, 2);"))));

        assertThat(files).hasSize(2);
        assertThat(files).flatExtracting(UnitTestFileService.MergedFile::caseIds).containsExactlyInAnyOrder(1L, 2L);
        compileAndRun(files, directory);
    }

    @Test
    void renamesQualifiedOuterThisInNestedHelpers(@TempDir Path directory) throws Exception {
        String nested = "class Reader {int read(){return UserServiceTest.this.value;}}";
        var files = service.mergeByClass(List.of(
                test(1L, "check", fixture("int value = 1;", nested, "assertEquals(1, new Reader().read());")),
                test(2L, "check", fixture("int value = 2;", nested, "assertEquals(2, new Reader().read());"))));

        compileAndRun(files, directory);
        assertThat(files).hasSize(2);
    }

    @Test
    void keepsAdditionalJunitExtensionsInsteadOfDroppingTheirLifecycle() {
        String first = fixture("int value;", "", "assertEquals(0, value);")
                .replace("public class UserServiceTest", "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class) public class UserServiceTest");
        String second = first.replace("void check()", "void other()")
                .replace("ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)",
                        "ExtendWith({org.mockito.junit.jupiter.MockitoExtension.class, CustomExtension.class})")
                .replace("int value;", "int value; static class CustomExtension implements org.junit.jupiter.api.extension.BeforeEachCallback { public void beforeEach(org.junit.jupiter.api.extension.ExtensionContext context) {} }");
        var files = service.mergeByClass(List.of(test(1L, "check", first), test(2L, "other", second)));

        assertThat(files).hasSize(2);
        assertThat(files.stream().filter(file -> file.caseIds().contains(2L)).findFirst().orElseThrow().sourceCode())
                .contains("CustomExtension.class");
    }

    private String fixture(String fields, String helpers, String assertion) {
        return """
                package com.example;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class UserServiceTest {
                    %s
                    %s
                    @Test void check() { %s }
                }
                """.formatted(fields, helpers, assertion);
    }

    private String encoderTest(String initializer, String method, String assertion) {
        return """
                package com.example;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
                import org.springframework.security.crypto.password.PasswordEncoder;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                import static org.mockito.Mockito.*;
                import static org.mockito.ArgumentMatchers.anyString;
                public class UserServiceTest {
                    private PasswordEncoder passwordEncoder;
                    @BeforeEach void setUp() { passwordEncoder = %s; }
                    @Test void %s() { %s }
                }
                """.formatted(initializer, method, assertion);
    }

    private UnitTest test(Long caseId, String methodName, String source) {
        UnitTest test = new UnitTest();
        test.setTestCaseId(caseId);
        test.setTestClassName("UserServiceTest");
        test.setPackageName("com.example");
        test.setTestMethodName(methodName);
        test.setFilePath("src/test/java/com/example/UserServiceTest.java");
        test.setSourceCode(source);
        return test;
    }

    private void compileAndRun(List<UnitTestFileService.MergedFile> files, Path directory) throws Exception {
        List<String> arguments = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", directory.toString()));
        for (var file : files) {
            Path source = directory.resolve(file.filePath());
            Files.createDirectories(source.getParent());
            Files.writeString(source, file.sourceCode(), StandardCharsets.UTF_8);
            arguments.add(source.toString());
        }
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)))
                .as("exported fixture variants compile together").isZero();
        try (var loader = new URLClassLoader(new java.net.URL[] { directory.toUri().toURL() }, getClass().getClassLoader())) {
            for (var file : files) {
                Class<?> type = loader.loadClass(file.packageName() + "." + file.testClassName());
                for (var method : type.getDeclaredMethods()) {
                    if (!method.isAnnotationPresent(Test.class)) continue;
                    Object instance = type.getDeclaredConstructor().newInstance();
                    for (var lifecycle : type.getDeclaredMethods()) {
                        if (lifecycle.isAnnotationPresent(org.junit.jupiter.api.BeforeEach.class)) {
                            lifecycle.setAccessible(true);
                            lifecycle.invoke(instance);
                        }
                    }
                    method.setAccessible(true);
                    method.invoke(instance);
                }
            }
        }
    }
}
