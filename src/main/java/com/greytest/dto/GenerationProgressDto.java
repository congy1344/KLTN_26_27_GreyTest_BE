package com.greytest.dto;

import java.util.List;

public record GenerationProgressDto(
        GenerationProgressStage stage,
        GenerationProgressStatus status,
        int percent,
        int completedSteps,
        int totalSteps,
        List<GenerationProgressStepDto> steps,
        List<GenerationProgressLogDto> logs,
        Boolean busy) {

    public GenerationProgressDto(
            GenerationProgressStage stage,
            GenerationProgressStatus status,
            int percent,
            int completedSteps,
            int totalSteps,
            List<GenerationProgressStepDto> steps,
            List<GenerationProgressLogDto> logs) {
        this(stage, status, percent, completedSteps, totalSteps, steps, logs, false);
    }
}
