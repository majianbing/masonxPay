package com.masonx.paygateway.domain.connector;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provider_accounts")
public class ProviderAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentProvider provider;

    @Column(nullable = false)
    private String label;

    @Column(name = "encrypted_secret_key", nullable = false)
    private String encryptedSecretKey;

    @Column(name = "encrypted_publishable_key")
    private String encryptedPublishableKey;

    /** Last 4 characters of the original secret key — safe to return in API responses. */
    @Column(name = "secret_key_hint", nullable = false)
    private String secretKeyHint;

    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ApiKeyMode mode = ApiKeyMode.TEST;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProviderAccountStatus status = ProviderAccountStatus.ACTIVE;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }
    public PaymentProvider getProvider() { return provider; }
    public void setProvider(PaymentProvider provider) { this.provider = provider; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getEncryptedSecretKey() { return encryptedSecretKey; }
    public void setEncryptedSecretKey(String encryptedSecretKey) { this.encryptedSecretKey = encryptedSecretKey; }
    public String getEncryptedPublishableKey() { return encryptedPublishableKey; }
    public void setEncryptedPublishableKey(String encryptedPublishableKey) { this.encryptedPublishableKey = encryptedPublishableKey; }
    public String getSecretKeyHint() { return secretKeyHint; }
    public void setSecretKeyHint(String secretKeyHint) { this.secretKeyHint = secretKeyHint; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
    public ApiKeyMode getMode() { return mode; }
    public void setMode(ApiKeyMode mode) { this.mode = mode; }
    public ProviderAccountStatus getStatus() { return status; }
    public void setStatus(ProviderAccountStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
