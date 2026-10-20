package com.greytest.dto.payment;

import com.greytest.entity.enums.UserTier;

public record CreatePaymentRequest(
        UserTier tier
) {}
