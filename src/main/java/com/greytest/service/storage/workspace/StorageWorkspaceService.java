package com.greytest.service.storage.workspace;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.greytest.entity.Project;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.exception.StorageException;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.storage.encryption.StorageEncryptionService;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Quản lý workspace giải mã tạm thời cho pipeline (Phase 2.3):
 * - Đối với project chưa mã hóa (legacy): trả về đường dẫn gốc không tốn chi
 * phí.
 * - Đối với project đã mã hóa: giải mã ra thư mục tạm riêng biệt theo từng tác
 * vụ/job.
 * - Tự động xóa sạch workspace tạm khi hoàn thành, gặp lỗi hoặc server restart.
 */
@Slf4j
@Service
public class StorageWorkspaceService {

    private final Path workspacesRoot;
    private final StorageEncryptionService encryptionService;
    private final ProjectRepository projectRepository;
    private final ConcurrentMap<Long, java.util.Set<Path>> activeWorkspaces = new ConcurrentHashMap<>();

    public StorageWorkspaceService(
            @Value("${greytest.storage.path:./storage}") String storagePath,
            StorageEncryptionService encryptionService,
            ProjectRepository projectRepository) {
        this.workspacesRoot = Path.of(storagePath, "workspaces").toAbsolutePath().normalize();
        this.encryptionService = encryptionService;
        this.projectRepository = projectRepository;
    }

    @PostConstruct
    public void cleanupLeftoverWorkspaces() {
        if (!Files.exists(workspacesRoot)) {
            try {
                Files.createDirectories(workspacesRoot);
            } catch (IOException e) {
                log.warn("Không tạo được thư mục workspaces: {}", workspacesRoot);
            }
            return;
        }
        try (Stream<Path> walk = Files.walk(workspacesRoot)) {
            walk.filter(p -> !p.equals(workspacesRoot))
                    .sorted(Comparator.reverseOrder())
                    .forEach(this::deleteQuietly);
            log.info("Đã dọn dẹp các thư mục workspace tạm còn sót lại tại {}", workspacesRoot);
        } catch (IOException e) {
            log.warn("Lỗi khi dọn dẹp workspace cũ: {}", e.getMessage());
        }
    }

    /**
     * Mở một workspace cho project. Nếu project đã mã hóa, giải mã vào thư mục tạm
     * thời.
     */
    public StorageWorkspace acquireWorkspace(Project project) {
        if (project == null || project.getStoragePath() == null || project.getStoragePath().isBlank()) {
            throw new StorageException("Project chưa có đường dẫn lưu trữ source");
        }
        Path originalStoragePath = Path.of(project.getStoragePath()).toAbsolutePath().normalize();
        if (!Files.exists(originalStoragePath)) {
            throw new StorageException("Thư mục lưu trữ không tồn tại: " + originalStoragePath);
        }

        if (!Boolean.TRUE.equals(project.getStorageEncrypted())) {
            // Project chưa mã hóa (legacy) -> trả về đường dẫn gốc không cần dọn dẹp
            return new StorageWorkspace(originalStoragePath, false, null);
        }

        // Project đã mã hóa -> giải mã vào workspace tạm thời
        try {
            Path ephemeralDir = workspacesRoot.resolve("ws-" + project.getId() + "-" + UUID.randomUUID());
            Files.createDirectories(ephemeralDir);
            setOwnerOnlyPermissions(ephemeralDir);

            SecretKey dek = encryptionService.unwrapDek(project.getEncryptedDek());
            encryptionService.decryptDirectory(originalStoragePath, ephemeralDir, dek, project.getId());

            activeWorkspaces.computeIfAbsent(project.getId(), k -> ConcurrentHashMap.newKeySet()).add(ephemeralDir);
            log.debug("Đã cấp phát ephemeral workspace cho project {} tại {}", project.getId(), ephemeralDir);

            return new StorageWorkspace(ephemeralDir, true, path -> {
                activeWorkspaces.computeIfPresent(project.getId(), (k, set) -> {
                    set.remove(path);
                    return set.isEmpty() ? null : set;
                });
                deleteTree(path);
                log.debug("Đã giải phóng và xóa sạch ephemeral workspace cho project {}", project.getId());
            });
        } catch (IOException e) {
            throw new StorageException("Không tạo được ephemeral workspace cho project " + project.getId(), e);
        }
    }

    public StorageWorkspace acquireWorkspace(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        return acquireWorkspace(project);
    }

    /**
     * Giải mã toàn bộ source của project vào một thư mục đích cụ thể (ví dụ: work
     * directory của sandbox).
     */
    public void decryptToDirectory(Project project, Path destinationDir) {
        if (!Boolean.TRUE.equals(project.getStorageEncrypted())) {
            copyTree(Path.of(project.getStoragePath()), destinationDir);
            return;
        }
        SecretKey dek = encryptionService.unwrapDek(project.getEncryptedDek());
        encryptionService.decryptDirectory(Path.of(project.getStoragePath()), destinationDir, dek, project.getId());
    }

