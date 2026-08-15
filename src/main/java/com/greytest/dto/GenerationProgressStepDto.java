package com.greytest.dto;

public record GenerationProgressStepDto(
        int order,
        String label,
        GenerationProgressStepStatus status,
        int percent,
        String errorMessage) {}
