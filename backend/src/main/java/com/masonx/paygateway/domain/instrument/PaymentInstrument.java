package com.masonx.paygateway.domain.instrument;

import com.masonx.paygateway.domain.payment.PaymentProvider;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_instruments")
public class PaymentInstrument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InstrumentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InstrumentSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InstrumentPortability portability = InstrumentPortability.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private PaymentProvider provider;

    @Column(name = "provider_account_id")
    private UUID providerAccountId;

    /** Opaque provider, vault, wallet, or network-token reference. Never store PAN or CVV here. */
    @Column(name = "token_reference", nullable = false, columnDefinition = "TEXT")
    private String tokenReference;

    @Column(name = "card_brand", length = 30)
    private String cardBrand;

    @Column(length = 4)
    private String last4;

    @Column(name = "expiry_month")
    private Integer expiryMonth;

    @Column(name = "expiry_year")
    private Integer expiryYear;

    @Column(name = "bin_country", length = 2)
    private String binCountry;

    @Column(name = "issuer_country", length = 2)
    private String issuerCountry;

    @Column(name = "card_type", length = 30)
    private String cardType;

    @Column(name = "wallet_type", length = 30)
    private String walletType;

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
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public InstrumentType getType() { return type; }
    public void setType(InstrumentType type) { this.type = type; }
    public InstrumentSource getSource() { return source; }
    public void setSource(InstrumentSource source) { this.source = source; }
    public InstrumentPortability getPortability() { return portability; }
    public void setPortability(InstrumentPortability portability) { this.portability = portability; }
    public PaymentProvider getProvider() { return provider; }
    public void setProvider(PaymentProvider provider) { this.provider = provider; }
    public UUID getProviderAccountId() { return providerAccountId; }
    public void setProviderAccountId(UUID providerAccountId) { this.providerAccountId = providerAccountId; }
    public String getTokenReference() { return tokenReference; }
    public void setTokenReference(String tokenReference) { this.tokenReference = tokenReference; }
    public String getCardBrand() { return cardBrand; }
    public void setCardBrand(String cardBrand) { this.cardBrand = cardBrand; }
    public String getLast4() { return last4; }
    public void setLast4(String last4) { this.last4 = last4; }
    public Integer getExpiryMonth() { return expiryMonth; }
    public void setExpiryMonth(Integer expiryMonth) { this.expiryMonth = expiryMonth; }
    public Integer getExpiryYear() { return expiryYear; }
    public void setExpiryYear(Integer expiryYear) { this.expiryYear = expiryYear; }
    public String getBinCountry() { return binCountry; }
    public void setBinCountry(String binCountry) { this.binCountry = binCountry; }
    public String getIssuerCountry() { return issuerCountry; }
    public void setIssuerCountry(String issuerCountry) { this.issuerCountry = issuerCountry; }
    public String getCardType() { return cardType; }
    public void setCardType(String cardType) { this.cardType = cardType; }
    public String getWalletType() { return walletType; }
    public void setWalletType(String walletType) { this.walletType = walletType; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
