package com.greytest.dto;

import java.util.List;

public record AnalysisResultDto(
        Long projectId,
        String projectName,
        String status,
        int totalClasses,
        int totalMethods,
        int totalEndpoints,
        int totalRelations,
        int existingTestFiles,
        List<JavaClassDto> classes,
        List<ServiceRelationDto> relations
) {
}
