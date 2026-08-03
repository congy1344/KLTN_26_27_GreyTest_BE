package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.greytest.entity.UnitTest;

public interface UnitTestRepository extends JpaRepository<UnitTest, Long> {
    UnitTest findByTestCaseId(Long testCaseId);

    List<UnitTest> findByTestCaseIdIn(List<Long> testCaseIds);
}
