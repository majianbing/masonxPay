package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.billing.Subscription;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SubscriptionResponse(
        UUID id,
        UUID merchantId,
        UUID customerId,
        String mode,
        String status,
        String currency,
        String intervalUnit,
        int intervalCount,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        Instant trialEndsAt,
        boolean cancelAtPeriodEnd,
        Instant canceledAt,
        Map<String, String> metadata,
        List<SubscriptionItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {
    public static SubscriptionResponse from(Subscription subscription,
                                            List<SubscriptionItemResponse> items,
                                            Map<String, String> metadata) {
        return new SubscriptionResponse(
                subscription.getId(),
                subscription.getMerchantId(),
                subscription.getCustomerId(),
                subscription.getMode().name(),
                subscription.getStatus().name(),
                subscription.getCurrency().toUpperCase(),
                subscription.getIntervalUnit().name(),
                subscription.getIntervalCount(),
                subscription.getCurrentPeriodStart(),
                subscription.getCurrentPeriodEnd(),
                subscription.getTrialEndsAt(),
                subscription.isCancelAtPeriodEnd(),
                subscription.getCanceledAt(),
                metadata,
                items,
                subscription.getCreatedAt(),
                subscription.getUpdatedAt()
        );
    }
}
