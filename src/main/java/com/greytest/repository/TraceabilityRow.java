package com.greytest.repository;

/** Projection cho một dòng của view v_traceability. */
public interface TraceabilityRow {
    Long getRuleId();

    String getRuleCode();

    String getRuleDescription();

    Long getPlanId();

    String getPlanCode();

    String getPlanTitle();

    String getTestType();

    Long getCaseId();

    String getCaseCode();

    String getCaseDescription();

    Long getUnitTestId();

    String getUnitTestName();
}
