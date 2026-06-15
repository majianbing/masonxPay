package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.webhook.WebhookDelivery;

import java.time.Instant;
import java.util.UUID;

public record WebhookDeliveryResponse(
        UUID id,
        UUID gatewayEventId,
        UUID webhookEndpointId,
        String status,
        Integer httpStatus,
        String responseBody,
        int attemptCount,
        Instant nextRetryAt,
        Instant lastAttemptedAt,
        Instant createdAt
) {
    public static WebhookDeliveryResponse from(WebhookDelivery d) {
        return new WebhookDeliveryResponse(
                d.getId(),
                d.getGatewayEventId(),
                d.getWebhookEndpointId(),
                d.getStatus().name(),
                d.getHttpStatus(),
                d.getResponseBody(),
                d.getAttemptCount(),
                d.getNextRetryAt(),
                d.getLastAttemptedAt(),
                d.getCreatedAt()
        );
    }
}
