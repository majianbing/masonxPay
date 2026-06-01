package com.masonx.paygateway.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PublicSubscriptionCheckoutInfo(
        String token,
        UUID subscriptionId,
        String merchantName,
        String customerName,
        String customerEmail,
        String mode,
        String status,
        String currency,
        String intervalUnit,
        int intervalCount,
        Instant trialEndsAt,
        boolean active,
        List<SubscriptionItemResponse> items,
        List<CheckoutConnectorInfo> connectors
) {}
