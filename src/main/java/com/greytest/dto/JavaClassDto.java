package com.greytest.dto;

import java.util.List;

public record JavaClassDto(
        Long id,
        String packageName,
        String className,
        String qualifiedName,
        String classType,
        String filePath,
        List<RelevantAnnotationDto> annotations,
        List<JavaMethodDto> methods
) {
}
