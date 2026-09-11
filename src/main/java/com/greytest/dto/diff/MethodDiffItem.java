package com.greytest.dto.diff;

import java.util.List;

/**
 * Kết quả so sánh AST của một method.
 */
public record MethodDiffItem(
        String className,
        String qualifiedClassName,
        String methodName,
        String signature,
        String methodKey,
        MethodDiffType diffType,
        String reason,
        String beforeSource,
        String afterSource,
        List<String> callerMethods,
        boolean isServiceMethod) {
}
