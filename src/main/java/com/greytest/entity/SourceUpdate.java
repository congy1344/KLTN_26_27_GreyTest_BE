package com.greytest.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.greytest.entity.enums.SourceUpdateStatus;

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

/**
 * Phiên cập nhật source code dạng bản nháp (Draft Update).
 */
@Entity
@Table(name = "source_update")
@Getter
@Setter
public class SourceUpdate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    private Long baseRevisionId;

    @Column(nullable = false)
    private Long candidateRevisionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceUpdateStatus status;

    private Integer totalChangedMethods;

    @JdbcTypeCode(SqlTypes.JSON)
    private String impactSummary;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
