package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.TestPlanCoveredRule;

public interface TestPlanCoveredRuleRepository extends JpaRepository<TestPlanCoveredRule, Long> {

    List<TestPlanCoveredRule> findByTestPlanIdIn(List<Long> testPlanIds);

    List<TestPlanCoveredRule> findByTestPlanId(Long testPlanId);

    void deleteByTestPlanId(Long testPlanId);
}
