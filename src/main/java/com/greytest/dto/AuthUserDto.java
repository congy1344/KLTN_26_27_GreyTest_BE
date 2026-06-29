package com.greytest.dto;

import com.greytest.entity.enums.UserRole;

public record AuthUserDto(
        Long id,
        String email,
        String fullName,
        UserRole role) {
}
