package com.greytest.dto;

public record EndpointDto(
        Long id,
        String httpMethod,
        String path,
        String consumes,
        String produces,
        String methodName
) {
}
