package com.greytest.entity;

import java.time.LocalDateTime;
import java.util.Map;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.greytest.entity.enums.Priority;
import com.greytest.entity.enums.ReviewStatus;
import com.greytest.entity.enums.TestType;

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
@Table(name = "test_case")
@Getter
@Setter
public class TestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long testPlanId;

    private String caseCode;

    @Enumerated(EnumType.STRING)
    private TestType testType;

    private String description;

    private String preconditions;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> testData;

    private String expectedResult;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    private String traceSource;

    @Enumerated(EnumType.STRING)
    private ReviewStatus status;

    private Boolean isModified;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
