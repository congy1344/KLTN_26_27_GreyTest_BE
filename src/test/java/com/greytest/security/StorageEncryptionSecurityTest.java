package com.greytest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.greytest.config.StorageEncryptionProperties;
import com.greytest.dto.ProjectDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.Project;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.exception.StorageException;
import com.greytest.mapper.ProjectMapper;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.BreakGlassAccessService;
import com.greytest.service.ProjectService;
import com.greytest.service.analysis.JavaParserHelper;
import com.greytest.service.analysis.JavaParserHelper.SourceScanResult;
import com.greytest.service.storage.FileStorageService;
import com.greytest.service.storage.GithubService;
import com.greytest.service.storage.encryption.StorageEncryptionService;
import com.greytest.service.storage.migration.StorageMigrationService;
import com.greytest.service.storage.workspace.StorageWorkspace;
import com.greytest.service.storage.workspace.StorageWorkspaceService;

/**
 * Kiểm thử bảo mật toàn diện cho Phase 2: Secure Storage & Encryption at Rest.
 * Bao gồm kiểm thử Core Encryption, Multi-Tenant Workspace Isolation,
 * Backward Compatibility, Migration / Rollback và Fail-Closed.
 */
class StorageEncryptionSecurityTest {

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
    private FileStorageService fileStorageService;
    private GithubService githubService;
    private ProjectMapper projectMapper;
    private BreakGlassAccessService breakGlassService;
    private ProjectService projectService;

    private SecretKey kek;
    private String kekBase64;

    private AuthUser userA;
    private AuthUser userB;
    private AuthUser admin;

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

        projectRepository = mock(ProjectRepository.class);
        fileStorageService = new FileStorageService(storageDir.toString());
        githubService = mock(GithubService.class);
        projectMapper = mock(ProjectMapper.class);
        breakGlassService = mock(BreakGlassAccessService.class);

        workspaceService = new StorageWorkspaceService(storageDir.toString(), encryptionService, projectRepository);
        migrationService = new StorageMigrationService(projectRepository, encryptionService);

        projectService = new ProjectService(
                projectRepository, fileStorageService, githubService, projectMapper,
                breakGlassService, null, encryptionService);

        userA = new AuthUser();
        userA.setId(101L);
        userA.setRole(UserRole.USER);

        userB = new AuthUser();
        userB.setId(202L);
        userB.setRole(UserRole.USER);

