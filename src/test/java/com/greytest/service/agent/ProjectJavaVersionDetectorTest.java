package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.entity.Project;
import com.greytest.repository.ProjectRepository;

class ProjectJavaVersionDetectorTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectJavaVersionDetector detector = new ProjectJavaVersionDetector(projects);

    @Test
    void detectsMavenReleaseAndReportsBuildFile(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><properties><java.version>1.8</java.version></properties>
                  <build><plugins><plugin><configuration><release>17</release></configuration></plugin></plugins></build>
                </project>
                """);
        when(projects.findById(1L)).thenReturn(Optional.of(projectAt(root)));

        assertThat(detector.detect(1L)).get()
                .extracting(ProjectJavaVersionDetector.JavaVersionInfo::version,
                        ProjectJavaVersionDetector.JavaVersionInfo::buildFile)
                .containsExactly("17", "pom.xml");
    }

    @Test
    void detectsGradleToolchain(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle.kts"), """
                java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
                """);
        when(projects.findById(1L)).thenReturn(Optional.of(projectAt(root)));

        assertThat(detector.detect(1L)).get()
                .extracting(ProjectJavaVersionDetector.JavaVersionInfo::version)
                .isEqualTo("21");
    }

    @Test
    void usesTheLowerGradleCompatibilityWhenToolchainIsNewer(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle"), """
                java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
                sourceCompatibility = '1.8'
                targetCompatibility = '1.8'
                """);
        when(projects.findById(1L)).thenReturn(Optional.of(projectAt(root)));

        assertThat(detector.detect(1L)).get()
                .extracting(ProjectJavaVersionDetector.JavaVersionInfo::version)
                .isEqualTo("8");
    }

    @Test
    void ignoresCommentedMavenVersionAndUsesActiveDeclaration(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <!-- <java.version>21</java.version> -->
                <project><properties><java.version>8</java.version></properties></project>
                """);
        when(projects.findById(1L)).thenReturn(Optional.of(projectAt(root)));

        assertThat(detector.detect(1L)).get()
                .extracting(ProjectJavaVersionDetector.JavaVersionInfo::version)
                .isEqualTo("8");
    }

    @Test
    void ignoresCommentedGradleVersion(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle"), """
                // sourceCompatibility = '21'
                /* targetCompatibility = '17' */
                """);
        when(projects.findById(1L)).thenReturn(Optional.of(projectAt(root)));

        assertThat(detector.detect(1L)).isEmpty();
    }

    @Test
    void usesTheLowerMavenSourceAndTarget(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><properties><maven.compiler.source>17</maven.compiler.source>
                <maven.compiler.target>1.8</maven.compiler.target></properties></project>
                """);
        when(projects.findById(1L)).thenReturn(Optional.of(projectAt(root)));

        assertThat(detector.detect(1L)).get()
                .extracting(ProjectJavaVersionDetector.JavaVersionInfo::version)
                .isEqualTo("8");
    }

    @Test
    void returnsEmptyWhenBuildDoesNotDeclareVersion(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        when(projects.findById(1L)).thenReturn(Optional.of(projectAt(root)));

        assertThat(detector.detect(1L)).isEmpty();
    }

    @Test
    void ignoresMalformedOrUnreasonablyLargeVersion(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"),
                "<project><properties><java.version>12345678901234567890</java.version></properties></project>");
        when(projects.findById(1L)).thenReturn(Optional.of(projectAt(root)));

        assertThat(detector.detect(1L)).isEmpty();
    }

    @Test
    void detectsJUnit4FromLegacySpringBootParent(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><parent>
                  <artifactId>spring-boot-starter-parent</artifactId><version>2.0.3.RELEASE</version>
                </parent></project>
                """);
        when(projects.findById(1L)).thenReturn(Optional.of(projectAt(root)));

        assertThat(detector.detectTestFramework(1L)).get()
                .extracting(ProjectJavaVersionDetector.TestFrameworkInfo::framework)
                .isEqualTo(ProjectJavaVersionDetector.TestFramework.JUNIT4);
    }

    @Test
    void detectsJUnit5FromJupiterDependency(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle"),
                "testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'\ntest { useJUnitPlatform() }");
        when(projects.findById(1L)).thenReturn(Optional.of(projectAt(root)));

        assertThat(detector.detectTestFramework(1L)).get()
                .extracting(ProjectJavaVersionDetector.TestFrameworkInfo::framework)
                .isEqualTo(ProjectJavaVersionDetector.TestFramework.JUNIT5);
    }

    @Test
    void detectsFrameworkFromModuleContainingSource(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"),
                "<project><properties><java.version>17</java.version></properties></project>");
        Path legacy = Files.createDirectories(root.resolve("legacy/src/main/java/demo"));
        Path modern = Files.createDirectories(root.resolve("modern/src/main/java/demo"));
        Files.writeString(root.resolve("legacy/pom.xml"),
                "<project><properties><java.version>8</java.version></properties>"
                        + "<dependencies><dependency><groupId>junit</groupId>"
                        + "<artifactId>junit</artifactId></dependency></dependencies></project>");
        Files.writeString(root.resolve("modern/pom.xml"),
                "<project><properties><java.version>21</java.version></properties>"
                        + "<dependencies><dependency><groupId>org.junit.jupiter</groupId>"
                        + "<artifactId>junit-jupiter</artifactId></dependency></dependencies></project>");
        Files.writeString(legacy.resolve("LegacyService.java"), "class LegacyService {}");
        Files.writeString(modern.resolve("ModernService.java"), "class ModernService {}");
        when(projects.findById(1L)).thenReturn(Optional.of(projectAt(root)));

        assertThat(detector.detectTestFramework(1L,
                List.of("legacy/src/main/java/demo/LegacyService.java"))).get()
                .extracting(ProjectJavaVersionDetector.TestFrameworkInfo::framework)
                .isEqualTo(ProjectJavaVersionDetector.TestFramework.JUNIT4);
        assertThat(detector.detectTestFramework(1L,
                List.of("modern/src/main/java/demo/ModernService.java"))).get()
                .extracting(ProjectJavaVersionDetector.TestFrameworkInfo::framework)
                .isEqualTo(ProjectJavaVersionDetector.TestFramework.JUNIT5);
        assertThat(detector.detectTestFramework(1L, List.of(
                "legacy/src/main/java/demo/LegacyService.java",
                "modern/src/main/java/demo/ModernService.java"))).isEmpty();
        assertThat(detector.detect(1L, List.of("legacy/src/main/java/demo/LegacyService.java"))).get()
                .extracting(ProjectJavaVersionDetector.JavaVersionInfo::version).isEqualTo("8");
        assertThat(detector.detect(1L, List.of("modern/src/main/java/demo/ModernService.java"))).get()
                .extracting(ProjectJavaVersionDetector.JavaVersionInfo::version).isEqualTo("21");
        assertThat(detector.detect(1L, List.of(
                "legacy/src/main/java/demo/LegacyService.java",
                "modern/src/main/java/demo/ModernService.java"))).isEmpty();
    }

    private Project projectAt(Path root) {
        Project project = new Project();
        project.setId(1L);
        project.setStoragePath(root.toString());
        return project;
    }
}
