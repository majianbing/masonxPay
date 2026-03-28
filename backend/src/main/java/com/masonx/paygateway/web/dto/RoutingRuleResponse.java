package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.routing.RoutingRule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoutingRuleResponse(
        UUID id,
        UUID merchantId,
        int priority,
        boolean enabled,
        int weight,
        List<String> currencies,
        Long amountMin,
        Long amountMax,
        List<String> countryCodes,
        List<String> paymentMethodTypes,
        UUID targetAccountId,
        String targetProvider,
        String targetAccountLabel,
        UUID fallbackAccountId,
        String fallbackProvider,
        String fallbackAccountLabel,
        Instant createdAt,
        Instant updatedAt
) {
    public static RoutingRuleResponse from(RoutingRule rule, ProviderAccount target, ProviderAccount fallback) {
        return new RoutingRuleResponse(
                rule.getId(),
                rule.getMerchantId(),
                rule.getPriority(),
                rule.isEnabled(),
                rule.getWeight(),
                rule.getCurrencyList(),
                rule.getAmountMin(),
                rule.getAmountMax(),
                rule.getCountryCodeList(),
                rule.getPaymentMethodTypeList(),
                target != null ? target.getId() : rule.getTargetAccountId(),
                target != null ? target.getProvider().name() : null,
                target != null ? target.getLabel() : null,
                fallback != null ? fallback.getId() : rule.getFallbackAccountId(),
                fallback != null ? fallback.getProvider().name() : null,
                fallback != null ? fallback.getLabel() : null,
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }
}
