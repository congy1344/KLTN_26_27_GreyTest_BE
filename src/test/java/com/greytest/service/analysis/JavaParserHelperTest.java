package com.greytest.service.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.greytest.dto.SourceBranchDto;
import com.greytest.entity.enums.AnnotationCategory;
import com.greytest.entity.enums.ClassType;
import com.greytest.exception.SourceAnalysisException;
import com.greytest.service.analysis.JavaParserHelper.ControllerServiceCall;
import com.greytest.service.analysis.JavaParserHelper.ExtractedEndpoint;
import com.greytest.service.analysis.JavaParserHelper.ExtractedMethod;
import com.greytest.service.analysis.JavaParserHelper.ExtractedAnnotation;
import com.greytest.service.analysis.JavaParserHelper.FieldDependency;
import com.greytest.service.analysis.JavaParserHelper.ParsedFile;
import com.greytest.service.analysis.JavaParserHelper.SourceScanResult;

class JavaParserHelperTest {

    private final JavaParserHelper parser = new JavaParserHelper();

    private static final Path FAVOURITE_SERVICE = Path.of(
            "src", "test", "resources", "fixtures", "FavouriteServiceImpl.java");

    @Test
    void testExtractMethodsAndEndpoints_OverloadedMethods_ReturnsDistinctKeys(@TempDir Path sourceDir)
            throws IOException {
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
    void testExtractMethods_TrivialAccessorsAndBusinessMethods_ExcludesOnlyAccessors(@TempDir Path sourceDir)
            throws IOException {
        Files.writeString(sourceDir.resolve("Sample.java"), """
                package demo;
                import org.springframework.web.bind.annotation.GetMapping;

                class Sample {
                    private String name;

                    String getName() { return name; }

                    void setName(String name) { this.name = name; }

                    @GetMapping("/name")
                    String getNameEndpoint() { return name; }

                    String getDisplayName() { return name.trim(); }
                }
                """);

        ParsedFile parsedFile = parser.parseDirectory(sourceDir).get(0);
        ClassOrInterfaceDeclaration declaration = parser.findClasses(parsedFile.compilationUnit()).get(0);

        assertThat(parser.extractMethods(declaration))
                .extracting(ExtractedMethod::key)
                .containsExactly("getNameEndpoint()", "getDisplayName()");
    }

    @Test
    void testParseDirectory_InvalidJavaFile_ThrowsSourceAnalysisException(@TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("Broken.java"), "class Broken {");

        assertThatThrownBy(() -> parser.parseDirectory(sourceDir))
                .isInstanceOf(SourceAnalysisException.class)
                .hasMessageContaining("Broken.java");
    }

    @Test
    void testParseDirectory_NoJavaFiles_ThrowsSourceAnalysisException(@TempDir Path sourceDir) {
        assertThatThrownBy(() -> parser.parseDirectory(sourceDir))
                .isInstanceOf(SourceAnalysisException.class)
                .hasMessageContaining("Khong tim thay file .java");
    }

    @Test
    void testParseDirectory_LegacyJava8Source_ParsesSuccessfully(@TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("LegacyJava8.java"), """
                class LegacyJava8 {
                    int _(int _) {
                        return _;
                    }
                }
                """);

