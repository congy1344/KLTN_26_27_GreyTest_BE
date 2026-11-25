package com.greytest.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.crypto.AEADBadTagException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;

import com.greytest.config.StorageEncryptionProperties;
import com.greytest.entity.Project;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.SourceType;
import com.greytest.exception.StorageException;
import com.greytest.service.storage.encryption.StorageEncryptionService;
import com.greytest.service.storage.workspace.StorageWorkspace;
import com.greytest.service.storage.workspace.StorageWorkspaceService;

/**
 * PHASE 6.2 - Kiểm thử tích hợp phục hồi dữ liệu & mã hóa (Database & Storage Recovery Testing).
 * Xác minh:
 * - Workstream B: Encrypted Storage Backup & Restore (SHA-256 checksum, giải mã nguyên vẹn).
 * - Workstream C: KEK/DEK Recovery (Missing KEK, Wrong KEK, Corrupt DEK, KEK Rotation).
 * - Workstream D: Database & Storage Consistency (Missing storage, Missing DEK, Tampered ciphertext, Workspace cleanup).
 */
@SpringBootTest(properties = "greytest.auth.token-secret=test-only-token-secret-at-least-32-bytes")
class DatabaseStorageRecoveryIntegrationTest {

    @TempDir
    Path tempDir;

    private Path storageRoot;
    private Path originalSourceDir;
    private StorageEncryptionProperties properties;
    private StorageEncryptionService encryptionService;
    private SecretKey kekV1;
    private String kekV1Base64;

    @BeforeEach
    void setUp() throws Exception {
        storageRoot = tempDir.resolve("storage");
        originalSourceDir = tempDir.resolve("sample-project-source");
        Files.createDirectories(storageRoot);
        Files.createDirectories(originalSourceDir);

        // Sinh KEK v1 chuẩn 256-bit
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, new SecureRandom());
        kekV1 = keyGen.generateKey();
        kekV1Base64 = Base64.getEncoder().encodeToString(kekV1.getEncoded());

        properties = new StorageEncryptionProperties();
        properties.setEncryptionEnabled(true);
        properties.setKek(kekV1Base64);
        properties.setKekVersion("v1");

        encryptionService = new StorageEncryptionService(properties);
        encryptionService.validateConfiguration();

