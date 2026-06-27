package com.greytest.dto;

import java.util.List;

/** Manifest ổn định, có thứ tự xác định để export và làm golden ground truth. */
public record AnalysisManifestDto(
        Long projectId,
        String projectName,
        String manifestVersion,
        List<String> classes,
        List<String> methods,
        List<String> endpoints,
        List<String> annotations,
        List<String> serviceRepositoryRelations,
        List<String> controllerServiceRelations
) {
}
