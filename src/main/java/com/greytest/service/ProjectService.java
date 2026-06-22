package com.greytest.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.greytest.dto.ProjectDto;
import com.greytest.entity.Project;
import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.SourceType;
import com.greytest.exception.InvalidProjectSourceException;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.exception.StorageException;
import com.greytest.mapper.ProjectMapper;
import com.greytest.repository.ProjectRepository;
import com.greytest.service.analysis.AnalysisService;
import com.greytest.service.storage.FileStorageService;
import com.greytest.service.storage.GithubService;

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
    private final AnalysisService analysisService;

    public ProjectService(
            ProjectRepository projectRepository,
            FileStorageService fileStorageService,
            GithubService githubService,
            ProjectMapper projectMapper,
            AnalysisService analysisService) {
        this.projectRepository = projectRepository;
        this.fileStorageService = fileStorageService;
        this.githubService = githubService;
        this.projectMapper = projectMapper;
        this.analysisService = analysisService;
    }

    @Transactional
    public ProjectDto createFromZip(MultipartFile file) {
        Path dir = fileStorageService.storeZip(file);
        requireSpringBootProject(dir);
        Project project = save(stripZipExtension(file.getOriginalFilename()), SourceType.ZIP, null, dir);
        return analyzeImportedProject(project, dir, "ZIP");
    }

    @Transactional
    public ProjectDto createFromGithub(String url) {
        Path dir = githubService.clone(url);
        requireSpringBootProject(dir);
        Project project = save(repoName(url), SourceType.GITHUB, url, dir);
        return analyzeImportedProject(project, dir, "GitHub " + url);
    }

    @Transactional(readOnly = true)
    public List<ProjectDto> getAll() {
        return projectRepository.findAll().stream().map(projectMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ProjectDto getById(Long id) {
        return projectMapper.toDto(findOrThrow(id));
    }

    @Transactional
    public void delete(Long id) {
        Project project = findOrThrow(id);
        if (project.getStoragePath() != null) {
            fileStorageService.delete(Path.of(project.getStoragePath()));
        }
        projectRepository.delete(project);
        log.info("Đã xóa project {}", id);
    }

    private Project save(String name, SourceType sourceType, String sourceUrl, Path dir) {
        Project project = new Project();
        project.setName(name);
        project.setSourceType(sourceType);
        project.setSourceUrl(sourceUrl);
        project.setStoragePath(dir.toString());
        project.setStatus(ProjectStatus.UPLOADED);
        return projectRepository.save(project);
    }

    private ProjectDto analyzeImportedProject(Project project, Path dir, String sourceDescription) {
        try {
            analysisService.analyze(project.getId());
            project.setStatus(ProjectStatus.ANALYZED);
            log.info("Tạo và phân tích project {} từ {} tại {}", project.getId(), sourceDescription, dir);
            return projectMapper.toDto(project);
        } catch (RuntimeException exception) {
            // DB transaction sẽ rollback; source trên filesystem cần được dọn riêng.
            fileStorageService.delete(dir);
            throw exception;
        }
    }

    private Project findOrThrow(Long id) {
        return projectRepository.findById(id).orElseThrow(() -> new ProjectNotFoundException(id));
    }

    // Project hợp lệ phải có file build (pom.xml/build.gradle) ở đâu đó trong cây thư mục.
    private void requireSpringBootProject(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            boolean hasBuildFile = walk.anyMatch(p -> BUILD_FILES.contains(p.getFileName().toString()));
            if (!hasBuildFile) {
                fileStorageService.delete(dir);
                throw new InvalidProjectSourceException("Không tìm thấy pom.xml hoặc build.gradle — không phải project Java");
            }
        } catch (IOException e) {
            fileStorageService.delete(dir);
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
