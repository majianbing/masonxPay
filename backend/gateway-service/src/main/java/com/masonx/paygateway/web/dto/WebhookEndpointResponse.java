package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.webhook.WebhookEndpoint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WebhookEndpointResponse(
        UUID id,
        UUID merchantId,
        String url,
        String description,
        String status,
        List<String> subscribedEvents,
        String signingSecret,
        Instant createdAt,
        Instant updatedAt
) {
    public static WebhookEndpointResponse from(WebhookEndpoint e) {
        return new WebhookEndpointResponse(
                e.getId(),
                e.getMerchantId(),
                e.getUrl(),
                e.getDescription(),
                e.getStatus().name(),
                e.getSubscribedEventList(),
                e.getSigningSecret(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
