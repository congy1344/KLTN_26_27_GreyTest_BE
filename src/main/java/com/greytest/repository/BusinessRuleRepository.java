package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.greytest.entity.BusinessRule;
import com.greytest.entity.enums.ReviewStatus;

public interface BusinessRuleRepository extends JpaRepository<BusinessRule, Long> {
    List<BusinessRule> findByProjectId(Long projectId);

    List<BusinessRule> findByProjectIdAndStatus(Long projectId, ReviewStatus status);

    @Query("SELECT br FROM BusinessRule br WHERE br.projectId = :projectId AND br.isModified = true")
    List<BusinessRule> findModifiedRules(@Param("projectId") Long projectId);
}
