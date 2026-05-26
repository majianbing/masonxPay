package com.masonx.paygateway.web.dto;

import java.util.UUID;

public record PublicSubscriptionCheckoutResponse(
        boolean success,
        String status,
        UUID subscriptionId,
        UUID paymentIntentId,
        String failureCode,
        String failureMessage
) {}
