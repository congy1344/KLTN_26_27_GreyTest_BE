package com.greytest.service;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greytest.entity.UserActivityLog;
import com.greytest.entity.enums.ActivityAction;
import com.greytest.repository.UserActivityLogRepository;

/** Ghi nhận usage và audit event để dashboard quản trị dùng chung một nguồn dữ liệu. */
@Service
public class UserActivityService {

    private final UserActivityLogRepository repository;

    public UserActivityService(UserActivityLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(Long userId, ActivityAction action, Long projectId, Map<String, Object> metadata) {
        if (userId == null) return;
        UserActivityLog activity = new UserActivityLog();
        activity.setUserId(userId);
        activity.setActionType(action);
        activity.setRelatedProjectId(projectId);
        activity.setMetadata(metadata == null ? Map.of() : Map.copyOf(metadata));
        repository.save(activity);
    }
}
