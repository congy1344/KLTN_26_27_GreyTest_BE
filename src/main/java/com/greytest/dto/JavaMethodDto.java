package com.greytest.dto;

import java.util.List;

public record JavaMethodDto(
        Long id,
        String methodName,
        String returnType,
        List<MethodParamDto> parameters,
        List<String> throwsList,
        String visibility,
        String sourceCode,
        Integer lineStart,
        Integer lineEnd,
        List<EndpointDto> endpoints
) {
}
