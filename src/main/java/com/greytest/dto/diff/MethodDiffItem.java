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
        boolean isServiceMethod,
        String servicePath) {

    public MethodDiffItem(
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
        this(className, qualifiedClassName, methodName, signature, methodKey,
                diffType, reason, beforeSource, afterSource, callerMethods, isServiceMethod, null);
    }
}
