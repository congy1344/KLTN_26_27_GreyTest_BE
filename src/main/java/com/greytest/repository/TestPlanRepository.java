package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.TestPlan;

public interface TestPlanRepository extends JpaRepository<TestPlan, Long> {
    List<TestPlan> findByProjectId(Long projectId);

    List<TestPlan> findByBusinessRuleId(Long businessRuleId);
}
