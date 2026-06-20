package com.greytest.entity;

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
@Table(name = "unit_test")
@Getter
@Setter
public class UnitTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long testCaseId;

    private String testClassName;

    private String testMethodName;

    private String packageName;

    private String sourceCode;

    private String filePath;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
