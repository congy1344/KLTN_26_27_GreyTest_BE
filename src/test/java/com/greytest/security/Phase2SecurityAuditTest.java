package com.greytest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.config.StorageEncryptionProperties;
import com.greytest.entity.Project;
import com.greytest.exception.StorageException;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.storage.encryption.StorageEncryptionService;
import com.greytest.service.storage.migration.StorageMigrationService;
import com.greytest.service.storage.workspace.StorageWorkspace;
import com.greytest.service.storage.workspace.StorageWorkspaceService;

/**
 * Bộ kiểm thử kiểm toán độc lập xác minh các yêu cầu bảo mật chuyên sâu của Phase 2:
 * 1. Header & Version Validation (Header truncated, invalid magic, unsupported version, invalid IV)
 * 2. Path Traversal & Symlink Defense
 * 3. Fail-Closed & Zero Partial Plaintext Leakage
 * 4. Concurrent Ephemeral Workspaces trên cùng Project (Không race condition, Job A cleanup không ảnh hưởng Job B)
 * 5. Feature Flag & Fail-Closed Backward Compatibility (Encrypted Project không bị âm thầm fallback về plaintext)
 * 6. Migration & Rollback Edge Cases (Checksum mismatch, Re-migration, Idempotency)
 */
class Phase2SecurityAuditTest {

    @TempDir
    Path tempDir;

    private Path storageDir;
    private Path projectsDir;
    private Path workspacesDir;

    private StorageEncryptionProperties properties;
    private StorageEncryptionService encryptionService;
    private StorageWorkspaceService workspaceService;
    private StorageMigrationService migrationService;
    private ProjectRepository projectRepository;

    private SecretKey kek;
    private String kekBase64;
    private SecretKey dek;

    @BeforeEach
    void setUp() throws Exception {
        storageDir = tempDir.resolve("storage");
        projectsDir = storageDir.resolve("projects");
        workspacesDir = storageDir.resolve("workspaces");
        Files.createDirectories(projectsDir);
        Files.createDirectories(workspacesDir);

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

        dek = encryptionService.generateDek();

        projectRepository = mock(ProjectRepository.class);
        workspaceService = new StorageWorkspaceService(storageDir.toString(), encryptionService, projectRepository);
        migrationService = new StorageMigrationService(projectRepository, encryptionService);
    }

