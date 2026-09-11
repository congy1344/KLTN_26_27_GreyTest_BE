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
        LocalDateTime updatedAt) {
}
