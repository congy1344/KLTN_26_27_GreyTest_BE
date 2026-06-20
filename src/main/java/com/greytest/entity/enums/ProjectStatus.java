package com.greytest.entity.enums;

/** State machine của một project trong pipeline (xem WORKFLOW.md). */
public enum ProjectStatus {
    UPLOADED,
    ANALYZED,
    BR_PENDING_REVIEW,
    BR_APPROVED,
    PLAN_PENDING_REVIEW,
    PLAN_APPROVED,
    CASE_PENDING_REVIEW,
    CASE_APPROVED,
    TEST_GENERATED,
    COVERAGE_ANALYZED,
    COMPLETED,
    FAILED
}
