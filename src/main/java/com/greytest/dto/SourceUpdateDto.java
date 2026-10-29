package com.greytest.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.greytest.entity.enums.SourceUpdateStatus;

public record SourceUpdateDto(
        Long id,
        Long projectId,
        Long baseRevisionId,
        Long candidateRevisionId,
        SourceUpdateStatus status,
        Integer totalChangedMethods,
        String impactSummary,
        List<SourceUpdateItemDto> items,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version,
        GenerationSummaryDto generationSummary) {
    public SourceUpdateDto(Long id, Long projectId, Long baseRevisionId, Long candidateRevisionId,
            SourceUpdateStatus status, Integer totalChangedMethods, String impactSummary,
            List<SourceUpdateItemDto> items, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, projectId, baseRevisionId, candidateRevisionId, status, totalChangedMethods, impactSummary,
                items, createdAt, updatedAt, null, null);
    }
}
