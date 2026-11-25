package com.greytest.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.greytest.entity.BreakGlassGrant;

public interface BreakGlassGrantRepository extends JpaRepository<BreakGlassGrant, Long> {

    @Query("SELECT g FROM BreakGlassGrant g WHERE g.projectId = :projectId AND g.adminUserId = :adminUserId AND g.status = 'APPROVED' AND g.revoked = false AND g.expiresAt > :now ORDER BY g.expiresAt DESC")
    List<BreakGlassGrant> findActiveGrants(
            @Param("projectId") Long projectId,
            @Param("adminUserId") Long adminUserId,
            @Param("now") LocalDateTime now);

    @Query("SELECT g FROM BreakGlassGrant g WHERE g.status = 'APPROVED' AND g.revoked = false AND g.expiresAt > :now ORDER BY g.expiresAt DESC")
    List<BreakGlassGrant> findAllActive(@Param("now") LocalDateTime now);

    List<BreakGlassGrant> findByStatusOrderByCreatedAtDesc(String status);
}
