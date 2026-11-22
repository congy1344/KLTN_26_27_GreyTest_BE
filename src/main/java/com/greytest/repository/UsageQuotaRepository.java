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

    /**
     * Tạo dòng quota nếu chưa có, nguyên tử ở mức DB: nhiều transaction cùng tạo cho một user chỉ một bên chèn,
     * bên còn lại chờ bên kia commit rồi bỏ qua (không lỗi unique user_id).
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO usage_quota (user_id, quota_limit, quota_used, period_start, updated_at) "
            + "VALUES (:userId, :limit, 0, :periodStart, now()) ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    int insertIfMissing(@Param("userId") Long userId, @Param("limit") Integer limit,
            @Param("periodStart") java.time.LocalDate periodStart);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select quota from UsageQuota quota where quota.userId = :userId")
    Optional<UsageQuota> findByUserIdForUpdate(@Param("userId") Long userId);

    @Query("select count(quota) from UsageQuota quota "
            + "where quota.periodStart = :periodStart and quota.quotaUsed >= quota.quotaLimit")
    long countExceededInPeriod(@Param("periodStart") java.time.LocalDate periodStart);

    void deleteByUserId(Long userId);
}
