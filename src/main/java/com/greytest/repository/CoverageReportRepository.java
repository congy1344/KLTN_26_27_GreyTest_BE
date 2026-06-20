package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.CoverageReport;

public interface CoverageReportRepository extends JpaRepository<CoverageReport, Long> {
    List<CoverageReport> findByProjectId(Long projectId);
}
