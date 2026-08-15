package com.greytest.dto;

/** Trạng thái hiện tại của một tác vụ sinh AI. */
public enum GenerationProgressStatus {
    IDLE,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}
