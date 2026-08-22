package com.greytest.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.greytest.entity.UsageQuota;

import jakarta.persistence.LockModeType;

public interface UsageQuotaRepository extends JpaRepository<UsageQuota, Long> {
    Optional<UsageQuota> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select quota from UsageQuota quota where quota.userId = :userId")
    Optional<UsageQuota> findByUserIdForUpdate(@Param("userId") Long userId);

    @Query("select count(quota) from UsageQuota quota "
            + "where quota.periodStart = :periodStart and quota.quotaUsed >= quota.quotaLimit")
    long countExceededInPeriod(@Param("periodStart") java.time.LocalDate periodStart);
}
