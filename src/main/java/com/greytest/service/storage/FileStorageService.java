package com.greytest.service.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.greytest.exception.InvalidProjectSourceException;
import com.greytest.exception.StorageException;

import lombok.extern.slf4j.Slf4j;

/**
 * Quản lý lưu trữ source code trên đĩa: tạo thư mục riêng cho mỗi project,
 * giải nén ZIP, và xóa khi cần.
 */
@Slf4j
@Service
public class FileStorageService {

    private final Path projectsRoot;

    public FileStorageService(@Value("${greytest.storage.path:./storage}") String storagePath) {
        this.projectsRoot = Path.of(storagePath, "projects").toAbsolutePath().normalize();
    }

    /** Tạo thư mục rỗng duy nhất cho một project (dùng cho clone GitHub). */
    public Path createProjectDir() {
        try {
            Path dir = projectsRoot.resolve(UUID.randomUUID().toString());
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new StorageException("Không tạo được thư mục lưu project", e);
        }
    }

    /** Giải nén file ZIP vào một thư mục project mới, trả về đường dẫn thư mục đó. */
    public Path storeZip(MultipartFile file) {
        Path dir = createProjectDir();
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                extractEntry(zis, entry, dir);
            }
        } catch (IOException e) {
            delete(dir);
            throw new StorageException("Không giải nén được file ZIP", e);
        }
        return dir;
    }

    /** Xóa toàn bộ thư mục source của project. */
    public void delete(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException e) {
            log.warn("Không xóa được thư mục {}: {}", dir, e.getMessage());
        }
    }

    private void extractEntry(ZipInputStream zis, ZipEntry entry, Path dir) throws IOException {
        // Chống zip-slip: entry không được trỏ ra ngoài thư mục đích
        Path target = dir.resolve(entry.getName()).normalize();
        if (!target.startsWith(dir)) {
            throw new InvalidProjectSourceException("File ZIP chứa đường dẫn không hợp lệ: " + entry.getName());
        }
        if (entry.isDirectory()) {
            Files.createDirectories(target);
        } else {
            Files.createDirectories(target.getParent());
            Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            log.warn("Không xóa được {}: {}", path, e.getMessage());
        }
    }
}
