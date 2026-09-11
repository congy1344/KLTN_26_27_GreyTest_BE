package com.greytest.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.greytest.entity.enums.SourceType;

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
 * Lưu trữ phiên bản snapshot source code của project (baseline hoặc candidate).
 */
@Entity
@Table(name = "source_revision")
@Getter
@Setter
public class SourceRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceType sourceType;

    private String sourceUrl;

    private String branch;

    private String commitSha;

    @Column(nullable = false)
    private String storagePath;

    private String contentHash;

    private String logicalRoot;

    @JdbcTypeCode(SqlTypes.JSON)
    private String analysisSnapshot;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
