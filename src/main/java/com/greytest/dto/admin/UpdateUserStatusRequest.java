package com.greytest.dto.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateUserStatusRequest(
        @NotNull(message = "Trạng thái tài khoản là bắt buộc") Boolean enabled,
        @Size(max = 500, message = "Lý do khóa không được quá 500 ký tự") String reason) {}

