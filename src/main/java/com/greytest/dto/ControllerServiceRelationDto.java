package com.greytest.dto;

public record ControllerServiceRelationDto(
        Long id,
        String controllerClassName,
        String controllerQualifiedName,
        String controllerMethodName,
        String serviceClassName,
        String serviceQualifiedName,
        String serviceMethodName,
        String serviceFieldName,
        String serviceFieldType
) {
}
