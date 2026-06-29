package com.greytest.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "existing_test")
@Getter
@Setter
public class ExistingTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    @Column(columnDefinition = "TEXT")
    private String filePath;

    private String packageName;

    private String testClassName;

    private Long relatedClassId;

    private Long relatedMethodId;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Map<String, Object>> testMethods;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> imports;

    @Column(columnDefinition = "TEXT")
    private String sourceCode;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
