package com.greytest.dto;

import java.time.LocalDateTime;

import com.greytest.entity.enums.SourceType;

public record SourceRevisionDto(
        Long id,
        Long projectId,
        SourceType sourceType,
        String sourceUrl,
        String branch,
        String commitSha,
        String storagePath,
        String contentHash,
        String logicalRoot,
        LocalDateTime createdAt) {
}
