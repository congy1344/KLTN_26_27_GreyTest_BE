package com.greytest.repository;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import com.greytest.entity.AuthUser;
import com.greytest.entity.enums.UserRole;

public interface AuthUserRepository extends JpaRepository<AuthUser, Long>, JpaSpecificationExecutor<AuthUser> {
    Optional<AuthUser> findByEmailIgnoreCase(String email);

    long countByCreatedAtAfter(java.time.LocalDateTime createdAt);

    long countByRoleAndEnabledTrue(UserRole role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from AuthUser user where user.role = com.greytest.entity.enums.UserRole.ADMIN "
            + "and user.enabled = true order by user.id")
    List<AuthUser> findEnabledAdminsForUpdate();
}
