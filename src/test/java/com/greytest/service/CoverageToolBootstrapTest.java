package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.greytest.dto.UnitTestFileDto;
import com.sun.net.httpserver.HttpServer;

class CoverageToolBootstrapTest {
    @TempDir Path workspace;
    private final UnitTestFileService service = new UnitTestFileService();

    @Test
    void windowsUsesShortJunctionForDeepAsciiWorkspace() throws Exception {
        windowsOnly();
        var module = module("deep-ascii-" + "x".repeat(80), true);
        Files.writeString(module.resolve("gradlew.bat"), GRADLE_CMD);
        var result = run(module, false, env -> { });
        assertThat(result.exit()).withFailMessage(result.output()).isZero();
        assertThat(result.output()).contains("Junction", "JUNCTION_1", "SUCCESS");
    }

    @Test
    void windowsKeepsParentGradleSettingsAndSiblingProjectsForDeepAsciiModule() throws Exception {
        windowsOnly();
        var module = module("subprojects/child-" + "x".repeat(80), true);
        Files.writeString(module.getParent().resolve("settings.gradle"), "include ':child', ':shared'\n");
        Files.createDirectories(module.getParent().resolve("shared"));
        Files.writeString(module.getParent().resolve("shared/marker.txt"), "sibling");
        Files.writeString(module.resolve("gradlew.bat"), GRADLE_CMD.replace("echo JUNCTION_", "if not exist ..\\settings.gradle exit /b 9\r\n"
                + "if not exist ..\\shared\\marker.txt exit /b 9\r\necho JUNCTION_"));
        var result = run(module, false, env -> { });
        assertThat(result.exit()).withFailMessage(result.output()).isZero();
        assertThat(result.output()).contains("SUCCESS").doesNotContain("JUNCTION_1");
    }

    private static final String GRADLE_CMD = """
            @echo off
            echo JUNCTION_%GREYTEST_JUNCTION_RUN%
            if not exist build\\reports\\jacoco\\test mkdir build\\reports\\jacoco\\test
            echo ^<report/^>>build\\reports\\jacoco\\test\\jacocoTestReport.xml
            exit /b 0
            """.replace("\n", "\r\n");

    private static final String MAVEN_CMD = """
            @echo off
            echo TEST_MAVEN %*
            if "%~1"=="-v" exit /b 0
            if not exist target\\test-classes mkdir target\\test-classes
            echo compiled>target\\test-classes\\SampleTest.class
            if not exist target\\site\\jacoco mkdir target\\site\\jacoco
            echo ^<report/^>>target\\site\\jacoco\\jacoco.xml
            exit /b 0
            """.replace("\n", "\r\n");

    private static final String MAVEN_SH = """
            #!/bin/sh
            echo "TEST_MAVEN $*"
            [ "${1-}" != '-v' ] || exit 0
            mkdir -p target/test-classes target/site/jacoco
            echo compiled > target/test-classes/SampleTest.class
            echo '<report/>' > target/site/jacoco/jacoco.xml
            """;

