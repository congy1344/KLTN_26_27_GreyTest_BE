package com.greytest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.dto.UnitTestFileDto;
import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;
import com.greytest.service.agent.GenerationSourceFingerprint;

class GenerationSourceArchiveTest {
    @TempDir static Path compilerDirectory;
    @TempDir Path workspace;
    private static String javaCommand;
    private static String javacCommand;
    private static final String SOURCE = "class Sample { String label = \"kiểm thử\"; int value = 1; }\n";

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void rootlessExportRejectsMixedSourceRootsEvenWhenHashesMatch(boolean changed) {
        String path = "backend/appointment-service/src/main/java/Sample.java";
        String old = metadata("hospital-management-demo-main/" + path, SOURCE);
        String current = metadata(path, changed ? SOURCE.replace("value = 1", "value = 2") : SOURCE);
        var test = new UnitTestFileDto("src/test/java/SampleTest.java", "SampleTest", "", 1, List.of(),
                old + current + "class SampleTest {}\n");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new UnitTestFileService().createCoverageArchive(List.of(test)))
                .isInstanceOf(com.greytest.exception.StorageException.class).hasMessageContaining("Mixed generation source roots");
    }

    @Test
    void alignedExportRejectsChangedHistoricalHashInsteadOfDroppingOldMetadata() throws Exception {
        Path root = Files.createDirectories(workspace.resolve("source"));
        String path = "backend/appointment-service/src/main/java/Sample.java";
        Files.createDirectories(root.resolve(path).getParent());
        Files.writeString(root.resolve(path), SOURCE.replace("value = 1", "value = 2"));
        String old = metadata("hospital-management-demo-main/" + path, SOURCE);
        String current = metadata(path, SOURCE.replace("value = 1", "value = 2"));
        var test = new UnitTestFileDto("src/test/java/SampleTest.java", "SampleTest", "", 1, List.of(),
                old + current + "class SampleTest {}\n");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new UnitTestFileService().createCoverageArchive(List.of(test), root))
                .isInstanceOf(com.greytest.exception.StorageException.class).hasMessageContaining("Source changed");
    }

    @Test
    void mixedWrapperExportKeepsTenUniqueFilesIncludingSiblingModule() throws Exception {
        Path root = Files.createDirectories(workspace.resolve("source"));
        StringBuilder old = new StringBuilder(); StringBuilder current = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            String path = "backend/" + (i == 9 ? "common-lib" : "appointment-service") + "/src/main/java/Source" + i + ".java";
            String source = "class Source" + i + " {}\n";
            Files.createDirectories(root.resolve(path).getParent()); Files.writeString(root.resolve(path), source);
            old.append(metadata("hospital-management-demo-main/" + path, source));
            current.append(metadata(path, source));
        }
        var test = new UnitTestFileDto("src/test/java/SampleTest.java", "SampleTest", "", 1, List.of(),
                old.toString() + current + "class SampleTest {}\n");
        Path module = root.resolve("backend/appointment-service");
        extractArchive(module, new UnitTestFileService().createCoverageArchive(List.of(test), root));
        String manifest = Files.readString(module.resolve("greytest-source-version.txt"));
        assertThat(manifest.lines()).hasSize(10);
        assertThat(manifest).isEqualTo(GenerationSourceFingerprint.manifest(current.toString()));
        assertThat(Files.readString(module.resolve(test.filePath()))).startsWith(current.toString());
        Result result = verify(module);
        assertThat(result.exit).withFailMessage(result.output).isZero();
        assertThat(result.output).contains("Source version verified for 10 generation files");
    }

    private static String metadata(String path, String source) {
        return GenerationSourceFingerprint.PREFIX + GenerationSourceFingerprint.hash(source) + " "
                + java.util.Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8)) + "\n";
    }

    @BeforeAll
    static void compileExportedJava8Verifier() throws Exception {
        String suffix = System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "";
        javaCommand = Path.of(System.getProperty("java.home"), "bin", "java" + suffix).toString();
        javacCommand = Path.of(System.getProperty("java.home"), "bin", "javac" + suffix).toString();
        try (var helper = GenerationSourceArchiveTest.class.getResourceAsStream("/coverage/GreyTestSourceCheck.java")) {
            assertThat(helper).isNotNull();
            Files.copy(helper, compilerDirectory.resolve("GreyTestSourceCheck.java"));
        }
        Result compilation = run(compilerDirectory, javacCommand, "-source", "8", "-target", "8", "-encoding", "UTF-8",
                "-d", compilerDirectory.toString(), "GreyTestSourceCheck.java");
        assertThat(compilation.exit).withFailMessage(compilation.output).isZero();
    }

    @Test
    void verifiesOriginalSnapshotAfterModuleExtractionAndCrLfConversion() throws Exception {
        Path module = archiveWithSource("uploaded/project-wrapper/module/", SOURCE);
        Files.writeString(module.resolve("src/main/java/Sample.java"), "\uFEFF" + SOURCE.replace("\n", "\r\n"));

        Result result = verify(module);

        assertThat(result.exit).withFailMessage(result.output).isZero();
        assertThat(result.output).contains("Source version verified");
    }

    @Test
    void exportsSixCanonicalSourcesAndAlignedHeadersForMixedUpdateRoots() throws Exception {
        Path current = Files.createDirectories(workspace.resolve("current"));
        Path previous = Files.createDirectories(workspace.resolve("previous"));
        String prefix = "backend/patient-service/";
        String wrapper = "hospital-management-demo-main/";
        var currentOwners = new java.util.ArrayList<ClassContextDto>();
        var previousOwners = new java.util.ArrayList<ClassContextDto>();
        for (int i = 0; i < 6; i++) {
            String relative = prefix + "src/main/java/Source" + i + ".java";
            String source = "class Source" + i + " {}\n";
            for (Path file : List.of(current.resolve(relative), previous.resolve(wrapper + relative))) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, source);
            }
            currentOwners.add(new ClassContextDto((long) i, "", "Source" + i, "Source" + i,
                    "SERVICE", relative, null, List.of(), List.of()));
            previousOwners.add(new ClassContextDto((long) i, "", "Source" + i, "Source" + i,
                    "SERVICE", wrapper + relative, null, List.of(), List.of()));
        }
        String currentMetadata = GenerationSourceFingerprint.capture(current, currentOwners);
        String previousMetadata = GenerationSourceFingerprint.capture(previous, previousOwners);
        var tests = new java.util.ArrayList<UnitTestFileDto>();
        for (int id : List.of(2716, 2720, 2724)) {
            String name = "PatientServiceTest_GreyTest_" + id;
            String metadata = id == 2716 ? currentMetadata : previousMetadata;
            tests.add(new UnitTestFileDto("src/test/java/" + name + ".java", name, "", 1, List.of(),
                    metadata + "class " + name + " { @org.junit.jupiter.api.Test void example() {} }"));
        }
        Path module = current.resolve(prefix);
        extractArchive(module, new UnitTestFileService().createCoverageArchive(tests, current));

        String manifest = Files.readString(module.resolve("greytest-source-version.txt"));
        assertThat(manifest).doesNotStartWith("\uFEFF");
        assertThat(manifest.lines()).hasSize(6);
        assertThat(manifest).isEqualTo(GenerationSourceFingerprint.manifest(currentMetadata));
        for (var test : tests) {
            assertThat(Files.readString(module.resolve(test.filePath()))).startsWith(currentMetadata);
        }
        Result result = verify(module);
        assertThat(result.exit).withFailMessage(result.output).isZero();
        assertThat(result.output).contains("Source version verified for 6 generation files");
    }

    @Test
    void rejectsChangedSourceWithoutLookingForAnotherMatchingBasename() throws Exception {
        Path module = archiveWithSource("wrapper/correct-module/", SOURCE);
        Files.writeString(module.resolve("src/main/java/Sample.java"), SOURCE.replace("value = 1", "value = 2"));
        Path unrelated = module.resolve("other-module/src/main/java/Sample.java");
        Files.createDirectories(unrelated.getParent());
        Files.writeString(unrelated, SOURCE);

        Result result = verify(module);

        assertThat(result.exit).isNotZero();
        assertThat(result.output).contains("Source changed: wrapper/correct-module/src/main/java/Sample.java",
                "re-analyze and regenerate");
    }

    @Test
    void refusesAmbiguousRecordedAndModuleRelativeSourceLayouts() throws Exception {
        Path module = archiveWithSource("wrapper/module/", SOURCE);
        Path unusedCopy = module.resolve("wrapper/module/src/main/java/Sample.java");
        Files.createDirectories(unusedCopy.getParent());
        Files.writeString(unusedCopy, SOURCE);
        Files.writeString(module.resolve("src/main/java/Sample.java"), SOURCE.replace("value = 1", "value = 2"));

        Result result = verify(module);

        assertThat(result.exit).isNotZero();
        assertThat(result.output).contains("Ambiguous source layout");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"appointment-service", "apps/appointment-service"})
    void verifiesSelectedModuleAndSiblingDependencyWithCompleteManifest(String modulePath) throws Exception {
        var archive = archiveAcrossModules(modulePath);
        String manifest = Files.readString(archive.module.resolve("greytest-source-version.txt"));

        Result result = verify(archive.module);

        assertThat(result.exit).withFailMessage(result.output).isZero();
        assertThat(result.output).contains("Source version verified for 2 generation files");
        assertThat(Files.readString(archive.module.resolve("greytest-source-version.txt"))).isEqualTo(manifest);
    }

    @Test
    void rejectsChangedSiblingDependencyEvenWhenSelectedModuleIsUnchanged() throws Exception {
        var archive = archiveAcrossModules("appointment-service");
        Files.writeString(archive.dependency, "class Dependency { int changed; }");

        Result result = verify(archive.module);

        assertThat(result.exit).isNotZero();
        assertThat(result.output).contains("Source changed: uploaded/project/backend/common-lib/src/main/java/Dependency.java");
    }

    @Test
    void rejectsMissingSiblingDependencyInsteadOfUsingMatchingBasenameInSelectedModule() throws Exception {
        var archive = archiveAcrossModules("appointment-service");
        Files.delete(archive.dependency);
        Files.writeString(archive.module.resolve("src/main/java/Dependency.java"), "class Dependency {}\n");

        Result result = verify(archive.module);

        assertThat(result.exit).isNotZero();
        assertThat(result.output).contains("Generation source files are missing");
    }

    @Test
    void alignmentRefusesToHideMissingSiblingWithACopyInTheActiveModule() throws Exception {
        var archive = archiveAcrossModules("appointment-service");
        Files.delete(archive.dependency);
        Files.writeString(archive.module.resolve("src/main/java/Dependency.java"), "class Dependency {}\n");
        var test = new UnitTestFileDto("src/test/java/SampleTest.java", "SampleTest", "", 1, List.of("TC-001"),
                Files.readString(archive.module.resolve("src/test/java/SampleTest.java")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new UnitTestFileService()
                .createCoverageArchive(List.of(test), archive.module))
                .isInstanceOf(com.greytest.exception.StorageException.class).hasMessageContaining("module");
    }

    @Test
    void rejectsAmbiguousFullAndSiblingLayoutsForMultipleModules() throws Exception {
        var archive = archiveAcrossModules("appointment-service");
        copyRecordedLayout(archive);

        Result result = verify(archive.module);

        assertThat(result.exit).isNotZero();
        assertThat(result.output).contains("Ambiguous source layout");
    }

    @Test
    void doesNotValidateAStaleNestedCopyWhenActiveModuleLayoutIsIncomplete() throws Exception {
        var archive = archiveAcrossModules("appointment-service");
        copyRecordedLayout(archive);
        Files.writeString(archive.module.resolve("src/main/java/Sample.java"), SOURCE.replace("value = 1", "value = 2"));
        Files.delete(archive.dependency);

        Result result = verify(archive.module);

        assertThat(result.exit).isNotZero();
        assertThat(result.output).contains("Generation source files are missing");
    }

    @Test
    void rejectsSiblingModuleJunctionThatEscapesTheRecordedProject() throws Exception {
        var archive = archiveAcrossModules("appointment-service");
        Path outside = Files.createDirectories(workspace.resolve("outside-project"));
        Files.writeString(outside.resolve("Dependency.java"), "class Dependency {}\n");
        Path link = archive.dependency.getParent();
        Files.delete(archive.dependency);
        Files.delete(link);
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Result junction = run(workspace, "cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), outside.toString());
            assertThat(junction.exit).withFailMessage(junction.output).isZero();
        } else {
            Files.createSymbolicLink(link, outside);
        }

        Result result = verify(archive.module);

        assertThat(result.exit).isNotZero();
        assertThat(result.output).contains("Unsafe");
    }

    private void copyRecordedLayout(ModuleArchive archive) throws Exception {
        for (String moduleName : List.of("appointment-service", "common-lib")) {
            String name = moduleName.equals("common-lib") ? "Dependency.java" : "Sample.java";
            Path copy = archive.module.resolve("uploaded/project/backend/" + moduleName + "/src/main/java/" + name);
            Files.createDirectories(copy.getParent());
            Files.copy(archive.project.resolve(moduleName + "/src/main/java/" + name), copy);
        }
    }

    @Test
    void rejectsTraversalMetadataBeforeReadingOutsideTheModule() throws Exception {
        Path module = archiveWithSource("", SOURCE);
        Files.writeString(module.resolve("greytest-source-version.txt"), "a".repeat(64) + " "
                + java.util.Base64.getEncoder().encodeToString("../outside.java".getBytes(StandardCharsets.UTF_8)) + "\n");

        Result result = verify(module);

        assertThat(result.exit).isNotZero();
        assertThat(result.output).contains("Unsafe generation source path");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void coverageScriptStopsBeforeRunningAnyBuildWhenSourceChanged(boolean windows) throws Exception {
        String[] command = coverageCommand(windows);
        Path module = archiveWithSource("wrapper/module/", SOURCE);
        Files.writeString(module.resolve("src/main/java/Sample.java"), SOURCE.replace("value = 1", "value = 2"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Files.writeString(module.getParent().resolve("pom.xml"), "<project/>");
        Path marker = module.resolve("BUILD_MUST_NOT_RUN");
        if (windows) Files.writeString(module.resolve("mvnw.cmd"), "@echo off\r\necho build>BUILD_MUST_NOT_RUN\r\nexit /b 0\r\n");
        else Files.writeString(module.resolve("mvnw"), "#!/bin/sh\necho build > BUILD_MUST_NOT_RUN\nexit 0\n");

        Result result = run(module, command);

        assertThat(result.exit).withFailMessage(result.output).isNotZero();
        assertThat(result.output).contains("Source changed");
        assertThat(marker).doesNotExist();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void coverageScriptsKeepDollarInTestSelectorsAndPassUserArguments(boolean windows) throws Exception {
        String[] command = coverageCommand(windows);
        Path module = archiveWithSource("wrapper/$HOME & echo injected/module/", SOURCE, "$SampleTest");
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Files.writeString(module.resolve("mvnw.cmd"), """
                @echo off
                echo %*>>build-arguments.txt
                if not exist target\\test-classes mkdir target\\test-classes
                echo compiled>target\\test-classes\\SampleTest.class
                if not exist target\\site\\jacoco mkdir target\\site\\jacoco
                echo report>target\\site\\jacoco\\jacoco.xml
                exit /b 0
                """.replace("\n", "\r\n"));
        Files.writeString(module.resolve("mvnw"), """
                #!/bin/sh
                printf '%s\\n' "$@" >> build-arguments.txt
                mkdir -p target/test-classes target/site/jacoco
                echo compiled > target/test-classes/SampleTest.class
                echo report > target/site/jacoco/jacoco.xml
                exit 0
                """);
        Files.createDirectories(module.resolve(".mvn/wrapper"));
        Files.writeString(module.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=unused\n");
        command = java.util.Arrays.copyOf(command, command.length + 1);
        command[command.length - 1] = "-Ptest-profile";

        Result result = run(module, command);

        assertThat(result.exit).withFailMessage(result.output).isZero();
        assertThat(result.output).contains("Source version verified", "SUCCESS");
        assertThat(Files.readString(module.resolve("build-arguments.txt")))
                .contains("-Dtest=$SampleTest", "-Ptest-profile");
        assertThat(module.resolve("injected")).doesNotExist();
    }

    private String[] coverageCommand(boolean windows) {
        boolean onWindows = System.getProperty("os.name").toLowerCase().contains("win");
        org.junit.jupiter.api.Assumptions.assumeTrue(!windows || onWindows);
        if (windows) return new String[] {"cmd.exe", "/d", "/c", "run-greytest-coverage.cmd"};
        String shell = onWindows ? "C:/Program Files/Git/bin/sh.exe" : "sh";
        org.junit.jupiter.api.Assumptions.assumeTrue(!onWindows || Files.isRegularFile(Path.of(shell)));
        return new String[] {shell, "./run-greytest-coverage.sh"};
    }

    @Test
    void pathsRemainDataEvenWhenTheyContainShellMetacharacters() throws Exception {
        Path module = archiveWithSource("wrapper/$HOME & echo injected/module/", SOURCE);
        Result result = verify(module);
        assertThat(result.exit).withFailMessage(result.output).isZero();
        assertThat(module.resolve("injected")).doesNotExist();
    }

    private Path archiveWithSource(String prefix, String source) throws Exception {
        return archiveWithSource(prefix, source, "SampleTest");
    }

    private Path archiveWithSource(String prefix, String source, String testClassName) throws Exception {
        Path snapshot = Files.createDirectories(workspace.resolve("snapshot"));
        String relative = prefix + "src/main/java/Sample.java";
        Path original = snapshot.resolve(relative);
        Files.createDirectories(original.getParent());
        Files.writeString(original, source);
        var owner = new ClassContextDto(1L, "", "Sample", "Sample", "SERVICE", relative,
                source, List.of(), List.of());
        String metadata = GenerationSourceFingerprint.capture(snapshot, List.of(owner));
        var test = new UnitTestFileDto("src/test/java/" + testClassName + ".java", testClassName, "", 1,
                List.of("TC-001"), metadata + "class " + testClassName + " {}\n");
        byte[] zip = new UnitTestFileService().createCoverageArchive(List.of(test));
        Path module = Files.createDirectories(workspace.resolve("module"));
        extractArchive(module, zip);
        Files.createDirectories(module.resolve("src/main/java"));
        Files.writeString(module.resolve("src/main/java/Sample.java"), source);
        return module;
    }

    private ModuleArchive archiveAcrossModules(String selectedModule) throws Exception {
        Path snapshot = Files.createDirectories(workspace.resolve("snapshot"));
        Path project = Files.createDirectories(workspace.resolve("checkout"));
        var classes = new java.util.ArrayList<ClassContextDto>();
        for (String moduleName : List.of(selectedModule, "common-lib")) {
            String className = moduleName.equals("common-lib") ? "Dependency" : "Sample";
            String source = className.equals("Sample") ? SOURCE : "class Dependency {}\n";
            String relative = moduleName + "/src/main/java/" + className + ".java";
            Path recorded = snapshot.resolve("uploaded/project/backend/" + relative);
            Path local = project.resolve(relative);
            Files.createDirectories(recorded.getParent());
            Files.createDirectories(local.getParent());
            Files.writeString(recorded, source);
            Files.writeString(local, source);
            classes.add(new ClassContextDto((long) classes.size() + 1, "", className, className, "CLASS",
                    "uploaded/project/backend/" + relative, source, List.of(), List.of()));
        }
        String metadata = GenerationSourceFingerprint.capture(snapshot, classes);
        var test = new UnitTestFileDto("src/test/java/SampleTest.java", "SampleTest", "", 1,
                List.of("TC-001"), metadata + "class SampleTest {}\n");
        Path module = project.resolve(selectedModule);
        extractArchive(module, new UnitTestFileService().createCoverageArchive(List.of(test)));
        return new ModuleArchive(project, module, project.resolve("common-lib/src/main/java/Dependency.java"));
    }

    private void extractArchive(Path module, byte[] zip) throws Exception {
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path path = module.resolve(entry.getName());
                Files.createDirectories(path.getParent());
                Files.write(path, input.readAllBytes());
            }
        }
    }

    private Result verify(Path module) throws Exception {
        return run(module, javaCommand, "-cp", compilerDirectory.toString(), "GreyTestSourceCheck");
    }

    private static Result run(Path directory, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("PATH", Path.of(System.getProperty("java.home"), "bin")
                + java.io.File.pathSeparator + builder.environment().getOrDefault("PATH", ""));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    private record Result(int exit, String output) {}
    private record ModuleArchive(Path project, Path module, Path dependency) {}
}
