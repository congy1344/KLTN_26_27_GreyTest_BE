package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.ServiceRepositoryRelation;

public interface ServiceRepositoryRelationRepository
        extends JpaRepository<ServiceRepositoryRelation, Long> {
    List<ServiceRepositoryRelation> findByServiceClassId(Long serviceClassId);
}
