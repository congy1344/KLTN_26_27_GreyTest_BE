package com.greytest.entity;

import com.greytest.entity.enums.AnnotationCategory;
import com.greytest.entity.enums.AnnotationTargetType;

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
@Table(name = "relevant_annotation")
@Getter
@Setter
public class RelevantAnnotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    private Long classId;

    private Long methodId;

    @Enumerated(EnumType.STRING)
    private AnnotationTargetType targetType;

    @Enumerated(EnumType.STRING)
    private AnnotationCategory category;

    private String annotationName;

    @Column(length = 2000)
    private String attributes;
}
