package com.greytest.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.UnitTest;
public interface UnitTestRepository extends JpaRepository<UnitTest, Long> {
    UnitTest findFirstByTestCaseId(Long testCaseId);

    default UnitTest findByTestCaseId(Long testCaseId) {
        return findFirstByTestCaseId(testCaseId);
    }

    List<UnitTest> findByTestCaseIdIn(List<Long> testCaseIds);

    /** Chỉ lấy ID của test có metadata thuộc project vừa áp dụng source. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT ut.id FROM UnitTest ut JOIN TestCase tc ON tc.id = ut.testCaseId
            JOIN TestPlan tp ON tp.id = tc.testPlanId WHERE tp.projectId = :projectId
            AND locate('// GREYTEST_SOURCE_V1 ', ut.sourceCode) > 0 ORDER BY ut.id
            """)
    List<Long> findStampedIdsByProjectId(Long projectId);

    /** Khóa test trong lúc đối chiếu để không ghi đè lần sinh lại đồng thời. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT ut FROM UnitTest ut WHERE ut.id = :id")
    Optional<UnitTest> findByIdForUpdate(Long id);

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
