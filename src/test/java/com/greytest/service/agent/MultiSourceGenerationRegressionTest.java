package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import com.github.javaparser.JavaParser;
import com.greytest.dto.UnitTestFileDto;
import com.greytest.dto.agent.GenerationContextDtos.*;
import com.greytest.dto.agent.GenerationResponseDtos.GeneratedUnitTestDto;
import com.greytest.dto.agent.GenerationResponseDtos.UnitTestResponseDto;
import com.greytest.service.UnitTestFileService;

/** Hồi quy bằng snapshot khai báo thật của nhiều source; không phụ thuộc đường dẫn máy hoặc gọi LLM. */
class MultiSourceGenerationRegressionTest {
    static Stream<Arguments> sources() {
        return Stream.of(
                Arguments.of("realworld/ProfileData.java.txt", "io.spring.application.data.ProfileData",
                        "new ProfileData(\"author-1\", \"alice\", \"\", \"\", false)", "new ProfileData(\"author-1\", \"alice\", \"\", \"\")", false),
                Arguments.of("hms/DepartmentDto.java.txt", "com.example.HMS.dto.DepartmentDto",
                        "new DepartmentDto(1L, \"Cardiology\")", "new DepartmentDto(1L)", false),
                Arguments.of("piggymetrics/User.java.txt", "com.piggymetrics.auth.domain.User",
                        "new User()", "new User(\"alice\", \"password\")", true),
                Arguments.of("hospital-demo/PatientResponseDto.java.txt", "com.hospital.patient.dto.PatientResponseDto",
                        "new PatientResponseDto()", "new PatientResponseDto(1L, \"alice\")", false),
                Arguments.of("eshop/PaymentResponse.java.txt", "eshop.paymentservice.rabbitmq.events.PaymentResponse",
                        "new PaymentResponse(java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000001\"), true)", "new PaymentResponse()", false),
                Arguments.of("ecommerce/DtoCollectionResponse.java.txt", "com.selimhorri.app.dto.response.collection.DtoCollectionResponse",
                        "new DtoCollectionResponse<String>()", null, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void sourceDeclaredConstructorsAreAcceptedAndInventedOverloadsAreRejected(
            String resource, String fqn, String valid, String invalid, boolean junit4) throws Exception {
        var context = context(resource, fqn);
        assertThat(GeneratedTestApiValidator.validate(context, new JavaParser().parse(testSource(fqn, valid, junit4)).getResult().orElseThrow()))
                .as(resource).isEmpty();
        if (invalid != null) {
            assertThat(GeneratedTestApiValidator.validate(context, new JavaParser().parse(testSource(fqn, invalid, junit4)).getResult().orElseThrow()))
                    .as(resource).anySatisfy(message -> assertThat(message).contains("Source constructor contract", fqn));
        }
    }

    @ParameterizedTest(name = "archive {0}")
    @MethodSource("sources")
    void coverageArchivePreservesSourcePackageImportsAndSelectsOnlyItsTest(
            String resource, String fqn, String valid, String invalid, boolean junit4) throws Exception {
        String source = testSource(fqn, valid, junit4);
        var file = new UnitTestFileDto("src/test/java/regression/SampleTest.java", "SampleTest", "regression", 1,
                List.of("TC-regression"), source);
        byte[] zip = new UnitTestFileService().createCoverageArchive(List.of(file));
        var entries = new java.util.HashMap<String, String>();
        try (var stream = new ZipInputStream(new ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = stream.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(entries.get(file.filePath())).isEqualTo(source).contains("import " + fqn + ";");
        assertThat(entries.get("run-greytest-coverage.cmd"))
                .contains("-Dtest=regression.SampleTest", "--tests \"regression.SampleTest\"", "%TEMP%\\greytest-junctions")
                .doesNotContainPattern("(?<!\\r)\\n");
        assertThat(entries.keySet()).noneMatch(path -> path.contains("mockito-extensions"));
    }

    static Stream<Arguments> builds() {
        return Stream.of(
                Arguments.of("realworld", "build.gradle", "11", ProjectJavaVersionDetector.TestFramework.JUNIT5),
                Arguments.of("hms", "build.gradle", "17", ProjectJavaVersionDetector.TestFramework.JUNIT5),
                Arguments.of("piggymetrics", "pom.xml", "8", ProjectJavaVersionDetector.TestFramework.JUNIT4),
                Arguments.of("hospital-demo", "pom.xml", "17", ProjectJavaVersionDetector.TestFramework.JUNIT5));
    }

    @ParameterizedTest(name = "build {0}")
    @MethodSource("builds")
    void libraryInstructionsFollowTheActualSourceBuild(String dataset, String buildFile, String javaVersion,
            ProjectJavaVersionDetector.TestFramework junit, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve(buildFile), load(dataset + "/" + buildFile + ".txt"));
        var detector = new ProjectJavaVersionDetector(null);
        var paths = List.of("src/main/java/regression/Service.java");
        assertThat(detector.detectSnapshot(root, paths)).get().extracting(ProjectJavaVersionDetector.JavaVersionInfo::version)
                .isEqualTo(javaVersion);
        assertThat(detector.detectSnapshotTestFramework(root, paths)).get()
                .extracting(ProjectJavaVersionDetector.TestFrameworkInfo::framework).isEqualTo(junit);
        if (dataset.equals("piggymetrics")) {
            assertThat(detector.detectSnapshotMockitoVersion(root, paths)).get()
                    .extracting(ProjectJavaVersionDetector.MockitoVersionInfo::major).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"service.completeAppointment(1L);", "Object status = com.example.HMS.enums.AppointmentStatus.CONFIRMED;",
            "Object status = com.example.HMS.entity.AppointmentStatus.APPOINTMENT_COMPLETED;"})
    void staleHmsApisAreRejectedWithoutInventingProductionMethodsOrEnumValues(String statement) throws Exception {
        String serviceSource = load("hms/AppointmentService.java.txt");
        String methodSource = new JavaParser().parse(serviceSource).getResult().orElseThrow()
                .getClassByName("AppointmentService").orElseThrow().getMethodsByName("getAppointmentById").get(0).toString();
        var method = new MethodContextDto(10L, "com.example.HMS.service.AppointmentService", "getAppointmentById", "AppointmentDto",
                List.of(), List.of(), "public", methodSource, 1, 2, List.of(), List.of(), List.of());
        var service = type("hms/AppointmentService.java.txt", "com.example.HMS.service.AppointmentService", List.of(method));
        var status = type("hms/AppointmentStatus.java.txt", "com.example.HMS.enums.AppointmentStatus", List.of());
        var rule = new BusinessRuleContextDto(20L, 10L, "BR-1", "lookup", null, "AI", "APPROVED", false, null);
        var plan = new TestPlanContextItemDto(30L, 20L, List.of(20L), "TP-1", "lookup", "lookup", "HAPPY_PATH", "APPROVED", false);
        var testCase = new TestCaseContextItemDto(40L, 30L, "TC-1", "HAPPY_PATH", "lookup", "exists", Map.of(),
                "returns appointment", "HIGH", "BR-1 -> TP-1", "APPROVED", false);
        var context = new UnitTestContextDto(null, null, List.of(service, status), List.of(rule), List.of(plan), List.of(testCase),
                List.of(), List.of(), List.of());
        String source = "package com.example.HMS.service; class AppointmentServiceTest { AppointmentService service; "
                + "@org.junit.jupiter.api.Test void testCase() { " + statement + " } }";
        var response = new UnitTestResponseDto(List.of(new GeneratedUnitTestDto(40L, "AppointmentServiceTest", "testCase",
                "com.example.HMS.service", "NEW_TEST", source)));
        assertThat(GeneratedUnitTestSemanticValidator.validate(context, response)).hasValueSatisfying(message -> {
            if (statement.contains("completeAppointment")) assertThat(message).contains("completeAppointment", "does not exist");
            else if (statement.contains("CONFIRMED")) assertThat(message).contains("Enum constant", "CONFIRMED");
            else assertThat(message).contains("Enum type", "com.example.HMS.entity.AppointmentStatus");
        });
    }

    private UnitTestContextDto context(String resource, String fqn) throws Exception {
        return new UnitTestContextDto(null, null, List.of(type(resource, fqn, List.of())), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }

    private ClassContextDto type(String resource, String fqn, List<MethodContextDto> methods) throws Exception {
        int dot = fqn.lastIndexOf('.');
        String source = load(resource);
        boolean isEnum = !new JavaParser().parse(source).getResult().orElseThrow()
                .findAll(com.github.javaparser.ast.body.EnumDeclaration.class).isEmpty();
        return new ClassContextDto(1L, fqn.substring(0, dot), fqn.substring(dot + 1), fqn, isEnum ? "ENUM" : "CLASS", resource,
                source, List.of(), methods);
    }

    private static String testSource(String fqn, String expression, boolean junit4) {
        String annotation = junit4 ? "@org.junit.Test" : "@org.junit.jupiter.api.Test";
        String assertions = junit4 ? "org.junit.Assert" : "org.junit.jupiter.api.Assertions";
        return "package regression; import " + fqn + "; public class SampleTest { " + annotation
                + " public void testCase() { Object value = " + expression + "; " + assertions + ".assertNotNull(value); } }";
    }

    private static String load(String resource) throws Exception {
        return new ClassPathResource("fixtures/multi-source/" + resource).getContentAsString(StandardCharsets.UTF_8);
    }
}
