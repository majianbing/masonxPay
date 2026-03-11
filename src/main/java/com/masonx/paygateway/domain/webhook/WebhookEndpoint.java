package com.masonx.paygateway.domain.webhook;

import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false, length = 64)
    private String signingSecret;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookEndpointStatus status = WebhookEndpointStatus.ACTIVE;

    @Column(nullable = false)
    private String subscribedEvents = "payment_intent.succeeded,payment_intent.failed,payment_intent.canceled";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public List<String> getSubscribedEventList() {
        if (subscribedEvents == null || subscribedEvents.isBlank()) return List.of();
        return Arrays.stream(subscribedEvents.split(",")).map(String::trim).toList();
    }

    public void setSubscribedEventList(List<String> events) {
        this.subscribedEvents = events == null ? "" : String.join(",", events);
    }

    // Getters and setters
    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public WebhookEndpointStatus getStatus() { return status; }
    public void setStatus(WebhookEndpointStatus status) { this.status = status; }
    public String getSubscribedEvents() { return subscribedEvents; }
    public void setSubscribedEvents(String subscribedEvents) { this.subscribedEvents = subscribedEvents; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
