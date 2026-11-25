package com.greytest.entity;

import java.time.LocalDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.greytest.entity.enums.ProjectStatus;
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

@Entity
@Table(name = "project")
@Getter
@Setter
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ownerUserId;

    private String name;

    @Enumerated(EnumType.STRING)
    private SourceType sourceType;

    private String sourceUrl;

    private String storagePath;

    @Column(nullable = false)
    private Boolean storageEncrypted = false;

    @Column(length = 512)
    private String encryptedDek;

    @Column(length = 64)
    private String dekKeyVersion;

    @Column(length = 32)
    private String encryptionAlgorithm;

    @Enumerated(EnumType.STRING)
    private ProjectStatus status;

    private Integer totalProductionFiles;

    private Integer parsedProductionFiles;

    private Integer failedParseFiles;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> failedParseFilePaths;

    private Long activeSourceRevisionId;

    private Long activeSourceUpdateId;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
