package com.greytest.dto;

import com.greytest.entity.enums.SourceUpdateAction;
import com.greytest.entity.enums.SourceUpdateReviewStatus;

public record SourceUpdateItemPatchRequest(
        SourceUpdateAction action,
        SourceUpdateReviewStatus reviewStatus,
        String modifiedAfterData,
        String reason,
        Long expectedVersion,
        Long expectedUserRevision,
        String conflictStage,
        String conflictTargetKey,
        String resolution) {

    public SourceUpdateItemPatchRequest(SourceUpdateAction action, SourceUpdateReviewStatus reviewStatus,
            String modifiedAfterData, String reason) {
        this(action, reviewStatus, modifiedAfterData, reason, null, null, null, null, null);
    }
}
