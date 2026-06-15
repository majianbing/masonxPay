package com.masonx.paygateway.domain.webhook;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Records every inbound provider webhook event that has been successfully processed.
 * Unique constraint on (provider, provider_event_id) prevents double-processing when
 * providers retry delivery on timeout.
 */
@Entity
@Table(name = "processed_webhook_events")
public class ProcessedWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false, length = 255)
    private String providerEventId;

    @Column(nullable = false, updatable = false)
    private Instant processedAt = Instant.now();

    public ProcessedWebhookEvent() {}

    public ProcessedWebhookEvent(String provider, String providerEventId) {
        this.provider = provider;
        this.providerEventId = providerEventId;
    }

    public UUID getId() { return id; }
    public String getProvider() { return provider; }
    public String getProviderEventId() { return providerEventId; }
    public Instant getProcessedAt() { return processedAt; }
}
