package com.masonx.paygateway.domain.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_read_models")
public class PaymentReadModel {

    @Id
    @Column(name = "payment_intent_id")
    private UUID paymentIntentId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 10)
    private String mode;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 10)
    private String currency;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "capture_method", length = 20)
    private String captureMethod;

    @Column(name = "resolved_provider", length = 20)
    private String resolvedProvider;

    @Column(name = "connector_account_id")
    private UUID connectorAccountId;

    @Column(name = "provider_payment_id", length = 255)
    private String providerPaymentId;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "order_id", length = 255)
    private String orderId;

    @Column(length = 500)
    private String description;

    @Column(name = "billing_email", length = 255)
    private String billingEmail;

    @Column(name = "refunded_amount_succeeded", nullable = false)
    private long refundedAmountSucceeded;

    @Column(name = "last_refund_id")
    private UUID lastRefundId;

    @Column(name = "last_refund_status", length = 20)
    private String lastRefundStatus;

    @Column(name = "search_text", columnDefinition = "TEXT")
    private String searchText;

    @Column(name = "source_created_at")
    private Instant sourceCreatedAt;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @Column(name = "last_event_id")
    private UUID lastEventId;

    @Column(name = "last_event_type", length = 100)
    private String lastEventType;

    @Column(name = "last_event_created_at")
    private Instant lastEventCreatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getPaymentIntentId() { return paymentIntentId; }
    public void setPaymentIntentId(UUID paymentIntentId) { this.paymentIntentId = paymentIntentId; }
    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCaptureMethod() { return captureMethod; }
    public void setCaptureMethod(String captureMethod) { this.captureMethod = captureMethod; }
    public String getResolvedProvider() { return resolvedProvider; }
    public void setResolvedProvider(String resolvedProvider) { this.resolvedProvider = resolvedProvider; }
    public UUID getConnectorAccountId() { return connectorAccountId; }
    public void setConnectorAccountId(UUID connectorAccountId) { this.connectorAccountId = connectorAccountId; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public void setProviderPaymentId(String providerPaymentId) { this.providerPaymentId = providerPaymentId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBillingEmail() { return billingEmail; }
    public void setBillingEmail(String billingEmail) { this.billingEmail = billingEmail; }
    public long getRefundedAmountSucceeded() { return refundedAmountSucceeded; }
    public void setRefundedAmountSucceeded(long refundedAmountSucceeded) { this.refundedAmountSucceeded = refundedAmountSucceeded; }
    public UUID getLastRefundId() { return lastRefundId; }
    public void setLastRefundId(UUID lastRefundId) { this.lastRefundId = lastRefundId; }
    public String getLastRefundStatus() { return lastRefundStatus; }
    public void setLastRefundStatus(String lastRefundStatus) { this.lastRefundStatus = lastRefundStatus; }
    public String getSearchText() { return searchText; }
    public void setSearchText(String searchText) { this.searchText = searchText; }
    public Instant getSourceCreatedAt() { return sourceCreatedAt; }
    public void setSourceCreatedAt(Instant sourceCreatedAt) { this.sourceCreatedAt = sourceCreatedAt; }
    public Instant getSourceUpdatedAt() { return sourceUpdatedAt; }
    public void setSourceUpdatedAt(Instant sourceUpdatedAt) { this.sourceUpdatedAt = sourceUpdatedAt; }
    public UUID getLastEventId() { return lastEventId; }
    public void setLastEventId(UUID lastEventId) { this.lastEventId = lastEventId; }
    public String getLastEventType() { return lastEventType; }
    public void setLastEventType(String lastEventType) { this.lastEventType = lastEventType; }
    public Instant getLastEventCreatedAt() { return lastEventCreatedAt; }
    public void setLastEventCreatedAt(Instant lastEventCreatedAt) { this.lastEventCreatedAt = lastEventCreatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
