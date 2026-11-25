package com.greytest.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.greytest.dto.ProjectDto;
import com.greytest.entity.AuthUser;
import com.greytest.entity.Project;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.SourceType;
import com.greytest.entity.enums.UserRole;
import com.greytest.exception.AuthException;
import com.greytest.exception.InvalidProjectSourceException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.exception.StorageException;
import com.greytest.mapper.ProjectMapper;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.storage.FileStorageService;
import com.greytest.service.storage.GithubService;
import com.greytest.service.storage.encryption.StorageEncryptionService;
import com.greytest.service.storage.workspace.StorageWorkspaceService;
import com.greytest.service.coverage.SandboxTestRunService;
import com.greytest.service.analysis.AnalysisService;

import lombok.extern.slf4j.Slf4j;

/**
 * Xử lý nhập source code vào hệ thống (WORKFLOW bước 1):
 * nhận ZIP/GitHub, validate là project Spring Boot, lưu source và tạo record Project.
 */
@Slf4j
@Service
public class ProjectService {

    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");

    private final ProjectRepository projectRepository;
    private final FileStorageService fileStorageService;
    private final GithubService githubService;
    private final ProjectMapper projectMapper;
    private final BreakGlassAccessService breakGlassAccessService;
    private final UserActivityService activityService;
    private StorageEncryptionService storageEncryptionService;
    @Autowired(required = false)
    private SandboxTestRunService sandboxTestRunService;
    @Autowired(required = false)
    private StorageWorkspaceService storageWorkspaceService;

    @Autowired
    public ProjectService(
            ProjectRepository projectRepository,
            FileStorageService fileStorageService,
            GithubService githubService,
            ProjectMapper projectMapper,
            @Autowired(required = false) BreakGlassAccessService breakGlassAccessService,
            @Autowired(required = false) UserActivityService activityService,
            @Autowired(required = false) StorageEncryptionService storageEncryptionService) {
        this.projectRepository = projectRepository;
        this.fileStorageService = fileStorageService;
        this.githubService = githubService;
        this.projectMapper = projectMapper;
        this.breakGlassAccessService = breakGlassAccessService;
        this.activityService = activityService;
        this.storageEncryptionService = storageEncryptionService;
    }

    public void setStorageEncryptionService(StorageEncryptionService storageEncryptionService) {
        this.storageEncryptionService = storageEncryptionService;
    }

    public void setSandboxTestRunService(SandboxTestRunService sandboxTestRunService) {
        this.sandboxTestRunService = sandboxTestRunService;
    }

    public void setStorageWorkspaceService(StorageWorkspaceService storageWorkspaceService) {
        this.storageWorkspaceService = storageWorkspaceService;
    }

    public ProjectService(
            ProjectRepository projectRepository,
            FileStorageService fileStorageService,
            GithubService githubService,
            ProjectMapper projectMapper,
            BreakGlassAccessService breakGlassAccessService,
            UserActivityService activityService) {
        this(projectRepository, fileStorageService, githubService, projectMapper, breakGlassAccessService, activityService, null);
    }

    public ProjectService(
            ProjectRepository projectRepository,
            FileStorageService fileStorageService,
            GithubService githubService,
            ProjectMapper projectMapper) {
        this(projectRepository, fileStorageService, githubService, projectMapper, null, null, null);
    }

    /** Backward-compatible constructor; upload no longer triggers analysis. */
    @Deprecated
    public ProjectService(ProjectRepository projectRepository, FileStorageService fileStorageService,
            GithubService githubService, ProjectMapper projectMapper, AnalysisService ignoredAnalysisService) {
        this(projectRepository, fileStorageService, githubService, projectMapper);
    }

    @Transactional
    public ProjectDto createFromZip(MultipartFile file, AuthUser owner) {
        requireOwner(owner);
        Path dir = fileStorageService.storeZip(file);
        try {
            requireSpringBootProject(dir);
            Project project = save(stripZipExtension(file.getOriginalFilename()), SourceType.ZIP, null, dir, owner);
            return projectMapper.toDto(project);
        } catch (RuntimeException e) {
            fileStorageService.delete(dir);
            throw e;
        }
    }

    @Transactional
    public ProjectDto createFromGithub(String url, AuthUser owner) {
        return createFromGithubWithToken(url, null, null, owner);
    }

    @Transactional
    public ProjectDto createFromGithubWithToken(String url, String token, AuthUser owner) {
        return createFromGithubWithToken(url, null, token, owner);
    }

