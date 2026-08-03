package com.greytest.dto;
import java.time.LocalDateTime;
public record UnitTestDto(Long id, Long testCaseId, String testClassName, String testMethodName, String packageName,
        String generationType, String existingTestFilePath, String sourceCode, String filePath, LocalDateTime createdAt) {}
