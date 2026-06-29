package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.ExistingTest;

public interface ExistingTestRepository extends JpaRepository<ExistingTest, Long> {
    List<ExistingTest> findByProjectId(Long projectId);

    void deleteByProjectId(Long projectId);
}
