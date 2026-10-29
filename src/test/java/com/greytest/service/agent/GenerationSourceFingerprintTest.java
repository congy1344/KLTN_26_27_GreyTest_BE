package com.greytest.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.dto.agent.GenerationContextDtos.ClassContextDto;

class GenerationSourceFingerprintTest {
    @TempDir Path directory;

    @Test
    void hashesActualFileAndNormalizesLineEndingsAndBom() throws Exception {
        Path source = directory.resolve("module/src/main/java/Sample.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "\uFEFFclass Sample {\r\n int value = 1;\r\n}\r\n");
        String captured = GenerationSourceFingerprint.capture(directory,
                List.of(owner("module/src/main/java/Sample.java", "class Sample { int value = 1; }")));
        assertThat(captured).contains(GenerationSourceFingerprint.hash("class Sample {\n int value = 1;\n}\n"));
        Files.writeString(source, "class Sample {\n int value = 2;\n}\n");
        assertThatThrownBy(() -> GenerationSourceFingerprint.capture(directory,
                List.of(owner("module/src/main/java/Sample.java", "class Sample { int value = 1; }"))))
                .hasMessageContaining("Source version differs from analysis");
    }

    @Test
    void allowsAnalysisToIsolateOneTopLevelClass() throws Exception {
        Files.writeString(directory.resolve("Sample.java"), "class Sample { class Nested {} } class Other {}");
        assertThat(GenerationSourceFingerprint.capture(directory,
                List.of(owner("Sample.java", "class Sample { class Nested {} }"))))
                .contains("GREYTEST_SOURCE_V1");
    }

    @Test
    void refusesUnsafeSourcePathsAndConflictingVersions() {
        assertThatThrownBy(() -> GenerationSourceFingerprint.capture(directory,
                List.of(owner("../outside.java", "class Sample {}")))).hasMessageContaining("Unsafe");
        assertThatThrownBy(() -> GenerationSourceFingerprint.capture(directory,
                List.of(owner("C:\\outside.java", "class Sample {}")))).hasMessageContaining("Unsafe");
        String one = "// GREYTEST_SOURCE_V1 " + "a".repeat(64) + " U2FtcGxlLmphdmE=\nclass Test {}";
        String two = "// GREYTEST_SOURCE_V1 " + "b".repeat(64) + " U2FtcGxlLmphdmE=\nclass Test {}";
        assertThatThrownBy(() -> GenerationSourceFingerprint.aggregate(List.of(one, two)))
                .hasMessageContaining("Conflicting source versions");
    }

    @Test
    void keepsLegacyRecordsUnverifiedInsteadOfInventingHistoricalHashes() {
        assertThat(GenerationSourceFingerprint.aggregate(List.of("class Test {}")))
                .isEqualTo("// GREYTEST_SOURCE_UNVERIFIED\n");
        assertThat(GenerationSourceFingerprint.capture(null, List.of()))
                .isEqualTo("// GREYTEST_SOURCE_UNVERIFIED\n");
    }

    @Test
    void doesNotTreatTextBlockContentsAsSourceMetadataOrChangeTestData() {
        String metadataLookingText = "// GREYTEST_SOURCE_V1 " + "a".repeat(64) + " U2FtcGxlLmphdmE=";
        String source = "class Test { String data = \"\"\"\n" + metadataLookingText + "\n\"\"\"; }";

        assertThat(GenerationSourceFingerprint.aggregate(List.of(source)))
                .isEqualTo("// GREYTEST_SOURCE_UNVERIFIED\n");
        assertThat(GenerationSourceFingerprint.attach(source, "// GREYTEST_SOURCE_UNVERIFIED\n"))
                .contains(metadataLookingText);
    }

    private ClassContextDto owner(String path, String source) {
        return new ClassContextDto(1L, "", "Sample", "Sample", "SERVICE", path,
                source, List.of(), List.of(), source);
    }
}
