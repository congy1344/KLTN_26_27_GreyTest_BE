package com.greytest.dto;

/** Phản hồi nhanh khi một tác vụ sinh AI đã được đưa vào hàng đợi nền. */
public record GenerationJobAcceptedDto(
        GenerationProgressStage stage,
        GenerationProgressStatus status,
        String message) {}
