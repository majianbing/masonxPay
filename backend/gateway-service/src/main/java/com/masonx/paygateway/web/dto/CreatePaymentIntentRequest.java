package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.payment.BillingDetails;
import com.masonx.paygateway.domain.payment.ShippingDetails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;

public record CreatePaymentIntentRequest(
        @NotNull @Positive long amount,
        @NotBlank String currency,
        @NotBlank String idempotencyKey,
        String captureMethod,
        String orderId,
        String description,
        Map<String, String> metadata,
        BillingDetails billingDetails,
        ShippingDetails shippingDetails,
        String successUrl,
        String cancelUrl,
        String failureUrl
) {}
