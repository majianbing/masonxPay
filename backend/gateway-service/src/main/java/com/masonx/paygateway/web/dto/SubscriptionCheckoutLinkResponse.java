package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLink;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionCheckoutLinkResponse(
        UUID id,
        UUID subscriptionId,
        UUID customerId,
        String token,
        String status,
        String checkoutUrl,
        Instant expiresAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static SubscriptionCheckoutLinkResponse from(SubscriptionCheckoutLink link, String payBaseUrl) {
        return new SubscriptionCheckoutLinkResponse(
                link.getId(),
                link.getSubscriptionId(),
                link.getCustomerId(),
                link.getToken(),
                link.getStatus().name(),
                payBaseUrl + "/subscribe/" + link.getToken(),
                link.getExpiresAt(),
                link.getCompletedAt(),
                link.getCreatedAt(),
                link.getUpdatedAt()
        );
    }
}
