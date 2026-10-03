package com.greytest.dto;

import java.time.LocalDate;
import com.greytest.entity.enums.UserTier;

public record UserQuotaResponse(
        UserTier tier,
        Integer limit,
        int used,
        Integer remaining,
        LocalDate periodStart,
        LocalDate resetDate,
        boolean exceeded) {
}
