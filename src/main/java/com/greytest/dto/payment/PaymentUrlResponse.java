package com.greytest.dto.payment;

public record PaymentUrlResponse(
        String paymentUrl,
        String orderId,
        Long amount
) {}
