package com.masonx.paygateway.domain.outbox;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox row written atomically with the payment intent save.
 * The WebhookDeliveryService poller reads unpublished rows and fires the Spring
 * application event, which creates the GatewayEvent + WebhookDelivery records.
 *
 * This ensures that a JVM crash between the DB write and the Spring event publish
 * cannot silently drop a merchant webhook notification.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false)
    private UUID resourceId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private boolean published = false;

    @Column(nullable = false)
    private boolean kafkaPublished = false;

    private Instant kafkaPublishedAt;

    @Column(nullable = false)
    private int kafkaPublishAttempts = 0;

    @Column(columnDefinition = "TEXT")
    private String kafkaLastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public OutboxEvent() {}

    public OutboxEvent(UUID merchantId, String eventType, UUID resourceId, String payload) {
        this.merchantId = merchantId;
        this.eventType = eventType;
        this.resourceId = resourceId;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getEventType() { return eventType; }
    public UUID getResourceId() { return resourceId; }
    public String getPayload() { return payload; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
    public boolean isKafkaPublished() { return kafkaPublished; }
    public Instant getKafkaPublishedAt() { return kafkaPublishedAt; }
    public int getKafkaPublishAttempts() { return kafkaPublishAttempts; }
    public String getKafkaLastError() { return kafkaLastError; }
    public Instant getCreatedAt() { return createdAt; }

    public void markKafkaPublished(Instant publishedAt) {
        this.kafkaPublished = true;
        this.kafkaPublishedAt = publishedAt;
        this.kafkaLastError = null;
    }

    public void recordKafkaPublishFailure(String error) {
        this.kafkaPublishAttempts++;
        this.kafkaLastError = error;
    }
}
