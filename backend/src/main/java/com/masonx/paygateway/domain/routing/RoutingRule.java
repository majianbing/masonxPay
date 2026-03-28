package com.masonx.paygateway.domain.routing;

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

    @Column(nullable = false)
    private int weight = 1;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Column(name = "fallback_account_id")
    private UUID fallbackAccountId;

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

    public UUID getTargetAccountId() { return targetAccountId; }
    public void setTargetAccountId(UUID targetAccountId) { this.targetAccountId = targetAccountId; }

    public UUID getFallbackAccountId() { return fallbackAccountId; }
    public void setFallbackAccountId(UUID fallbackAccountId) { this.fallbackAccountId = fallbackAccountId; }

    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
