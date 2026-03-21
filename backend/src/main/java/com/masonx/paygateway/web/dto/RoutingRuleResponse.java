package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.payment.PaymentProvider;
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
        PaymentProvider targetProvider,
        PaymentProvider fallbackProvider,
        Instant createdAt,
        Instant updatedAt
) {
    public static RoutingRuleResponse from(RoutingRule rule) {
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
                rule.getTargetProvider(),
                rule.getFallbackProvider(),
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }
}
