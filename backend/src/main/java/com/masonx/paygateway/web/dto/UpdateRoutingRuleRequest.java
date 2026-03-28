package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record UpdateRoutingRuleRequest(
        @NotNull int priority,
        boolean enabled,
        int weight,
        List<String> currencies,
        Long amountMin,
        Long amountMax,
        List<String> countryCodes,
        List<String> paymentMethodTypes,
        @NotNull UUID targetAccountId,
        UUID fallbackAccountId
) {}
