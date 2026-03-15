package com.masonx.paygateway.domain.payment;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_tokens")
public class PaymentToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** Raw provider PM ID (e.g. pm_3P...). Never returned in API responses. */
    @Column(name = "provider_pm_id", nullable = false, columnDefinition = "TEXT")
    private String providerPmId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public String getProviderPmId() { return providerPmId; }
    public void setProviderPmId(String providerPmId) { this.providerPmId = providerPmId; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    public boolean isUsed()    { return usedAt != null; }
}
