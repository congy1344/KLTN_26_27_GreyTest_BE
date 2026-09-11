package com.greytest.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.greytest.exception.InvalidProjectSourceException;
import com.greytest.exception.StorageException;
import com.greytest.service.storage.SourceRevisionStorageService.SnapshotResult;

class SourceRevisionStorageServiceTest {

    @Test
    void storesZipSnapshotAndDetectsLogicalRoot(@TempDir Path tempDir) throws IOException {
        SourceRevisionStorageService service = new SourceRevisionStorageService(tempDir.toString());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("my-app-master/"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("my-app-master/pom.xml"));
            zos.write("<project></project>".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("my-app-master/src/main/java/App.java"));
            zos.write("class App {}".getBytes());
            zos.closeEntry();
        }

        MockMultipartFile file = new MockMultipartFile("file", "project.zip", "application/zip", baos.toByteArray());

        SnapshotResult result = service.storeZipSnapshot(file);

        assertThat(result).isNotNull();
        assertThat(result.storageDir()).exists();
        assertThat(result.logicalRoot()).exists();
        assertThat(result.logicalRoot().resolve("pom.xml")).exists();
        assertThat(result.contentHash()).isNotBlank();

        // Cleanup
        service.delete(result.storageDir());
        assertThat(result.storageDir()).doesNotExist();
    }

    @Test
    void ignoresGeneratedDirectoriesWhenStoringZipSnapshot(@TempDir Path tempDir) throws IOException {
        SourceRevisionStorageService service = new SourceRevisionStorageService(tempDir.toString());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("demo/pom.xml"));
            zos.write("<project/>".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("demo/src/main/java/App.java"));
            zos.write("class App {}".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("demo/target/classes/App.class"));
            zos.write(new byte[] {1, 2, 3});
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("demo/.git/config"));
            zos.write("[core]".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("demo/generated-sources/Generated.java"));
            zos.write("generated".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("demo/generated-test-sources/GeneratedTest.java"));
            zos.write("generated".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("demo/out/App.class"));
            zos.write(new byte[] {4, 5, 6});
            zos.closeEntry();
        }

        SnapshotResult result = service.storeZipSnapshot(
                new MockMultipartFile("file", "project.zip", "application/zip", baos.toByteArray()));

        assertThat(result.logicalRoot().resolve("src/main/java/App.java")).exists();
        assertThat(result.logicalRoot().resolve("target")).doesNotExist();
        assertThat(result.logicalRoot().resolve(".git")).doesNotExist();
        assertThat(result.logicalRoot().resolve("generated-sources")).doesNotExist();
        assertThat(result.logicalRoot().resolve("generated-test-sources")).doesNotExist();
        assertThat(result.logicalRoot().resolve("out")).doesNotExist();
    }

    @Test
    void ignoresGeneratedDirectoriesWhenCreatingBaseline(@TempDir Path tempDir) throws IOException {
        Path existing = tempDir.resolve("existing");
        Files.createDirectories(existing.resolve("src/main/java"));
        Files.createDirectories(existing.resolve("target/classes"));
        Files.createDirectories(existing.resolve(".idea"));
        Files.createDirectories(existing.resolve("generated-sources"));
        Files.createDirectories(existing.resolve("generated-test-sources"));
        Files.createDirectories(existing.resolve("out"));
        Files.writeString(existing.resolve("pom.xml"), "<project/>");
        Files.writeString(existing.resolve("src/main/java/App.java"), "class App {}");
        Files.writeString(existing.resolve("target/classes/App.class"), "generated");
        Files.writeString(existing.resolve(".idea/workspace.xml"), "generated");
        Files.writeString(existing.resolve("generated-sources/Generated.java"), "generated");
        Files.writeString(existing.resolve("generated-test-sources/GeneratedTest.java"), "generated");
        Files.writeString(existing.resolve("out/App.class"), "generated");

        SourceRevisionStorageService service = new SourceRevisionStorageService(tempDir.toString());
        SnapshotResult result = service.createBaselineFromExistingDir(existing);

        assertThat(result.logicalRoot().resolve("src/main/java/App.java")).exists();
        assertThat(result.logicalRoot().resolve("target")).doesNotExist();
        assertThat(result.logicalRoot().resolve(".idea")).doesNotExist();
        assertThat(result.logicalRoot().resolve("generated-sources")).doesNotExist();
        assertThat(result.logicalRoot().resolve("generated-test-sources")).doesNotExist();
        assertThat(result.logicalRoot().resolve("out")).doesNotExist();
    }

    @Test
    void ignoresGeneratedDirectoriesWhenComputingContentHash(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source.resolve("src"));
        Files.createDirectories(source.resolve("generated-sources"));
        Files.writeString(source.resolve("pom.xml"), "<project/>");
        Files.writeString(source.resolve("src/App.java"), "class App {}");

        SourceRevisionStorageService service = new SourceRevisionStorageService(tempDir.toString());
        String hashBefore = service.computeContentHash(source);
        Files.writeString(source.resolve("generated-sources/Generated.java"), "generated");

        assertThat(service.computeContentHash(source)).isEqualTo(hashBefore);
    }

    @Test
    void keepsContentHashFramingForDifferentPathAndContentPairs(@TempDir Path tempDir) throws IOException {
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(first.resolve("a"), "bc");
        Files.writeString(second.resolve("ab"), "c");

        SourceRevisionStorageService service = new SourceRevisionStorageService(tempDir.toString());

        assertThat(service.computeContentHash(first)).isNotEqualTo(service.computeContentHash(second));
    }

    @Test
    void rejectsZipSlipPathEvenWhenItUsesAnExcludedDirectoryName(@TempDir Path tempDir) throws IOException {
        SourceRevisionStorageService service = new SourceRevisionStorageService(tempDir.toString());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("demo/pom.xml"));
            zos.write("<project/>".getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("../target/escaped.txt"));
            zos.write("must not escape".getBytes());
            zos.closeEntry();
        }

        assertThatThrownBy(() -> service.storeZipSnapshot(
                new MockMultipartFile("file", "project.zip", "application/zip", baos.toByteArray())))
                .isInstanceOf(InvalidProjectSourceException.class)
                .hasMessageContaining("../target/escaped.txt");
    }

    @Test
    void rejectsZipWithoutBuildFile(@TempDir Path tempDir) throws IOException {
        SourceRevisionStorageService service = new SourceRevisionStorageService(tempDir.toString());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("folder/README.md"));
            zos.write("Hello".getBytes());
            zos.closeEntry();
        }

        MockMultipartFile file = new MockMultipartFile("file", "no_build.zip", "application/zip", baos.toByteArray());

        assertThatThrownBy(() -> service.storeZipSnapshot(file))
                .isInstanceOf(InvalidProjectSourceException.class)
                .hasMessageContaining("Không tìm thấy file build");
    }

    @Test
    void cleansRevisionDirectoryWhenZipIsRejected(@TempDir Path tempDir) throws IOException {
        SourceRevisionStorageService service = new SourceRevisionStorageService(tempDir.toString());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("demo/pom.xml"));
            zos.write("<project/>".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("../escaped.txt"));
            zos.write("must not escape".getBytes());
            zos.closeEntry();
        }

        assertThatThrownBy(() -> service.storeZipSnapshot(
                new MockMultipartFile("file", "project.zip", "application/zip", baos.toByteArray())))
                .isInstanceOf(InvalidProjectSourceException.class);
        try (var revisions = Files.list(tempDir.resolve("revisions"))) {
            assertThat(revisions).isEmpty();
        }
    }

    @Test
    void rejectsZipWithTooManyEntries(@TempDir Path tempDir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (int i = 0; i <= 10_000; i++) {
                zos.putNextEntry(new ZipEntry("demo/file-" + i + ".txt"));
                zos.closeEntry();
            }
        }

        SourceRevisionStorageService service = new SourceRevisionStorageService(tempDir.toString());
        assertThatThrownBy(() -> service.storeZipSnapshot(
                new MockMultipartFile("file", "large-entry-count.zip", "application/zip", baos.toByteArray())))
                .isInstanceOf(InvalidProjectSourceException.class)
                .hasMessageContaining("quá nhiều entry");
        try (var revisions = Files.list(tempDir.resolve("revisions"))) {
            assertThat(revisions).isEmpty();
        }
    }

    @Test
    void rejectsMalformedZipAsInvalidSource(@TempDir Path tempDir) throws IOException {
        SourceRevisionStorageService service = new SourceRevisionStorageService(tempDir.toString());

        assertThatThrownBy(() -> service.storeZipSnapshot(
                new MockMultipartFile("file", "broken.zip", "application/zip", "not a zip".getBytes())))
                .isInstanceOf(InvalidProjectSourceException.class);
        try (var revisions = Files.list(tempDir.resolve("revisions"))) {
            assertThat(revisions).isEmpty();
        }
    }

    @Test
    void rejectsBaselineThatContainsRevisionStorage(@TempDir Path tempDir) {
        SourceRevisionStorageService service = new SourceRevisionStorageService(tempDir.toString());

        assertThatThrownBy(() -> service.createBaselineFromExistingDir(tempDir))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("revisions");
    }

    @Test
    void createsBaselineFromExistingDirectory(@TempDir Path tempDir) throws IOException {
        Path existingDir = tempDir.resolve("existing_project");
        Files.createDirectories(existingDir.resolve("src"));
        Files.writeString(existingDir.resolve("pom.xml"), "<project/>");
        Files.writeString(existingDir.resolve("src/Test.java"), "class Test {}");

        SourceRevisionStorageService service = new SourceRevisionStorageService(tempDir.toString());
        SnapshotResult baseline = service.createBaselineFromExistingDir(existingDir);

        assertThat(baseline.storageDir()).exists();
        assertThat(baseline.logicalRoot().resolve("pom.xml")).exists();
        assertThat(baseline.contentHash()).isNotBlank();

        // Second calculation should yield the identical hash
        String hash2 = service.computeContentHash(baseline.logicalRoot());
        assertThat(hash2).isEqualTo(baseline.contentHash());
    }
}
