package com.greytest.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.greytest.exception.InvalidProjectSourceException;
import com.greytest.exception.StorageException;

import lombok.extern.slf4j.Slf4j;

/**
 * Quản lý lưu trữ và snapshot phiên bản source code (SourceRevision):
 * - Lưu snapshot ZIP/GitHub cách ly
 * - Chuẩn hóa thư mục bọc ngoài (logical root)
 * - Tính toán content hash (SHA-256)
 * - Hỗ trợ branch và lấy commit SHA từ GitHub
 */
@Slf4j
@Service
public class SourceRevisionStorageService {

    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".idea", ".gradle", "target", "build", "node_modules",
            "generated-sources", "generated-test-sources", "out");
    private static final int MAX_ZIP_ENTRIES = 10_000;
    private static final long MAX_ZIP_ENTRY_BYTES = 50L * 1024 * 1024;
    private static final long MAX_ZIP_TOTAL_BYTES = 200L * 1024 * 1024;
    private static final int GITHUB_CLONE_TIMEOUT_SECONDS = 90;
    private static final int MAX_SNAPSHOT_FILES = 100_000;
    private static final long MAX_SNAPSHOT_BYTES = 200L * 1024 * 1024;
    private final Path revisionsRoot;

    public SourceRevisionStorageService(@Value("${greytest.storage.path:./storage}") String storagePath) {
        this.revisionsRoot = Path.of(storagePath, "revisions").toAbsolutePath().normalize();
    }

    public record SnapshotResult(Path storageDir, Path logicalRoot, String contentHash, String branch, String commitSha) {}

    /**
     * Tạo snapshot từ file ZIP tải lên.
     */
    public SnapshotResult storeZipSnapshot(MultipartFile file) {
        Path snapshotDir = createRevisionDir();
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            int entryCount = 0;
            long totalBytes = 0;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw new InvalidProjectSourceException("File ZIP chứa quá nhiều entry");
                }
                totalBytes = extractEntry(zis, entry, snapshotDir, totalBytes);
            }
        } catch (ZipException e) {
            delete(snapshotDir);
            throw new InvalidProjectSourceException("File ZIP không hợp lệ: " + e.getMessage());
        } catch (IOException e) {
            delete(snapshotDir);
            throw new StorageException("Không giải nén được file ZIP snapshot", e);
        } catch (RuntimeException e) {
            delete(snapshotDir);
            throw e;
        }

        try {
            Path logicalRoot = detectLogicalRoot(snapshotDir);
            String hash = computeContentHash(logicalRoot);
            return new SnapshotResult(snapshotDir, logicalRoot, hash, null, null);
        } catch (RuntimeException e) {
            delete(snapshotDir);
            throw e;
        }
    }

    /**
     * Tạo snapshot từ một thư mục source đã có sẵn trên đĩa (dùng để tạo baseline cho project hiện hành).
     */
    public SnapshotResult createBaselineFromExistingDir(Path existingDir) {
        if (!Files.isDirectory(existingDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException("Thư mục source baseline không tồn tại: " + existingDir);
        }
        Path sourceDir = existingDir.toAbsolutePath().normalize();
        if (pathsOverlap(sourceDir, revisionsRoot)) {
            throw new StorageException("Thư mục source baseline không được chứa thư mục revisions");
        }
        Path snapshotDir = createRevisionDir();
        try {
            copyDirectory(sourceDir, snapshotDir);
        } catch (IOException e) {
            delete(snapshotDir);
            throw new StorageException("Không sao chép được source baseline", e);
        } catch (RuntimeException e) {
            delete(snapshotDir);
            throw e;
        }

        try {
            Path logicalRoot = detectLogicalRoot(snapshotDir);
            String hash = computeContentHash(logicalRoot);
            return new SnapshotResult(snapshotDir, logicalRoot, hash, null, null);
        } catch (RuntimeException e) {
            delete(snapshotDir);
            throw e;
        }
    }

    /**
     * Tạo snapshot từ GitHub repo theo branch được chỉ định.
     */
    public SnapshotResult storeGithubSnapshot(String url, String branch) {
        validateGithubUrl(url);
        Path snapshotDir = createRevisionDir();
        String branchToUse = (branch == null || branch.isBlank()) ? null : branch.trim();

        try {
            var cloneCommand = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(snapshotDir.toFile())
                    .setTimeout(GITHUB_CLONE_TIMEOUT_SECONDS)
                    .setDepth(1);

            if (branchToUse != null) {
                cloneCommand.setBranch(branchToUse);
            }

            String commitSha;
            String actualBranch;
            try (Git git = cloneCommand.call()) {
                Ref head = git.getRepository().findRef("HEAD");
                ObjectId commitId = head != null ? head.getObjectId() : null;
                commitSha = commitId != null ? commitId.getName() : null;
                actualBranch = branchToUse != null ? branchToUse : git.getRepository().getBranch();
            }
            pruneExcludedDirectories(snapshotDir);
            rejectSymbolicLinks(snapshotDir);
            validateSnapshotBudget(snapshotDir);
            Path logicalRoot = detectLogicalRoot(snapshotDir);
            String hash = computeContentHash(logicalRoot);
            log.info("Đã clone snapshot GitHub: url={}, branch={}, commit={}", url, actualBranch, commitSha);
            return new SnapshotResult(snapshotDir, logicalRoot, hash, actualBranch, commitSha);
        } catch (GitAPIException | IOException e) {
            delete(snapshotDir);
            throw new InvalidProjectSourceException("Không clone được snapshot GitHub branch ["
                    + (branchToUse == null ? "default" : branchToUse) + "]: " + e.getMessage());
        } catch (RuntimeException e) {
            delete(snapshotDir);
            throw e;
        }
    }

    /**
     * Phát hiện logical root (thư mục chứa file build pom.xml / build.gradle).
     */
    public Path detectLogicalRoot(Path rootDir) {
        try (Stream<Path> walk = Files.walk(rootDir)) {
            List<Path> buildPaths = walk
                    .filter(p -> !isExcludedPath(rootDir.relativize(p)))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> BUILD_FILES.contains(p.getFileName().toString()))
                    .sorted(Comparator.comparingInt(p -> p.getNameCount()))
                    .toList();

            if (buildPaths.isEmpty()) {
                throw new InvalidProjectSourceException("Không tìm thấy file build (pom.xml hoặc build.gradle) trong source");
            }
            // Thư mục chứa file build gần root nhất
            return buildPaths.get(0).getParent();
        } catch (IOException e) {
            throw new StorageException("Lỗi khi duyệt cây thư mục source", e);
        }
    }

    /**
     * Tính hash SHA-256 toàn bộ các file trong thư mục để xác định content digest duy nhất.
     */
    public String computeContentHash(Path dir) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> walk = Files.walk(dir)) {
                List<Path> regularFiles = walk
                        .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> !isExcludedPath(dir.relativize(p)))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();

                for (Path file : regularFiles) {
                    byte[] relativePath = dir.relativize(file).toString().getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(relativePath.length).array());
                    digest.update(relativePath);
                    long fileSize = Files.readAttributes(file,
                            java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
                    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(fileSize).array());
                    try (InputStream is = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                         DigestInputStream dis = new DigestInputStream(is, digest)) {
                        byte[] buffer = new byte[8192];
                        while (dis.read(buffer) != -1) {
                            // đọc để digest cập nhật
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            log.warn("Không tính được content hash cho thư mục {}: {}", dir, e.getMessage());
            throw new StorageException("Không tính được content hash cho thư mục source", e);
        }
    }

    public Path createRevisionDir() {
        try {
            Path dir = revisionsRoot.resolve(UUID.randomUUID().toString());
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new StorageException("Không tạo được thư mục revision", e);
        }
    }

    public void delete(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException e) {
            log.warn("Không xóa được thư mục revision {}: {}", dir, e.getMessage());
        }
    }

    private long extractEntry(ZipInputStream zis, ZipEntry entry, Path dir, long totalBytes) throws IOException {
        Path target = dir.resolve(entry.getName()).normalize();
        if (!target.startsWith(dir)) {
            throw new InvalidProjectSourceException("File ZIP chứa đường dẫn không hợp lệ: " + entry.getName());
        }
        boolean excluded = isExcludedPath(dir.relativize(target));
        if (entry.isDirectory()) {
            if (!excluded) {
                Files.createDirectories(target);
            }
        } else if (!excluded) {
            Files.createDirectories(target.getParent());
        }
        long entryBytes = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream output = excluded || entry.isDirectory()
                ? OutputStream.nullOutputStream()
                : Files.newOutputStream(target)) {
            int read;
            while ((read = zis.read(buffer)) != -1) {
                entryBytes += read;
                totalBytes += read;
                if (entryBytes > MAX_ZIP_ENTRY_BYTES || totalBytes > MAX_ZIP_TOTAL_BYTES) {
                    throw new InvalidProjectSourceException("File ZIP vượt quá giới hạn kích thước cho phép");
                }
                output.write(buffer, 0, read);
            }
        }
        return totalBytes;
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        int copiedFiles = 0;
        long copiedBytes = 0;
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path relativePath = source.relativize(path);
                if (!relativePath.toString().isBlank() && isExcludedPath(relativePath)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw new InvalidProjectSourceException("Source baseline không hỗ trợ symbolic link: " + relativePath);
                }
                Path dest = target.resolve(relativePath);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.exists(dest)) {
                        Files.createDirectories(dest);
                    }
                } else {
                    long fileSize = Files.readAttributes(path,
                            java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
                    if (++copiedFiles > MAX_SNAPSHOT_FILES || fileSize > MAX_SNAPSHOT_BYTES - copiedBytes) {
                        throw new InvalidProjectSourceException("Source baseline vượt quá giới hạn file hoặc dung lượng");
                    }
                    copiedBytes += fileSize;
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                }
            }
        }
    }

    private void pruneExcludedDirectories(Path rootDir) {
        try (Stream<Path> walk = Files.walk(rootDir)) {
            List<Path> excludedPaths = walk
                    .filter(path -> !path.equals(rootDir))
                    .filter(path -> isExcludedPath(rootDir.relativize(path)))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            excludedPaths.forEach(this::deleteQuietly);
        } catch (IOException e) {
            throw new StorageException("Không dọn được thư mục generated trong snapshot", e);
        }
    }

    private void rejectSymbolicLinks(Path rootDir) {
        try (Stream<Path> walk = Files.walk(rootDir)) {
            Path symbolicLink = walk.filter(Files::isSymbolicLink).findFirst().orElse(null);
            if (symbolicLink != null) {
                throw new InvalidProjectSourceException("Snapshot không hỗ trợ symbolic link: "
                        + rootDir.relativize(symbolicLink));
            }
        } catch (IOException e) {
            throw new StorageException("Không kiểm tra được symbolic link trong snapshot", e);
        }
    }

    private void validateSnapshotBudget(Path rootDir) {
        try (Stream<Path> walk = Files.walk(rootDir)) {
            List<Path> files = walk
                    .filter(path -> !isExcludedPath(rootDir.relativize(path)))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .toList();
            if (files.size() > MAX_SNAPSHOT_FILES) {
                throw new InvalidProjectSourceException("Snapshot chứa quá nhiều file");
            }

            long totalBytes = 0;
            for (Path file : files) {
                totalBytes += Files.readAttributes(file,
                        java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
                if (totalBytes > MAX_SNAPSHOT_BYTES) {
                    throw new InvalidProjectSourceException("Snapshot vượt quá giới hạn dung lượng cho phép");
                }
            }
        } catch (IOException e) {
            throw new StorageException("Không kiểm tra được dung lượng snapshot", e);
        }
    }

    private boolean pathsOverlap(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            log.warn("Không xóa được {}: {}", path, e.getMessage());
        }
    }

    private boolean isExcludedPath(Path relativePath) {
        for (Path segment : relativePath) {
            if (EXCLUDED_DIRECTORIES.contains(segment.toString())) return true;
        }
        return false;
    }

    private void validateGithubUrl(String url) {
        try {
            URI uri = URI.create(url);
            boolean valid = "https".equalsIgnoreCase(uri.getScheme())
                    && "github.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && uri.getPath() != null
                    && uri.getPath().matches("/[^/]+/[^/]+(?:\\.git)?/?");
            if (valid) return;
        } catch (IllegalArgumentException ignored) {
        }
        throw new InvalidProjectSourceException("URL phải là GitHub repository public (https://github.com/...)");
    }
}
