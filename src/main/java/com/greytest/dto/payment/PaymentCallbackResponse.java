package com.greytest.dto.payment;

import com.greytest.entity.enums.PaymentStatus;
import com.greytest.entity.enums.UserTier;

public record PaymentCallbackResponse(
        PaymentStatus status,
        String message,
        String orderId,
        UserTier tier,
        Long amount,
        String transactionNo
) {}
