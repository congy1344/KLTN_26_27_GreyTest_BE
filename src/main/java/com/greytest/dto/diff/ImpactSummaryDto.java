package com.greytest.dto.diff;

import java.util.List;

/**
 * Báo cáo tổng kết mức độ ảnh hưởng của thay đổi source code lên các artifact trong hệ thống.
 */
public record ImpactSummaryDto(
        int totalChangedMethods,
        int addedMethodsCount,
        int modifiedMethodsCount,
        int deletedMethodsCount,
        List<MethodDiffItem> changedMethods,
        List<String> affectedServiceMethods,
        List<Long> affectedBusinessRuleIds,
        List<Long> affectedTestPlanIds,
        List<Long> affectedTestCaseIds,
        List<Long> affectedUnitTestIds) {
}
