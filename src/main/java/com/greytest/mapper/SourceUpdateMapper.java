package com.greytest.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.greytest.dto.SourceRevisionDto;
import com.greytest.dto.SourceUpdateDto;
import com.greytest.dto.SourceUpdateItemDto;
import com.greytest.entity.SourceRevision;
import com.greytest.entity.SourceUpdate;
import com.greytest.entity.SourceUpdateItem;

@Component
public class SourceUpdateMapper {

    public SourceRevisionDto toRevisionDto(SourceRevision revision) {
        if (revision == null) {
            return null;
        }
        return new SourceRevisionDto(
                revision.getId(),
                revision.getProjectId(),
                revision.getSourceType(),
                revision.getSourceUrl(),
                revision.getBranch(),
                revision.getCommitSha(),
                revision.getStoragePath(),
                revision.getContentHash(),
                revision.getLogicalRoot(),
                revision.getCreatedAt());
    }

    public SourceUpdateItemDto toItemDto(SourceUpdateItem item) {
        if (item == null) {
            return null;
        }
        return new SourceUpdateItemDto(
                item.getId(),
                item.getSourceUpdateId(),
                item.getTargetType(),
                item.getTargetId(),
                item.getTargetKey(),
                item.getAction(),
                item.getReason(),
                item.getBeforeData(),
                item.getAfterData(),
                item.getReviewStatus(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }

    public SourceUpdateDto toUpdateDto(SourceUpdate update, List<SourceUpdateItem> items) {
        if (update == null) {
            return null;
        }
        List<SourceUpdateItemDto> itemDtos = items != null
                ? items.stream().map(this::toItemDto).toList()
                : List.of();

        return new SourceUpdateDto(
                update.getId(),
                update.getProjectId(),
                update.getBaseRevisionId(),
                update.getCandidateRevisionId(),
                update.getStatus(),
                update.getTotalChangedMethods(),
                update.getImpactSummary(),
                itemDtos,
                update.getCreatedAt(),
                update.getUpdatedAt());
    }
}
