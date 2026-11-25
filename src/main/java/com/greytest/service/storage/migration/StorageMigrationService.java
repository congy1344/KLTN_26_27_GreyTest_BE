package com.greytest.service.storage.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.greytest.entity.Project;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.exception.StorageException;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.storage.encryption.StorageEncryptionService;

import lombok.extern.slf4j.Slf4j;

/**
 * Quản lý quy trình di chuyển (migration) và khôi phục (rollback) dữ liệu source code
 * hiện có sang dạng mã hóa AES-256-GCM theo từng Project (Phase 2.4).
 */
@Slf4j
@Service
public class StorageMigrationService {

    private final ProjectRepository projectRepository;
    private final StorageEncryptionService encryptionService;

    public StorageMigrationService(
            ProjectRepository projectRepository,
            StorageEncryptionService encryptionService) {
        this.projectRepository = projectRepository;
        this.encryptionService = encryptionService;
    }

    public record MigrationResult(
            Long projectId,
            String status,
            int fileCount,
            long totalBytes,
            String message) {}

    public record RollbackResult(
            Long projectId,
            String status,
            String message) {}

    /**
     * Thực hiện mã hóa source code của một Project với hỗ trợ Dry-Run và kiểm tra tính toàn vẹn (checksum).
     */
    @Transactional
    public MigrationResult migrateProject(Long projectId, boolean dryRun) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        if (Boolean.TRUE.equals(project.getStorageEncrypted())) {
            return new MigrationResult(projectId, "ALREADY_ENCRYPTED", 0, 0,
                    "Project " + projectId + " đã được mã hóa từ trước.");
        }

        if (project.getStoragePath() == null || project.getStoragePath().isBlank()) {
            throw new StorageException("Project " + projectId + " không có đường dẫn lưu trữ source");
        }