    @Transactional
    public ProjectDto createFromGithubWithToken(String url, String branch, String token, AuthUser owner) {
        requireOwner(owner);
        Path dir = githubService.cloneWithToken(url, branch, token);
        try {
            requireSpringBootProject(dir);
            Project project = save(repoName(url), SourceType.GITHUB, url, dir, owner);
            return projectMapper.toDto(project);
        } catch (RuntimeException e) {
            fileStorageService.delete(dir);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectDto> getAll() {
        return projectRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(projectMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectDto> getAll(AuthUser user) {
        if (user.getRole() == UserRole.ADMIN) {
            return getAll();
        }
        return projectRepository.findByOwnerUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(projectMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectDto getById(Long id) {
        return projectMapper.toDto(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public ProjectDto getById(Long id, AuthUser user) {
        Project project = requireMetadataAccess(id, user);
        return projectMapper.toDto(project);
    }

    /**
     * Kiểm tra quyền sở hữu dự án hoặc quyền Break-Glass hợp lệ đối với Admin.
     * Sử dụng repository-level query findByIdAndOwnerUserId để tối ưu hiệu năng và an toàn dữ liệu.
     */
    @Transactional(readOnly = true)
    public Project requireProjectOwner(Long id, AuthUser user) {
        if (user == null) {
            throw new AuthException("AUTH_ERROR", "Cần đăng nhập để truy cập project", HttpStatus.UNAUTHORIZED);
        }
        if (user.getRole() != UserRole.ADMIN) {
            return projectRepository.findByIdAndOwnerUserId(id, user.getId())
                    .orElseThrow(() -> {
                        if (projectRepository.existsById(id)) {
                            recordAccessDenied(user.getId(), id, "NOT_OWNER");
                            return new AuthException("FORBIDDEN", "Không có quyền truy cập project này", HttpStatus.FORBIDDEN);
                        }
                        return new ProjectNotFoundException(id);
                    });
        }

        // Với tài khoản ADMIN:
        Project project = findOrThrow(id);
        if (project.getOwnerUserId() != null && project.getOwnerUserId().equals(user.getId())) {
            return project;
        }
        if (breakGlassAccessService != null && breakGlassAccessService.hasActiveBreakGlass(id, user.getId())) {
            return project;
        }
        recordAccessDenied(user.getId(), id, "ADMIN_WITHOUT_BREAK_GLASS");
        throw new AuthException("FORBIDDEN", "Admin không có quyền truy cập dữ liệu dự án này khi chưa kích hoạt Break-Glass", HttpStatus.FORBIDDEN);
    }

    @Transactional(readOnly = true)
    public void requireProjectOwner(Project project, AuthUser user) {
        if (user == null) {
            throw new AuthException("AUTH_ERROR", "Cần đăng nhập để truy cập project", HttpStatus.UNAUTHORIZED);
        }
        if (user.getRole() != UserRole.ADMIN) {
            if (project.getOwnerUserId() == null || !project.getOwnerUserId().equals(user.getId())) {
                recordAccessDenied(user.getId(), project.getId(), "NOT_OWNER");
                throw new AuthException("FORBIDDEN", "Không có quyền truy cập project này", HttpStatus.FORBIDDEN);
            }
            return;
        }
        if (project.getOwnerUserId() != null && project.getOwnerUserId().equals(user.getId())) {
            return;
        }
        if (breakGlassAccessService != null && breakGlassAccessService.hasActiveBreakGlass(project.getId(), user.getId())) {
            return;
        }
        recordAccessDenied(user.getId(), project.getId(), "ADMIN_WITHOUT_BREAK_GLASS");
        throw new AuthException("FORBIDDEN", "Admin không có quyền truy cập dữ liệu dự án này khi chưa kích hoạt Break-Glass", HttpStatus.FORBIDDEN);
    }

    /**
     * Cho phép xem metadata tóm tắt của project (ID, tên, trạng thái, ngày tạo).
     * Admin có quyền xem metadata theo thẩm quyền quản trị; User thường chỉ xem metadata của chính mình.
     */
    @Transactional(readOnly = true)
    public Project requireMetadataAccess(Long id, AuthUser user) {
        if (user == null) {
            throw new AuthException("AUTH_ERROR", "Cần đăng nhập để truy cập project", HttpStatus.UNAUTHORIZED);
        }
        if (user.getRole() == UserRole.ADMIN) {
            return findOrThrow(id);
        }
        return requireProjectOwner(id, user);
    }

    /**
     * Kiểm tra quyền truy cập vào source code hoặc artifact kiểm thử chi tiết của dự án.
     * Bắt buộc phải là Project Owner hoặc có Break-Glass còn hiệu lực.
     */
    @Transactional
    public Project requireSourceAccess(Long id, AuthUser user) {
        Project project = requireProjectOwner(id, user);
        if (activityService != null && user != null) {
            activityService.record(user.getId(), ActivityAction.SOURCE_ACCESSED, id, Map.of());
        }
        return project;
    }

    @Transactional(readOnly = true)
    public void requireAccess(Long id, AuthUser user) {
        requireProjectOwner(id, user);
    }

    @Transactional
    public void delete(Long id) {
        Project project = findOrThrow(id);
        // Phase 3.3: Dọn dẹp an toàn các tài nguyên sandbox, cache volume và ephemeral workspaces
        if (sandboxTestRunService != null) {
            sandboxTestRunService.cleanupProjectResources(id);
        }
        if (storageWorkspaceService != null) {
            storageWorkspaceService.cleanupProjectWorkspaces(id);
        }
        if (project.getStoragePath() != null) {
            fileStorageService.delete(Path.of(project.getStoragePath()));
        }
        projectRepository.delete(project);
        log.info("Đã xóa project {}", id);
    }

    @Transactional
    public void delete(Long id, AuthUser user) {
        Project project = requireProjectOwner(id, user);
        if (activityService != null) {
            String projectName = project.getName() != null ? project.getName() : "";
            activityService.record(user.getId(), ActivityAction.PROJECT_DELETED, null,
                    Map.of("deletedProjectId", id, "projectName", projectName));
        }
        // Phase 3.3: Dọn dẹp an toàn các tài nguyên sandbox, cache volume và ephemeral workspaces
        if (sandboxTestRunService != null) {
            sandboxTestRunService.cleanupProjectResources(id);
        }
        if (storageWorkspaceService != null) {
            storageWorkspaceService.cleanupProjectWorkspaces(id);
        }
        if (project.getStoragePath() != null) {
            fileStorageService.delete(Path.of(project.getStoragePath()));
        }
        projectRepository.delete(project);
        log.info("Đã xóa project {}", id);
    }

    @Transactional
    public ProjectDto complete(Long id, AuthUser user) {
        Project project = requireProjectOwner(id, user);
        if (project.getStatus() == ProjectStatus.COVERAGE_ANALYZED) {
            project.setStatus(ProjectStatus.COMPLETED);
            project = projectRepository.save(project);
            log.info("Project {} đã hoàn thành toàn bộ pipeline, cập nhật trạng thái COMPLETED", id);
        }
        return projectMapper.toDto(project);
    }

    private Project save(String name, SourceType sourceType, String sourceUrl, Path dir, AuthUser owner) {
        Project project = new Project();
        project.setName(name);
        project.setSourceType(sourceType);
        project.setSourceUrl(sourceUrl);
        project.setStoragePath(dir.toString());
        project.setStatus(ProjectStatus.UPLOADED);
        project.setOwnerUserId(owner.getId());
        project.setStorageEncrypted(false);
        project = projectRepository.save(project);

        if (storageEncryptionService != null && storageEncryptionService.isEncryptionEnabled()) {
            try {
                javax.crypto.SecretKey dek = storageEncryptionService.generateDek();
                String wrappedDek = storageEncryptionService.wrapDek(dek);
                Path stagingEnc = dir.resolveSibling(dir.getFileName().toString() + ".staging-enc");
                storageEncryptionService.encryptDirectory(dir, stagingEnc, dek, project.getId());

                Path backup = dir.resolveSibling(dir.getFileName().toString() + ".bak");
                Files.move(dir, backup, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                Files.move(stagingEnc, dir, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                fileStorageService.delete(backup);

                project.setStorageEncrypted(true);
                project.setEncryptedDek(wrappedDek);
                project.setDekKeyVersion(storageEncryptionService.getKekVersion());
                project.setEncryptionAlgorithm("AES-256-GCM");
                project = projectRepository.save(project);
                log.info("Project {} đã được mã hóa lưu trữ tự động bằng AES-256-GCM", project.getId());
            } catch (Exception e) {
                log.error("Lỗi khi mã hóa source cho project {}: {}", project.getId(), e.getMessage(), e);
                fileStorageService.delete(dir);
                projectRepository.delete(project);
                throw new StorageException("Không thể mã hóa source code của project: " + e.getMessage(), e);
            }
        }

        if (activityService != null) {
            ActivityAction action = sourceType == SourceType.GITHUB ? ActivityAction.SOURCE_IMPORTED : ActivityAction.SOURCE_UPLOADED;
            activityService.record(owner.getId(), action, project.getId(), Map.of("name", name, "sourceType", sourceType.name()));
        }
        return project;
    }

    private Project findOrThrow(Long id) {
        return projectRepository.findById(id).orElseThrow(() -> new ProjectNotFoundException(id));
    }

    private void recordAccessDenied(Long userId, Long projectId, String reason) {
        if (activityService != null && userId != null) {
            try {
                activityService.recordIsolated(userId, ActivityAction.PROJECT_ACCESS_DENIED, projectId, Map.of("reason", reason));
            } catch (Exception ex) {
                log.warn("Không thể ghi nhận audit log access denied [userId={}, projectId={}]: {}",
                        userId, projectId, ex.getMessage());
            }
        }
    }

    private void requireOwner(AuthUser owner) {
        if (owner == null) {
            throw new AuthException("AUTH_ERROR", "Cần đăng nhập để tạo project", HttpStatus.UNAUTHORIZED);
        }
    }

    private void requireSpringBootProject(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            boolean hasBuildFile = walk.anyMatch(p -> BUILD_FILES.contains(p.getFileName().toString()));
            if (!hasBuildFile) {
                throw new InvalidProjectSourceException("Không tìm thấy pom.xml hoặc build.gradle — không phải project Java");
            }
        } catch (IOException e) {
            throw new StorageException("Không đọc được thư mục source", e);
        }
    }

    private String stripZipExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "project";
        }
        return filename.toLowerCase().endsWith(".zip")
                ? filename.substring(0, filename.length() - 4)
                : filename;
    }

    private String repoName(String url) {
        String trimmed = url.replaceAll("/+$", "");
        String name = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }
}
