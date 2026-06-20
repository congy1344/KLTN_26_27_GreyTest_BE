package com.greytest.entity;

import java.math.BigDecimal;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "coverage_detail")
@Getter
@Setter
public class CoverageDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long reportId;

    private Long methodId;

    private BigDecimal lineCoverage;

    private BigDecimal branchCoverage;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Integer> missedLines;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Integer> missedBranches;

    private Boolean hasGap;
}
