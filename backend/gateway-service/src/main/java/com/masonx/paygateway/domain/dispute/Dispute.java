package com.masonx.paygateway.domain.dispute;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "disputes")
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "external_id", length = 40)
    private String externalId;

    private UUID merchantId;
    private UUID paymentIntentId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false, unique = true)
    private String providerDisputeId;

    private String providerChargeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DisputeStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 60)
    private DisputeReason reason;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    private Instant evidenceDueBy;
    private Instant submittedAt;
    private Instant resolvedAt;

    @Column(columnDefinition = "TEXT")
    private String evidenceTextJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ApiKeyMode mode = ApiKeyMode.LIVE;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public void setPaymentIntentId(UUID paymentIntentId) { this.paymentIntentId = paymentIntentId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderDisputeId() { return providerDisputeId; }
    public void setProviderDisputeId(String providerDisputeId) { this.providerDisputeId = providerDisputeId; }
    public String getProviderChargeId() { return providerChargeId; }
    public void setProviderChargeId(String providerChargeId) { this.providerChargeId = providerChargeId; }
    public DisputeStatus getStatus() { return status; }
    public void setStatus(DisputeStatus status) { this.status = status; }
    public DisputeReason getReason() { return reason; }
    public void setReason(DisputeReason reason) { this.reason = reason; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Instant getEvidenceDueBy() { return evidenceDueBy; }
    public void setEvidenceDueBy(Instant evidenceDueBy) { this.evidenceDueBy = evidenceDueBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getEvidenceTextJson() { return evidenceTextJson; }
    public void setEvidenceTextJson(String evidenceTextJson) { this.evidenceTextJson = evidenceTextJson; }
    public ApiKeyMode getMode() { return mode; }
    public void setMode(ApiKeyMode mode) { this.mode = mode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
