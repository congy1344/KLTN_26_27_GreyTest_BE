package com.greytest.dto.diff;

/**
 * Loại thay đổi của một Java method giữa 2 phiên bản source code.
 */
public enum MethodDiffType {
    ADDED,
    MODIFIED,
    DELETED,
    UNCHANGED
}
