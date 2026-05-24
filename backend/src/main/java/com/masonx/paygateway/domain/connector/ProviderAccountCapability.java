package com.masonx.paygateway.domain.connector;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provider_account_capabilities")
public class ProviderAccountCapability {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "provider_account_id", nullable = false)
    private UUID providerAccountId;

    @Column(name = "payment_method_type", nullable = false, length = 50)
    private String paymentMethodType;

    @Column(length = 2)
    private String country;

    @Column(length = 10)
    private String currency;

    @Column(name = "min_amount")
    private Long minAmount;

    @Column(name = "max_amount")
    private Long maxAmount;

    @Column(name = "supports_manual_capture", nullable = false)
    private boolean supportsManualCapture = true;

    @Column(name = "supports_refund", nullable = false)
    private boolean supportsRefund = true;

    @Column(name = "supports_partial_refund", nullable = false)
    private boolean supportsPartialRefund = true;

    @Column(name = "supports_3ds", nullable = false)
    private boolean supports3ds = false;

    @Column(name = "supports_redirect", nullable = false)
    private boolean supportsRedirect = false;

    @Column(name = "supports_provider_token", nullable = false)
    private boolean supportsProviderToken = true;

    @Column(name = "supports_vault_token", nullable = false)
    private boolean supportsVaultToken = false;

    @Column(name = "supports_network_token", nullable = false)
    private boolean supportsNetworkToken = false;

    @Column(name = "supports_installments", nullable = false)
    private boolean supportsInstallments = false;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }
    public UUID getProviderAccountId() { return providerAccountId; }
    public void setProviderAccountId(UUID providerAccountId) { this.providerAccountId = providerAccountId; }
    public String getPaymentMethodType() { return paymentMethodType; }
    public void setPaymentMethodType(String paymentMethodType) { this.paymentMethodType = paymentMethodType; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Long getMinAmount() { return minAmount; }
    public void setMinAmount(Long minAmount) { this.minAmount = minAmount; }
    public Long getMaxAmount() { return maxAmount; }
    public void setMaxAmount(Long maxAmount) { this.maxAmount = maxAmount; }
    public boolean isSupportsManualCapture() { return supportsManualCapture; }
    public void setSupportsManualCapture(boolean supportsManualCapture) { this.supportsManualCapture = supportsManualCapture; }
    public boolean isSupportsRefund() { return supportsRefund; }
    public void setSupportsRefund(boolean supportsRefund) { this.supportsRefund = supportsRefund; }
    public boolean isSupportsPartialRefund() { return supportsPartialRefund; }
    public void setSupportsPartialRefund(boolean supportsPartialRefund) { this.supportsPartialRefund = supportsPartialRefund; }
    public boolean isSupports3ds() { return supports3ds; }
    public void setSupports3ds(boolean supports3ds) { this.supports3ds = supports3ds; }
    public boolean isSupportsRedirect() { return supportsRedirect; }
    public void setSupportsRedirect(boolean supportsRedirect) { this.supportsRedirect = supportsRedirect; }
    public boolean isSupportsProviderToken() { return supportsProviderToken; }
    public void setSupportsProviderToken(boolean supportsProviderToken) { this.supportsProviderToken = supportsProviderToken; }
    public boolean isSupportsVaultToken() { return supportsVaultToken; }
    public void setSupportsVaultToken(boolean supportsVaultToken) { this.supportsVaultToken = supportsVaultToken; }
    public boolean isSupportsNetworkToken() { return supportsNetworkToken; }
    public void setSupportsNetworkToken(boolean supportsNetworkToken) { this.supportsNetworkToken = supportsNetworkToken; }
    public boolean isSupportsInstallments() { return supportsInstallments; }
    public void setSupportsInstallments(boolean supportsInstallments) { this.supportsInstallments = supportsInstallments; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
