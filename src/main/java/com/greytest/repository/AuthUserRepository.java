package com.greytest.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.greytest.entity.AuthUser;

public interface AuthUserRepository extends JpaRepository<AuthUser, Long>, JpaSpecificationExecutor<AuthUser> {
    Optional<AuthUser> findByEmailIgnoreCase(String email);

    long countByCreatedAtAfter(java.time.LocalDateTime createdAt);
}
