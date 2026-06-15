package com.masonx.paygateway.domain.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projection_processed_events")
public class ProjectionProcessedEvent {

    @Id
    @Column(name = "outbox_event_id")
    private UUID outboxEventId;

    @Column(name = "consumer_name", nullable = false, length = 100)
    private String consumerName;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectionEventStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    public ProjectionProcessedEvent() {}

    public ProjectionProcessedEvent(UUID outboxEventId, String consumerName, UUID merchantId, String eventType,
                                    UUID resourceId, ProjectionEventStatus status, String errorMessage) {
        this.outboxEventId = outboxEventId;
        this.consumerName = consumerName;
        this.merchantId = merchantId;
        this.eventType = eventType;
        this.resourceId = resourceId;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public UUID getOutboxEventId() { return outboxEventId; }
    public ProjectionEventStatus getStatus() { return status; }
    public Instant getProcessedAt() { return processedAt; }
}
