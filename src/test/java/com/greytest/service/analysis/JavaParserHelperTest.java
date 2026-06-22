package com.greytest.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.greytest.exception.SourceAnalysisException;
import com.greytest.service.analysis.JavaParserHelper.ExtractedEndpoint;
import com.greytest.service.analysis.JavaParserHelper.ExtractedMethod;
import com.greytest.service.analysis.JavaParserHelper.ParsedFile;
import com.greytest.service.analysis.JavaParserHelper.SourceScanResult;

class JavaParserHelperTest {

    private final JavaParserHelper parser = new JavaParserHelper();

    @Test
    void keepsOverloadedMethodsAndEndpointsDistinct(@TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("SampleController.java"), """
                package demo;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class SampleController {
                    @GetMapping("/by-id")
                    String find(Long id) { return id.toString(); }

                    @GetMapping("/by-name")
                    String find(String name) { return name; }
                }
                """);

        ParsedFile parsedFile = parser.parseDirectory(sourceDir).get(0);
        ClassOrInterfaceDeclaration declaration = parser.findClasses(parsedFile.compilationUnit()).get(0);
        List<ExtractedMethod> methods = parser.extractMethods(declaration);
        List<ExtractedEndpoint> endpoints = parser.extractEndpoints(declaration);

        assertThat(methods).extracting(ExtractedMethod::key)
                .containsExactly("find(Long)", "find(String)");
        assertThat(endpoints).extracting(ExtractedEndpoint::javaMethodKey)
                .containsExactly("find(Long)", "find(String)");
    }

    @Test
    void rejectsProjectWhenAnyJavaFileCannotBeParsed(@TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("Broken.java"), "class Broken {");

        assertThatThrownBy(() -> parser.parseDirectory(sourceDir))
                .isInstanceOf(SourceAnalysisException.class)
                .hasMessageContaining("Broken.java");
    }

    @Test
    void rejectsProjectWithoutJavaSource(@TempDir Path sourceDir) {
        assertThatThrownBy(() -> parser.parseDirectory(sourceDir))
                .isInstanceOf(SourceAnalysisException.class)
                .hasMessageContaining("Không tìm thấy file .java");
    }

    @Test
    void parsesJava17InstanceofPatternUsedBySpringPetClinic(@TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("PostgresIntegrationTests.java"), """
                class PostgresIntegrationTests {
                    void inspect(Object source) {
                        if (source instanceof Iterable<?> enumerable) {
                            enumerable.iterator();
                        }
                    }
                }
                """);

        List<ParsedFile> parsedFiles = parser.parseDirectory(sourceDir);

        assertThat(parsedFiles).hasSize(1);
        assertThat(parser.findClasses(parsedFiles.get(0).compilationUnit()))
                .extracting(ClassOrInterfaceDeclaration::getNameAsString)
                .containsExactly("PostgresIntegrationTests");
    }

    @Test
    void scansOnlyProductionSourceAndCountsExistingTests(@TempDir Path projectDir) throws IOException {
        Path mainSource = projectDir.resolve("module-a/src/main/java/demo/App.java");
        Path existingTest = projectDir.resolve("module-a/src/test/java/demo/AppTest.java");
        Path generatedSource = projectDir.resolve("module-a/target/generated-sources/Generated.java");
        Files.createDirectories(mainSource.getParent());
        Files.createDirectories(existingTest.getParent());
        Files.createDirectories(generatedSource.getParent());
        Files.writeString(mainSource, "package demo; class App { void run() {} }");
        // Test cố ý sai cú pháp: phải được đếm nhưng không được parse.
        Files.writeString(existingTest, "class AppTest {");
        Files.writeString(generatedSource, "class Generated {}");

        SourceScanResult result = parser.scanProject(projectDir);

        assertThat(result.existingTestFileCount()).isEqualTo(1);
        assertThat(result.productionFiles()).singleElement()
                .extracting(ParsedFile::relativePath)
                .isEqualTo("module-a/src/main/java/demo/App.java");
    }

    @Test
    void extractsEnumRecordAndAllEndpointPathMethodCombinations(@TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("Api.java"), """
                package demo;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                enum Status { ACTIVE, INACTIVE }
                record UserRequest(String name) {}

                @RestController
                @RequestMapping({"/api", "/internal"})
                class Api {
                    @RequestMapping(path = {"/users", "/members"},
                            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.HEAD})
                    void users() { class LocalOnly {} }
                }
                """);

        ParsedFile parsedFile = parser.parseDirectory(sourceDir).get(0);

        assertThat(parser.findTypes(parsedFile.compilationUnit()))
                .anyMatch(EnumDeclaration.class::isInstance)
                .anyMatch(RecordDeclaration.class::isInstance)
                .extracting(type -> type.getNameAsString())
                .containsExactly("Status", "UserRequest", "Api");
        ClassOrInterfaceDeclaration controller = parser.findClasses(parsedFile.compilationUnit()).stream()
                .filter(type -> type.getNameAsString().equals("Api"))
                .findFirst()
                .orElseThrow();
        assertThat(parser.extractEndpoints(controller)).hasSize(12)
                .extracting(ExtractedEndpoint::path)
                .contains("/api/users", "/api/members", "/internal/users", "/internal/members");
        assertThat(parser.extractEndpoints(controller))
                .extracting(ExtractedEndpoint::httpMethod)
                .contains(
                        com.greytest.entity.enums.HttpMethod.GET,
                        com.greytest.entity.enums.HttpMethod.POST,
                        com.greytest.entity.enums.HttpMethod.HEAD);
    }
}