    @ParameterizedTest
    @ValueSource(strings = {"portable module", "portable mẫu", "portable O'Brien"})
    void windowsDownloadsBundledMavenWhenPathToolIsBrokenAndReusesCache(String name) throws Exception {
        windowsOnly();
        var module = module(name, false);
        var broken = Files.createDirectories(workspace.resolve("broken-path"));
        Files.writeString(broken.resolve("mvn.cmd"), "@echo off\r\nexit /b 9\r\n");
        try (var server = new MavenDistributionServer(true)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                var result = run(module, false, env -> {
                    env.environment().put("PATH", broken + ";" + systemPath());
                    env.environment().put("MVNW_REPOURL", server.url());
                });
                assertThat(result.exit()).withFailMessage(result.output()).isZero();
                assertThat(result.output()).contains("TEST_MAVEN", "SUCCESS");
            }
            assertThat(server.downloads.get()).isEqualTo(1);
        }
    }

    @Test
    void windowsBrokenLocalMavenDoesNotMaskHealthyPathMaven() throws Exception {
        windowsOnly();
        var module = module("prefer usable path", false);
        var broken = Files.createDirectories(module.resolve(".tools/apache-maven-broken/bin"));
        Files.writeString(broken.resolve("mvn.cmd"), "@echo off\r\nexit /b 8\r\n");
        var pathTool = Files.createDirectories(workspace.resolve("path maven"));
        Files.writeString(pathTool.resolve("mvn.cmd"), MAVEN_CMD.replace("TEST_MAVEN", "HEALTHY_PATH_MAVEN"));
        try (var server = new MavenDistributionServer(true)) {
            var result = run(module, false, env -> {
                env.environment().put("PATH", pathTool + ";" + systemPath());
                env.environment().put("MVNW_REPOURL", server.url());
            });
            assertThat(result.exit()).withFailMessage(result.output()).isZero();
            assertThat(result.output()).contains("HEALTHY_PATH_MAVEN", "SUCCESS");
            assertThat(server.downloads.get()).isZero();
        }
    }

    @Test
    void windowsPathMavenCanLocateItsOwnInstallationDirectory() throws Exception {
        windowsOnly();
        var module = module("path bootstrap", false);
        var pathTool = Files.createDirectories(workspace.resolve("Maven installation/bin"));
        Files.writeString(pathTool.resolve("maven-home.marker"), "installation");
        Files.writeString(pathTool.resolve("mvn.cmd"), MAVEN_CMD.replace("@echo off\r\n", """
                @echo off
                if not exist "%~dp0maven-home.marker" (
                  echo MAVEN_BOOTSTRAP_DIRECTORY_MISSING
                  exit /b 19
                )
                """.replace("\n", "\r\n")));
        var result = run(module, false, env -> env.environment().put("PATH", pathTool + ";" + systemPath()));
        assertThat(result.exit()).withFailMessage(result.output()).isZero();
        assertThat(result.output()).contains("TEST_MAVEN", "SUCCESS").doesNotContain("MAVEN_BOOTSTRAP_DIRECTORY_MISSING");
    }

    @Test
    void windowsSkipsBrokenLocalMavenAndUsesAnotherWorkingLocalVersion() throws Exception {
        windowsOnly();
        var module = module("multiple local tools", false);
        var broken = Files.createDirectories(module.resolve(".tools/apache-maven-0-broken/bin"));
        Files.writeString(broken.resolve("mvn.cmd"), "@echo off\r\nexit /b 8\r\n");
        var healthy = Files.createDirectories(module.resolve(".tools/apache-maven-1-good/bin"));
        Files.writeString(healthy.resolve("mvn.cmd"), MAVEN_CMD.replace("TEST_MAVEN", "HEALTHY_LOCAL_MAVEN"));
        try (var server = new MavenDistributionServer(true)) {
            var result = run(module, false, env -> env.environment().put("MVNW_REPOURL", server.url()));
            assertThat(result.exit()).withFailMessage(result.output()).isZero();
            assertThat(result.output()).contains("HEALTHY_LOCAL_MAVEN", "SUCCESS");
            assertThat(server.downloads.get()).isZero();
        }
    }

    @Test
    void windowsPrefersProjectWrapperEvenWhenPathMavenIsAvailable() throws Exception {
        windowsOnly();
        var module = module("project wrapper", false);
        Files.createDirectories(module.resolve(".mvn/wrapper"));
        Files.writeString(module.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=unused\n");
        Files.writeString(module.resolve("mvnw.cmd"), MAVEN_CMD.replace("TEST_MAVEN", "PROJECT_WRAPPER"));
        var pathTool = Files.createDirectories(workspace.resolve("path-tool"));
        Files.writeString(pathTool.resolve("mvn.cmd"), MAVEN_CMD.replace("TEST_MAVEN", "PATH_MAVEN"));
        var result = run(module, false, env -> env.environment().put("PATH", pathTool + ";" + systemPath()));
        assertThat(result.exit()).withFailMessage(result.output()).isZero();
        assertThat(result.output()).contains("PROJECT_WRAPPER").doesNotContain("PATH_MAVEN");
    }

    @Test
    void windowsUsesAncestorWrapperForMavenModule() throws Exception {
        windowsOnly();
        var module = module("parent/child module", false);
        var parent = module.getParent();
        Files.writeString(parent.resolve("pom.xml"), "<project/>");
        Files.createDirectories(parent.resolve(".mvn/wrapper"));
        Files.writeString(parent.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=unused\n");
        Files.writeString(parent.resolve("mvnw.cmd"), MAVEN_CMD.replace("TEST_MAVEN", "PARENT_WRAPPER"));
        var result = run(module, false, env -> { });
        assertThat(result.exit()).withFailMessage(result.output()).isZero();
        assertThat(result.output()).contains("PARENT_WRAPPER", "SUCCESS");
        assertThat(module.resolve("target/site/jacoco/jacoco.xml")).exists();
    }

    @Test
    void windowsReportsDownloadFailureWithoutPretendingBuildRan() throws Exception {
        windowsOnly();
        var module = module("download failure", false);
        try (var server = new MavenDistributionServer(false)) {
            var result = run(module, false, env -> env.environment().put("MVNW_REPOURL", server.url()));
            assertThat(result.exit()).isNotZero();
            assertThat(result.output()).contains("[GreyTest] ERROR:", "Maven", "network", ".tools")
                    .doesNotContain("TEST_MAVEN", "Running tests and generating");
            assertThat(module.resolve("target/site/jacoco/jacoco.xml")).doesNotExist();
        }
    }

    @Test
    void windowsReportsInvalidJdkBeforeAttemptingDownload() throws Exception {
        windowsOnly();
        var module = module("missing jdk", false);
        try (var server = new MavenDistributionServer(true)) {
            var result = run(module, false, env -> {
                env.environment().put("JAVA_HOME", workspace.resolve("missing-jdk").toString());
                env.environment().put("MVNW_REPOURL", server.url());
            });
            assertThat(result.exit()).isNotZero();
            assertThat(result.output()).contains("[GreyTest] ERROR:", "JDK", "JAVA_HOME");
            assertThat(server.downloads.get()).isZero();
        }
    }

    @Test
    void windowsDoesNotSwitchVersionWhenProjectWrapperFails() throws Exception {
        windowsOnly();
        var module = module("broken project wrapper", false);
        Files.createDirectories(module.resolve(".mvn/wrapper"));
        Files.writeString(module.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=unused\n");
        Files.writeString(module.resolve("mvnw.cmd"), "@echo off\r\necho PROJECT_WRAPPER_FAILED\r\nexit /b 7\r\n");
        try (var server = new MavenDistributionServer(true)) {
            var result = run(module, false, env -> env.environment().put("MVNW_REPOURL", server.url()));
            assertThat(result.exit()).isNotZero();
            assertThat(result.output()).contains("[GreyTest] ERROR:", "wrapper");
            assertThat(server.downloads.get()).isZero();
        }
    }

    @Test
    void windowsRunsGradleWrapperWithoutInstallingMaven() throws Exception {
        windowsOnly();
        var module = module("gradle module", true);
        Files.writeString(module.resolve("gradlew.bat"), """
                @echo off
                echo PROJECT_GRADLE
                if not exist build\\reports\\jacoco\\test mkdir build\\reports\\jacoco\\test
                echo ^<report/^>>build\\reports\\jacoco\\test\\jacocoTestReport.xml
                exit /b 0
                """.replace("\n", "\r\n"));
        try (var server = new MavenDistributionServer(true)) {
            var result = run(module, false, env -> env.environment().put("MVNW_REPOURL", server.url()));
            assertThat(result.exit()).withFailMessage(result.output()).isZero();
            assertThat(result.output()).contains("PROJECT_GRADLE", "SUCCESS");
            assertThat(server.downloads.get()).isZero();
        }
    }

    @Test
    void windowsReportsMissingGradleWithSetupInstructionsWithoutDownloadingMaven() throws Exception {
        windowsOnly();
        var module = module("missing gradle", true);
        try (var server = new MavenDistributionServer(true)) {
            var result = run(module, false, env -> env.environment().put("MVNW_REPOURL", server.url()));
            assertThat(result.exit()).isNotZero();
            assertThat(result.output()).contains("[GreyTest] ERROR:", "Gradle", "gradle/wrapper", "PATH");
            assertThat(server.downloads.get()).isZero();
        }
    }

    @Test
    void unixDownloadsBundledMavenWithoutPathMaven() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("C:/Program Files/Git/bin/bash.exe")));
        var module = module("unix module", false);
        try (var server = new MavenDistributionServer(true)) {
            var result = run(module, true, env -> env.environment().put("MVNW_REPOURL", server.url()));
            assertThat(result.exit()).withFailMessage(result.output()).isZero();
            assertThat(result.output()).contains("TEST_MAVEN", "SUCCESS");
            assertThat(server.downloads.get()).isEqualTo(1);
        }
    }

    private Path module(String name, boolean gradle) throws Exception {
        Path module = Files.createDirectories(workspace.resolve(name));
        var file = new UnitTestFileDto("src/test/java/SampleTest.java", "SampleTest", "", 1,
                List.of("TC-001"), "class SampleTest {}");
        try (var zip = new ZipInputStream(new ByteArrayInputStream(service.createCoverageArchive(List.of(file))), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path output = module.resolve(entry.getName());
                Files.createDirectories(output.getParent());
                Files.write(output, zip.readAllBytes());
            }
        }
        Files.writeString(module.resolve(gradle ? "build.gradle" : "pom.xml"), gradle ? "" : "<project/>");
        return module;
    }

    private Result run(Path module, boolean unix, Consumer<ProcessBuilder> configure) throws Exception {
        var builder = unix
                ? new ProcessBuilder("C:/Program Files/Git/bin/bash.exe", "-c", "export PATH=/usr/bin:/mingw64/bin:/bin; sh ./run-greytest-coverage.sh")
                : new ProcessBuilder("cmd.exe", "/d", "/c", "run-greytest-coverage.cmd");
        builder.directory(module.toFile()).redirectErrorStream(true);
        builder.environment().remove("GREYTEST_JUNCTION_RUN");
        builder.environment().remove("MAVEN_HOME");
        builder.environment().put("MAVEN_USER_HOME", workspace.resolve("isolated maven cache").toString());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home").replace('\\', '/'));
        builder.environment().put("PATH", systemPath());
        configure.accept(builder);
        Path output = Files.createTempFile(workspace, "runner-", ".log");
        var process = builder.redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).withFailMessage(Files.readString(output)).isTrue();
            return new Result(process.exitValue(), Files.readString(output));
        } finally { process.destroyForcibly(); }
    }

    private String systemPath() {
        return System.getenv("SystemRoot") + "\\System32;" + System.getenv("SystemRoot") + "\\System32\\WindowsPowerShell\\v1.0";
    }

    private void windowsOnly() {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
    }

    private record Result(int exit, String output) { }

    private static class MavenDistributionServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger downloads = new AtomicInteger();

        MavenDistributionServer(boolean available) throws Exception {
            var bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes)) {
                for (String platform : List.of("cmd", "sh")) {
                    zip.putNextEntry(new ZipEntry("apache-maven-3.9.14/bin/mvn" + (platform.equals("cmd") ? ".cmd" : "")));
                    zip.write((platform.equals("cmd") ? MAVEN_CMD : MAVEN_SH).getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                downloads.incrementAndGet();
                byte[] body = bytes.toByteArray();
                exchange.sendResponseHeaders(available ? 200 : 503, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        public void close() { server.stop(0); }
    }
}
