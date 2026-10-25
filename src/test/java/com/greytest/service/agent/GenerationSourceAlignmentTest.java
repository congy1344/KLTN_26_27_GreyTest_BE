package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationSourceAlignmentTest {
    @TempDir Path directory;
    private static final String PATH = "backend/patient-service/src/main/java/Patient.java";
    private static final String SOURCE = "class Patient {}\n";

    @Test
    void alignsWrapperAliasesAndPreservesHistoricalHash() throws Exception {
        write(PATH, SOURCE);
        var aligned = GenerationSourceFingerprint.alignToSourceRoot(List.of(
                test(PATH, SOURCE), test("hospital-management-demo-main/" + PATH, SOURCE)), directory);
        String metadata = GenerationSourceFingerprint.aggregate(aligned);
        assertThat(GenerationSourceFingerprint.manifest(metadata).lines()).hasSize(1);
        assertThat(aligned).allSatisfy(source -> assertThat(source).startsWith(header(PATH, SOURCE)));
    }

    @Test
    void alignsToAnActualWrapperInsteadOfAssumingTheShortestPathIsCorrect() throws Exception {
        write("hospital-management-demo-main/" + PATH, SOURCE);
        var aligned = GenerationSourceFingerprint.alignToSourceRoot(List.of(test(PATH, SOURCE)), directory);
        assertThat(aligned.get(0)).startsWith(header("hospital-management-demo-main/" + PATH, SOURCE));
    }

    @Test
    void refusesChangedHistoricalSourceInsteadOfReplacingItsHash() throws Exception {
        write(PATH, "class Patient { int changed; }\n");
        assertThatThrownBy(() -> GenerationSourceFingerprint.alignToSourceRoot(
                List.of(test("wrapper/" + PATH, SOURCE)), directory))
                .hasMessageContaining("Source changed", "regenerate");
    }

    @Test
    void refusesAmbiguousTreesEvenWhenBothHashesMatch() throws Exception {
        write(PATH, SOURCE);
        write("nested/" + PATH, SOURCE);
        assertThatThrownBy(() -> GenerationSourceFingerprint.alignToSourceRoot(List.of(test(PATH, SOURCE)), directory))
                .hasMessageContaining("Ambiguous");
    }

    @Test
    void refusesMissingSiblingRatherThanDroppingItFromTheManifest() throws Exception {
        write(PATH, SOURCE);
        assertThatThrownBy(() -> GenerationSourceFingerprint.alignToSourceRoot(List.of(test(PATH, SOURCE),
                test("backend/common-lib/src/main/java/Dependency.java", "class Dependency {}")), directory))
                .hasMessageContaining("missing", "Dependency.java");
    }

    @Test
    void preservesDistinctModulesWithTheSameClassAndContent() throws Exception {
        String other = "backend/other-service/src/main/java/Patient.java";
        write(PATH, SOURCE);
        write(other, SOURCE);
        var aligned = GenerationSourceFingerprint.alignToSourceRoot(
                List.of(test("wrapper/" + PATH, SOURCE), test("wrapper/" + other, SOURCE)), directory);
        assertThat(GenerationSourceFingerprint.manifest(GenerationSourceFingerprint.aggregate(aligned)).lines()).hasSize(2);
    }

    @Test
    void refusesToCollapseRecordedSiblingModulesIntoTheActiveModule() throws Exception {
        write("src/main/java/Patient.java", SOURCE);
        write("src/main/java/Dependency.java", "class Dependency {}\n");
        assertThatThrownBy(() -> GenerationSourceFingerprint.alignToSourceRoot(List.of(test(PATH, SOURCE),
                test("backend/common-lib/src/main/java/Dependency.java", "class Dependency {}\n")), directory))
                .hasMessageContaining("module");
    }

    @Test
    void refusesToSplitOneHistoricalModuleAcrossTwoCurrentModules() throws Exception {
        write("first/src/main/java/Patient.java", SOURCE);
        write("second/src/main/java/Dependency.java", "class Dependency {}\n");
        assertThatThrownBy(() -> GenerationSourceFingerprint.alignToSourceRoot(List.of(
                test("src/main/java/Patient.java", SOURCE),
                test("src/main/java/Dependency.java", "class Dependency {}\n")), directory))
                .hasMessageContaining("module");
    }

    @Test
    void refusesDisjointRootAndWrappedModulesWithoutAliasEvidence() throws Exception {
        write("src/main/java/Patient.java", SOURCE);
        write("src/main/java/Dependency.java", "class Dependency {}\n");
        var aliases = List.of(test("src/main/java/Patient.java", SOURCE),
                test("wrapper/src/main/java/Dependency.java", "class Dependency {}\n"));
        assertThatThrownBy(() -> GenerationSourceFingerprint.alignToSourceRoot(aliases, directory))
                .hasMessageContaining("module");
    }

    @Test
    void allowsDifferentHistoricalWrappersWhenTheActualModuleIdentityIsPreserved() throws Exception {
        write(PATH, SOURCE);
        String dependency = "backend/patient-service/src/main/java/Dependency.java";
        write(dependency, "class Dependency {}\n");
        var aligned = GenerationSourceFingerprint.alignToSourceRoot(List.of(
                test("snapshot-v1/" + PATH, SOURCE),
                test("snapshot-v2/" + dependency, "class Dependency {}\n")), directory);
        assertThat(aligned.get(0)).startsWith(header(PATH, SOURCE));
        assertThat(aligned.get(1)).startsWith(header(dependency, "class Dependency {}\n"));
    }

    @Test
    void allowsRootWrapperAliasesWithOverlappingSourceEvidence() throws Exception {
        String relative = "src/main/java/Patient.java";
        write(relative, SOURCE);
        var aligned = GenerationSourceFingerprint.alignToSourceRoot(List.of(
                test(relative, SOURCE), test("wrapper/" + relative, SOURCE)), directory);
        assertThat(aligned).allSatisfy(source -> assertThat(source).startsWith(header(relative, SOURCE)));
        assertThat(GenerationSourceFingerprint.manifest(GenerationSourceFingerprint.aggregate(aligned)).lines()).hasSize(1);
    }

    private void write(String path, String source) throws Exception {
        Path file = directory.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private String test(String path, String source) {
        return header(path, source) + "class SampleTest {}";
    }

    private String header(String path, String source) {
        return GenerationSourceFingerprint.PREFIX + GenerationSourceFingerprint.hash(source) + " "
                + Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8)) + "\n";
    }
}
