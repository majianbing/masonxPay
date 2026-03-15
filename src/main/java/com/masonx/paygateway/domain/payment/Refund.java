package com.masonx.paygateway.domain.payment;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_intent_id", nullable = false)
    private UUID paymentIntentId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ApiKeyMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status = RefundStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RefundReason reason;

    @Column(name = "provider_refund_id")
    private String providerRefundId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }

    public UUID getPaymentIntentId() { return paymentIntentId; }
    public void setPaymentIntentId(UUID paymentIntentId) { this.paymentIntentId = paymentIntentId; }

    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public ApiKeyMode getMode() { return mode; }
    public void setMode(ApiKeyMode mode) { this.mode = mode; }
    public RefundStatus getStatus() { return status; }
    public void setStatus(RefundStatus status) { this.status = status; }

    public RefundReason getReason() { return reason; }
    public void setReason(RefundReason reason) { this.reason = reason; }

    public String getProviderRefundId() { return providerRefundId; }
    public void setProviderRefundId(String providerRefundId) { this.providerRefundId = providerRefundId; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
