package com.greytest.dto.admin;

import com.greytest.entity.enums.UserRole;
import com.greytest.entity.enums.UserTier;

import jakarta.validation.constraints.Min;

public record UpdateUserRequest(
        String fullName,
        String password,
        UserRole role,
        UserTier tier,
        @Min(value = 0, message = "Quota không được âm")
        Integer quotaLimit,
        Boolean unlimitedQuota) {}

