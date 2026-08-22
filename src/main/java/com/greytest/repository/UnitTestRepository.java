package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.UnitTest;

public interface UnitTestRepository extends JpaRepository<UnitTest, Long> {
    UnitTest findByTestCaseId(Long testCaseId);

    List<UnitTest> findByTestCaseIdIn(List<Long> testCaseIds);

    @org.springframework.data.jpa.repository.Query(value = """
            SELECT COUNT(ut.id)
            FROM unit_test ut
            JOIN test_case tc ON tc.id = ut.test_case_id
            JOIN test_plan tp ON tp.id = tc.test_plan_id
            JOIN project p ON p.id = tp.project_id
            WHERE p.owner_user_id = :userId
            """, nativeQuery = true)
    long countByOwnerUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}
