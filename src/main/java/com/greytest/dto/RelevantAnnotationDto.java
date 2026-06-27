package com.greytest.dto;

public record RelevantAnnotationDto(
        Long id,
        String targetType,
        String category,
        String annotationName,
        String attributes
) {
}
