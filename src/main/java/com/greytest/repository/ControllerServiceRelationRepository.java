package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.ControllerServiceRelation;

public interface ControllerServiceRelationRepository extends JpaRepository<ControllerServiceRelation, Long> {
    List<ControllerServiceRelation> findByControllerClassId(Long controllerClassId);
}
