package com.masonx.paygateway.domain.routing;

import com.masonx.paygateway.domain.payment.PaymentProvider;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "routing_rules")
public class RoutingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(columnDefinition = "TEXT")
    private String currencies;

    @Column(name = "amount_min")
    private Long amountMin;

    @Column(name = "amount_max")
    private Long amountMax;

    @Column(name = "country_codes", columnDefinition = "TEXT")
    private String countryCodes;

    @Column(name = "payment_method_types", columnDefinition = "TEXT")
    private String paymentMethodTypes;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_provider", nullable = false, length = 20)
    private PaymentProvider targetProvider;

    @Enumerated(EnumType.STRING)
    @Column(name = "fallback_provider", length = 20)
    private PaymentProvider fallbackProvider;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    // --- comma-separated helpers ---

    public List<String> getCurrencyList() {
        return splitOrEmpty(currencies);
    }

    public void setCurrencyList(List<String> list) {
        this.currencies = joinOrNull(list);
    }

    public List<String> getCountryCodeList() {
        return splitOrEmpty(countryCodes);
    }

    public void setCountryCodeList(List<String> list) {
        this.countryCodes = joinOrNull(list);
    }

    public List<String> getPaymentMethodTypeList() {
        return splitOrEmpty(paymentMethodTypes);
    }

    public void setPaymentMethodTypeList(List<String> list) {
        this.paymentMethodTypes = joinOrNull(list);
    }

    private static List<String> splitOrEmpty(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static String joinOrNull(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        return String.join(",", list);
    }

    // --- getters / setters ---

    public UUID getId() { return id; }

    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCurrencies() { return currencies; }
    public void setCurrencies(String currencies) { this.currencies = currencies; }

    public Long getAmountMin() { return amountMin; }
    public void setAmountMin(Long amountMin) { this.amountMin = amountMin; }

    public Long getAmountMax() { return amountMax; }
    public void setAmountMax(Long amountMax) { this.amountMax = amountMax; }

    public String getCountryCodes() { return countryCodes; }
    public void setCountryCodes(String countryCodes) { this.countryCodes = countryCodes; }

    public String getPaymentMethodTypes() { return paymentMethodTypes; }
    public void setPaymentMethodTypes(String paymentMethodTypes) { this.paymentMethodTypes = paymentMethodTypes; }

    public PaymentProvider getTargetProvider() { return targetProvider; }
    public void setTargetProvider(PaymentProvider targetProvider) { this.targetProvider = targetProvider; }

    public PaymentProvider getFallbackProvider() { return fallbackProvider; }
    public void setFallbackProvider(PaymentProvider fallbackProvider) { this.fallbackProvider = fallbackProvider; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
