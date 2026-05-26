package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.billing.SubscriptionItem;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionItemResponse(
        UUID id,
        UUID subscriptionId,
        String description,
        long amount,
        int quantity,
        Instant createdAt,
        Instant updatedAt
) {
    public static SubscriptionItemResponse from(SubscriptionItem item) {
        return new SubscriptionItemResponse(
                item.getId(),
                item.getSubscriptionId(),
                item.getDescription(),
                item.getAmount(),
                item.getQuantity(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
