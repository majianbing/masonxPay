package com.masonx.paygateway.provider;

import java.util.UUID;

public record ChargeRequest(
        UUID paymentIntentId,
        long amount,
        String currency,
        String paymentMethodType,
        String paymentMethodId,   // provider-specific PM token (Stripe pm_xxx, Square sourceId, …)
        String idempotencyKey
) {}
