package com.greytest.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/** Ground truth manifest do user/golden dataset cung cấp để validation. */
public record AnalysisManifestInput(
        @NotNull List<String> classes,
        @NotNull List<String> methods,
        @NotNull List<String> endpoints,
        @NotNull List<String> serviceRepositoryRelations
) {
}
