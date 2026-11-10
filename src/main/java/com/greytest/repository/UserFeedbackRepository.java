package com.greytest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.greytest.entity.UserFeedback;

public interface UserFeedbackRepository
        extends JpaRepository<UserFeedback, Long>, JpaSpecificationExecutor<UserFeedback> {

    List<UserFeedback> findAllByOrderByCreatedAtDesc();

    List<UserFeedback> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByStatus(String status);
}

