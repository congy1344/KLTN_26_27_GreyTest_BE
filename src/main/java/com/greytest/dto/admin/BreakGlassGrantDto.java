package com.greytest.dto.admin;

import java.time.LocalDateTime;

public record BreakGlassGrantDto(
        Long id,
        Long projectId,
        Long adminUserId,
        Long approverUserId,
        String reason,
        Integer durationMinutes,
        String status,
        LocalDateTime grantedAt,
        LocalDateTime expiresAt,
        boolean active
) {
}
