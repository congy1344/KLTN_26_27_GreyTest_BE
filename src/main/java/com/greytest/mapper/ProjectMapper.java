package com.greytest.mapper;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.greytest.dto.ProjectDto;
import com.greytest.entity.Project;

@Component
public class ProjectMapper {

    public ProjectDto toDto(Project project) {
        return new ProjectDto(
                project.getId(),
                project.getName(),
                project.getSourceType(),
                project.getSourceUrl(),
                project.getStatus(),
                project.getCreatedAt(),
                sourceAvailable(project));
    }

    private boolean sourceAvailable(Project project) {
        if (project.getStoragePath() == null || project.getStoragePath().isBlank()) {
            return false;
        }
        return Files.isDirectory(Path.of(project.getStoragePath()));
    }
}
