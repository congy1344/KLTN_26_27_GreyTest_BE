package com.greytest.dto;

import java.util.List;

public record UnitTestFileDto(
        String filePath,
        String testClassName,
        String packageName,
        int testCount,
        List<String> caseCodes,
        String sourceCode) {
}
