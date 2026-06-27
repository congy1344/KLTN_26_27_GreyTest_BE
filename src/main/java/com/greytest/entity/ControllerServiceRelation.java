package com.greytest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "controller_service_relation")
@Getter
@Setter
public class ControllerServiceRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long controllerClassId;

    private Long controllerMethodId;

    private Long serviceClassId;

    private Long serviceMethodId;

    @Column(length = 255)
    private String serviceFieldName;

    @Column(length = 1000)
    private String serviceFieldType;

    @Column(length = 255)
    private String calledMethodName;
}
