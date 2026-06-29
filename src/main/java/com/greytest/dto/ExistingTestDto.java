package com.greytest.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ExistingTestDto(
        Long id,
        Long projectId,
        String filePath,
        String packageName,
        String testClassName,
        Long relatedClassId,
        Long relatedMethodId,
        List<Map<String, Object>> testMethods,
        List<String> imports,
        LocalDateTime createdAt) {
}
