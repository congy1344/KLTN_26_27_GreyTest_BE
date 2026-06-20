package com.greytest.entity;

import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.greytest.entity.enums.Visibility;

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
@Table(name = "java_method")
@Getter
@Setter
public class JavaMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long classId;

    private String methodName;

    private String returnType;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<MethodParam> parameters;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> throwsList;

    @Enumerated(EnumType.STRING)
    private Visibility visibility;

    private String sourceCode;

    private Integer lineStart;

    private Integer lineEnd;
}
