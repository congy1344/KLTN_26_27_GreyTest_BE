package com.greytest.dto;

import com.greytest.entity.enums.SourceUpdateAction;
import com.greytest.entity.enums.SourceUpdateReviewStatus;

public record SourceUpdateItemPatchRequest(
        SourceUpdateAction action,
        SourceUpdateReviewStatus reviewStatus,
        String modifiedAfterData,
        String reason) {
}
