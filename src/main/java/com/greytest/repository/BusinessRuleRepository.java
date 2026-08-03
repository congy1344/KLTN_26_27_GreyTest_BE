package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.greytest.entity.BusinessRule;
import com.greytest.entity.enums.ReviewStatus;

public interface BusinessRuleRepository extends JpaRepository<BusinessRule, Long> {
    List<BusinessRule> findByProjectId(Long projectId);

    // Re-analyze: xóa BR kéo theo Plan/Case/Unit Test qua FK CASCADE
    void deleteByProjectId(Long projectId);

    List<BusinessRule> findByProjectIdAndStatus(Long projectId, ReviewStatus status);

    @Query("SELECT br FROM BusinessRule br WHERE br.projectId = :projectId AND br.isModified = true")
    List<BusinessRule> findModifiedRules(@Param("projectId") Long projectId);

    // Truy vấn Traceability Matrix từ view v_traceability (BR → Plan → Case → Unit Test)
    @Query(value = """
            SELECT rule_id AS "ruleId", rule_code AS "ruleCode", rule_description AS "ruleDescription",
                   plan_id AS "planId", plan_code AS "planCode", plan_title AS "planTitle", test_type AS "testType",
                   case_id AS "caseId", case_code AS "caseCode", case_description AS "caseDescription",
                   unit_test_id AS "unitTestId", unit_test_name AS "unitTestName"
            FROM v_traceability
            WHERE project_id = :projectId
            ORDER BY rule_code, plan_code, case_code
            """, nativeQuery = true)
    List<TraceabilityRow> findTraceabilityRows(@Param("projectId") Long projectId);
}
