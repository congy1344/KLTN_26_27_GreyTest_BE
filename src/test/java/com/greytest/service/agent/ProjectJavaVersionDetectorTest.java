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

    @Test
    void reusesBuildEvidenceWithinJobAndRefreshesForNextJob(@TempDir Path root) throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project><properties><java.version>17</java.version></properties></project>");
        com.greytest.service.GenerationJobContext.bind(42L, ignored -> { });
        try {
            assertThat(detector.detectSnapshot(root, List.of("src/main/java/demo/Service.java")).map(ProjectJavaVersionDetector.JavaVersionInfo::version)).contains("17");
            Files.writeString(pom, "<project><properties><java.version>21</java.version></properties></project>");
            assertThat(detector.detectSnapshot(root, List.of("src/main/java/demo/Service.java")).map(ProjectJavaVersionDetector.JavaVersionInfo::version)).contains("17");
        } finally {
            com.greytest.service.GenerationJobContext.clear();
        }
        assertThat(detector.detectSnapshot(root, List.of("src/main/java/demo/Service.java")).map(ProjectJavaVersionDetector.JavaVersionInfo::version)).contains("21");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"2.0.9.RELEASE", "2.1.18.RELEASE"})
    void detectsLegacyMockitoFromBootParent(String bootVersion, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><parent><artifactId>spring-boot-starter-parent</artifactId><version>"
                + bootVersion + "</version></parent></project>");
        assertThat(detector.detectSnapshotMockitoVersion(root, List.of("src/main/java/demo/Service.java"))).get()
                .extracting(ProjectJavaVersionDetector.MockitoVersionInfo::major).isEqualTo(2);
    }

    @Test
    void childMockitoPropertyOverridesInheritedDependencyVersion(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><properties><mockito.version>2.15.0</mockito.version></properties><dependencies><dependency>
                <groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><version>${mockito.version}</version>
                </dependency></dependencies></project>
                """);
        Files.createDirectories(root.resolve("module"));
        Files.writeString(root.resolve("module/pom.xml"), "<project><properties><mockito.version>5.11.0</mockito.version></properties></project>");

        assertThat(detector.detectSnapshotMockitoVersion(root, List.of("module/src/main/java/demo/Service.java"))).get()
                .extracting(ProjectJavaVersionDetector.MockitoVersionInfo::version).isEqualTo("5.11.0");
    }

    @Test
    void explicitMockitoDependencyOverridesLegacyBootDefault(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><parent><artifactId>spring-boot-starter-parent</artifactId><version>2.1.18.RELEASE</version></parent>
                <dependencies><dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId>
                <version>4.11.0</version></dependency></dependencies></project>
                """);
        assertThat(detector.detectSnapshotMockitoVersion(root, List.of("src/main/java/demo/Service.java"))).get()
                .extracting(ProjectJavaVersionDetector.MockitoVersionInfo::major).isEqualTo(4);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"testImplementation 'org.mockito:mockito-core:3.9.0'",
            "val mockitoVersion = \"3.9.0\"\ntestImplementation(\"org.mockito:mockito-core:$mockitoVersion\")"})
    void detectsMockitoFromGradleDependency(String build, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle.kts"), build);
        assertThat(detector.detectSnapshotMockitoVersion(root, List.of("src/main/java/demo/Service.java"))).get()
                .extracting(ProjectJavaVersionDetector.MockitoVersionInfo::version).isEqualTo("3.9.0");
    }

    @Test
    void conflictingModuleMockitoVersionsRemainUnknown(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("old")); Files.createDirectories(root.resolve("new"));
        Files.writeString(root.resolve("old/pom.xml"), "<project><properties><mockito.version>2.15.0</mockito.version></properties></project>");
        Files.writeString(root.resolve("new/pom.xml"), "<project><properties><mockito.version>5.11.0</mockito.version></properties></project>");
        assertThat(detector.detectSnapshotMockitoVersion(root, List.of("old/src/main/java/demo/Old.java", "new/src/main/java/demo/New.java"))).isEmpty();
    }

    @Test
    void sameMajorWithDifferentApiAvailabilityRemainsUnknownAcrossModules(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("first")); Files.createDirectories(root.resolve("second"));
        Files.writeString(root.resolve("first/pom.xml"), "<project><properties><mockito.version>3.9.0</mockito.version></properties></project>");
        Files.writeString(root.resolve("second/pom.xml"), "<project><properties><mockito.version>3.0.0</mockito.version></properties></project>");
        assertThat(detector.detectSnapshotMockitoVersion(root, List.of("first/src/main/java/demo/First.java", "second/src/main/java/demo/Second.java"))).isEmpty();
    }

    @Test
    void sameMajorWithDifferentApiAvailabilityRemainsUnknownAcrossDependencies(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><dependencies>
                <dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><version>3.9.0</version></dependency>
                <dependency><groupId>org.mockito</groupId><artifactId>mockito-inline</artifactId><version>3.0.0</version></dependency>
                </dependencies></project>
                """);
        assertThat(detector.detectSnapshotMockitoVersion(root, List.of("src/main/java/demo/Service.java"))).isEmpty();
    }

    @Test
    void candidateMockitoDoesNotUseActiveBuild(@TempDir Path root) throws Exception {
        Path active = root.resolve("active"), candidate = root.resolve("candidate");
        Files.createDirectories(active); Files.createDirectories(candidate);
        Files.writeString(active.resolve("pom.xml"), "<project><properties><mockito.version>5.11.0</mockito.version></properties></project>");
        Files.writeString(candidate.resolve("pom.xml"), "<project><properties><mockito.version>2.15.0</mockito.version></properties></project>");
        when(projects.findById(1L)).thenReturn(Optional.of(projectAt(active)));
        assertThat(detector.detectMockitoVersion(1L, List.of("src/main/java/demo/Service.java"))).get()
                .extracting(ProjectJavaVersionDetector.MockitoVersionInfo::major).isEqualTo(5);
        assertThat(detector.detectSnapshotMockitoVersion(candidate, List.of("src/main/java/demo/Service.java"))).get()
                .extracting(ProjectJavaVersionDetector.MockitoVersionInfo::major).isEqualTo(2);
    }

    @Test
    void profileDependentMockitoVersionRemainsUnknown(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><properties><mockito.version>2.15.0</mockito.version></properties>
                <profiles><profile><id>optional-modern-tests</id><properties><mockito.version>5.11.0</mockito.version></properties>
                </profile></profiles></project>
                """);
        assertThat(detector.detectSnapshotMockitoVersion(root, List.of("src/main/java/demo/Service.java"))).isEmpty();
    }

    @Test
    void unresolvedMockitoOverrideMustNotFallBackToLegacyBoot(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><parent><artifactId>spring-boot-starter-parent</artifactId><version>2.1.18.RELEASE</version></parent>
                <dependencies><dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId>
                <version>${external.version}</version></dependency></dependencies></project>
                """);
        assertThat(detector.detectSnapshotMockitoVersion(root, List.of("src/main/java/demo/Service.java"))).isEmpty();
    }

    @Test
    void pluginScopedMockitoDoesNotDefineProjectTestVersion(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><parent><artifactId>spring-boot-starter-parent</artifactId><version>2.1.18.RELEASE</version></parent>
                <build><plugins><plugin><artifactId>some-plugin</artifactId><dependencies><dependency>
                <groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><version>5.11.0</version>
                </dependency></dependencies></plugin></plugins></build></project>
                """);
        assertThat(detector.detectSnapshotMockitoVersion(root, List.of("src/main/java/demo/Service.java"))).get()
                .extracting(ProjectJavaVersionDetector.MockitoVersionInfo::major).isEqualTo(2);
    }

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

    @Test
    void buildEvidenceIncludesTargetModuleAndParentButNotSibling(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><parent><artifactId>spring-boot-starter-parent</artifactId><version>2.0.3.RELEASE</version></parent></project>");
        Files.createDirectories(root.resolve("auth")); Files.createDirectories(root.resolve("other"));
        Files.writeString(root.resolve("auth/pom.xml"), "<project><dependencies><dependency><artifactId>spring-boot-starter-web</artifactId>"
                + "<exclusions><exclusion><artifactId>spring-boot-starter-logging</artifactId></exclusion></exclusions></dependency></dependencies></project>");
        Files.writeString(root.resolve("other/pom.xml"), "<project><dependencies><dependency><artifactId>logback-classic</artifactId></dependency></dependencies></project>");
        var evidence = detector.snapshotBuildEvidence(root, List.of("auth/src/main/java/demo/Service.java"));
        assertThat(evidence).containsOnlyKeys("pom.xml", "auth/pom.xml");
        assertThat(evidence.get("auth/pom.xml")).contains("spring-boot-starter-logging", "<exclusions>");
        assertThat(detector.snapshotBuildEvidence(root, List.of("../outside/Service.java"))).isEmpty();
        assertThat(detector.snapshotBuildEvidence(root, List.of())).isEmpty();
    }

    private Project projectAt(Path root) {
        Project project = new Project();
        project.setId(1L);
        project.setStoragePath(root.toString());
        return project;
    }
}
