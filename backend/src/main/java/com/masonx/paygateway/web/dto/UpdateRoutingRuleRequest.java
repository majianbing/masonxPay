package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.payment.PaymentProvider;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateRoutingRuleRequest(
        @NotNull int priority,
        boolean enabled,
        int weight,
        List<String> currencies,
        Long amountMin,
        Long amountMax,
        List<String> countryCodes,
        List<String> paymentMethodTypes,
        @NotNull PaymentProvider targetProvider,
        PaymentProvider fallbackProvider
) {}
