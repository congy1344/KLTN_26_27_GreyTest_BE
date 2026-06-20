package com.greytest.service.storage;

import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.stereotype.Service;

import com.greytest.exception.InvalidProjectSourceException;

import lombok.extern.slf4j.Slf4j;

/**
 * Clone public GitHub repository về thư mục local bằng JGit (shallow clone).
 */
@Slf4j
@Service
public class GithubService {

    private final FileStorageService fileStorageService;

    public GithubService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /** Clone repo public, trả về thư mục chứa source. */
    public Path clone(String url) {
        validateUrl(url);
        Path dir = fileStorageService.createProjectDir();
        try (Git git = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir.toFile())
                .setDepth(1)
                .call()) {
            log.info("Đã clone {} về {}", url, dir);
            return dir;
        } catch (GitAPIException e) {
            fileStorageService.delete(dir);
            throw new InvalidProjectSourceException("Không clone được repo GitHub: " + e.getMessage());
        }
    }

    private void validateUrl(String url) {
        if (url == null || !url.startsWith("https://") || !url.contains("github.com")) {
            throw new InvalidProjectSourceException("URL phải là GitHub repository public (https://github.com/...)");
        }
    }
}
