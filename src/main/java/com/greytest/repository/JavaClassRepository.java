package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.JavaClass;
import com.greytest.entity.enums.ClassType;

public interface JavaClassRepository extends JpaRepository<JavaClass, Long> {
    List<JavaClass> findByProjectId(Long projectId);

    List<JavaClass> findByProjectIdAndClassType(Long projectId, ClassType classType);
}
