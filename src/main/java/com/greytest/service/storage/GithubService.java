package com.greytest.service.storage;

import java.net.URI;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
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
        return cloneWithToken(url, null, null);
    }

    /** Clone repo GitHub (public hoặc private qua token), trả về thư mục chứa source. */
    public Path cloneWithToken(String url, String token) {
        return cloneWithToken(url, null, token);
    }

    /** Clone repo GitHub chỉ định branch (public hoặc private qua token), trả về thư mục chứa source. */
    public Path cloneWithToken(String url, String branch, String token) {
        validateUrl(url);
        Path dir = fileStorageService.createProjectDir();
        var cloneCommand = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir.toFile())
                .setDepth(1);
        if (branch != null && !branch.isBlank()) {
            cloneCommand.setBranch(branch.trim());
        }
        if (token != null && !token.isBlank()) {
            cloneCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token.trim()));
        }
        try (Git git = cloneCommand.call()) {
            log.info("Đã clone {} (branch: {}) về {}", url, branch, dir);
            return dir;
        } catch (GitAPIException e) {
            fileStorageService.delete(dir);
            throw new InvalidProjectSourceException("Không clone được repo GitHub: " + e.getMessage());
        }
    }

    private void validateUrl(String url) {
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
            // Loi duoc chuan hoa thanh InvalidProjectSourceException ben duoi.
        }
        throw new InvalidProjectSourceException("URL phải là GitHub repository public (https://github.com/...)");
    }
}
