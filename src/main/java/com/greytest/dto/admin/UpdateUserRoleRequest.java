package com.greytest.dto.admin;

import com.greytest.entity.enums.UserRole;

import jakarta.validation.constraints.NotNull;

public record UpdateUserRoleRequest(@NotNull(message = "Role là bắt buộc") UserRole role) {}

