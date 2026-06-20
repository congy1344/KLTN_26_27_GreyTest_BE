package com.greytest.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.greytest.entity.enums.ClassType;

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
@Table(name = "java_class")
@Getter
@Setter
public class JavaClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    private String packageName;

    private String className;

    private String filePath;

    @Enumerated(EnumType.STRING)
    private ClassType classType;

    private String sourceCode;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
