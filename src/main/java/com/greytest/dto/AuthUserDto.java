package com.greytest.dto;

import com.greytest.entity.enums.UserRole;
import com.greytest.entity.enums.UserTier;

public record AuthUserDto(
        Long id,
        String email,
        String fullName,
        UserRole role,
        UserTier tier) {
}
