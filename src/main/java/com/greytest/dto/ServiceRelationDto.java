package com.greytest.dto;

public record ServiceRelationDto(
        Long id,
        String serviceClassName,
        String serviceQualifiedName,
        String repositoryClassName,
        String repositoryQualifiedName
) {
}
