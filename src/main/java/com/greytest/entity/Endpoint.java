package com.greytest.entity;

import com.greytest.entity.enums.HttpMethod;

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
@Table(name = "endpoint")
@Getter
@Setter
public class Endpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long methodId;

    @Enumerated(EnumType.STRING)
    private HttpMethod httpMethod;

    private String path;

    private String consumes;

    private String produces;
}
