package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.JavaMethod;

public interface JavaMethodRepository extends JpaRepository<JavaMethod, Long> {
    List<JavaMethod> findByClassId(Long classId);
}
