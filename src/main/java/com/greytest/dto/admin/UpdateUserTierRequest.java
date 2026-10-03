package com.greytest.dto.admin;

import com.greytest.entity.enums.UserTier;

import jakarta.validation.constraints.NotNull;

public record UpdateUserTierRequest(@NotNull(message = "Tier không được để trống") UserTier tier) {
}
