package com.greytest.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.greytest.entity.UserActivityLog;
import com.greytest.entity.enums.ActivityAction;

public interface UserActivityLogRepository
        extends JpaRepository<UserActivityLog, Long>, JpaSpecificationExecutor<UserActivityLog> {

    long countByUserId(Long userId);

    long countByActionType(ActivityAction actionType);

    long countByActionTypeIn(List<ActivityAction> actionTypes);

    List<UserActivityLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Query(value = """
            SELECT date_trunc(CAST(:granularity AS text), created_at) AS bucket,
                   COUNT(*) AS total
            FROM user_activity_log
            WHERE created_at >= :fromDate
              AND (:actionType IS NULL OR action_type = :actionType)
            GROUP BY bucket
            ORDER BY bucket
            """, nativeQuery = true)
    List<UsageTrendProjection> usageTrend(
            @Param("fromDate") LocalDateTime fromDate,
            @Param("granularity") String granularity,
            @Param("actionType") String actionType);

    @Query(value = """
            SELECT u.id AS userId, u.email AS email, COUNT(a.id) AS total
            FROM auth_user u
            JOIN user_activity_log a ON a.user_id = u.id
            WHERE a.created_at >= :fromDate AND a.action_type = 'LLM_CALL'
            GROUP BY u.id, u.email
            ORDER BY total DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<TopUserProjection> topLlmUsers(
            @Param("fromDate") LocalDateTime fromDate,
            @Param("limit") int limit);

    interface UsageTrendProjection {
        LocalDateTime getBucket();
        long getTotal();
    }

    interface TopUserProjection {
        Long getUserId();
        String getEmail();
        long getTotal();
    }
}

