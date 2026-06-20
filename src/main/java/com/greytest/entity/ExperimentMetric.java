package com.greytest.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.greytest.entity.enums.MethodUsed;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "experiment_metric")
@Getter
@Setter
public class ExperimentMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    @Enumerated(EnumType.STRING)
    private MethodUsed methodUsed;

    private BigDecimal requirementCoverage;

    private BigDecimal lineCoverage;

    private BigDecimal branchCoverage;

    private Integer generationTimeSeconds;

    private BigDecimal userModificationRate;

    private Integer inputTokens;

    private Integer outputTokens;

    private BigDecimal stabilityScore;

    private BigDecimal traceabilityScore;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime runAt;
}