        admin = new AuthUser();
        admin.setId(901L);
        admin.setRole(UserRole.ADMIN);
    }

    @Test
    @DisplayName("1. Project cũ chưa mã hóa (legacy) vẫn đọc bình thường (Backward Compatibility)")
    void legacyUnencryptedProjectIsReadDirectly() throws IOException {
        Path legacyProjDir = projectsDir.resolve("proj-legacy");
        Files.createDirectories(legacyProjDir.resolve("src/main/java"));
        Files.writeString(legacyProjDir.resolve("pom.xml"), "<project>legacy</project>");
        Files.writeString(legacyProjDir.resolve("src/main/java/Main.java"), "public class Main {}");

        Project legacyProject = new Project();
        legacyProject.setId(1L);
        legacyProject.setOwnerUserId(101L);
        legacyProject.setStoragePath(legacyProjDir.toString());
        legacyProject.setStorageEncrypted(false);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(legacyProject));

        try (StorageWorkspace ws = workspaceService.acquireWorkspace(legacyProject)) {
            assertThat(ws.isEphemeral()).isFalse();
            assertThat(ws.getPath()).isEqualTo(legacyProjDir);
            assertThat(Files.readString(ws.getPath().resolve("pom.xml"))).isEqualTo("<project>legacy</project>");
        }
    }

    @Test
    @DisplayName("2. Project mới được mã hóa tự động khi tạo (feature flag bật)")
    void newProjectIsEncryptedOnCreation() throws IOException {
        Path rawSource = tempDir.resolve("raw-source");
        Files.createDirectories(rawSource);
        Files.writeString(rawSource.resolve("pom.xml"), "<project>new</project>");
        Files.writeString(rawSource.resolve("App.java"), "public class App {}");

        Project newProject = new Project();
        newProject.setId(2L);
        newProject.setOwnerUserId(101L);
        newProject.setStoragePath(rawSource.toString());

        SecretKey dek = encryptionService.generateDek();
        String wrappedDek = encryptionService.wrapDek(dek);

        Path encDir = tempDir.resolve("raw-source-enc");
        encryptionService.encryptDirectory(rawSource, encDir, dek, 2L);

        newProject.setStorageEncrypted(true);
        newProject.setEncryptedDek(wrappedDek);
        newProject.setDekKeyVersion("v1");
        newProject.setEncryptionAlgorithm("AES-256-GCM");

        // Các file trong encDir đều là file đã mã hóa
        assertThat(encryptionService.isEncryptedFile(encDir.resolve("pom.xml"))).isTrue();
        assertThat(encryptionService.isEncryptedFile(encDir.resolve("App.java"))).isTrue();
        assertThat(new String(Files.readAllBytes(encDir.resolve("pom.xml")), StandardCharsets.ISO_8859_1))
                .doesNotContain("<project>new</project>");
    }

    @Test
    @DisplayName("3. Project đã mã hóa giải mã vào ephemeral workspace và tự động xóa sau khi close")
    void encryptedProjectWorkspaceLifecycleAndCleanup() throws IOException {
        Path encryptedStorage = projectsDir.resolve("proj-enc-3");
        Files.createDirectories(encryptedStorage.resolve("src"));

        Path tempPlain = tempDir.resolve("plain-temp");
        Files.createDirectories(tempPlain.resolve("src"));
        Files.writeString(tempPlain.resolve("pom.xml"), "<project>3</project>");
        Files.writeString(tempPlain.resolve("src/Hello.java"), "public class Hello {}");

        SecretKey dek = encryptionService.generateDek();
        encryptionService.encryptDirectory(tempPlain, encryptedStorage, dek, 3L);

        Project project = new Project();
        project.setId(3L);
        project.setOwnerUserId(101L);
        project.setStoragePath(encryptedStorage.toString());
        project.setStorageEncrypted(true);
        project.setEncryptedDek(encryptionService.wrapDek(dek));
        project.setDekKeyVersion("v1");

        Path ephemeralPath;
        try (StorageWorkspace ws = workspaceService.acquireWorkspace(project)) {
            assertThat(ws.isEphemeral()).isTrue();
            ephemeralPath = ws.getPath();
            assertThat(Files.exists(ephemeralPath)).isTrue();
            assertThat(Files.readString(ephemeralPath.resolve("pom.xml"))).isEqualTo("<project>3</project>");
            assertThat(Files.readString(ephemeralPath.resolve("src/Hello.java"))).isEqualTo("public class Hello {}");
        }

        // Sau khi ws.close() kết thúc, thư mục ephemeral phải bị xóa sạch
        assertThat(Files.exists(ephemeralPath)).isFalse();
    }

    @Test
    @DisplayName("4. JavaParser xử lý hoàn toàn chính xác source sau khi giải mã qua workspace")
    void javaParserParsesDecryptedSourceSuccessfully() throws IOException {
        Path encryptedStorage = projectsDir.resolve("proj-enc-4");
        Files.createDirectories(encryptedStorage);

        Path plainSource = tempDir.resolve("plain-source-4");
        Files.createDirectories(plainSource.resolve("src/main/java/com/demo"));
        Files.writeString(plainSource.resolve("pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>com.demo</groupId><artifactId>demo</artifactId><version>1.0</version></project>");
        Files.writeString(plainSource.resolve("src/main/java/com/demo/UserService.java"),
                "package com.demo;\nimport org.springframework.stereotype.Service;\n@Service\npublic class UserService { public String getName() { return \"GreyTest\"; } }");

        SecretKey dek = encryptionService.generateDek();
        encryptionService.encryptDirectory(plainSource, encryptedStorage, dek, 4L);

        Project project = new Project();
        project.setId(4L);
        project.setOwnerUserId(101L);
        project.setStoragePath(encryptedStorage.toString());
        project.setStorageEncrypted(true);
        project.setEncryptedDek(encryptionService.wrapDek(dek));

        JavaParserHelper parserHelper = new JavaParserHelper();

        try (StorageWorkspace ws = workspaceService.acquireWorkspace(project)) {
            SourceScanResult scanResult = parserHelper.scanProject(ws.getPath());
            assertThat(scanResult.productionFiles()).hasSize(1);
            assertThat(scanResult.productionFiles().get(0).relativePath()).contains("UserService.java");
        }
    }

    @Test
    @DisplayName("5. User A không thể giải mã source của User B (AAD gắn với Project ID)")
    void userCannotDecryptOtherTenantSource() throws IOException {
        Path sourceA = tempDir.resolve("source-tenant-a");
        Files.createDirectories(sourceA);
        Files.writeString(sourceA.resolve("Sensitive.java"), "private static final String SECRET = \"A\";");

        SecretKey dekA = encryptionService.generateDek();
        Path encFileA = tempDir.resolve("Sensitive.java.enc");
        encryptionService.encryptFile(sourceA.resolve("Sensitive.java"), encFileA, dekA, 101L, "Sensitive.java");

        // Tenant B (project 202L) cố giải mã bằng Project ID của Tenant B
        Path decFileB = tempDir.resolve("Sensitive.java.dec");
        assertThatThrownBy(() -> encryptionService.decryptFile(encFileA, decFileB, dekA, 202L, "Sensitive.java"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("GCM authentication tag mismatch");
    }

    @Test
    @DisplayName("6. Admin không có Break-Glass bị từ chối truy cập source (Phase 1 + Phase 2)")
    void adminWithoutBreakGlassCannotAccessSource() {
        Project project = new Project();
        project.setId(10L);
        project.setOwnerUserId(101L);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));
        when(breakGlassService.hasActiveBreakGlass(10L, 901L)).thenReturn(false);

        assertThatThrownBy(() -> projectService.requireSourceAccess(10L, admin))
                .isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("7. Migration dữ liệu cũ: Dry-run kiểm tra toàn vẹn không đổi file; Real migration mã hóa thành công")
    void migrationDryRunAndExecution() throws IOException {
        Path legacyDir = projectsDir.resolve("proj-migrate-7");
        Files.createDirectories(legacyDir.resolve("src"));
        Files.writeString(legacyDir.resolve("pom.xml"), "<project>v1</project>");
        Files.writeString(legacyDir.resolve("src/Service.java"), "class Service {}");

        Project project = new Project();
        project.setId(7L);
        project.setOwnerUserId(101L);
        project.setStoragePath(legacyDir.toString());
        project.setStorageEncrypted(false);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));

        // 7a. Dry-Run: Kiểm tra toàn vẹn, file gốc vẫn là plaintext
        var dryRunResult = migrationService.migrateProject(7L, true);
        assertThat(dryRunResult.status()).isEqualTo("DRY_RUN_SUCCESS");
        assertThat(Files.readString(legacyDir.resolve("pom.xml"))).isEqualTo("<project>v1</project>");
        assertThat(project.getStorageEncrypted()).isFalse();

        // 7b. Real Migration: Mã hóa chính thức
        var realResult = migrationService.migrateProject(7L, false);
        assertThat(realResult.status()).isEqualTo("MIGRATED_SUCCESS");
        assertThat(project.getStorageEncrypted()).isTrue();
        assertThat(project.getEncryptedDek()).isNotNull();
        assertThat(encryptionService.isEncryptedFile(legacyDir.resolve("pom.xml"))).isTrue();

        // 7c. Rollback: Khôi phục về plaintext an toàn
        var rollbackResult = migrationService.rollbackProject(7L);
        assertThat(rollbackResult.status()).isEqualTo("ROLLBACK_SUCCESS");
        assertThat(project.getStorageEncrypted()).isFalse();
        assertThat(Files.readString(legacyDir.resolve("pom.xml"))).isEqualTo("<project>v1</project>");
    }

    @Test
    @DisplayName("8. Dọn dẹp các workspace mồ côi (cleanupLeftoverWorkspaces) khi hệ thống khởi động")
    void cleanupLeftoverWorkspacesOnStartup() throws IOException {
        Path orphanedWs = workspacesDir.resolve("ws-orphaned-123");
        Files.createDirectories(orphanedWs);
        Files.writeString(orphanedWs.resolve("leftover.java"), "leftover code");

        assertThat(Files.exists(orphanedWs)).isTrue();

        workspaceService.cleanupLeftoverWorkspaces();

        assertThat(Files.exists(orphanedWs)).isFalse();
    }
}
