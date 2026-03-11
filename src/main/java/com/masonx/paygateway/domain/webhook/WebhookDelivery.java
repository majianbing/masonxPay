package com.masonx.paygateway.domain.webhook;

import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID gatewayEventId;

    @Column(nullable = false)
    private UUID webhookEndpointId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;

    private Integer httpStatus;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Column(nullable = false)
    private int attemptCount = 0;

    private Instant nextRetryAt;
    private Instant lastAttemptedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    // Getters and setters
    public UUID getId() { return id; }
    public UUID getGatewayEventId() { return gatewayEventId; }
    public void setGatewayEventId(UUID gatewayEventId) { this.gatewayEventId = gatewayEventId; }
    public UUID getWebhookEndpointId() { return webhookEndpointId; }
    public void setWebhookEndpointId(UUID webhookEndpointId) { this.webhookEndpointId = webhookEndpointId; }
    public WebhookDeliveryStatus getStatus() { return status; }
    public void setStatus(WebhookDeliveryStatus status) { this.status = status; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public Instant getLastAttemptedAt() { return lastAttemptedAt; }
    public void setLastAttemptedAt(Instant lastAttemptedAt) { this.lastAttemptedAt = lastAttemptedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
