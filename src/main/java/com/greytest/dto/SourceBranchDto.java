package com.greytest.dto;

public record SourceBranchDto(
        String branchId,
        String kind,
        String outcome,
        String condition,
        Integer lineStart,
        Integer lineEnd) {
}
