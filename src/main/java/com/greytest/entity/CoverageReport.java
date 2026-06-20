package com.greytest.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "coverage_report")
@Getter
@Setter
public class CoverageReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    private BigDecimal lineCoverage;

    private BigDecimal branchCoverage;

    private BigDecimal requirementCoverage;

    private Integer totalLines;

    private Integer coveredLines;

    private Integer totalBranches;

    private Integer coveredBranches;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime uploadedAt;

    private String xmlFilePath;
}
