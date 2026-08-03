package com.greytest.dto;

import java.util.List;

public record TraceabilityMatrixDto(
        Long projectId,
        List<TraceabilityRowDto> rows,
        List<TraceabilityRowDto> uncoveredRules) {
}
