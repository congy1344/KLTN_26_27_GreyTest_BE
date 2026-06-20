package com.greytest.mapper;

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
                project.getCreatedAt());
    }
}