    /**
     * Mở một workspace cho một thư mục cụ thể (ví dụ: snapshot revision) của
     * project.
     * Nếu isEncrypted=true, giải mã vào thư mục tạm thời.
     */
    public StorageWorkspace acquireDirectoryWorkspace(Project project, Path sourceDir, boolean isEncrypted) {
        return acquireDirectoryWorkspace(project, sourceDir, isEncrypted, true);
    }

    /** Revision lịch sử chỉ dùng để so sánh, không được lộ như source active cho validator. */
    public StorageWorkspace acquireDirectoryWorkspace(Project project, Path sourceDir, boolean isEncrypted, boolean active) {
        if (!isEncrypted || !Boolean.TRUE.equals(project.getStorageEncrypted())) {
            return new StorageWorkspace(sourceDir, false, null);
        }
        try {
            Path ephemeralDir = workspacesRoot.resolve("ws-" + project.getId() + "-" + UUID.randomUUID());
            Files.createDirectories(ephemeralDir);
            setOwnerOnlyPermissions(ephemeralDir);
            SecretKey dek = encryptionService.unwrapDek(project.getEncryptedDek());
            encryptionService.decryptDirectory(sourceDir, ephemeralDir, dek, project.getId());
            if (active) activeWorkspaces.computeIfAbsent(project.getId(), k -> ConcurrentHashMap.newKeySet()).add(ephemeralDir);
            return new StorageWorkspace(ephemeralDir, true, path -> {
                if (active) activeWorkspaces.computeIfPresent(project.getId(), (k, set) -> {
                    set.remove(path);
                    return set.isEmpty() ? null : set;
                });
                deleteTree(path);
            });
        } catch (IOException e) {
            throw new StorageException("Không tạo được ephemeral workspace cho thư mục " + sourceDir, e);
        }
    }

    /**
     * Lấy đường dẫn source code hoạt động (ưu tiên ephemeral workspace đang active
     * nếu có,
     * ngược lại fallback về storagePath của project).
     */
    public Path getActiveSourcePath(Long projectId) {
        java.util.Set<Path> activeSet = activeWorkspaces.get(projectId);
        if (activeSet != null && !activeSet.isEmpty()) {
            for (Path p : activeSet) {
                if (Files.isDirectory(p)) {
                    return p;
                }
            }
        }
        return projectRepository.findById(projectId)
                .map(Project::getStoragePath)
                .filter(p -> p != null && !p.isBlank())
                .map(Path::of)
                .orElse(null);
    }

    /**
     * Thu hồi và dọn dẹp sạch toàn bộ ephemeral workspaces của một project (khi
     * project bị xóa hoặc hủy).
     */
    public void cleanupProjectWorkspaces(Long projectId) {
        if (projectId == null)
            return;
        java.util.Set<Path> paths = activeWorkspaces.remove(projectId);
        if (paths != null) {
            for (Path path : paths) {
                deleteTree(path);
            }
        }
        if (Files.isDirectory(workspacesRoot)) {
            try (Stream<Path> stream = Files.list(workspacesRoot)) {
                stream.filter(p -> p.getFileName().toString().startsWith("ws-" + projectId + "-"))
                        .forEach(this::deleteTree);
            } catch (IOException ignored) {
            }
        }
        log.info("Đã xóa sạch toàn bộ ephemeral workspace của project {}", projectId);
    }

    /**
     * Thiết lập quyền truy cập tối thiểu (Owner read/write/execute only - 0700).
     */
    public static void setOwnerOnlyPermissions(Path path) {
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            } else {
                File file = path.toFile();
                file.setReadable(false, false);
                file.setReadable(true, true);
                file.setWritable(false, false);
                file.setWritable(true, true);
                file.setExecutable(false, false);
                file.setExecutable(true, true);
            }
        } catch (Exception ignored) {
        }
    }

    private void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir))
            return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException e) {
            log.warn("Lỗi khi xóa cây thư mục {}: {}", dir, e.getMessage());
        }
    }

    private void deleteQuietly(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                try {
                    long size = Files.size(path);
                    if (size > 0 && size < 50L * 1024 * 1024) { // Ghi đè 0 byte (zero-byte shredding) cho file dưới 50MB
                        byte[] zeros = new byte[(int) Math.min(size, 8192)];
                        try (var os = Files.newOutputStream(path, java.nio.file.StandardOpenOption.WRITE)) {
                            long remaining = size;
                            while (remaining > 0) {
                                int toWrite = (int) Math.min(remaining, zeros.length);
                                os.write(zeros, 0, toWrite);
                                remaining -= toWrite;
                            }
                            os.flush();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private void copyTree(Path source, Path target) {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(sourcePath -> {
                Path relative = source.relativize(sourcePath);
                Path targetPath = target.resolve(relative);
                try {
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else if (Files.isRegularFile(sourcePath)) {
                        Files.copy(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new StorageException("Lỗi sao chép source vào thư mục đích", e);
                }
            });
        } catch (IOException e) {
            throw new StorageException("Lỗi duyệt cây thư mục source", e);
        }
    }
}
