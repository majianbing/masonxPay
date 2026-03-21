package com.masonx.paygateway.provider;

import java.util.UUID;

public record RefundRequest(
        UUID refundId,
        String providerPaymentId,
        long amount,
        String reason
) {}
