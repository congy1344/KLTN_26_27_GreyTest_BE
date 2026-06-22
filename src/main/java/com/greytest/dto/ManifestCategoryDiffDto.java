package com.greytest.dto;

import java.util.List;

public record ManifestCategoryDiffDto(
        int expectedCount,
        int actualCount,
        boolean exactMatch,
        List<String> missing,
        List<String> unexpected
) {
}
