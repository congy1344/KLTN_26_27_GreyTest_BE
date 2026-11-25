package com.greytest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.config.StorageEncryptionProperties;
import com.greytest.exception.StorageException;
import com.greytest.service.storage.encryption.StorageEncryptionService;

class StorageEncryptionCoreTest {

    @TempDir
    Path tempDir;

    private StorageEncryptionProperties properties;
    private StorageEncryptionService encryptionService;
    private SecretKey kek;
    private String kekBase64;

    @BeforeEach
    void setUp() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, new SecureRandom());
        kek = keyGen.generateKey();
        kekBase64 = Base64.getEncoder().encodeToString(kek.getEncoded());

        properties = new StorageEncryptionProperties();
        properties.setEncryptionEnabled(true);
        properties.setKek(kekBase64);
        properties.setKekVersion("v1");

        encryptionService = new StorageEncryptionService(properties);
        encryptionService.validateConfiguration();
    }

    @Test
    @DisplayName("1. Mã hóa và giải mã file đơn trả về đúng dữ liệu gốc byte-by-byte")
    void encryptAndDecryptFileRoundTrip() throws IOException {
        Path originalFile = tempDir.resolve("App.java");
        Path encryptedFile = tempDir.resolve("App.java.enc");
        Path decryptedFile = tempDir.resolve("App.java.dec");

        String originalContent = "package com.example;\npublic class App { public static void main(String[] args) {} }";
        Files.writeString(originalFile, originalContent, StandardCharsets.UTF_8);

        SecretKey dek = encryptionService.generateDek();
        Long projectId = 101L;
        String relativePath = "src/main/java/App.java";

        encryptionService.encryptFile(originalFile, encryptedFile, dek, projectId, relativePath);

        assertThat(Files.exists(encryptedFile)).isTrue();
        assertThat(Files.readAllBytes(encryptedFile)).isNotEqualTo(originalContent.getBytes(StandardCharsets.UTF_8));
        assertThat(encryptionService.isEncryptedFile(encryptedFile)).isTrue();

        encryptionService.decryptFile(encryptedFile, decryptedFile, dek, projectId, relativePath);

        assertThat(Files.exists(decryptedFile)).isTrue();
        assertThat(Files.readString(decryptedFile)).isEqualTo(originalContent);
    }

    @Test
    @DisplayName("2. Mỗi Project sinh DEK ngẫu nhiên riêng biệt 256-bit")
    void eachProjectGeneratesUniqueDek() {
        Set<String> dekSet = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            SecretKey dek = encryptionService.generateDek();
            assertThat(dek.getEncoded()).hasSize(32); // 256 bits
            String dekBase64 = Base64.getEncoder().encodeToString(dek.getEncoded());
            assertThat(dekSet.add(dekBase64)).isTrue(); // Không được trùng lặp
        }
    }

    @Test
    @DisplayName("3. Wrapped DEK không chứa plaintext DEK bytes")
    void wrappedDekDoesNotContainPlaintextDek() {
        SecretKey dek = encryptionService.generateDek();
        String wrappedDek = encryptionService.wrapDek(dek);

        byte[] plaintextDekBytes = dek.getEncoded();
        byte[] wrappedDekBytes = Base64.getDecoder().decode(wrappedDek);

        assertThat(wrappedDekBytes).isNotEqualTo(plaintextDekBytes);
        assertThat(new String(wrappedDekBytes, StandardCharsets.ISO_8859_1))
                .doesNotContain(new String(plaintextDekBytes, StandardCharsets.ISO_8859_1));

        // Unwrap kiểm tra lại
        SecretKey unwrappedDek = encryptionService.unwrapDek(wrappedDek);
        assertThat(unwrappedDek.getEncoded()).isEqualTo(plaintextDekBytes);
    }

    @Test
    @DisplayName("4. Sai KEK không thể giải mã (unwrap DEK thất bại)")
    void wrongKekFailsToUnwrapDek() throws Exception {
        SecretKey dek = encryptionService.generateDek();
        String wrappedDek = encryptionService.wrapDek(dek);

        // Khởi tạo service khác với KEK khác
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, new SecureRandom());
        SecretKey wrongKek = keyGen.generateKey();

        StorageEncryptionProperties wrongProps = new StorageEncryptionProperties();
        wrongProps.setEncryptionEnabled(true);
        wrongProps.setKek(Base64.getEncoder().encodeToString(wrongKek.getEncoded()));
        StorageEncryptionService wrongService = new StorageEncryptionService(wrongProps);
        wrongService.validateConfiguration();

        assertThatThrownBy(() -> wrongService.unwrapDek(wrappedDek))
                .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("5. Sai DEK không thể giải mã file (GCM authentication tag mismatch)")
    void wrongDekFailsToDecryptFile() throws IOException {
        Path originalFile = tempDir.resolve("Service.java");
        Path encryptedFile = tempDir.resolve("Service.java.enc");
        Path decryptedFile = tempDir.resolve("Service.java.dec");

        Files.writeString(originalFile, "public class Service {}", StandardCharsets.UTF_8);

        SecretKey correctDek = encryptionService.generateDek();
        SecretKey wrongDek = encryptionService.generateDek();

        encryptionService.encryptFile(originalFile, encryptedFile, correctDek, 101L, "Service.java");

        assertThatThrownBy(() -> encryptionService.decryptFile(encryptedFile, decryptedFile, wrongDek, 101L, "Service.java"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("GCM authentication tag mismatch");
    }

    @Test
    @DisplayName("6. Ciphertext bị chỉnh sửa (tampered) phát hiện lỗi xác thực GCM")
    void tamperedCiphertextFailsAuthentication() throws IOException {
        Path originalFile = tempDir.resolve("Secret.java");
        Path encryptedFile = tempDir.resolve("Secret.java.enc");
        Path decryptedFile = tempDir.resolve("Secret.java.dec");

        Files.writeString(originalFile, "secret token = 123456", StandardCharsets.UTF_8);

        SecretKey dek = encryptionService.generateDek();
        encryptionService.encryptFile(originalFile, encryptedFile, dek, 101L, "Secret.java");

        // Làm hỏng 1 byte trong phần ciphertext (sau header 18 bytes)
        byte[] bytes = Files.readAllBytes(encryptedFile);
        int tamperIndex = bytes.length - 5;
        bytes[tamperIndex] = (byte) (bytes[tamperIndex] ^ 0xFF);
        Files.write(encryptedFile, bytes);

        assertThatThrownBy(() -> encryptionService.decryptFile(encryptedFile, decryptedFile, dek, 101L, "Secret.java"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("GCM authentication tag mismatch");
    }

    @Test
    @DisplayName("7. Nonce (IV) được tạo ngẫu nhiên mới cho mỗi lần mã hóa cùng nội dung")
    void nonceIsRandomlyGeneratedForEachEncryption() throws IOException {
        Path originalFile = tempDir.resolve("File.java");
        Path enc1 = tempDir.resolve("File1.enc");
        Path enc2 = tempDir.resolve("File2.enc");

        Files.writeString(originalFile, "same content", StandardCharsets.UTF_8);
        SecretKey dek = encryptionService.generateDek();

        encryptionService.encryptFile(originalFile, enc1, dek, 101L, "File.java");
        encryptionService.encryptFile(originalFile, enc2, dek, 101L, "File.java");

        byte[] bytes1 = Files.readAllBytes(enc1);
        byte[] bytes2 = Files.readAllBytes(enc2);

        // Header chứa IV ở vị trí 6 đến 18
        byte[] iv1 = new byte[12];
        byte[] iv2 = new byte[12];
        System.arraycopy(bytes1, 6, iv1, 0, 12);
        System.arraycopy(bytes2, 6, iv2, 0, 12);

        assertThat(iv1).isNotEqualTo(iv2);
        assertThat(bytes1).isNotEqualTo(bytes2);
    }

    @Test
    @DisplayName("8. AAD không khớp Project ID hoặc đường dẫn phải thất bại giải mã")
    void aadMismatchFailsDecryption() throws IOException {
        Path originalFile = tempDir.resolve("ProjectA.java");
        Path encryptedFile = tempDir.resolve("ProjectA.java.enc");
        Path decryptedFile = tempDir.resolve("ProjectA.java.dec");

        Files.writeString(originalFile, "code of tenant A", StandardCharsets.UTF_8);
        SecretKey dek = encryptionService.generateDek();

        // Mã hóa cho project 101L với relative path "src/Main.java"
        encryptionService.encryptFile(originalFile, encryptedFile, dek, 101L, "src/Main.java");

        // Tenant B (project 202L) cố giải mã ciphertext này
        assertThatThrownBy(() -> encryptionService.decryptFile(encryptedFile, decryptedFile, dek, 202L, "src/Main.java"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("GCM authentication tag mismatch");

        // Đường dẫn file bị tráo (swapped file path) cũng phải thất bại
        assertThatThrownBy(() -> encryptionService.decryptFile(encryptedFile, decryptedFile, dek, 101L, "src/Hacked.java"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("GCM authentication tag mismatch");
    }

    @Test
    @DisplayName("9. Đọc trực tiếp in-memory (readDecryptedBytes/readDecryptedString) trả về đúng dữ liệu")
    void inMemoryDecryptionWorks() throws IOException {
        Path originalFile = tempDir.resolve("pom.xml");
        Path encryptedFile = tempDir.resolve("pom.xml.enc");

        String pomXml = "<project><modelVersion>4.0.0</modelVersion></project>";
        Files.writeString(originalFile, pomXml, StandardCharsets.UTF_8);

        SecretKey dek = encryptionService.generateDek();
        encryptionService.encryptFile(originalFile, encryptedFile, dek, 101L, "pom.xml");

        String decryptedPom = encryptionService.readDecryptedString(encryptedFile, dek, 101L, "pom.xml");
        assertThat(decryptedPom).isEqualTo(pomXml);
    }

    @Test
    @DisplayName("10. Mã hóa và giải mã toàn bộ cây thư mục source")
    void directoryTreeEncryptionAndDecryption() throws IOException {
        Path srcRoot = tempDir.resolve("source-tree");
        Path encRoot = tempDir.resolve("encrypted-tree");
        Path decRoot = tempDir.resolve("decrypted-tree");

        Files.createDirectories(srcRoot.resolve("src/main/java/com/demo"));
        Files.createDirectories(srcRoot.resolve("src/test/java/com/demo"));
        Files.writeString(srcRoot.resolve("pom.xml"), "<pom></pom>");
        Files.writeString(srcRoot.resolve("src/main/java/com/demo/DemoApp.java"), "class DemoApp {}");
        Files.writeString(srcRoot.resolve("src/test/java/com/demo/DemoAppTest.java"), "class DemoAppTest {}");

        SecretKey dek = encryptionService.generateDek();
        Long projectId = 101L;

        encryptionService.encryptDirectory(srcRoot, encRoot, dek, projectId);

        assertThat(Files.exists(encRoot.resolve("pom.xml"))).isTrue();
        assertThat(Files.exists(encRoot.resolve("src/main/java/com/demo/DemoApp.java"))).isTrue();
        assertThat(encryptionService.isEncryptedFile(encRoot.resolve("pom.xml"))).isTrue();

        encryptionService.decryptDirectory(encRoot, decRoot, dek, projectId);

        assertThat(Files.readString(decRoot.resolve("pom.xml"))).isEqualTo("<pom></pom>");
        assertThat(Files.readString(decRoot.resolve("src/main/java/com/demo/DemoApp.java"))).isEqualTo("class DemoApp {}");
        assertThat(Files.readString(decRoot.resolve("src/test/java/com/demo/DemoAppTest.java"))).isEqualTo("class DemoAppTest {}");
    }

    @Test
    @DisplayName("11. Khởi động fail-closed nếu bật mã hóa nhưng thiếu APP_STORAGE_KEK")
    void failsClosedWhenKekIsMissing() {
        StorageEncryptionProperties missingKekProps = new StorageEncryptionProperties();
        missingKekProps.setEncryptionEnabled(true);
        missingKekProps.setKek("");

        StorageEncryptionService badService = new StorageEncryptionService(missingKekProps);
        assertThatThrownBy(badService::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_STORAGE_KEK chưa được cấu hình");
    }
}