        // Tạo cấu trúc mã nguồn mẫu để kiểm thử
        Files.createDirectories(originalSourceDir.resolve("src/main/java/com/demo"));
        Files.writeString(originalSourceDir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.demo</groupId><artifactId>demo</artifactId><version>1.0</version></project>",
                StandardCharsets.UTF_8);
        Files.writeString(originalSourceDir.resolve("src/main/java/com/demo/OrderService.java"),
                "package com.demo;\npublic class OrderService {\n    public double calculateTotal(double price, int qty) {\n        return price * qty;\n    }\n}",
                StandardCharsets.UTF_8);
        Files.writeString(originalSourceDir.resolve("src/main/java/com/demo/PaymentService.java"),
                "package com.demo;\npublic class PaymentService {\n    public boolean process(String account, double amount) {\n        return amount > 0;\n    }\n}",
                StandardCharsets.UTF_8);
    }

    // =========================================================================
    // WORKSTREAM B: ENCRYPTED STORAGE BACKUP & RESTORE
    // =========================================================================

    @Test
    @DisplayName("Workstream B: Backup & Restore Encrypted Storage bảo toàn 100% SHA-256 và giải mã đúng mã nguồn gốc")
    void testEncryptedStorageBackupAndRestoreRoundTrip() throws Exception {
        Long projectId = 701L;
        SecretKey dek = encryptionService.generateDek();
        String wrappedDek = encryptionService.wrapDek(dek);

        Path encryptedStorageDir = storageRoot.resolve("projects/" + projectId);
        Files.createDirectories(encryptedStorageDir);

        // 1. Mã hóa toàn bộ thư mục dự án bằng AES-256-GCM
        encryptionService.encryptDirectory(originalSourceDir, encryptedStorageDir, dek, projectId);

        // 2. Tính SHA-256 checksum của toàn bộ file ciphertext trước khi backup
        Map<String, String> preBackupChecksums = computeDirectoryChecksums(encryptedStorageDir);
        assertThat(preBackupChecksums).isNotEmpty();

        // 3. Giả lập quá trình Backup (nén/sao chép ra thư mục backup archive)
        Path backupArchiveDir = tempDir.resolve("storage-backup-archive");
        Files.createDirectories(backupArchiveDir);
        copyDirectoryRecursively(encryptedStorageDir, backupArchiveDir);

        // 4. Giả lập thảm họa: Xóa sạch thư mục storage ban đầu
        deleteDirectoryRecursively(encryptedStorageDir);
        assertThat(Files.exists(encryptedStorageDir)).isFalse();

        // 5. Restore từ Backup Archive sang một vị trí khôi phục mới (Clean Restore Target)
        Path restoredStorageDir = storageRoot.resolve("projects-restored/" + projectId);
        Files.createDirectories(restoredStorageDir);
        copyDirectoryRecursively(backupArchiveDir, restoredStorageDir);

        // 6. Xác minh SHA-256 checksum sau restore: Phải trùng khớp 100% từng byte
        Map<String, String> postRestoreChecksums = computeDirectoryChecksums(restoredStorageDir);
        assertThat(postRestoreChecksums).isEqualTo(preBackupChecksums);

        // 7. Giải mã dữ liệu đã restore với DEK unwrap từ KEK
        SecretKey recoveredDek = encryptionService.unwrapDek(wrappedDek);
        Path decryptedRestoredDir = tempDir.resolve("decrypted-after-restore");
        Files.createDirectories(decryptedRestoredDir);
        encryptionService.decryptDirectory(restoredStorageDir, decryptedRestoredDir, recoveredDek, projectId);

        // 8. Đối chiếu từng file plaintext sau khôi phục với mã nguồn gốc ban đầu
        try (Stream<Path> stream = Files.walk(originalSourceDir)) {
            List<Path> originalFiles = stream.filter(Files::isRegularFile).toList();
            for (Path origFile : originalFiles) {
                Path relative = originalSourceDir.relativize(origFile);
                Path decFile = decryptedRestoredDir.resolve(relative);
                assertThat(Files.exists(decFile)).as("File sau giải mã phải tồn tại: %s", relative).isTrue();
                assertThat(Files.readString(decFile)).as("Nội dung file sau giải mã phải trùng khớp gốc: %s", relative)
                        .isEqualTo(Files.readString(origFile));
            }
        }
    }

    // =========================================================================
    // WORKSTREAM C: KEK/DEK RECOVERY & ROTATION
    // =========================================================================

    @Test
    @DisplayName("Workstream C.1: Thiếu KEK từ chối giải mã an toàn mà không làm rò rỉ dữ liệu")
    void testKekRecoveryMissingKekFailsGracefully() {
        StorageEncryptionProperties missingProps = new StorageEncryptionProperties();
        missingProps.setEncryptionEnabled(true);
        missingProps.setKek(null);

        StorageEncryptionService unconfiguredService = new StorageEncryptionService(missingProps);
        assertThatThrownBy(unconfiguredService::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_STORAGE_KEK chưa được cấu hình");
    }

    @Test
    @DisplayName("Workstream C.2: KEK sai (khóa 32 bytes khác) từ chối unwrap DEK an toàn")
    void testKekRecoveryWrongKekThrowsAuthenticationError() throws Exception {
        SecretKey dek = encryptionService.generateDek();
        String wrappedDek = encryptionService.wrapDek(dek);

        // Tạo KEK v2 độc lập khác hoàn toàn
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, new SecureRandom());
        SecretKey wrongKek = keyGen.generateKey();
        String wrongKekBase64 = Base64.getEncoder().encodeToString(wrongKek.getEncoded());

        StorageEncryptionProperties wrongProps = new StorageEncryptionProperties();
        wrongProps.setEncryptionEnabled(true);
        wrongProps.setKek(wrongKekBase64);
        wrongProps.setKekVersion("v1-wrong");

        StorageEncryptionService wrongService = new StorageEncryptionService(wrongProps);
        wrongService.validateConfiguration();

        // unwrap bằng KEK sai phải bị từ chối
        assertThatThrownBy(() -> wrongService.unwrapDek(wrappedDek))
                .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("Workstream C.3: Wrapped DEK bị hỏng/giả mạo trong Database bị từ chối an toàn")
    void testKekRecoveryCorruptedWrappedDekFailsGracefully() {
        SecretKey dek = encryptionService.generateDek();
        String wrappedDek = encryptionService.wrapDek(dek);

        // Làm hỏng 4 bytes trong chuỗi Base64 của wrapped DEK
        byte[] rawWrapped = Base64.getDecoder().decode(wrappedDek);
        rawWrapped[0] ^= 0xFF;
        rawWrapped[1] ^= 0xAA;
        String corruptedWrappedDek = Base64.getEncoder().encodeToString(rawWrapped);

        assertThatThrownBy(() -> encryptionService.unwrapDek(corruptedWrappedDek))
                .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("Workstream C.4: KEK Rotation - Di chuyển wrapped DEK từ KEK v1 sang KEK v2 giải mã thành công")
    void testKekRotationSeamlessRecovery() throws Exception {
        Long projectId = 702L;
        SecretKey dek = encryptionService.generateDek();
        String wrappedDekV1 = encryptionService.wrapDek(dek);

        Path encDir = storageRoot.resolve("projects/" + projectId);
        Files.createDirectories(encDir);
        encryptionService.encryptDirectory(originalSourceDir, encDir, dek, projectId);

        // Sinh KEK v2 cho quy trình Rotation
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, new SecureRandom());
        SecretKey kekV2 = keyGen.generateKey();
        String kekV2Base64 = Base64.getEncoder().encodeToString(kekV2.getEncoded());

        // Mô phỏng KEK Rotation: Unwrap bằng KEK v1, sau đó wrap lại bằng KEK v2
        SecretKey unwrappedDek = encryptionService.unwrapDek(wrappedDekV1);

        StorageEncryptionProperties v2Props = new StorageEncryptionProperties();
        v2Props.setEncryptionEnabled(true);
        v2Props.setKek(kekV2Base64);
        v2Props.setKekVersion("v2");
        StorageEncryptionService serviceV2 = new StorageEncryptionService(v2Props);
        serviceV2.validateConfiguration();

        String wrappedDekV2 = serviceV2.wrapDek(unwrappedDek);

        // Kiểm tra trực tiếp hàm reWrapDek của StorageEncryptionService
        SecretKey oldKek = encryptionService.parseKekBase64(kekV1Base64);
        SecretKey newKek = encryptionService.parseKekBase64(kekV2Base64);
        String reWrappedDek = encryptionService.reWrapDek(wrappedDekV1, oldKek, newKek);
        SecretKey reWrappedDekParsed = serviceV2.unwrapDek(reWrappedDek);
        assertThat(reWrappedDekParsed.getEncoded()).isEqualTo(dek.getEncoded());

        // Sau khi rotation, service V2 dùng wrappedDekV2 giải mã trơn tru
        SecretKey recoveredDekV2 = serviceV2.unwrapDek(wrappedDekV2);
        Path decDir = tempDir.resolve("decrypted-v2");
        Files.createDirectories(decDir);
        serviceV2.decryptDirectory(encDir, decDir, recoveredDekV2, projectId);

        assertThat(Files.readString(decDir.resolve("src/main/java/com/demo/OrderService.java")))
                .contains("calculateTotal");
    }

    @Test
    @DisplayName("Workstream D.4: Project đã mã hóa nhưng thiếu DEK (encryptedDek=null) ném StorageException rõ ràng")
    void testDatabaseStorageConsistencyProjectMissingDekThrowsStorageException() {
        StorageWorkspaceService workspaceService = new StorageWorkspaceService(
                storageRoot.toString(), encryptionService, null);

        Project project = new Project();
        project.setId(8888L);
        project.setStoragePath(storageRoot.resolve("projects/8888").toString());
        try {
            Files.createDirectories(Path.of(project.getStoragePath()));
        } catch (IOException ignored) {}
        project.setStorageEncrypted(true);
        project.setEncryptedDek(null); // Missing DEK

        assertThatThrownBy(() -> workspaceService.acquireWorkspace(project))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Không có DEK đã mã hóa để giải mã");
    }

    // =========================================================================
    // WORKSTREAM D: DATABASE & STORAGE CONSISTENCY
    // =========================================================================

    @Test
    @DisplayName("Workstream D.1: Database có Project nhưng thiếu Storage ném StorageException rõ ràng")
    void testDatabaseStorageConsistencyProjectMissingStorageThrowsStorageException() {
        StorageWorkspaceService workspaceService = new StorageWorkspaceService(
                storageRoot.toString(), encryptionService, null);

        Project project = new Project();
        project.setId(9999L);
        project.setStoragePath(storageRoot.resolve("non-existent-storage-path").toString());
        project.setStorageEncrypted(true);

        assertThatThrownBy(() -> workspaceService.acquireWorkspace(project))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Thư mục lưu trữ không tồn tại");
    }

    @Test
    @DisplayName("Workstream D.2: File Ciphertext bị sửa đổi trái phép (Flipped Bit) bị AES-GCM Authentication Tag chặn đứng")
    void testDatabaseStorageConsistencyTamperedCiphertextDetectedByGcm() throws IOException {
        Long projectId = 703L;
        SecretKey dek = encryptionService.generateDek();

        Path encDir = storageRoot.resolve("projects/" + projectId);
        Files.createDirectories(encDir);
        encryptionService.encryptDirectory(originalSourceDir, encDir, dek, projectId);

        // Giả lập hacker hoặc bad sector sửa đổi 1 byte trong file mã hóa
        Path targetEncFile = encDir.resolve("src/main/java/com/demo/OrderService.java");
        byte[] fileBytes = Files.readAllBytes(targetEncFile);
        // Sửa 1 byte ở phần ciphertext (sau 18 bytes header)
        fileBytes[25] ^= 0x01;
        Files.write(targetEncFile, fileBytes);

        Path decDir = tempDir.resolve("tampered-decrypt");
        Files.createDirectories(decDir);

        // AES-256-GCM bắt buộc ném lỗi tính toàn vẹn (AEADBadTagException bọc trong StorageException)
        assertThatThrownBy(() -> encryptionService.decryptDirectory(encDir, decDir, dek, projectId))
                .isInstanceOf(StorageException.class)
                .hasRootCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    @DisplayName("Workstream D.3: Dọn dẹp Workspace mồ côi (cleanupProjectWorkspaces) chỉ xóa đúng thư mục của Project chỉ định")
    void testProjectDeletionCleansUpOnlyTargetProjectWorkspaces() throws IOException {
        StorageWorkspaceService workspaceService = new StorageWorkspaceService(
                storageRoot.toString(), encryptionService, null);

        Path wsRoot = storageRoot.resolve("workspaces");
        Files.createDirectories(wsRoot);

        // Tạo workspace cho project 101 và project 202
        Path wsP101_1 = wsRoot.resolve("ws-101-uuid-1");
        Path wsP101_2 = wsRoot.resolve("ws-101-uuid-2");
        Path wsP202 = wsRoot.resolve("ws-202-uuid-1");

        Files.createDirectories(wsP101_1);
        Files.createDirectories(wsP101_2);
        Files.createDirectories(wsP202);

        Files.writeString(wsP101_1.resolve("leak.txt"), "sensitive");
        Files.writeString(wsP202.resolve("keep.txt"), "safe");

        // Gọi cleanup cho project 101
        workspaceService.cleanupProjectWorkspaces(101L);

        // Workspace của 101 phải bị xóa hoàn toàn, 202 phải còn nguyên
        assertThat(Files.exists(wsP101_1)).isFalse();
        assertThat(Files.exists(wsP101_2)).isFalse();
        assertThat(Files.exists(wsP202)).isTrue();
        assertThat(Files.exists(wsP202.resolve("keep.txt"))).isTrue();
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private Map<String, String> computeDirectoryChecksums(Path dir) throws Exception {
        Map<String, String> checksums = new HashMap<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                String relPath = dir.relativize(file).toString().replace('\\', '/');
                checksums.put(relPath, computeFileSha256(file));
            }
        }
        return checksums;
    }

    private String computeFileSha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(file);
             DigestInputStream dis = new DigestInputStream(is, digest)) {
            byte[] buf = new byte[8192];
            while (dis.read(buf) != -1) {}
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void copyDirectoryRecursively(Path source, Path destination) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path src : stream.toList()) {
                Path dest = destination.resolve(source.relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    });
        }
    }
}