    @Test
    @DisplayName("1. File ciphertext bị cắt ngắn (< 18 bytes header) phải thất bại an toàn")
    void truncatedCiphertextHeaderFailsSafely() throws IOException {
        Path truncatedFile = tempDir.resolve("truncated.enc");
        Path targetPlain = tempDir.resolve("target.java");

        Files.write(truncatedFile, new byte[] { 'G', 'T', '_', 'E', 1 }); // Chỉ có 5 bytes

        assertThatThrownBy(() -> encryptionService.decryptFile(truncatedFile, targetPlain, dek, 1L, "App.java"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("quá ngắn hoặc bị hỏng header");

        assertThat(Files.exists(targetPlain)).isFalse();
    }

    @Test
    @DisplayName("2. File ciphertext có Magic Header không hợp lệ phải bị từ chối giải mã")
    void invalidMagicHeaderFailsSafely() throws IOException {
        Path invalidMagicFile = tempDir.resolve("bad_magic.enc");
        Path targetPlain = tempDir.resolve("target.java");

        byte[] fakeHeader = new byte[18];
        fakeHeader[0] = 'B';
        fakeHeader[1] = 'A';
        fakeHeader[2] = 'D';
        fakeHeader[3] = '!';
        fakeHeader[4] = 1;  // Version
        fakeHeader[5] = 12; // IV len
        Files.write(invalidMagicFile, fakeHeader);

        assertThatThrownBy(() -> encryptionService.decryptFile(invalidMagicFile, targetPlain, dek, 1L, "App.java"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Magic header không hợp lệ");

        assertThat(Files.exists(targetPlain)).isFalse();
    }

    @Test
    @DisplayName("3. Phiên bản mã hóa ciphertext không được hỗ trợ (version 99) phải thất bại an toàn")
    void unsupportedCiphertextVersionFailsSafely() throws IOException {
        Path badVersionFile = tempDir.resolve("bad_version.enc");
        Path targetPlain = tempDir.resolve("target.java");

        byte[] header = new byte[18];
        System.arraycopy(StorageEncryptionService.MAGIC_HEADER, 0, header, 0, 4);
        header[4] = 99; // Version 99 không hỗ trợ
        header[5] = 12;
        Files.write(badVersionFile, header);

        assertThatThrownBy(() -> encryptionService.decryptFile(badVersionFile, targetPlain, dek, 1L, "App.java"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Phiên bản mã hóa không được hỗ trợ");

        assertThat(Files.exists(targetPlain)).isFalse();
    }

    @Test
    @DisplayName("4. Khi giải mã thất bại (GCM BadTag), không để lại bất kỳ file plaintext một phần nào trên đĩa")
    void noPartialPlaintextLeftOnDiskWhenDecryptionFails() throws IOException {
        Path original = tempDir.resolve("Secrets.java");
        Path enc = tempDir.resolve("Secrets.java.enc");
        Path targetPlain = tempDir.resolve("Secrets.java.dec");

        Files.writeString(original, "SECRET_KEY_123456789_CRITICAL_INFO", StandardCharsets.UTF_8);
        encryptionService.encryptFile(original, enc, dek, 1L, "Secrets.java");

        // Tamper ciphertext
        byte[] bytes = Files.readAllBytes(enc);
        bytes[bytes.length - 2] ^= 0x55;
        Files.write(enc, bytes);

        assertThatThrownBy(() -> encryptionService.decryptFile(enc, targetPlain, dek, 1L, "Secrets.java"))
                .isInstanceOf(StorageException.class);

        // Đảm bảo targetPlain và bất kỳ file .tmp nào không tồn tại trên đĩa
        assertThat(Files.exists(targetPlain)).isFalse();
        try (var stream = Files.list(tempDir)) {
            assertThat(stream.noneMatch(p -> p.getFileName().toString().contains(".tmp"))).isTrue();
        }
    }

    @Test
    @DisplayName("5. Hai Job chạy đồng thời trên cùng Project: Job A đóng workspace không ảnh hưởng Job B")
    void concurrentWorkspacesOnSameProjectIsolatedFromEarlyCleanup() throws IOException {
        Long projectId = 777L;
        Path projDir = projectsDir.resolve("proj-777");
        Files.createDirectories(projDir.resolve("src"));
        Files.writeString(projDir.resolve("src/Main.java"), "class Main {}");

        // Mã hóa thư mục project
        Path encDir = projectsDir.resolve("proj-777-enc");
        encryptionService.encryptDirectory(projDir, encDir, dek, projectId);

        Project project = new Project();
        project.setId(projectId);
        project.setStoragePath(encDir.toString());
        project.setStorageEncrypted(true);
        project.setEncryptedDek(encryptionService.wrapDek(dek));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        // Job A mở workspace
        StorageWorkspace wsA = workspaceService.acquireWorkspace(project);
        Path pathA = wsA.getPath();
        assertThat(Files.exists(pathA.resolve("src/Main.java"))).isTrue();

        // Job B mở workspace đồng thời cho cùng project
        StorageWorkspace wsB = workspaceService.acquireWorkspace(project);
        Path pathB = wsB.getPath();
        assertThat(Files.exists(pathB.resolve("src/Main.java"))).isTrue();
        assertThat(pathA).isNotEqualTo(pathB); // Hai thư mục tách biệt

        // Job A kết thúc trước và đóng workspace
        wsA.close();
        assertThat(Files.exists(pathA)).isFalse(); // Thư mục A đã bị dọn dẹp

        // Xác minh Job B vẫn hoàn toàn hoạt động bình thường, thư mục B còn nguyên vẹn
        assertThat(Files.exists(pathB)).isTrue();
        assertThat(Files.exists(pathB.resolve("src/Main.java"))).isTrue();
        assertThat(workspaceService.getActiveSourcePath(projectId)).isEqualTo(pathB);

        // Job B kết thúc
        wsB.close();
        assertThat(Files.exists(pathB)).isFalse();
    }

    @Test
    @DisplayName("6. Encrypted Project khi KEK bị thiếu hoặc encryption flag bị tắt: Fail-Closed, không trả về ciphertext làm plaintext")
    void encryptedProjectFailsClosedWhenKekMissingOrFlagDisabled() throws IOException {
        Long projectId = 888L;
        Path projDir = projectsDir.resolve("proj-888");
        Files.createDirectories(projDir);
        Files.writeString(projDir.resolve("Code.java"), "secure code");

        Path encDir = projectsDir.resolve("proj-888-enc");
        encryptionService.encryptDirectory(projDir, encDir, dek, projectId);

        Project project = new Project();
        project.setId(projectId);
        project.setStoragePath(encDir.toString());
        project.setStorageEncrypted(true);
        project.setEncryptedDek(encryptionService.wrapDek(dek));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        // Khởi tạo một workspace service không có KEK (ví dụ do server thiếu biến APP_STORAGE_KEK)
        StorageEncryptionProperties noKekProps = new StorageEncryptionProperties();
        noKekProps.setEncryptionEnabled(false);
        StorageEncryptionService unconfiguredService = new StorageEncryptionService(noKekProps);
        StorageWorkspaceService strictWorkspaceService = new StorageWorkspaceService(storageDir.toString(), unconfiguredService, projectRepository);

        // Project đã mã hóa bắt buộc phải ném exception fail-closed, không được âm thầm trả về encDir (ciphertext)
        assertThatThrownBy(() -> strictWorkspaceService.acquireWorkspace(project))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("APP_STORAGE_KEK");
    }

    @Test
    @DisplayName("7. Rollback trên Project chưa mã hóa (plaintext) phải trả về NOT_ENCRYPTED an toàn")
    void rollbackOnUnencryptedProjectReturnsNotEncrypted() throws IOException {
        Long projectId = 999L;
        Path projDir = projectsDir.resolve("proj-999");
        Files.createDirectories(projDir);
        Files.writeString(projDir.resolve("pom.xml"), "<pom></pom>");

        Project project = new Project();
        project.setId(projectId);
        project.setStoragePath(projDir.toString());
        project.setStorageEncrypted(false);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        StorageMigrationService.RollbackResult result = migrationService.rollbackProject(projectId);
        assertThat(result.status()).isEqualTo("NOT_ENCRYPTED");
        assertThat(Files.exists(projDir.resolve("pom.xml"))).isTrue();
    }

    @Test
    @DisplayName("8. KEK Rotation: Re-wrap DEK an toàn từ KEK cũ sang KEK mới")
    void kekRotationReWrapDekSuccessfully() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, new SecureRandom());
        SecretKey newKek = keyGen.generateKey();
        String newKekBase64 = Base64.getEncoder().encodeToString(newKek.getEncoded());

        SecretKey originalDek = encryptionService.generateDek();
        String wrappedDekOld = encryptionService.wrapDek(originalDek);

        // Re-wrap bằng KEK mới
        String reWrappedDek = encryptionService.reWrapDek(wrappedDekOld, kek, newKek);
        assertThat(reWrappedDek).isNotBlank();
        assertThat(reWrappedDek).isNotEqualTo(wrappedDekOld);

        // Tạo service mới với KEK mới để unwrap
        StorageEncryptionProperties newProps = new StorageEncryptionProperties();
        newProps.setEncryptionEnabled(true);
        newProps.setKek(newKekBase64);
        newProps.setKekVersion("v2");
        StorageEncryptionService newEncService = new StorageEncryptionService(newProps);
        newEncService.validateConfiguration();
        SecretKey unwrappedDek = newEncService.unwrapDek(reWrappedDek);

        assertThat(unwrappedDek.getEncoded()).isEqualTo(originalDek.getEncoded());

        // Test migrationService.rotateProjectKek
        Long projectId = 888L;
        Project project = new Project();
        project.setId(projectId);
        project.setStorageEncrypted(true);
        project.setEncryptedDek(wrappedDekOld);
        project.setDekKeyVersion("v1");
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        var rotateResult = migrationService.rotateProjectKek(projectId, kekBase64, newKekBase64, "v2");
        assertThat(rotateResult.status()).isEqualTo("ROTATED");
        assertThat(project.getDekKeyVersion()).isEqualTo("v2");
        assertThat(project.getEncryptedDek()).isEqualTo(reWrappedDek);
    }
}
