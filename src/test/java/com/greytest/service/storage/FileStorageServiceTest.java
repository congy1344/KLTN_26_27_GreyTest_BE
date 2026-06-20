package com.greytest.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.greytest.exception.InvalidProjectSourceException;

class FileStorageServiceTest {

    @TempDir
    Path tmp;

    @Test
    void extractsZipContent() throws IOException {
        FileStorageService service = new FileStorageService(tmp.toString());
        byte[] zip = zipOf("pom.xml", "<project/>");

        Path dir = service.storeZip(new MockMultipartFile("file", "app.zip", "application/zip", zip));

        Path extracted = dir.resolve("pom.xml");
        assertThat(extracted).exists();
        assertThat(Files.readString(extracted)).isEqualTo("<project/>");
    }

    @Test
    void rejectsZipSlipEntry() {
        FileStorageService service = new FileStorageService(tmp.toString());
        byte[] zip = zipOf("../evil.txt", "pwned");

        assertThatThrownBy(() ->
                service.storeZip(new MockMultipartFile("file", "bad.zip", "application/zip", zip)))
                .isInstanceOf(InvalidProjectSourceException.class);
    }

    @Test
    void deleteRemovesDirectory() throws IOException {
        FileStorageService service = new FileStorageService(tmp.toString());
        Path dir = service.storeZip(new MockMultipartFile("file", "app.zip", "application/zip",
                zipOf("pom.xml", "<project/>")));

        service.delete(dir);

        assertThat(dir).doesNotExist();
    }

    private byte[] zipOf(String entryName, String content) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }
}
