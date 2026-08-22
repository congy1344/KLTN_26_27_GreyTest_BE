package com.greytest.dto.admin;

import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(@NotNull(message = "Trạng thái tài khoản là bắt buộc") Boolean enabled) {}

