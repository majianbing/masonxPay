package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;

public record CreatePaymentIntentRequest(
        @NotNull @Positive long amount,
        @NotBlank String currency,
        @NotBlank String idempotencyKey,
        String captureMethod,
        Map<String, String> metadata,
        String successUrl,
        String cancelUrl,
        String failureUrl
) {}
