package com.greytest.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Thực thể lưu trữ cấu hình gói dịch vụ và hạn mức Quota trong cơ sở dữ liệu.
 */
@Entity
@Table(name = "tier_package")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TierPackage {

    @Id
    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    private Long price = 0L;

    @Column(nullable = false, length = 20)
    private String cycle = "MONTHLY";

    @Column(name = "quota_limit", nullable = false)
    private Integer quotaLimit = 100;

    @Column(name = "batch_concurrency", nullable = false)
    private Integer batchConcurrency = 2;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String features;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public TierPackage(String code, String name, Long price, String cycle, Integer quotaLimit, Integer batchConcurrency,
            String features, String description, Boolean active) {
        this.code = code;
        this.name = name;
        this.price = price != null ? price : 0L;
        this.cycle = cycle != null ? cycle : "MONTHLY";
        this.quotaLimit = quotaLimit != null ? quotaLimit : 100;
        this.batchConcurrency = batchConcurrency != null ? batchConcurrency : 2;
        this.features = features != null ? features : "";
        this.description = description != null ? description : "";
        this.active = active != null ? active : true;
    }
}