        Path originalDir = Path.of(project.getStoragePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(originalDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException("Thư mục source của project không tồn tại trên đĩa: " + originalDir);
        }

        SecretKey dek = encryptionService.generateDek();
        String wrappedDek = encryptionService.wrapDek(dek);

        Path stagingEncDir = originalDir.resolveSibling(originalDir.getFileName().toString() + ".staging-enc");
        Path verifyDecDir = originalDir.resolveSibling(originalDir.getFileName().toString() + ".verify-dec");

        deleteTreeQuietly(stagingEncDir);
        deleteTreeQuietly(verifyDecDir);

        Path swappedBackup = null;
        try {
            // 1. Mã hóa toàn bộ thư mục vào staging
            encryptionService.encryptDirectory(originalDir, stagingEncDir, dek, projectId);

            // 2. Thử giải mã ngược lại vào thư mục verify để đối chiếu SHA-256 checksum
            encryptionService.decryptDirectory(stagingEncDir, verifyDecDir, dek, projectId);

            // 3. Kiểm tra tính toàn vẹn (Checksum Verification)
            int fileCount = 0;
            long totalBytes = 0;
            try (Stream<Path> stream = Files.walk(originalDir)) {
                List<Path> originalFiles = stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList();
                fileCount = originalFiles.size();
                for (Path orig : originalFiles) {
                    Path rel = originalDir.relativize(orig);
                    Path dec = verifyDecDir.resolve(rel);
                    if (!Files.exists(dec)) {
                        throw new StorageException("File giải mã bị thiếu: " + rel);
                    }
                    totalBytes += Files.size(orig);
                    String origHash = computeFileSha256(orig);
                    String decHash = computeFileSha256(dec);
                    if (!origHash.equals(decHash)) {
                        throw new StorageException("Sai lệch checksum sau giải mã cho file: " + rel);
                    }
                }
            }

            // Dọn dẹp thư mục verify
            deleteTreeQuietly(verifyDecDir);

            if (dryRun) {
                deleteTreeQuietly(stagingEncDir);
                log.info("Dry-run migration cho project {} thành công: {} files, {} bytes", projectId, fileCount, totalBytes);
                return new MigrationResult(projectId, "DRY_RUN_SUCCESS", fileCount, totalBytes,
                        "Xác minh mã hóa thành công hoàn toàn (chế độ dry-run, dữ liệu chưa thay đổi)");
            }

            // 4. Áp dụng chính thức (Atomic Swap có cơ chế phục hồi và trì hoãn xóa backup đến khi commit)
            Path backupPlainDir = originalDir.resolveSibling(originalDir.getFileName().toString() + ".bak-plain");
            requireNoLeftoverBackup(backupPlainDir);

            moveOrReplace(originalDir, backupPlainDir);
            swappedBackup = backupPlainDir;
            moveOrReplace(stagingEncDir, originalDir);

            // Cập nhật Database metadata
            project.setStorageEncrypted(true);
            project.setEncryptedDek(wrappedDek);
            project.setDekKeyVersion(encryptionService.getKekVersion());
            project.setEncryptionAlgorithm("AES-256-GCM");
            projectRepository.save(project);

            // Chỉ xóa bản backup plaintext sau khi DB đã commit thành công
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deleteTreeQuietly(backupPlainDir);
                    }
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            restoreBackup(backupPlainDir, originalDir, projectId);
                        }
                    }
                });
            } else {
                deleteTreeQuietly(backupPlainDir);
            }

            log.info("Migration thành công project {}: đã mã hóa {} files, {} bytes với AES-256-GCM",
                    projectId, fileCount, totalBytes);
            return new MigrationResult(projectId, "MIGRATED_SUCCESS", fileCount, totalBytes,
                    "Mã hóa project thành công và đã cập nhật metadata an toàn.");
        } catch (Exception e) {
            // Phục hồi nguyên trạng nếu có lỗi
            deleteTreeQuietly(stagingEncDir);
            deleteTreeQuietly(verifyDecDir);
            if (swappedBackup != null) {
                restoreBackup(swappedBackup, originalDir, projectId);
            }
            log.error("Migration project {} thất bại, dữ liệu gốc được bảo toàn: {}", projectId, e.getMessage(), e);
            throw new StorageException("Migration project " + projectId + " thất bại: " + e.getMessage(), e);
        }
    }

    /**
     * Khôi phục (Rollback) project đã mã hóa về dạng plaintext nếu cần thiết.
     */
    @Transactional
    public RollbackResult rollbackProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        if (!Boolean.TRUE.equals(project.getStorageEncrypted())) {
            return new RollbackResult(projectId, "NOT_ENCRYPTED", "Project " + projectId + " không ở trạng thái mã hóa.");
        }

        Path encryptedDir = Path.of(project.getStoragePath()).toAbsolutePath().normalize();
        SecretKey dek = encryptionService.unwrapDek(project.getEncryptedDek());

        Path stagingPlainDir = encryptedDir.resolveSibling(encryptedDir.getFileName().toString() + ".staging-plain");
        deleteTreeQuietly(stagingPlainDir);

        Path swappedBackup = null;
        try {
            // Giải mã ra staging
            encryptionService.decryptDirectory(encryptedDir, stagingPlainDir, dek, projectId);

            // Atomic Swap có cơ chế phục hồi
            Path backupEncDir = encryptedDir.resolveSibling(encryptedDir.getFileName().toString() + ".bak-enc");
            requireNoLeftoverBackup(backupEncDir);

            moveOrReplace(encryptedDir, backupEncDir);
            swappedBackup = backupEncDir;
            moveOrReplace(stagingPlainDir, encryptedDir);

            project.setStorageEncrypted(false);
            project.setEncryptedDek(null);
            project.setDekKeyVersion(null);
            project.setEncryptionAlgorithm(null);
            projectRepository.save(project);

            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deleteTreeQuietly(backupEncDir);
                    }
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            restoreBackup(backupEncDir, encryptedDir, projectId);
                        }
                    }
                });
            } else {
                deleteTreeQuietly(backupEncDir);
            }

            log.info("Rollback thành công project {} về plaintext", projectId);
            return new RollbackResult(projectId, "ROLLBACK_SUCCESS", "Đã giải mã dự án về dạng plaintext thành công.");
        } catch (Exception e) {
            deleteTreeQuietly(stagingPlainDir);
            if (swappedBackup != null) {
                restoreBackup(swappedBackup, encryptedDir, projectId);
            }
            throw new StorageException("Rollback project " + projectId + " thất bại: " + e.getMessage(), e);
        }
    }

    /**
     * Re-wrap DEK khi thực hiện KEK Rotation từ KEK cũ sang KEK mới mà không cần giải mã source code về plaintext.
     */
    @Transactional
    public MigrationResult rotateProjectKek(Long projectId, String oldKekBase64, String newKekBase64, String newKeyVersion) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        if (!Boolean.TRUE.equals(project.getStorageEncrypted()) || project.getEncryptedDek() == null) {
            return new MigrationResult(projectId, "SKIPPED", 0, 0, "Project chưa được mã hóa nên không cần rotate KEK.");
        }
        javax.crypto.SecretKey oldKek = encryptionService.parseKekBase64(oldKekBase64);
        javax.crypto.SecretKey newKek = encryptionService.parseKekBase64(newKekBase64);
        String reWrappedDek = encryptionService.reWrapDek(project.getEncryptedDek(), oldKek, newKek);
        project.setEncryptedDek(reWrappedDek);
        project.setDekKeyVersion(newKeyVersion);
        projectRepository.save(project);
        log.info("Đã rotate KEK thành công cho project {} sang version {}", projectId, newKeyVersion);
        return new MigrationResult(projectId, "ROTATED", 1, 0, "Đã re-wrap DEK thành công sang KEK version: " + newKeyVersion);
    }

    /** Bản sao còn sót lại có thể là bản gốc duy nhất của lần chạy bị gián đoạn: không được tự xóa. */
    private void requireNoLeftoverBackup(Path backup) {
        if (Files.exists(backup)) {
            throw new StorageException("Còn bản sao lưu của lần chuyển đổi bị gián đoạn: " + backup
                    + ". Cần kiểm tra và khôi phục thủ công trước khi chạy lại.");
        }
    }

    /** Đưa bản sao lưu về chỗ cũ khi DB không commit; bản vừa hoán đổi không còn khóa trong DB nên phải bỏ. */
    private void restoreBackup(Path backup, Path current, Long projectId) {
        if (!Files.exists(backup)) return;
        try {
            deleteTreeQuietly(current);
            moveOrReplace(backup, current);
        } catch (Exception ex) {
            log.error("Không khôi phục được bản sao lưu {} cho project {}: {}", backup, projectId, ex.getMessage());
        }
    }

    private void moveOrReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String computeFileSha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                 DigestInputStream dis = new DigestInputStream(is, md)) {
                byte[] buf = new byte[8192];
                while (dis.read(buf) != -1) {}
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IOException("Không tính được SHA-256 cho " + file, e);
        }
    }

    private void deleteTreeQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
