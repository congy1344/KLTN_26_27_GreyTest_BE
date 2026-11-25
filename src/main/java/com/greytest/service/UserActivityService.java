package com.greytest.service;

import java.util.Map;
import java.util.Collections;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.entity.UserActivityLog;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.repository.UserActivityLogRepository;

/** Ghi nhận usage và audit event để dashboard quản trị dùng chung một nguồn dữ liệu. */
@Service
public class UserActivityService {

    private static final Logger log = LoggerFactory.getLogger(UserActivityService.class);
    private final UserActivityLogRepository repository;

    public UserActivityService(UserActivityLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(Long userId, ActivityAction action, Long projectId, Map<String, Object> metadata) {
        if (userId == null) return;
        try {
            UserActivityLog activity = new UserActivityLog();
            activity.setUserId(userId);
            activity.setActionType(action);
            activity.setRelatedProjectId(projectId);
            activity.setMetadata(metadata == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(metadata)));
            repository.save(activity);
        } catch (Exception ex) {
            log.warn("Không thể ghi log hoạt động người dùng [userId={}, action={}, projectId={}]: {}",
                    userId, action, projectId, ex.getMessage());
        }
    }

    /**
     * Ghi nhận audit event độc lập trong transaction mới (REQUIRES_NEW).
     * Dùng cho các sự kiện như PROJECT_ACCESS_DENIED cần được commit ngay cả khi caller ném RuntimeException/AuthException.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordIsolated(Long userId, ActivityAction action, Long projectId, Map<String, Object> metadata) {
        if (userId == null) return;
        UserActivityLog activity = new UserActivityLog();
        activity.setUserId(userId);
        activity.setActionType(action);
        activity.setRelatedProjectId(projectId);
        activity.setMetadata(metadata == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(metadata)));
        repository.save(activity);
    }
}

