package com.greytest.service;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.dto.ProjectServiceDto;
import com.greytest.exception.ProjectNotFoundException;
import com.greytest.repository.ProjectRepository;

/**
 * Liệt kê các module source của project cùng trạng thái pipeline riêng.
 */
@Service
public class ProjectServiceScopeService {

    private final ProjectRepository projects;
    private final ServiceScopeResolver scopes;
    private final ServicePipelineStatusService statuses;

    public ProjectServiceScopeService(
            ProjectRepository projects,
            ServiceScopeResolver scopes,
            ServicePipelineStatusService statuses) {
        this.projects = projects;
        this.scopes = scopes;
        this.statuses = statuses;
    }

    @Transactional(readOnly = true)
    public List<ProjectServiceDto> list(Long projectId) {
        var project = projects.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        return scopes.listScopes(projectId).stream()
                .map(scope -> new ProjectServiceDto(
                        scope.servicePath(),
                        ".".equals(scope.servicePath())
                                ? project.getName()
                                : Path.of(scope.servicePath()).getFileName().toString(),
                        statuses.status(projectId, scope)))
                .toList();
    }
}
