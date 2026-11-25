package com.greytest.dto;

import java.time.LocalDate;

import com.greytest.entity.enums.UserRole;
import com.greytest.entity.enums.UserTier;

public record AuthUserDto(
        Long id,
        String email,
        String fullName,
        UserRole role,
        UserTier tier,
        String phoneNumber,
        String gender,
        LocalDate dateOfBirth,
        String githubUsername) {

    public AuthUserDto(Long id, String email, String fullName, UserRole role, UserTier tier) {
        this(id, email, fullName, role, tier, null, null, null, null);
    }

    public AuthUserDto(
            Long id,
            String email,
            String fullName,
            UserRole role,
            UserTier tier,
            String phoneNumber,
            String gender,
            LocalDate dateOfBirth) {
        this(id, email, fullName, role, tier, phoneNumber, gender, dateOfBirth, null);
    }
}

