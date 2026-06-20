package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.ExperimentMetric;

public interface ExperimentMetricRepository extends JpaRepository<ExperimentMetric, Long> {
    List<ExperimentMetric> findByProjectId(Long projectId);
}
