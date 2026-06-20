package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.CoverageDetail;

public interface CoverageDetailRepository extends JpaRepository<CoverageDetail, Long> {
    List<CoverageDetail> findByReportId(Long reportId);

    List<CoverageDetail> findByReportIdAndHasGapTrue(Long reportId);
}
