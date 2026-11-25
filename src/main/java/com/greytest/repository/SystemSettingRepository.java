package com.greytest.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.greytest.entity.SystemSetting;

/**
 * Repository thao tác với bảng system_setting trong cơ sở dữ liệu.
 */
@Repository
public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {

    /** Chuyển quyền cập nhật sang admin đang thao tác trước khi xóa admin cũ để vừa tránh lỗi FK vừa giữ hiệu lực restoreRuntimeConfig. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE SystemSetting s SET s.updatedBy = :replacementUserId WHERE s.updatedBy = :userId")
    void reassignUpdatedBy(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("replacementUserId") Long replacementUserId);
}

