package com.greytest.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.greytest.entity.SystemSetting;

/**
 * Repository thao tác với bảng system_setting trong cơ sở dữ liệu.
 */
@Repository
public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {
}

