package com.greytest.dto;

public record AnalysisManifestValidationDto(
        Long projectId,
        String projectName,
        boolean exactMatch,
        ManifestCategoryDiffDto classes,
        ManifestCategoryDiffDto methods,
        ManifestCategoryDiffDto endpoints,
        ManifestCategoryDiffDto annotations,
        ManifestCategoryDiffDto serviceRepositoryRelations,
        ManifestCategoryDiffDto controllerServiceRelations
) {
}
