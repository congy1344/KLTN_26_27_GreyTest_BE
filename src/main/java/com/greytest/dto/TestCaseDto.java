package com.greytest.dto;

import java.time.LocalDateTime;
import java.util.Map;
import com.greytest.entity.enums.Priority;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.TestType;

public record TestCaseDto(Long id, Long testPlanId, String caseCode, TestType testType, String description,
        String preconditions, Map<String,Object> testData, String expectedResult, Priority priority,
        String traceSource, ReviewStatus status, Boolean isModified, LocalDateTime createdAt) {}
