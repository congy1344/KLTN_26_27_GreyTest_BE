package com.greytest.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.CoverageReport;

public interface CoverageReportRepository extends JpaRepository<CoverageReport, Long> {
    List<CoverageReport> findByProjectId(Long projectId);

    Optional<CoverageReport> findTopByProjectIdOrderByIdDesc(Long projectId);

    // Re-analyze: source đổi nên lịch sử coverage cũ hết ý nghĩa
    void deleteByProjectId(Long projectId);
}
