package com.greytest.entity.enums;

/**
 * Trạng thái của phiên cập nhật source (bản nháp).
 */
public enum SourceUpdateStatus {
    DRAFT,
    ANALYZING,
    ANALYZED,
    GENERATING,
    READY_TO_APPLY,
    APPLIED,
    CANCELLED,
    FAILED
}