        assertThat(parser.parseDirectory(sourceDir)).hasSize(1);
    }

    @Test
    void testParseDirectory_Java21PatternSwitch_ParsesSuccessfully(@TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("PatternSwitchSample.java"), """
                class PatternSwitchSample {
                    String inspect(Object source) {
                        return switch (source) {
                            case String text -> text;
                            case Integer number -> number.toString();
                            default -> "";
                        };
                    }
                }
                """);

        List<ParsedFile> parsedFiles = parser.parseDirectory(sourceDir);

        assertThat(parsedFiles).hasSize(1);
        assertThat(parser.findClasses(parsedFiles.get(0).compilationUnit()))
                .extracting(ClassOrInterfaceDeclaration::getNameAsString)
                .containsExactly("PatternSwitchSample");
    }

    @Test
    void testScanProject_ProductionAndExistingTestSources_ReturnsProductionOnly(@TempDir Path projectDir)
            throws IOException {
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
        assertThat(result.failedParseFiles()).isEmpty();
        assertThat(result.totalProductionFileCount()).isEqualTo(1);
    }

    @Test
    void testScanProject_BrokenProductionFile_SkipsAndReportsFile(@TempDir Path projectDir) throws IOException {
        Path validSource = projectDir.resolve("src/main/java/demo/Valid.java");
        Path brokenSource = projectDir.resolve("src/main/java/demo/Broken.java");
        Files.createDirectories(validSource.getParent());
        Files.writeString(validSource, "package demo; class Valid { void run() {} }");
        Files.writeString(brokenSource, "package demo; class Broken {");

        SourceScanResult result = parser.scanProject(projectDir);

        assertThat(result.productionFiles()).singleElement()
                .extracting(ParsedFile::relativePath)
                .isEqualTo("src/main/java/demo/Valid.java");
        assertThat(result.failedParseFiles()).containsExactly("src/main/java/demo/Broken.java");
        assertThat(result.totalProductionFileCount()).isEqualTo(2);
    }

    @Test
    void testFindTypesAndExtractEndpoints_EnumsRecordsAndMultipleMappings_ReturnsAllMetadata(@TempDir Path sourceDir)
            throws IOException {
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

    @Test
    void testExtractMethods_MethodWithComments_ReturnsCommentFreeSource(@TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("CommentedService.java"), """
                package demo;

                class CommentedService {
                    /** Javadoc note */
                    public void process(int value) {
                        // Single line note: check threshold
                        /* Block comment note */
                        if (value > 100) {
                            throw new IllegalArgumentException();
                        }
                    }
                }
                """);

        ParsedFile parsedFile = parser.parseDirectory(sourceDir).get(0);
        ClassOrInterfaceDeclaration declaration = parser.findClasses(parsedFile.compilationUnit()).get(0);
        List<ExtractedMethod> methods = parser.extractMethods(declaration);

        assertThat(methods).hasSize(1);
        String source = methods.get(0).sourceCode();
        assertThat(source).doesNotContain("Javadoc note")
                .doesNotContain("Single line note")
                .doesNotContain("Block comment note")
                .contains("if (value > 100)")
                .contains("throw new IllegalArgumentException();");
    }

    @Test
    void testCleanMethodSource_MethodWithoutFileStorage_ThrowsSourceAnalysisException() {
        MethodDeclaration method = JavaParserFactory.parseMethodDeclaration(
                "public void process() { return; }")
                .getResult()
                .orElseThrow();

        assertThatThrownBy(() -> JavaParserHelper.cleanMethodSource(method))
                .isInstanceOf(SourceAnalysisException.class)
                .hasMessageContaining("Khong the doc source");
    }

    @Test
    void testExtractMethods_MultilineChainsAndNestedLambdas_PreservesStatementRanges() throws IOException {
        List<ExtractedMethod> methods = extractedMethods();

        assertThat(method(methods, "findAll").lineStart()).isEqualTo(33);
        assertThat(method(methods, "findAll").lineEnd()).isEqualTo(50);
        assertRange(method(methods, "findAll"), "STMT-2", 36, 49);

        assertThat(method(methods, "findById").lineStart()).isEqualTo(52);
        assertThat(method(methods, "findById").lineEnd()).isEqualTo(68);
        assertRange(method(methods, "findById"), "STMT-2", 55, 67);

        assertThat(method(methods, "save").lineStart()).isEqualTo(70);
        assertThat(method(methods, "save").lineEnd()).isEqualTo(74);
        assertRange(method(methods, "save"), "STMT-1", 72, 73);

        assertThat(method(methods, "update").lineStart()).isEqualTo(76);
        assertThat(method(methods, "update").lineEnd()).isEqualTo(80);
        assertRange(method(methods, "update"), "STMT-1", 78, 79);

        assertThat(method(methods, "deleteById").lineStart()).isEqualTo(82);
        assertThat(method(methods, "deleteById").lineEnd()).isEqualTo(85);
        assertRange(method(methods, "deleteById"), "STMT-1", 84, 84);
    }

    @Test
    void testDetermineClassType_SpringTypesAndRepositoryInterface_ReturnsExpectedTypes(@TempDir Path sourceDir)
            throws IOException {
        Files.writeString(sourceDir.resolve("Types.java"), """
                @Service class SampleService {}
                @RestController class SampleController {}
                @Entity class SampleEntity {}
                interface SampleRepository extends JpaRepository<SampleEntity, Long> {}
                class PlainType {}
                enum Status { ACTIVE }
                record Request(String value) {}
                """);

        List<TypeDeclaration<?>> types = parser.findTypes(parser.parseDirectory(sourceDir).get(0).compilationUnit());

        assertEquals(ClassType.SERVICE, parser.determineClassType(types.get(0)));
        assertEquals(ClassType.CONTROLLER, parser.determineClassType(types.get(1)));
        assertEquals(ClassType.ENTITY, parser.determineClassType(types.get(2)));
        assertEquals(ClassType.REPOSITORY, parser.determineClassType(types.get(3)));
        assertEquals(ClassType.OTHER, parser.determineClassType(types.get(4)));
        assertEquals(ClassType.ENUM, parser.determineClassType(types.get(5)));
        assertEquals(ClassType.RECORD, parser.determineClassType(types.get(6)));
    }

    @Test
    void testExtractFieldDependenciesAndControllerServiceCalls_FieldsAndDirectCalls_ReturnsMetadata(
            @TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("SampleController.java"), """
                package demo;

                class SampleController {
                    private UserService userService;
                    private java.util.List<UserRepository> repositories;

                    void load(Long id) {
                        userService.findById(id);
                        this.userService.save();
                    }
                }
                """);

        ClassOrInterfaceDeclaration declaration = parser.findClasses(
                parser.parseDirectory(sourceDir).get(0).compilationUnit()).get(0);
        MethodDeclaration method = declaration.getMethods().get(0);
        List<FieldDependency> dependencies = parser.extractFieldDependencies(declaration);
        List<ControllerServiceCall> calls = parser.extractControllerServiceCalls(method);

        assertEquals(2, dependencies.size());
        assertEquals("userService", dependencies.get(0).name());
        assertEquals("UserService", dependencies.get(0).type());
        assertEquals("repositories", dependencies.get(1).name());
        assertEquals("java.util.List", dependencies.get(1).type());
        assertEquals(List.of("UserService", "java.util.List"), parser.extractRepositoryDependencies(declaration));
        assertEquals(2, calls.size());
        assertEquals("load(Long)", calls.get(0).controllerMethodKey());
        assertEquals("userService", calls.get(0).fieldName());
        assertEquals("findById", calls.get(0).calledMethodName());
        assertEquals(1, calls.get(0).argumentCount());
        assertEquals("userService", calls.get(1).fieldName());
        assertEquals("save", calls.get(1).calledMethodName());
        assertEquals(0, calls.get(1).argumentCount());
    }

    @Test
    void testExtractRelevantAnnotations_ClassMethodAndParameterAnnotations_ReturnsCategories(@TempDir Path sourceDir)
            throws IOException {
        Files.writeString(sourceDir.resolve("AnnotatedService.java"), """
                package demo;

                @Service
                @Transactional
                class AnnotatedService {
                    @PreAuthorize("hasRole('USER')")
                    @GetMapping("/items")
                    void find(@Valid @NotBlank String query) {}
                }
                """);

        ClassOrInterfaceDeclaration declaration = parser.findClasses(
                parser.parseDirectory(sourceDir).get(0).compilationUnit()).get(0);
        List<ExtractedAnnotation> classAnnotations = parser.extractRelevantAnnotations(declaration);
        List<ExtractedAnnotation> methodAnnotations = parser.extractRelevantAnnotations(declaration.getMethods().get(0));

        assertThat(classAnnotations)
                .extracting(ExtractedAnnotation::category)
                .containsExactly(AnnotationCategory.COMPONENT, AnnotationCategory.TRANSACTION);
        assertThat(methodAnnotations)
                .extracting(ExtractedAnnotation::category)
                .containsExactly(
                        AnnotationCategory.SECURITY,
                        AnnotationCategory.ENDPOINT,
                        AnnotationCategory.VALIDATION,
                        AnnotationCategory.VALIDATION);
        assertThat(methodAnnotations)
                .extracting(ExtractedAnnotation::name)
                .containsExactly("PreAuthorize", "GetMapping", "Valid", "NotBlank");
    }

    @Test
    void testGetPackageName_NoPackageDeclaration_ReturnsEmptyString(@TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("DefaultPackage.java"), "class DefaultPackage {}");

        String packageName = parser.getPackageName(parser.parseDirectory(sourceDir).get(0).compilationUnit());

        assertEquals("", packageName);
    }

    private List<ExtractedMethod> extractedMethods() throws IOException {
        TypeDeclaration<?> type = JavaParserFactory.parse(FAVOURITE_SERVICE)
                .getResult()
                .orElseThrow()
                .getTypes()
                .getFirst()
                .orElseThrow();
        return new JavaParserHelper().extractMethods(type);
    }

    private ExtractedMethod method(List<ExtractedMethod> methods, String name) {
        return methods.stream().filter(method -> name.equals(method.name())).findFirst().orElseThrow();
    }

    private void assertRange(ExtractedMethod method, String anchorId, int lineStart, int lineEnd) {
        SourceBranchDto anchor = MethodBranchAnalyzer.analyze(method.sourceCode(), method.lineStart()).stream()
                .filter(branch -> anchorId.equals(branch.branchId()))
                .findFirst()
                .orElseThrow();
        assertThat(anchor.lineStart()).isEqualTo(lineStart);
        assertThat(anchor.lineEnd()).isEqualTo(lineEnd);
    }
}
