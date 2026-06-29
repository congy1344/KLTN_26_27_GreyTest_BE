package com.greytest.dto;

import java.time.LocalDateTime;

import com.greytest.entity.enums.ProjectStatus;
import com.greytest.entity.enums.SourceType;

public record ProjectDto(
        Long id,
        String name,
        SourceType sourceType,
        String sourceUrl,
        ProjectStatus status,
        LocalDateTime createdAt,
        Long ownerUserId,
        boolean sourceAvailable) {
}
