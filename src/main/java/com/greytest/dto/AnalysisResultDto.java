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
        int totalControllerServiceRelations,
        int existingTestFiles,
        int totalProductionFiles,
        int parsedProductionFiles,
        int failedParseFiles,
        List<String> failedParseFilePaths,
        List<JavaClassDto> classes,
        List<ServiceRelationDto> relations,
        List<ControllerServiceRelationDto> controllerServiceRelations
) {
}
