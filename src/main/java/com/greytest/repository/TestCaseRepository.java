package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.greytest.entity.TestCase;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {
    List<TestCase> findByTestPlanId(Long testPlanId);

    List<TestCase> findByTestPlanIdIn(List<Long> testPlanIds);

    @Query("SELECT tc FROM TestCase tc JOIN TestPlan tp ON tc.testPlanId = tp.id WHERE tp.projectId = :projectId")
    List<TestCase> findByProjectId(@Param("projectId") Long projectId);
}
