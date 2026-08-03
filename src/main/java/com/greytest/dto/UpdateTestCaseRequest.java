package com.greytest.dto;
import java.util.Map;
import com.greytest.entity.enums.Priority;
import com.greytest.entity.enums.TestType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
public record UpdateTestCaseRequest(@NotNull TestType testType, @NotBlank String description,
        @NotBlank String preconditions, @NotNull Map<String,Object> testData, @NotBlank String expectedResult,
        @NotNull Priority priority, @NotBlank String traceSource) {}
